## Assumption for V4

This V4 plan assumes V1–V3 already exist:

* V1: javac sidecar, semantic rename, safe delete, top-level type move, inline local/constant.
* V2: refactor sessions, change signature, extract method/interface, move member, introduce parameter/field, encapsulate field, constrained inline method.
* V3: transformation workspaces, package/module/resource-aware refactors, propagating delete, extract class/superclass, framework SPI, recipe engine, resource graph, impact reports.
* JDTLS remains Serena’s default Java language-server backend, but V4 Java refactor execution still avoids JetBrains entirely. Serena already presents language servers as the default open backend and JetBrains as an optional richer backend, so V4 should continue narrowing that capability gap inside the open/LSP path rather than depending on the JetBrains plugin. 

V4 should not mainly add another list of individual refactorings. It should add **campaign-scale, verified, extensible Java modernization**: the ability to run multi-step, multi-module, multi-repository transformations with dependency upgrades, API compatibility checks, test selection, review packaging, and recipe interoperability.

---

# V4 objective

V4 turns Serena’s Java refactoring system into a **refactoring control plane**.

The main deliverables:

1. Multi-repository Java refactor campaigns.
2. API and binary compatibility analysis.
3. Dependency and build-file migration.
4. Version-aware framework migrations.
5. OpenRewrite interoperability.
6. Error Prone / Refaster interoperability.
7. Semantic patch DSL for Serena-native recipes.
8. Example-driven recipe authoring.
9. A persistent whole-program index.
10. Incremental validation and affected-test selection.
11. Refactor review packs with patch series and risk gates.
12. Rebase-aware campaign continuation.
13. Plugin SDK for company/framework-specific refactors.
14. Performance, observability, and audit logs for large transformations.

Serena’s current docs frame refactoring precision as a major product differentiator: without precise refactor tools, agents fall back to fragile search/replace, and the language-server path is currently weaker than JetBrains for move, inline, and propagated-delete depth.  V4’s goal is to make large Java modernization workflows reliable enough for agent-driven work without requiring JetBrains.

---

# 1. V4 architecture: refactor control plane

## 1.1 New top-level concept: campaign

V3 has transformation workspaces. V4 adds **campaigns**.

A campaign is a durable, resumable collection of transformation workspaces across one or more projects:

```json
{
  "campaignId": "jrc-20260607-000001",
  "name": "Migrate internal billing services to Jakarta and Java 21",
  "scope": {
    "repositories": [
      "/repos/billing-core",
      "/repos/billing-api",
      "/repos/billing-service"
    ],
    "modules": ["*"],
    "sourceSets": ["main", "test"]
  },
  "stages": [
    {
      "stageId": "stage-001",
      "kind": "dependencyUpgrade",
      "status": "previewReady"
    },
    {
      "stageId": "stage-002",
      "kind": "apiMigrationRecipeSet",
      "status": "blocked"
    },
    {
      "stageId": "stage-003",
      "kind": "packageMove",
      "status": "notStarted"
    }
  ],
  "validationPolicy": {
    "compile": true,
    "testSelection": "affected",
    "binaryCompatibility": true
  }
}
```

A campaign can be previewed, partitioned into patch sets, applied stage by stage, rebased after source changes, and resumed.

## 1.2 Sidecar process split

V1–V3 can use one sidecar process. V4 should split responsibilities internally while still exposing one Serena-facing sidecar endpoint:

```text
Serena Python
  ↓
JavaRefactorControlPlaneClient
  ↓
java-refactor-sidecar
  ├── javac semantic engine
  ├── project/build model service
  ├── persistent graph/index service
  ├── recipe execution service
  ├── validation/test-selection service
  ├── campaign/session store
  └── optional external-engine adapters
```

The core semantic engine remains built around javac APIs: `JavacTask` exposes javac-specific compiler access, `parse()` returns ASTs, and `analyze()` completes attribution; `Trees` bridges compiler tasks and tree APIs and exposes element/type/source-position access. ([Oracle Docs][1]) ([Oracle Docs][2])

## 1.3 Persistent index

V4 should stop rebuilding the whole transformation graph on every large request.

Add a persistent project index:

```text
.serena/java-refactor/index/
  project-model.sqlite
  source-symbols.sqlite
  references.sqlite
  resource-references.sqlite
  callgraph.sqlite
  type-hierarchy.sqlite
  buildgraph.sqlite
  testgraph.sqlite
  api-surface.sqlite
  campaign-store.sqlite
```

Use SQLite or RocksDB; SQLite is simpler and adequate for local agent workflows.

Core tables:

```sql
source_file(path, sha256, source_set, module, package_name, generated, last_indexed_at)
symbol(symbol_key, kind, fqn, owner_key, file_path, start_offset, end_offset, visibility, flags)
reference(source_symbol_key, target_symbol_key, file_path, start_offset, end_offset, kind)
resource_reference(file_path, start_offset, end_offset, target_key, kind, confidence)
call_edge(caller_key, callee_key, dispatch_kind)
type_edge(subtype_key, supertype_key, edge_kind)
api_member(symbol_key, api_level, binary_descriptor, source_signature)
```

Invalidation should be file-hash based. Build-model files invalidate the project/build graph; Java files invalidate their symbol/reference/call edges; resource files invalidate resource references.

---

# 2. New Python modules and tool families

Add:

```text
src/serena/java_refactor_v4/
  __init__.py
  campaign_client.py
  campaign_models.py
  review_pack.py
  recipe_registry.py
  validation_models.py
  api_compat.py
  external_adapters.py

src/serena/tools/java_refactor_v4_tools.py
```

Export tools from `src/serena/tools/__init__.py`, matching Serena’s existing module re-export pattern.  Keep them normal Serena `Tool` classes so existing tool metadata, active-project checks, timeout handling, exception wrapping, usage recording, and cache saving remain intact.  

---

# 3. V4 tool surface

## 3.1 Campaign lifecycle tools

```python
class JavaCreateRefactorCampaignTool(...):
    def apply(
        self,
        name: str,
        scope_json: str,
        validation_policy_json: str = "{}",
        preview: bool = True,
    ) -> str: ...
```

```python
class JavaAddCampaignStageTool(...):
    def apply(
        self,
        campaign_id: str,
        stage_kind: str,
        stage_args_json: str,
        after_stage_id: str | None = None,
        preview: bool = True,
    ) -> str: ...
```

```python
class JavaPreviewCampaignTool(...):
    def apply(
        self,
        campaign_id: str,
        stage_ids_json: str = "[]",
        max_answer_chars: int = -1,
    ) -> str: ...
```

```python
class JavaApplyCampaignStageTool(...):
    def apply(
        self,
        campaign_id: str,
        stage_id: str,
        require_clean_revision: bool = True,
        create_patch_only: bool = True,
        validate: bool = True,
    ) -> str: ...
```

```python
class JavaRebaseCampaignTool(...):
    def apply(
        self,
        campaign_id: str,
        strategy: str = "recompute",  # recompute | three_way | abort_on_conflict
        max_answer_chars: int = -1,
    ) -> str: ...
```

## 3.2 Index and analysis tools

```python
class JavaBuildPersistentIndexTool(...):
    def apply(
        self,
        scope_json: str = "{}",
        refresh: bool = False,
        max_answer_chars: int = -1,
    ) -> str: ...
```

```python
class JavaAnalyzeApiSurfaceTool(...):
    def apply(
        self,
        scope_json: str = "{}",
        include_internal: bool = False,
        max_answer_chars: int = -1,
    ) -> str: ...
```

```python
class JavaAnalyzeBinaryCompatibilityTool(...):
    def apply(
        self,
        campaign_id: str | None = None,
        baseline_ref: str | None = None,
        candidate_ref: str | None = None,
        max_answer_chars: int = -1,
    ) -> str: ...
```

```python
class JavaSelectAffectedTestsTool(...):
    def apply(
        self,
        campaign_id: str,
        stage_id: str | None = None,
        strategy: str = "hybrid",  # static | naming | coverage | hybrid
        max_answer_chars: int = -1,
    ) -> str: ...
```

## 3.3 Recipe and external interoperability tools

```python
class JavaImportOpenRewriteRecipeTool(...):
    def apply(
        self,
        recipe_yaml_or_id: str,
        mode: str = "translate",  # translate | delegate | inspect
        max_answer_chars: int = -1,
    ) -> str: ...
```

```python
class JavaExportOpenRewriteRecipeTool(...):
    def apply(
        self,
        serena_recipe_id: str,
        max_answer_chars: int = -1,
    ) -> str: ...
```

```python
class JavaImportRefasterTemplateTool(...):
    def apply(
        self,
        template_path: str,
        compile_template: bool = False,
        max_answer_chars: int = -1,
    ) -> str: ...
```

```python
class JavaAuthorRecipeFromExamplesTool(...):
    def apply(
        self,
        examples_json: str,
        recipe_name: str,
        validate_on_scope_json: str = "{}",
        preview: bool = True,
        max_answer_chars: int = -1,
    ) -> str: ...
```

## 3.4 Review and governance tools

```python
class JavaGenerateRefactorReviewPackTool(...):
    def apply(
        self,
        campaign_id: str,
        output_format: str = "markdown",  # markdown | json | patch_series
        include_risk_report: bool = True,
        include_test_plan: bool = True,
        max_answer_chars: int = -1,
    ) -> str: ...
```

```python
class JavaExplainRefactorRiskTool(...):
    def apply(
        self,
        campaign_id: str,
        stage_id: str | None = None,
        max_answer_chars: int = -1,
    ) -> str: ...
```

```python
class JavaApproveRiskGateTool(...):
    def apply(
        self,
        campaign_id: str,
        gate_id: str,
        approval_note: str,
    ) -> str: ...
```

---

# 4. Sidecar package layout

Add:

```text
java-refactor/src/main/java/io/serena/javarefactor/v4/
  campaign/
    CampaignManager.java
    Campaign.java
    CampaignStage.java
    CampaignStore.java
    CampaignRebaser.java
    StagePlanner.java
    PatchSeriesPlanner.java

  index/
    PersistentIndex.java
    IndexSchema.java
    IndexWriter.java
    IndexReader.java
    IndexInvalidator.java
    IncrementalIndexer.java

  api/
    ApiSurfaceAnalyzer.java
    BinaryCompatibilityAnalyzer.java
    SourceCompatibilityAnalyzer.java
    PublicApiPolicy.java
    SignatureDescriptor.java

  dependencies/
    DependencyGraph.java
    MavenDependencyPlanner.java
    GradleDependencyPlanner.java
    VersionCatalogPlanner.java
    DependencyUpgradePlanner.java

  validation/
    ValidationPipeline.java
    JavacValidationStep.java
    BuildToolValidationStep.java
    BinaryCompatibilityValidationStep.java
    TestSelectionEngine.java
    TestCommandPlanner.java
    ValidationCache.java

  recipes/
    SerenaRecipe.java
    SerenaRecipeParser.java
    SerenaSemanticPatch.java
    RecipeCompiler.java
    RecipeSandbox.java
    RecipeProvenance.java
    ExampleRecipeInferencer.java

  adapters/
    OpenRewriteAdapter.java
    OpenRewriteRecipeTranslator.java
    OpenRewriteRunner.java
    RefasterAdapter.java
    ErrorPronePatchAdapter.java

  governance/
    RiskGate.java
    RiskClassifier.java
    ReviewPackGenerator.java
    AuditLog.java
    ChangeProvenanceTracker.java

  plugins/
    PluginSdk.java
    PluginDescriptor.java
    PluginLoader.java
    PluginSandbox.java
```

---

# 5. Persistent whole-program index

## 5.1 Goals

The V4 index should make large transformations practical:

* Large package moves should not require fresh whole-project scans every time.
* Dead-code and API-surface analysis should be queryable.
* Campaign rebase should identify only changed semantic regions.
* Review packs should explain why every edit exists.

## 5.2 Index build pipeline

```text
Project model discovery
  ↓
Source file inventory
  ↓
javac parse/analyze per source set
  ↓
symbol extraction
  ↓
reference extraction
  ↓
call graph construction
  ↓
resource reference scan
  ↓
framework plugin enrichment
  ↓
API surface extraction
  ↓
persistent storage
```

`JavacTask.parse()` and `JavacTask.analyze()` remain the semantic backbone; `Trees` supplies element, type, path, scope, source-position, and accessibility queries. ([Oracle Docs][1]) ([Oracle Docs][2])

## 5.3 Index API

```java
interface JavaRefactorIndex {
    Optional<SymbolRecord> findSymbol(SymbolKey key);
    List<ReferenceRecord> findReferences(SymbolKey key);
    List<SymbolRecord> findPublicApi(ApiScope scope);
    List<CallEdge> findCallers(SymbolKey methodKey);
    List<TypeEdge> findSubtypes(SymbolKey typeKey);
    List<ResourceReferenceRecord> findResourceReferences(SymbolKey key);
    List<TestRecord> findLikelyTests(Collection<SymbolKey> changedSymbols);
}
```

## 5.4 Incremental invalidation

Rules:

| Change                             | Invalidate                                             |
| ---------------------------------- | ------------------------------------------------------ |
| `.java` file changed               | symbols, refs, calls, API rows for that file           |
| `pom.xml` / `build.gradle` changed | project model, classpath, affected source-set analysis |
| resource file changed              | resource refs for that file                            |
| recipe/plugin changed              | recipe compilation cache                               |
| framework config changed           | framework-enriched graph rows                          |
| source root changed                | package mapping and file inventory                     |

---

# 6. Multi-repository campaigns

## 6.1 Scope model

```json
{
  "repositories": [
    {
      "name": "billing-core",
      "root": "/repos/billing-core",
      "role": "library"
    },
    {
      "name": "billing-service",
      "root": "/repos/billing-service",
      "role": "consumer"
    }
  ],
  "dependencyLinks": [
    {
      "producer": "billing-core",
      "consumer": "billing-service",
      "coordinate": "com.acme:billing-core"
    }
  ]
}
```

## 6.2 Cross-repo dependency graph

Build:

```java
record CrossRepoGraph(
    Map<RepoId, ProjectGraph> projects,
    Map<ArtifactCoordinate, RepoId> producedArtifacts,
    Map<RepoId, Set<ArtifactCoordinate>> consumedArtifacts,
    Map<ApiSymbolKey, Set<CrossRepoReference>> apiReferences
) {}
```

Sources of truth:

* Maven reactor and installed coordinates.
* Gradle project dependencies and included builds.
* Explicit Serena config.
* Local checkout mapping.

## 6.3 Campaign stage application

V4 should support two apply modes:

```text
patch_only: write patch files, do not modify working tree
apply_in_place: modify working trees transactionally
```

Patch-only output:

```text
.serena/java-refactor/campaigns/<campaign-id>/
  0001-upgrade-dependencies.patch
  0002-migrate-api-calls.patch
  0003-move-packages.patch
  review.md
  risk-report.json
  test-plan.json
```

This is safer for large campaigns and easier for agents to review.

---

# 7. API and binary compatibility analysis

V3 validates source. V4 must reason about API compatibility.

## 7.1 API surface extraction

Extract:

* public/protected classes
* public/protected constructors
* public/protected methods
* public/protected fields
* annotations
* records and record components
* sealed permits
* module exports/opens
* service loader interfaces/providers

Model:

```java
record ApiMember(
    SymbolKey key,
    String binaryName,
    String descriptor,
    String genericSignature,
    ApiVisibility visibility,
    ApiKind kind,
    Path sourceFile,
    boolean exportedByModule
) {}
```

## 7.2 Binary compatibility rules

Implement a compatibility checker for common Java binary compatibility hazards:

| Change                           | Default                                           |
| -------------------------------- | ------------------------------------------------- |
| remove public method             | breaking                                          |
| change method descriptor         | breaking                                          |
| rename public class              | breaking                                          |
| remove public field              | breaking                                          |
| change field type                | breaking                                          |
| move class package               | breaking                                          |
| add method                       | compatible unless conflicts                       |
| add abstract method to interface | breaking for implementors                         |
| add default method to interface  | usually source/binary-compatible but can conflict |
| change superclass                | risky                                             |
| change sealed permits            | breaking/risky                                    |
| change record component          | breaking                                          |

Tool output:

```json
{
  "binaryCompatibility": {
    "status": "breaking",
    "breakages": [
      {
        "kind": "METHOD_DESCRIPTOR_CHANGED",
        "symbol": "com.acme.BillingApi#calculate(Order)",
        "oldDescriptor": "(Lcom/acme/Order;)Lcom/acme/Money;",
        "newDescriptor": "(Lcom/acme/Order;Ljava/util/Currency;)Lcom/acme/Money;"
      }
    ]
  }
}
```

## 7.3 Policy gates

Campaigns should block on breaking API changes unless configured:

```yaml
java_refactor:
  v4:
    api:
      binary_compatibility: enforce
      allow_breaking_internal_api: true
      allow_breaking_public_api: false
```

Risk gate:

```json
{
  "gateId": "api-breaking-001",
  "severity": "blocker",
  "message": "Stage changes 7 exported public API descriptors."
}
```

---

# 8. Dependency and build-file migration

V3 mostly avoided build-file rewrites. V4 should support them under strict parsers and risk gates.

## 8.1 Maven

Support:

* dependency version upgrade
* plugin version upgrade
* group/artifact coordinate rewrite
* property-based version update
* dependency-management update
* module list update
* compiler source/target/release update

Model:

```java
record MavenEditPlan(
    Path pom,
    List<XmlEdit> edits,
    List<DependencyChange> dependencyChanges,
    List<RiskGate> gates
) {}
```

Rules:

* Prefer updating version properties over literal dependency versions when property indirection exists.
* Preserve formatting and comments.
* Refuse ambiguous property ownership.
* Do not reorder dependencies unless recipe requests it.

## 8.2 Gradle Groovy DSL

Support conservative edits:

* `dependencies { implementation "g:a:v" }`
* `implementation("g:a:v")`
* plugin versions
* Java toolchain version
* simple `ext` version properties

## 8.3 Gradle Kotlin DSL

Support only simple structural edits initially:

* `implementation("g:a:v")`
* version catalog references when `libs.versions.toml` exists
* plugin version changes

Refuse dynamic expressions:

```kotlin
implementation("$group:$artifact:$version")
```

unless a recipe supplies an explicit strategy.

## 8.4 Version catalogs

Support:

```text
gradle/libs.versions.toml
```

Operations:

* add library alias
* update version
* update plugin version
* migrate literal dependency to catalog alias

Risk classification:

```text
SAFE: exact alias/version update
REVIEW_REQUIRED: adding new alias
REFUSED: dynamic catalog generation
```

---

# 9. OpenRewrite interoperability

V4 should not replace OpenRewrite. It should interoperate with it.

OpenRewrite’s official docs describe it as an open-source automated refactoring ecosystem that runs refactoring recipes for framework migrations, security fixes, and style tasks; it uses Lossless Semantic Trees and visitors aggregated into recipes, and has Maven/Gradle plugins for running recipes. ([docs.openrewrite.org][3]) The recipe catalog is broad and includes Java, Maven, Gradle, XML, YAML, JSON, and many framework domains, which makes it useful as an optional source of migration knowledge rather than something Serena must duplicate. ([docs.openrewrite.org][4])

## 9.1 Three interop modes

### `inspect`

Read an OpenRewrite recipe and report:

```json
{
  "recipeId": "org.openrewrite.java.migrate.UpgradeToJava21",
  "supportedBySerena": "partial",
  "rules": [
    {
      "kind": "ChangeType",
      "serenaEquivalent": "replaceType",
      "status": "translatable"
    },
    {
      "kind": "CustomVisitor",
      "status": "delegateOnly"
    }
  ]
}
```

### `translate`

Translate supported OpenRewrite recipe forms into Serena-native recipes:

```text
ChangeType -> Serena replaceType
ChangeMethodName -> Serena changeMethodName
ChangePackage -> Serena renamePackage
AddOrUpdateAnnotationAttribute -> Serena annotation rewrite
UpgradeDependencyVersion -> Serena dependency upgrade
```

### `delegate`

Run OpenRewrite externally and import the patch as a Serena campaign stage:

```text
OpenRewrite runner
  ↓
patch/diff
  ↓
Serena patch importer
  ↓
semantic validation + risk report
  ↓
campaign stage
```

## 9.2 Safety rule

Even in delegate mode, Serena should not blindly apply external patches. It should:

1. Import patch.
2. Map changed spans back to symbols/resources.
3. Run javac validation.
4. Run API compatibility checks.
5. Produce a Serena review pack.
6. Apply only if campaign gates pass.

---

# 10. Error Prone / Refaster interoperability

Error Prone Refaster templates are before/after templates: the docs describe compiling template classes into `.refaster` files and using Error Prone patch flags to refactor source code according to those rules. ([Error Prone][5]) Refaster is especially relevant for V4 because its documented strengths include migrating method calls, migrating calls with particular argument types, migrating fluent invocation sequences, and replacing consecutive statement sequences. ([Error Prone][5])

## 10.1 Import Refaster templates

Flow:

```text
.refaster or template source
  ↓
RefasterAdapter
  ↓
Serena pattern model
  ↓
match against javac AST + type info
  ↓
preview replacements
```

For templates Serena can translate, run natively.

For templates it cannot translate, use delegate mode:

```text
Error Prone patch run
  ↓
import generated patch
  ↓
Serena validation/risk gates
```

## 10.2 Serena-native pattern subset

Support equivalent forms:

```java
@BeforeTemplate
T oldPattern(A a, B b) { ... }

@AfterTemplate
T newPattern(A a, B b) { ... }
```

Map to:

```json
{
  "kind": "semanticPatch",
  "before": "...",
  "after": "...",
  "typeConstraints": {...}
}
```

## 10.3 Use cases

* Method-call modernization.
* Fluent chain simplification.
* Stream API simplification.
* Assertion migration.
* Common performance replacements.
* Library API migration.

---

# 11. Serena semantic patch DSL

V3 has recipes. V4 should formalize a **semantic patch DSL** designed for agent-readable and agent-authorable refactors.

## 11.1 DSL goals

* More expressive than simple JSON replacement rules.
* Less complex than arbitrary Java plugin code.
* Type-aware.
* Import-aware.
* Previewable.
* Sandboxed.
* Explainable.

## 11.2 Example

```yaml
id: com.acme.migrate-money-of
name: Replace Money.fromCents with Money.ofMinor
language: java
match:
  expression: "Money.fromCents($amount)"
  constraints:
    amount:
      type: "long|int|java.math.BigInteger"
replace:
  expression: "Money.ofMinor($amount, Currency.USD)"
imports:
  add:
    - "java.util.Currency"
validation:
  require_compile: true
risk:
  default: safe
```

## 11.3 Supported match shapes

```text
expression
statement
method_invocation
constructor_invocation
field_access
annotation
class_declaration
method_declaration
import
dependency
resource_exact_value
```

## 11.4 Template variables

```yaml
variables:
  receiver:
    kind: expression
    type: com.acme.Client
  arg0:
    kind: expression
    purity: pure_or_single_use
  method:
    kind: method
    owner: com.acme.Client
```

## 11.5 Control features

```yaml
where:
  - "targetMethod.isDeprecated()"
  - "receiver.type.isSubtypeOf('com.acme.Client')"
  - "not enclosingMethod.hasAnnotation('Generated')"
```

Keep this controlled. Do not allow arbitrary Java code inside YAML. For arbitrary logic, require a compiled plugin.

---

# 12. Example-driven recipe authoring

Agents often know “before and after” examples. V4 can infer candidate semantic recipes from examples and validate them.

## 12.1 Input

```json
{
  "examples": [
    {
      "before": "assertEquals(true, result.isValid());",
      "after": "assertTrue(result.isValid());"
    },
    {
      "before": "Assert.assertEquals(true, service.ready());",
      "after": "Assert.assertTrue(service.ready());"
    }
  ]
}
```

## 12.2 Output

```yaml
id: inferred.assert-equals-true-to-assert-true
match:
  methodInvocation:
    owner: "org.junit.Assert"
    name: "assertEquals"
    arguments:
      - literal: true
      - capture: actual
replace:
  methodInvocation:
    owner: "org.junit.Assert"
    name: "assertTrue"
    arguments:
      - "$actual"
risk:
  default: review_required
```

## 12.3 Algorithm

1. Parse before/after snippets in synthetic context.
2. Resolve symbols and types.
3. Compute AST edit pattern.
4. Generalize stable nodes to captures.
5. Infer constraints:

   * owner type
   * method name
   * argument count
   * literal positions
   * type constraints
6. Search project for candidate matches.
7. Validate on a sample.
8. Return recipe with confidence score.

## 12.4 Safety

Example-derived recipes must default to `review_required`, not `safe`.

---

# 13. Version-aware framework migrations

V3 plugins detect framework roots and references. V4 plugins should understand **versions** and **migration recipes**.

## 13.1 Framework version model

```java
record FrameworkVersionContext(
    String frameworkId,
    Version currentVersion,
    Optional<Version> targetVersion,
    List<ArtifactCoordinate> coordinates,
    Map<String, String> detectedFeatures
) {}
```

Examples:

```text
spring-boot: 2.7.x -> 3.3.x
jakarta-ee: javax.* -> jakarta.*
junit: 4.x -> 5.x
hibernate: 5.x -> 6.x
jackson: 2.x -> 3.x
```

## 13.2 Migration plugin interface

```java
interface FrameworkMigrationPlugin extends FrameworkPlugin {
    List<MigrationRecipe> availableMigrations(FrameworkVersionContext context);
    TransformationWorkspace planMigration(MigrationRequest request);
    List<RiskGate> migrationRiskGates(MigrationRequest request);
}
```

## 13.3 Concrete initial migrations

Keep initial scope realistic:

* `javax.*` to `jakarta.*` import/type/package migration.
* JUnit 4 assertion/import/annotation basics.
* Spring Boot property key exact migrations from a curated map.
* Hibernate package/method exact migrations from curated recipes.
* Jackson annotation/value warnings, not broad behavior changes.

Do not claim full Spring Boot or Hibernate migration completeness. V4 should support staged, exact, validated migrations.

---

# 14. Test selection engine

V4 should reduce validation cost by selecting affected tests.

## 14.1 Test graph

```java
record TestGraph(
    Map<TestKey, Set<SymbolKey>> staticallyReferencedSymbols,
    Map<SymbolKey, Set<TestKey>> testsBySymbol,
    Map<String, Set<TestKey>> namingHeuristicLinks,
    Optional<CoverageMap> coverageMap
) {}
```

Signals:

* Static references from test source.
* Naming conventions:

  * `FooTest` ↔ `Foo`
  * `FooIT` ↔ `Foo`
* Package proximity.
* Framework annotations.
* Optional coverage file import:

  * JaCoCo XML
  * Gradle test reports
  * Maven Surefire reports

## 14.2 Tool output

```json
{
  "strategy": "hybrid",
  "selectedTests": [
    {
      "test": "com.acme.billing.OrderServiceTest",
      "reason": "references changed method com.acme.billing.OrderService#calculate"
    },
    {
      "test": "com.acme.billing.OrderApiIT",
      "reason": "same package and integration-test naming heuristic"
    }
  ],
  "commands": [
    "./gradlew :billing-service:test --tests com.acme.billing.OrderServiceTest",
    "./gradlew :billing-service:integrationTest --tests com.acme.billing.OrderApiIT"
  ]
}
```

## 14.3 Validation policy

```yaml
java_refactor:
  v4:
    validation:
      test_selection:
        enabled: true
        strategy: hybrid
        require_user_to_run_commands: false
```

Serena should generate commands. It should only execute them when existing Serena shell/tool policy permits and the user/tool call explicitly requests execution.

---

# 15. Review packs and patch partitioning

Large refactors need readable review output.

## 15.1 Patch partitioning

Partition by:

1. Build/dependency changes.
2. Mechanical type/package moves.
3. API call migrations.
4. Resource updates.
5. Test updates.
6. Formatting/import cleanup.

Output:

```text
0001-build-upgrade-java-21.patch
0002-rename-javax-to-jakarta-imports.patch
0003-update-spring-properties.patch
0004-fix-tests.patch
0005-import-cleanup.patch
```

## 15.2 Review pack contents

```text
review.md
risk-report.json
api-compatibility.json
test-plan.json
resource-changes.json
recipe-provenance.json
patches/
  0001-...
```

`review.md` should include:

* campaign summary
* file counts
* public API impact
* binary compatibility result
* risk gates
* manual review items
* test commands
* non-applied candidates
* rollback plan

## 15.3 Provenance

Every edit should be traceable:

```json
{
  "path": "src/main/java/com/acme/Foo.java",
  "startOffset": 812,
  "endOffset": 846,
  "newText": "jakarta.persistence.Entity",
  "provenance": {
    "campaignId": "jrc-...",
    "stageId": "stage-002",
    "recipeId": "javax-to-jakarta-basic",
    "ruleId": "replace-type-javax.persistence.Entity",
    "confidence": "safe"
  }
}
```

---

# 16. Rebase-aware campaign continuation

Between preview and apply, the repo may change. V4 should support campaign rebase.

## 16.1 Rebase strategies

```text
recompute
  Re-run stage planner from current index.

three_way
  Use old base, old planned result, current file to merge local text edits.

abort_on_conflict
  Refuse if any touched file changed.
```

Default:

```text
recompute
```

for semantic refactors, because semantic plans are safer when recomputed.

## 16.2 Conflict model

```json
{
  "status": "conflicts",
  "conflicts": [
    {
      "path": "src/main/java/com/acme/Foo.java",
      "kind": "TARGET_SYMBOL_CHANGED",
      "message": "Method Foo#calculate(Order) no longer resolves to the same descriptor."
    }
  ]
}
```

## 16.3 Stable semantic anchors

Store anchors:

```java
record SemanticAnchor(
    SymbolKey symbolKey,
    Path file,
    int oldStartOffset,
    int oldEndOffset,
    String oldText,
    String enclosingSymbolKey,
    String contextHash
) {}
```

During rebase:

1. Try symbol key.
2. Try enclosing symbol + context hash.
3. Try local AST similarity.
4. If ambiguous, refuse.

---

# 17. Governance and risk gates

V4 needs explicit approval points.

## 17.1 Gate types

```text
PUBLIC_API_BREAK
BINARY_COMPAT_BREAK
BUILD_FILE_EDIT
RESOURCE_REVIEW_REQUIRED
LOW_CONFIDENCE_REFLECTION
FRAMEWORK_BEHAVIOR_CHANGE
TESTS_NOT_RUN
EXTERNAL_PATCH_IMPORTED
DYNAMIC_GRADLE_UNSUPPORTED
```

## 17.2 Gate lifecycle

```json
{
  "gateId": "gate-017",
  "severity": "requires_approval",
  "status": "open",
  "message": "Stage rewrites 14 Spring configuration property keys.",
  "evidence": [...]
}
```

Tool:

```python
java_approve_risk_gate(campaign_id, gate_id, approval_note)
```

Approvals should be recorded in the audit log.

## 17.3 Apply policy

```yaml
java_refactor:
  v4:
    gates:
      require_approval_for_review_required: true
      block_on_binary_break: true
      block_on_unvalidated_external_patch: true
      block_on_unrun_tests: false
```

---

# 18. Plugin SDK

V3 has framework plugin SPI. V4 should make it a documented SDK.

## 18.1 Plugin packaging

```text
serena-java-refactor-plugin.yaml
plugin.jar
```

Descriptor:

```yaml
id: com.acme.billing-serena-plugin
version: 1.0.0
entrypoint: com.acme.serena.BillingFrameworkPlugin
capabilities:
  - resourceReferenceProvider
  - frameworkMigrationPlugin
  - riskClassifier
  - recipeProvider
```

## 18.2 Plugin API

```java
public interface SerenaJavaRefactorPlugin {
    String id();
    List<ResourceReferenceProvider> resourceProviders();
    List<FrameworkPlugin> frameworkPlugins();
    List<MigrationRecipeProvider> migrationRecipes();
    List<RiskClassifier> riskClassifiers();
}
```

## 18.3 Sandboxing

Default restrictions:

* No network.
* No file writes outside campaign workspace.
* Read-only project access through provided APIs.
* Time limits.
* Memory limits.
* Explicit opt-in for external processes.

---

# 19. External engine adapter safety

V4 may delegate to OpenRewrite or Error Prone, but it must never treat external patches as trusted.

Adapter pipeline:

```text
external engine plan/run
  ↓
patch capture
  ↓
Serena patch parser
  ↓
semantic attribution of changed spans
  ↓
risk classification
  ↓
javac validation
  ↓
API compatibility validation
  ↓
review pack / campaign stage
```

External stage metadata:

```json
{
  "externalEngine": "openrewrite",
  "recipe": "org.openrewrite.java.migrate.UpgradeToJava21",
  "mode": "delegate",
  "patchImported": true,
  "serenaValidated": true
}
```

---

# 20. Observability and audit logs

V4 campaigns can be long and complex. Add structured logs.

## 20.1 Metrics

Track:

```text
index build time
files indexed
symbols indexed
references indexed
resource references indexed
campaign planning time
validation time
javac diagnostic counts
test selection time
patch size
risk gate counts
external adapter runtime
```

## 20.2 Audit log

```json
{
  "timestamp": "2026-06-07T18:12:00Z",
  "event": "stage_applied",
  "campaignId": "jrc-...",
  "stageId": "stage-002",
  "tool": "java_apply_campaign_stage",
  "filesChanged": 214,
  "riskGatesApproved": ["gate-017"],
  "validation": {
    "javacErrors": 0,
    "binaryCompatibility": "compatible"
  }
}
```

Store under:

```text
.serena/java-refactor/campaigns/<campaign-id>/audit.jsonl
```

---

# 21. V4 configuration

Add:

```yaml
java_refactor:
  v4:
    enabled: true

    index:
      enabled: true
      storage: sqlite
      rebuild_on_model_change: true
      max_db_size_mb: 2048
      background_warmup: false

    campaigns:
      max_open_campaigns: 8
      campaign_ttl_days: 14
      default_apply_mode: patch_only
      require_clean_revision_on_apply: true
      allow_multi_repo: true

    api:
      analyze_binary_compatibility: true
      public_api_policy: block_breaking
      internal_api_policy: warn_breaking
      module_exports_are_public_api: true

    dependencies:
      maven_edits: true
      gradle_groovy_edits: true
      gradle_kotlin_edits: conservative
      version_catalog_edits: true
      dynamic_build_logic_policy: refuse

    external_adapters:
      openrewrite:
        enabled: false
        mode: delegate
        executable: null
      error_prone:
        enabled: false
        mode: delegate
        classpath: []

    recipes:
      serena_dsl_enabled: true
      user_recipe_dirs: []
      example_inference_enabled: true
      inferred_recipe_default_risk: review_required

    validation:
      javac_required: true
      binary_compat_required: true
      build_tool_compile: optional
      affected_tests: suggest
      run_tests: false
      max_validation_seconds: 600

    gates:
      require_approval_for_review_required: true
      block_on_binary_break: true
      block_on_unvalidated_external_patch: true
      block_on_low_confidence_resource_edit: true

    review:
      generate_patch_series: true
      include_provenance: true
      include_test_plan: true
      include_rollback_plan: true

    plugins:
      enabled: true
      plugin_dirs: []
      sandbox: true
```

---

# 22. Implementation phases

## Phase V4-1: Campaign core

Deliverables:

* `CampaignManager`
* persistent campaign store
* campaign/stage model
* patch-only apply mode
* campaign preview/apply/cancel/rebase tools
* audit log skeleton

Acceptance criteria:

* Create campaign.
* Add V3 transformation workspace as a campaign stage.
* Generate patch-only output.
* Rebase by recomputation.
* Audit events are written.

## Phase V4-2: Persistent index

Deliverables:

* SQLite schema
* incremental indexer
* source symbol/reference tables
* resource reference tables
* API surface table
* invalidation engine
* `java_build_persistent_index`

Acceptance criteria:

* Warm index avoids full re-scan for unchanged project.
* Changed Java file invalidates only its rows.
* Build-file change invalidates project model.
* Queries return symbol refs, public API, resource refs, and call edges.

## Phase V4-3: API compatibility

Deliverables:

* API surface analyzer
* binary descriptor generator
* compatibility checker
* risk gates for API breaks
* `java_analyze_api_surface`
* `java_analyze_binary_compatibility`

Acceptance criteria:

* Detect public method removal.
* Detect descriptor changes.
* Detect moved/renamed public classes.
* Detect new abstract interface method.
* Treat module exports as public API.
* Produce machine-readable breakage report.

## Phase V4-4: Build/dependency migration

Deliverables:

* Maven edit planner
* Gradle Groovy conservative planner
* Gradle Kotlin conservative planner
* version catalog planner
* dependency-upgrade campaign stage

Acceptance criteria:

* Update Maven dependency version through property.
* Update direct Maven dependency version.
* Update Gradle literal dependency version.
* Update version catalog entry.
* Refuse dynamic build logic with clear reason.
* Validate project model after build edit.

## Phase V4-5: OpenRewrite adapter

Deliverables:

* inspect/translate/delegate modes
* supported recipe mapping
* external patch importer
* OpenRewrite provenance tags
* `java_import_openrewrite_recipe`
* `java_export_openrewrite_recipe`

Acceptance criteria:

* Translate simple type/method/package/dependency recipes.
* Delegate unsupported recipes.
* Import patch into campaign stage.
* Run Serena validation and risk classification.
* Never apply external patch without gates.

## Phase V4-6: Refaster / Error Prone adapter

Deliverables:

* Refaster template importer
* translatable subset
* delegate patch mode
* Error Prone patch importer
* `java_import_refaster_template`

Acceptance criteria:

* Translate simple before/after expression template.
* Translate simple statement template.
* Delegate unsupported template.
* Import patch and validate.

## Phase V4-7: Semantic patch DSL

Deliverables:

* YAML/JSON parser
* recipe compiler
* type-aware matcher
* template substitution engine
* import planner integration
* recipe sandbox
* recipe provenance

Acceptance criteria:

* Run expression replacement recipe.
* Run method invocation recipe.
* Run annotation rewrite recipe.
* Run dependency recipe.
* Validate substitutions with javac.
* Classify recipe edits by confidence.

## Phase V4-8: Example-driven recipe inference

Deliverables:

* snippet parser
* AST edit differ
* capture generalizer
* candidate matcher
* confidence scorer
* `java_author_recipe_from_examples`

Acceptance criteria:

* Infer simple method-call migration.
* Infer literal-argument simplification.
* Infer constructor replacement.
* Mark inferred recipes as review-required.
* Validate inferred recipe on sample matches.

## Phase V4-9: Test selection

Deliverables:

* test graph
* static reference test selector
* naming heuristic selector
* optional coverage importer
* build-tool test command planner
* `java_select_affected_tests`

Acceptance criteria:

* Select tests referencing changed symbols.
* Select likely tests by naming/package heuristic.
* Generate Maven and Gradle test commands.
* Include confidence/reason for each test.

## Phase V4-10: Review packs and governance

Deliverables:

* patch partitioner
* review pack generator
* risk report
* approval gates
* provenance report
* rollback plan
* `java_generate_refactor_review_pack`
* `java_approve_risk_gate`

Acceptance criteria:

* Generate patch series.
* Generate `review.md`.
* Generate JSON risk/test/API reports.
* Block apply on unapproved required gates.
* Record approvals in audit log.

## Phase V4-11: Plugin SDK

Deliverables:

* plugin descriptor
* plugin loader
* resource/framework/recipe provider APIs
* sandbox policy
* example plugin
* documentation

Acceptance criteria:

* Load local plugin.
* Plugin contributes resource references.
* Plugin contributes risk gate.
* Plugin cannot write outside allowed area.
* Plugin failure is isolated and reported.

## Phase V4-12: Hardening and scale

Deliverables:

* large-repo performance suite
* index compaction
* cache cleanup
* timeout controls
* memory controls
* stress tests
* docs

Acceptance criteria:

* Multi-module repo with thousands of Java files indexes incrementally.
* Campaign preview is resumable.
* Sidecar crash does not corrupt campaign store.
* Patch-only mode works for multi-repo campaigns.
* No JetBrains dependency is introduced.

---

# 23. Test matrix

## 23.1 Fixture repositories

Add:

```text
test/resources/repos/java_refactor_v4/
  campaign-single-repo/
  campaign-multi-repo/
  persistent-index/
  api-compatibility/
  binary-compatibility/
  maven-dependency-migration/
  gradle-groovy-migration/
  gradle-kotlin-migration/
  version-catalog/
  openrewrite-adapter/
  refaster-adapter/
  semantic-patch-dsl/
  example-recipe-inference/
  affected-test-selection/
  review-pack/
  plugin-sdk/
  spring-boot-versioned/
  jakarta-migration/
```

## 23.2 Campaign tests

* create campaign
* add stages
* preview selected stage
* patch-only output
* apply in place
* rebase by recompute
* stage blocked by risk gate
* approve gate and apply
* audit log recovery after crash

## 23.3 Index tests

* cold index
* warm index
* Java file invalidation
* resource file invalidation
* build-file invalidation
* symbol lookup
* reference lookup
* API surface lookup
* call graph lookup

## 23.4 API compatibility tests

* public method removed
* public method parameter changed
* public return type changed
* public class moved
* interface abstract method added
* default method added
* record component changed
* sealed permits changed
* module export removed
* internal-only change allowed

## 23.5 Dependency migration tests

* Maven property version update
* Maven dependency-management update
* Gradle literal update
* Gradle Kotlin literal update
* version catalog update
* ambiguous property refusal
* dynamic Gradle expression refusal
* project model refresh after update

## 23.6 OpenRewrite tests

* inspect supported recipe
* translate simple recipe
* delegate unsupported recipe
* import patch
* provenance added
* validation catches compile break
* gate blocks external unvalidated patch

## 23.7 Refaster tests

* import expression template
* import statement template
* delegate unsupported advanced template
* import Error Prone patch
* validate and classify risk

## 23.8 Semantic DSL tests

* expression rewrite
* method call rewrite
* constructor rewrite
* annotation rewrite
* import add/remove
* dependency rewrite
* resource exact-value rewrite
* invalid recipe refusal

## 23.9 Example inference tests

* infer method-call migration
* infer assertion simplification
* infer constructor migration
* infer with type constraint
* reject overgeneralized pattern
* default review-required risk

## 23.10 Test selection tests

* static test references
* naming heuristic
* package heuristic
* coverage XML import
* Maven command generation
* Gradle command generation
* no-test-found warning

## 23.11 Review pack tests

* patch partitioning
* review markdown
* risk report JSON
* API report JSON
* test plan JSON
* rollback plan
* provenance per edit

## 23.12 Plugin SDK tests

* load plugin
* plugin contributes resource provider
* plugin contributes recipe
* plugin contributes risk classifier
* plugin timeout
* plugin sandbox violation

---

# 24. Performance targets

For a large local Java workspace:

```text
10,000 Java files
1,000 resource files
200 Maven/Gradle modules
3 local repositories
```

Targets:

| Operation                    |  Cold target |              Warm target |
| ---------------------------- | -----------: | -----------------------: |
| persistent index build       |  under 5 min | under 30 sec incremental |
| API surface analysis         | under 60 sec |             under 10 sec |
| package campaign preview     |  under 2 min |             under 30 sec |
| dependency migration preview | under 60 sec |             under 20 sec |
| recipe scan                  |  under 3 min |             under 45 sec |
| affected test selection      | under 30 sec |             under 10 sec |
| review pack generation       | under 30 sec |             under 15 sec |

These are engineering targets, not correctness gates.

---

# 25. Safety model

V4 apply should require all of these unless explicitly configured otherwise:

```text
all touched files match expected revision or campaign was successfully rebased
javac validation passes
API compatibility policy passes
external patches are semantically imported and validated
required risk gates are approved
build-file edits are parsed and project model refresh succeeds
resource edits are high-confidence or approved
patch series can be rolled back
```

Risk classes:

```text
SAFE
REVIEW_REQUIRED
APPROVAL_REQUIRED
BLOCKED
```

Default policy:

```text
SAFE can apply.
REVIEW_REQUIRED appears in preview but requires config or approval.
APPROVAL_REQUIRED blocks until approved.
BLOCKED cannot apply.
```

---

# 26. What V4 should still not attempt

Keep these outside V4:

* Guaranteed behavioral equivalence for arbitrary refactors.
* Fully automatic Spring Boot major-version migration.
* Fully automatic Hibernate major-version migration.
* Arbitrary Gradle plugin logic rewriting.
* Kotlin source transformation.
* Android resource/manifest refactoring.
* Runtime reflection correctness.
* Distributed CI orchestration.
* Automatic publication of multi-repo changes.
* Automatic dependency version choice from the internet.
* Security-sensitive dependency upgrades without human review.

---

# 27. V4 acceptance criteria

V4 is complete when Serena can do all of this without JetBrains:

| Capability           | Required behavior                                                                       |
| -------------------- | --------------------------------------------------------------------------------------- |
| Campaigns            | Create, preview, apply, patch, rebase, audit multi-stage Java refactors.                |
| Persistent index     | Incrementally query symbols, refs, resources, call graph, API surface, and tests.       |
| Multi-repo scope     | Plan changes across linked local repositories.                                          |
| API compatibility    | Detect and gate source/binary public API breakage.                                      |
| Dependency migration | Safely edit Maven, Gradle literals, and version catalogs.                               |
| OpenRewrite interop  | Inspect, translate where possible, delegate where necessary, validate imported patches. |
| Refaster interop     | Import/translate simple templates and validate delegated patches.                       |
| Semantic DSL         | Run Serena-native type-aware patch recipes.                                             |
| Example inference    | Generate review-required recipes from before/after examples.                            |
| Test selection       | Produce affected test list and Maven/Gradle commands.                                   |
| Review packs         | Generate patch series, risk report, API report, test plan, provenance, rollback plan.   |
| Risk gates           | Block or approve risky stages with audit trail.                                         |
| Plugin SDK           | Load sandboxed domain plugins for resources, recipes, and risk classifiers.             |

The highest-value V4 feature is **campaign-scale modernization with API gates and review packs**. It gives agents a controlled way to perform broad Java upgrades and refactors as explainable, validated patch series rather than as ad hoc file edits.

[1]: https://docs.oracle.com/en/java/javase/11/docs/api/jdk.compiler/com/sun/source/util/JavacTask.html "JavacTask (Java SE 11 & JDK 11 )"
[2]: https://docs.oracle.com/en/java/javase/11/docs/api/jdk.compiler/com/sun/source/util/Trees.html "Trees (Java SE 11 & JDK 11 )"
[3]: https://docs.openrewrite.org/ "OpenRewrite by Moderne | Large Scale Automated Refactoring | OpenRewrite Docs"
[4]: https://docs.openrewrite.org/recipes "Recipe catalog | OpenRewrite Docs"
[5]: https://errorprone.info/docs/refaster "Refaster templates"
