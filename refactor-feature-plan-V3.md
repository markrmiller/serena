## Assumption for V3

This V3 plan assumes V1 and V2 already exist:

* V1: Java compiler-backed sidecar, semantic rename, safe delete, top-level type move, inline local/constant.
* V2: refactor sessions, type hierarchy index, change signature, move member, extract method, extract interface, introduce parameter/field, encapsulate field, constrained inline method.
* JDTLS remains Serena’s normal Java language-server backend.
* The Java refactoring engine remains independent of JetBrains APIs.

V3 should address the gap that remains after symbol/member-level refactoring: **whole-package, whole-module, framework-aware, resource-aware, and recipe-based Java migrations**. Serena already positions language servers as the default open backend and JetBrains as the richer optional backend; V3’s purpose is to close more of that gap without requiring the JetBrains plugin.  Current Serena docs explicitly show language-server mode lacking move, inline, and propagated-delete depth compared with JetBrains, which is the product gap V1/V2/V3 are meant to narrow. 

---

# V3 objective

V3 should turn the Java sidecar from a collection of single refactor operations into a **whole-repo transformation platform**.

The core V3 deliverables are:

1. Package and directory refactoring.
2. Module-aware refactoring.
3. Propagating safe delete / dead-code removal.
4. Extract class / extract superclass.
5. Replace inheritance with delegation.
6. Generalized inline method for multi-statement methods.
7. Convert anonymous class to lambda.
8. Convert lambda to method reference.
9. Java API migration recipes.
10. Resource-aware refactoring for XML, properties, YAML, JSON, service-loader files, and common framework annotations.
11. Multi-operation refactor recipes with staged preview/apply.
12. Refactor impact reports for agents.

V3 should still default to conservative behavior. It should preview broadly, apply only when validation passes, and refuse dynamic cases that cannot be proven safe.

---

# 1. V3 architecture shift

## 1.1 From “refactor session” to “transformation workspace”

V2 introduced refactor sessions. V3 should add a higher-level unit: a **transformation workspace**.

A transformation workspace can contain multiple related refactor sessions:

```json
{
  "workspaceId": "jwt-20260607-000042",
  "goal": "Move package com.acme.legacy.billing to com.acme.billing",
  "sessions": [
    {
      "sessionId": "jr-001",
      "operation": "renamePackage"
    },
    {
      "sessionId": "jr-002",
      "operation": "movePackageDirectory"
    },
    {
      "sessionId": "jr-003",
      "operation": "rewriteSpringReferences"
    },
    {
      "sessionId": "jr-004",
      "operation": "rewriteServiceLoaderFiles"
    }
  ],
  "status": "previewReady"
}
```

New sidecar protocol:

```json
{
  "id": 100,
  "method": "transformation.createWorkspace",
  "params": {
    "goal": "rename package",
    "operation": "renamePackage",
    "arguments": {
      "oldPackage": "com.acme.legacy.billing",
      "newPackage": "com.acme.billing",
      "includeSubpackages": true,
      "rewriteResources": true,
      "rewriteBuildFiles": false
    }
  }
}
```

Response:

```json
{
  "workspaceId": "jwt-20260607-000042",
  "summary": "Rename package com.acme.legacy.billing to com.acme.billing including subpackages",
  "status": "previewReady",
  "stats": {
    "javaFilesMoved": 42,
    "javaFilesEdited": 119,
    "resourceFilesEdited": 8,
    "buildFilesEdited": 0,
    "textEdits": 391,
    "fileOperations": 42
  },
  "warnings": [
    {
      "code": "REFLECTION_STRING_MATCH",
      "message": "Found 3 string literals containing old package name. They are shown in preview but not changed by default."
    }
  ]
}
```

Why this matters: package moves, API migrations, and resource-aware refactors are not a single edit plan. They are bundles of coordinated plans with separate preconditions and validations.

## 1.2 Transformation graph

Add a graph layer above the existing V2 indexes.

```java
record TransformationGraph(
    ProjectGraph projectGraph,
    JavaSymbolGraph javaSymbolGraph,
    TypeHierarchyIndex typeHierarchy,
    CallGraph callGraph,
    ResourceReferenceGraph resourceGraph,
    BuildGraph buildGraph,
    TestGraph testGraph
) {}
```

This graph should be cached per project revision and incrementally updated.

### Graph components

```text
ProjectGraph
  modules, source sets, source roots, generated roots, output dirs

JavaSymbolGraph
  packages, types, members, locals, semantic references

TypeHierarchyIndex
  supertypes, subtypes, override groups

CallGraph
  method -> called methods
  method -> constructors
  method -> method references

ResourceReferenceGraph
  XML class references
  YAML/properties class references
  JSON class references
  service loader provider references
  framework-discovered references
  reflective string candidates

BuildGraph
  Maven modules, Gradle projects, module-info.java exports/opens/requires

TestGraph
  likely tests affected by a changed package/type/member
```

Serena already has symbol-level query and referencing tools on the LSP side; V3 should reuse the user-facing model but compute richer Java-specific references inside the sidecar. The existing `find_referencing_symbols` tool is a useful UX precedent: it returns referencing symbols plus snippets around references. 

---

# 2. New V3 Python modules

Add:

```text
src/serena/java_refactor_v3/
  __init__.py
  transformation_client.py
  transformation_models.py
  recipe_models.py
  impact_report.py
  resource_preview.py

src/serena/tools/java_refactor_v3_tools.py
```

Keep V1/V2 modules stable. V3 should call into them rather than rewriting them.

New MCP tools should remain normal Serena `Tool` classes. Serena derives MCP tool names and metadata from tool class names and `apply(...)` signatures, so V3 tools should follow the same pattern as earlier tools.  Serena’s existing `apply_ex` wrapper already handles active-project checks, execution through the task executor, timeout behavior, exception handling, usage recording, and cache saving, so V3 should not invent a parallel tool execution framework. 

---

# 3. New V3 sidecar packages

Add:

```text
java-refactor/src/main/java/io/serena/javarefactor/v3/
  transformation/
    TransformationWorkspaceManager.java
    TransformationWorkspace.java
    TransformationPlan.java
    TransformationStep.java
    TransformationValidator.java

  graph/
    TransformationGraphBuilder.java
    JavaSymbolGraph.java
    ResourceReferenceGraph.java
    BuildGraph.java
    TestGraph.java
    GraphInvalidation.java

  packages/
    RenamePackagePlanner.java
    MovePackagePlanner.java
    PackageDirectoryMapper.java
    ModuleInfoRewritePlanner.java

  deletion/
    PropagatingSafeDeletePlanner.java
    DeadCodeAnalyzer.java
    ReachabilityAnalyzer.java
    PublicApiBoundaryAnalyzer.java

  class_refactors/
    ExtractClassPlanner.java
    ExtractSuperclassPlanner.java
    ReplaceInheritanceWithDelegationPlanner.java
    DelegateSynthesizer.java

  inline/
    DeepInlineMethodPlanner.java
    StatementSubstitutionEngine.java
    TempVariablePlanner.java
    ControlFlowInliner.java

  conversions/
    AnonymousToLambdaPlanner.java
    LambdaToMethodReferencePlanner.java
    MethodReferenceCompatibilityAnalyzer.java

  migration/
    RecipeEngine.java
    RecipeParser.java
    ApiMigrationPlanner.java
    DeprecatedApiScanner.java
    RewriteRule.java

  resources/
    ResourceScanner.java
    ResourceReferenceProvider.java
    XmlReferenceProvider.java
    PropertiesReferenceProvider.java
    YamlReferenceProvider.java
    JsonReferenceProvider.java
    ServiceLoaderReferenceProvider.java

  frameworks/
    FrameworkPlugin.java
    FrameworkPluginRegistry.java
    SpringPlugin.java
    JakartaPersistencePlugin.java
    JacksonPlugin.java
    JUnitPlugin.java
```

---

# 4. V3 tool surface

Add these Java-specific tools first. Do not route generic Serena tools to them by default until V3 is hardened.

## 4.1 Package and module tools

```python
class JavaRenamePackageTool(...):
    def apply(
        self,
        old_package: str,
        new_package: str,
        include_subpackages: bool = True,
        rewrite_resources: bool = True,
        rewrite_module_info: bool = True,
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

```python
class JavaMovePackageTool(...):
    def apply(
        self,
        source_package: str,
        target_package: str,
        include_subpackages: bool = True,
        target_source_root: str | None = None,
        rewrite_resources: bool = True,
        rewrite_module_info: bool = True,
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

```python
class JavaMoveSourceRootTool(...):
    def apply(
        self,
        source_root: str,
        target_source_root: str,
        packages_json: str = "[]",
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

## 4.2 Propagating delete tools

```python
class JavaPropagateSafeDeleteTool(...):
    def apply(
        self,
        roots_json: str,
        delete_private_only: bool = True,
        include_tests: bool = False,
        include_resources: bool = True,
        max_cascade_depth: int = 5,
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

```python
class JavaFindDeadCodeTool(...):
    def apply(
        self,
        scope: str = "project",
        include_tests: bool = False,
        public_api_policy: str = "keep",
        max_answer_chars: int = -1,
    ) -> str: ...
```

## 4.3 Class decomposition tools

```python
class JavaExtractClassTool(...):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        new_class_name: str,
        members_json: str,
        target_package: str | None = None,
        leave_delegate_methods: bool = True,
        update_usages: bool = False,
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

```python
class JavaExtractSuperclassTool(...):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        superclass_name: str,
        members_json: str,
        target_package: str | None = None,
        make_abstract: bool = True,
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

```python
class JavaReplaceInheritanceWithDelegationTool(...):
    def apply(
        self,
        subclass_name_path: str,
        relative_path: str,
        superclass_fqn: str,
        delegate_field_name: str,
        methods_json: str = "[]",
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

## 4.4 Advanced expression/code-shape tools

```python
class JavaDeepInlineMethodTool(...):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        delete_inlined_method: bool = False,
        max_call_sites: int = 25,
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

```python
class JavaConvertAnonymousToLambdaTool(...):
    def apply(
        self,
        relative_path: str,
        start_line: int,
        start_col: int,
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

```python
class JavaConvertLambdaToMethodReferenceTool(...):
    def apply(
        self,
        relative_path: str,
        start_line: int,
        start_col: int,
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

## 4.5 Recipe/migration tools

```python
class JavaScanMigrationOpportunitiesTool(...):
    def apply(
        self,
        recipe_id: str,
        scope: str = "project",
        max_answer_chars: int = -1,
    ) -> str: ...
```

```python
class JavaApplyRefactorRecipeTool(...):
    def apply(
        self,
        recipe_json: str,
        scope: str = "project",
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

```python
class JavaRefactorImpactReportTool(...):
    def apply(
        self,
        workspace_id: str,
        include_tests: bool = True,
        include_resources: bool = True,
        max_answer_chars: int = -1,
    ) -> str: ...
```

---

# 5. Package rename and package move

This is the most important V3 feature.

## 5.1 Supported V3 scope

Support:

* Rename package.
* Move package under another package.
* Include or exclude subpackages.
* Move corresponding directories.
* Rewrite:

  * Java package declarations.
  * Imports.
  * Static imports.
  * Fully qualified names.
  * Javadocs links.
  * `module-info.java`.
  * Service-loader files.
  * selected XML/properties/YAML/JSON class references.
* Validate affected source sets.

Do not rewrite arbitrary string literals by default.

## 5.2 Package graph

Create:

```java
record PackageInfo(
    String packageName,
    List<Path> sourceFiles,
    List<TypeKey> topLevelTypes,
    List<Path> sourceRoots,
    Optional<ModuleInfo> moduleInfo
) {}
```

Package move plan:

```java
record PackageMovePlan(
    String oldPackage,
    String newPackage,
    boolean includeSubpackages,
    List<JavaFileMove> fileMoves,
    List<TextEditPlan> packageDeclarationEdits,
    List<TextEditPlan> importEdits,
    List<TextEditPlan> fqnEdits,
    List<ResourceEditPlan> resourceEdits,
    List<ModuleInfoEditPlan> moduleInfoEdits
) {}
```

## 5.3 Algorithm

1. Resolve all packages matching `old_package`.
2. Compute new package name for each:

   * exact package:

     ```text
     com.old -> com.new
     ```
   * subpackage:

     ```text
     com.old.internal -> com.new.internal
     ```
3. Map every Java file to new path:

   ```text
   src/main/java/com/old/internal/Foo.java
   -> src/main/java/com/new/internal/Foo.java
   ```
4. Edit `package` declarations.
5. Rewrite references:

   * imports
   * static imports
   * fully qualified type names
   * Javadocs `{@link ...}`
   * annotations that contain `Class<?>` values
6. Rewrite resources via registered providers.
7. Rewrite `module-info.java`.
8. Check collisions:

   * target file exists
   * target package already contains same top-level type
   * source roots mismatch
   * split packages across modules
9. Validate with javac.
10. Optionally run targeted tests.

## 5.4 `module-info.java` handling

V1/V2 should have refused many module cases. V3 should handle them.

Rewrite:

```java
exports com.old.api;
opens com.old.model;
provides com.old.Service with com.old.impl.ServiceImpl;
uses com.old.Service;
```

to:

```java
exports com.new.api;
opens com.new.model;
provides com.new.Service with com.new.impl.ServiceImpl;
uses com.new.Service;
```

Rules:

* If only some types in an exported package moved, warn or refuse.
* If package is split across modules, refuse unless explicit module strategy is supplied.
* If `opens ... to` specific modules are present, preserve target module list.
* If moving all classes out of a package, remove stale `exports`/`opens` only when safe.

## 5.5 Resource handling

Default resources to scan:

```text
src/main/resources
src/test/resources
META-INF/services/*
*.xml
*.properties
*.yml
*.yaml
*.json
```

Resource rewrite policy:

```yaml
java_refactor:
  v3:
    resources:
      rewrite_exact_class_names: true
      rewrite_package_prefixes: false
      rewrite_reflective_strings: false
```

Default safe edit:

```text
com.old.Foo -> com.new.Foo
```

Default unsafe non-edit candidate:

```java
Class.forName("com.old." + name)
```

Return as warning.

---

# 6. Move directory / source root

## 6.1 Scope

This is not a generic filesystem move. It is Java-aware source relocation.

Support:

* Move package directories between source roots.
* Move test packages to test source roots.
* Move generated-source packages only when editable.
* Keep package names unchanged or update them depending on mode.

Tool:

```python
java_move_source_root(
    source_root: str,
    target_source_root: str,
    packages_json: str = "[]",
    preserve_package_names: bool = True
)
```

## 6.2 Algorithm

1. Resolve source roots from project model.
2. Validate target root is a known Java source root or explicitly allowed.
3. Enumerate selected packages.
4. Move files.
5. If `preserve_package_names=True`, do not edit package declarations.
6. If false, compute new package from directory mapping and run package rename logic.
7. Rewrite build model only if explicitly enabled.
8. Validate.

## 6.3 Build file edits

Default should be no build-file edits. V3 may support guarded edits:

* Maven: add source directory through build-helper plugin only when project already uses it.
* Gradle: add source directory to `sourceSets` only when the file is simple enough to patch safely.

Otherwise return a clear instruction:

```json
{
  "code": "BUILD_FILE_UPDATE_REQUIRED",
  "message": "Target source root is not part of Gradle sourceSets.main.java.srcDirs. Edit build.gradle manually or enable build file rewrite."
}
```

---

# 7. Propagating safe delete and dead-code removal

V1 safe delete refuses if a symbol is referenced. V3 should support **cascade deletion**.

## 7.1 Supported V3 scope

Support cascading deletion for:

* private methods
* private fields
* private nested types
* package-private classes in internal packages
* unused constructors
* unused overloads
* unused test helpers when `include_tests=true`
* resource entries that only refer to deleted types

Default public API boundary:

```text
public/protected types and members are roots, not deletable.
```

## 7.2 Reachability model

Build:

```java
record ReachabilityGraph(
    Set<ElementKey> roots,
    Map<ElementKey, Set<ElementKey>> outgoingReferences,
    Map<ElementKey, Set<ElementKey>> incomingReferences
) {}
```

Root categories:

```text
public API
protected API
main methods
test methods/fixtures if include_tests=false
framework entry points
serialization hooks
service loader providers
Spring/Jakarta/JUnit entry points
native methods
reflective candidates
```

A symbol is deletable if:

```text
not root
and all incoming references are from symbols already scheduled for deletion
and delete span can be computed safely
```

## 7.3 Algorithm

1. Accept explicit roots to delete.
2. For each root:

   * If referenced only by deletable symbols, add referencing symbols to candidate cascade.
   * If referenced by non-deletable symbols, block.
3. Iterate until fixed point.
4. Compute delete spans.
5. Remove now-unused imports.
6. Remove empty classes only if explicitly enabled.
7. Remove empty packages/directories.
8. Rewrite resources:

   * remove service-loader provider lines for deleted classes
   * remove exact XML bean definitions only when unambiguous
9. Validate.

## 7.4 Output

Preview should be graph-shaped, not just a flat edit list:

```json
{
  "deletePlan": {
    "requested": ["com.acme.LegacyBillingService"],
    "cascade": [
      {
        "symbol": "com.acme.LegacyBillingService#calculate",
        "reason": "Only referenced by deleted class"
      },
      {
        "symbol": "com.acme.LegacyBillingConfig",
        "reason": "Only referenced from deleted service-loader entry"
      }
    ],
    "blocked": [
      {
        "symbol": "com.acme.LegacyBillingMapper",
        "reason": "Referenced by public method com.acme.BillingApi#getMapper"
      }
    ]
  }
}
```

## 7.5 Dead code scan

`java_find_dead_code` should produce candidates, not apply deletions:

```json
{
  "deadCodeCandidates": [
    {
      "symbol": "com.acme.internal.LegacyParser",
      "confidence": "high",
      "reason": "Package-private class has no incoming semantic references."
    },
    {
      "symbol": "com.acme.OldController",
      "confidence": "low",
      "reason": "No Java references, but @RequestMapping may make this a framework entry point."
    }
  ]
}
```

---

# 8. Extract class

V2 extracted interfaces and methods. V3 should support class decomposition.

## 8.1 Supported V3 scope

Extract selected fields and methods from one class into a new collaborator class.

Example:

Before:

```java
class OrderService {
    private final TaxPolicy taxPolicy;
    private final DiscountPolicy discountPolicy;

    Money calculateTax(Order order) { ... }
    Money calculateDiscount(Order order) { ... }
    Money calculateTotal(Order order) { ... }
}
```

After:

```java
class OrderPricing {
    private final TaxPolicy taxPolicy;
    private final DiscountPolicy discountPolicy;

    Money calculateTax(Order order) { ... }
    Money calculateDiscount(Order order) { ... }
}
```

Source class:

```java
class OrderService {
    private final OrderPricing pricing;

    Money calculateTotal(Order order) {
        return pricing.calculateTax(order).add(pricing.calculateDiscount(order));
    }
}
```

## 8.2 Inputs

```json
{
  "newClassName": "OrderPricing",
  "members": [
    "field:taxPolicy",
    "field:discountPolicy",
    "method:calculateTax(Order)",
    "method:calculateDiscount(Order)"
  ],
  "targetPackage": "com.acme.pricing",
  "leaveDelegateMethods": true,
  "updateUsages": false
}
```

## 8.3 Algorithm

1. Resolve source class.
2. Resolve selected members.
3. Build dependency closure:

   * selected methods need selected fields?
   * selected methods need unselected fields?
   * selected methods call unselected methods?
4. Classify dependencies:

   * move with selected members
   * pass as constructor parameter
   * keep delegate call
   * block
5. Generate new class:

   * package declaration
   * imports
   * selected fields
   * constructor
   * selected methods
6. Modify source class:

   * add field for extracted class
   * initialize in constructors
   * remove moved fields/methods or leave delegates
7. Rewrite internal call sites:

   * `calculateTax(order)` → `pricing.calculateTax(order)`
   * `taxPolicy` usages inside moved methods remain local to new class
8. Optionally rewrite external call sites if delegates are not left.
9. Validate.

## 8.4 Refusal cases

Refuse by default when selected members:

* depend on source class `super`
* access private state not selected and not passable
* rely on initialization order that cannot be preserved
* are synchronized on source `this`
* use source class type parameters not reproduced in new class
* contain native methods
* are public API and `leaveDelegateMethods=false`

---

# 9. Extract superclass

## 9.1 Supported V3 scope

Support extracting a superclass from common members of one class or several sibling classes.

Example:

```java
abstract class AbstractOrderHandler {
    protected final Logger logger;

    protected void log(Order order) { ... }
}
```

Subclasses extend it.

## 9.2 Inputs

```json
{
  "classes": [
    "com.acme.OnlineOrderHandler",
    "com.acme.StoreOrderHandler"
  ],
  "superclassName": "AbstractOrderHandler",
  "members": [
    "field:logger",
    "method:log(Order)"
  ],
  "targetPackage": "com.acme"
}
```

## 9.3 Algorithm

1. Resolve selected classes.
2. Verify they can share a superclass:

   * no existing incompatible superclass
   * same or compatible constructors
   * no generic conflicts
3. Resolve selected members.
4. Generate superclass.
5. Move or copy members.
6. Update `extends` clauses.
7. Rewrite constructors:

   * insert `super(...)`
   * pass required field constructor args
8. Add `@Override` where appropriate.
9. Validate.

## 9.4 Conservative V3 rule

If any selected class already extends a non-`Object` superclass, refuse unless that superclass is the same across all selected classes and can itself be extended by the new superclass.

---

# 10. Replace inheritance with delegation

## 10.1 Scope

Support changing:

```java
class C extends Base
```

to:

```java
class C {
    private final Base base;

    public String name() {
        return base.name();
    }
}
```

## 10.2 Algorithm

1. Resolve subclass and superclass.
2. Identify inherited methods used by clients.
3. Generate delegate field.
4. Generate forwarding methods for selected inherited methods.
5. Remove `extends Base`.
6. Fix constructor initialization.
7. Rewrite `super.method(...)` inside subclass:

   * `base.method(...)` when safe
   * refuse for protected methods inaccessible through instance
8. Update imports.
9. Validate.

## 10.3 Refusal cases

Refuse when:

* subclass depends on protected fields
* superclass constructor has complex requirements
* subclass overrides methods that call `super`
* type is part of sealed hierarchy
* generic superclass substitution is not representable
* public API change is not allowed

---

# 11. Generalized inline method

V2 supports single-expression inline. V3 should support common multi-statement methods.

## 11.1 Supported V3 scope

Support private, non-overridden methods with:

* local declarations
* straight-line statements
* one final return
* no loops initially, or loops only when body can be block-inlined
* no checked exception mismatch
* no `synchronized`
* no `yield`
* no `super`
* no early returns

Example:

```java
private Money calculate(Order order) {
    Money subtotal = order.subtotal();
    Money tax = taxPolicy.calculate(order);
    return subtotal.add(tax);
}
```

Call site:

```java
Money total = calculate(order);
```

After:

```java
Money subtotal = order.subtotal();
Money tax = taxPolicy.calculate(order);
Money total = subtotal.add(tax);
```

## 11.2 Algorithm

1. Resolve target method.
2. Find call sites.
3. For each call site:

   * Determine whether expression inline or block inline is needed.
   * Build parameter substitution.
   * Introduce temps for side-effecting arguments.
   * Rename locals to avoid collisions.
   * Preserve evaluation order.
   * Replace `return expr` with assignment/expression appropriate to call context.
4. Validate all call sites independently.
5. Delete method if requested and no references remain.
6. Validate whole affected project.

## 11.3 Temp variable strategy

If parameter used twice and argument has side effects:

```java
foo(next())
```

Inline by introducing temp:

```java
Type nextValue = next();
...
```

But only if a block insertion point exists. If call occurs in an expression context where statements cannot be inserted safely, refuse.

## 11.4 Collision handling

If inlined method declares:

```java
Money subtotal
```

and caller already has `subtotal`, rename to:

```java
Money subtotal1
```

Use semantic local-scope analysis, not text search.

---

# 12. Convert anonymous class to lambda

## 12.1 Supported V3 scope

Convert anonymous classes implementing functional interfaces.

Before:

```java
Runnable r = new Runnable() {
    @Override
    public void run() {
        doWork();
    }
};
```

After:

```java
Runnable r = () -> doWork();
```

## 12.2 Preconditions

The anonymous class must:

* implement exactly one abstract method
* not declare fields
* not declare additional methods
* not use `this` to mean anonymous class identity
* not use `super`
* not have an instance initializer
* not rely on anonymous class name/class identity
* not override `equals`, `hashCode`, or `toString`

## 12.3 Algorithm

1. Resolve anonymous class `NewClassTree`.
2. Determine target functional interface.
3. Resolve single abstract method.
4. Verify method signature.
5. Extract method body.
6. Determine lambda parameter list:

   * omit parameter types when inferable
   * keep types if ambiguity exists
7. Generate lambda:

   * expression body if single return/expression statement
   * block body otherwise
8. Remove now-unneeded imports if anonymous class required explicit type.
9. Validate.

## 12.4 Refusal examples

Refuse:

```java
new Runnable() {
    private int count;
    public void run() { count++; }
}
```

Refuse:

```java
new Runnable() {
    public void run() { System.out.println(this); }
}
```

because `this` changes meaning in lambdas.

---

# 13. Convert lambda to method reference

## 13.1 Supported V3 scope

Convert:

```java
x -> foo(x)
```

to:

```java
this::foo
```

or:

```java
String::trim
```

or:

```java
Objects::nonNull
```

## 13.2 Algorithm

1. Resolve lambda expression.
2. Match body shape:

   * single method invocation
   * single constructor invocation
   * identity parameter forwarding
3. Verify parameters are passed in order and not transformed.
4. Verify receiver compatibility:

   * `x -> x.trim()` → `String::trim`
   * `x -> helper(x)` → `this::helper`
   * `x -> SomeType.create(x)` → `SomeType::create`
5. Verify target functional interface still resolves.
6. Replace lambda text.
7. Validate.

## 13.3 Refusal cases

Refuse:

```java
x -> foo(transform(x))
```

Refuse:

```java
(x, y) -> foo(y, x)
```

unless V4 supports advanced argument reordering.

---

# 14. API migration recipes

V3 should add recipe-driven refactoring. This is especially useful for agents making broad codebase updates.

## 14.1 Recipe format

Use JSON/YAML-compatible schema:

```json
{
  "id": "java.util.Date-to-java.time",
  "description": "Migrate selected Date usages to Instant",
  "rules": [
    {
      "kind": "replaceMethodCall",
      "owner": "java.util.Date",
      "name": "toInstant",
      "parameterTypes": [],
      "replacement": "${receiver}.toInstant()"
    },
    {
      "kind": "replaceConstructor",
      "owner": "java.util.Date",
      "parameterTypes": ["long"],
      "replacement": "Instant.ofEpochMilli(${arg0})",
      "requiredImports": ["java.time.Instant"]
    }
  ]
}
```

Support rule kinds:

```text
replaceType
replaceMethodCall
replaceStaticMethodCall
replaceConstructor
replaceFieldAccess
replaceAnnotation
replaceImport
removeAnnotation
addAnnotation
```

> **A compiler-backed structural rule kind (F13 — approved revision of this section).** `changeMethodSignature`
> is a *structural* declaration change (re-order/add/remove/retype parameters and optional rename, with cascading
> call-site and override rewrites), not a text-template replacement. An earlier draft deferred it from the recipe
> engine and refused it; that was deliberately revised — the engine now supports it as a first-class rule kind that
> still never degrades a structural edit into a textual one. `RecipeParser` parses a `changeMethodSignature` rule
> into a `SignatureChangeRule` and delegates to the *same* dedicated structural change-signature operation (V1/V2
> `changeMethodSignature`) used directly: it resolves the overload via javac, rewrites the declaration and every
> call site through the compiler, and the merged edits pass the sidecar's before/after javac delta validator
> (`diagnosticDeltaValidated`). The operation's own refusals surface verbatim — e.g. a public method without
> confirmation yields `PUBLIC_API_CONFIRMATION_REQUIRED`, never a generic recipe refusal — so the capability can
> never silently no-op. Proof: live-sidecar tests `test_recipe_apply_change_method_signature` (declaration +
> call-site rewrite, javac-validated) and `test_recipe_change_signature_refusal_passthrough` (public-API refusal
> passthrough) in `test_java_refactor_v3_recipe_engine_protocol.py`. A *genuinely* unknown rule kind is still
> refused with `recipe_unknown_rule_kind` via `RecipeParser`'s default branch.

## 14.2 Recipe engine algorithm

1. Parse recipe.
2. Validate all referenced types/methods resolve.
3. Scan semantic graph for matches.
4. For each match:

   * build replacement from template
   * add imports
   * remove stale imports
   * check type compatibility
5. Produce grouped preview:

   * per rule
   * per file
   * per risk level
6. Validate.
7. Apply transactionally.

## 14.3 Risk classification

Each match should be classified:

```text
safe
needs_review
refused
```

Examples:

* `safe`: exact method call replacement with same return type.
* `needs_review`: replacement changes nullability or exception behavior.
* `refused`: dynamic dispatch target cannot be proven.

## 14.4 Built-in recipe examples

Ship a small set, disabled unless explicitly invoked:

```text
junit4-to-junit5-basic
javax-to-jakarta-basic
deprecated-guava-optional-to-java-optional
thread-stop-suspend-destroy-removal
date-calendar-to-java-time-basic
```

Avoid claiming full framework migrations. V3 should support exact semantic recipes, not magical end-to-end upgrades.

---

# 15. Resource-aware refactoring

## 15.1 Resource provider SPI

Add:

```java
public interface ResourceReferenceProvider {
    boolean supports(Path file);
    List<ResourceReference> findReferences(ResourceFile file, TransformationGraph graph);
    List<ResourceEdit> planEdits(ResourceReference reference, SymbolChange change);
}
```

> **Shipped scope — both halves of this SPI are live.** The read-only `findReferences` half is surfaced as the
> independently-callable `resources.findReferences` protocol op (the live interface is `ResourceReferenceProvider`
> with `id()`/`supports(Path)`/`findReferences(Path, String, ResourceQuery)`). The `planEdits` method above —
> resource providers owning edit planning in response to a `SymbolChange` — is **implemented and live** via
> `ResourceEditPlanner`: providers attach a confidence, an edit kind, and an offset to each proposed edit, and a
> `ResourceApplyPolicy` governs application — HIGH-confidence edits are auto-applied, MEDIUM-confidence edits are
> previewed unless configured to apply, and LOW-confidence edits are never auto-applied. This coexists with the
> package rename/move planners' `rewrite_resources` policy, which rewrites exact fully-qualified names in scanned
> resource files (see §5.5, `ResourceRewriter`, and the sidecar tests
> `test_sidecar_{rename,move}_package_*rewrite*resource*`). The framework SPI in §16 is likewise fully wired:
> `frameworks.detect`/`frameworks.findReferences` provide read-only facts, and framework participation
> (`participate(SymbolChange, TransformationContext)`) is live via `FrameworkParticipationCoordinator`, joined into
> the deletion and package planner paths.

Reference model:

```java
record ResourceReference(
    Path file,
    int startOffset,
    int endOffset,
    String oldText,
    ResourceReferenceKind kind,
    Confidence confidence,
    ElementKey target
) {}
```

Kinds:

```text
EXACT_CLASS_NAME
PACKAGE_PREFIX
SERVICE_LOADER_PROVIDER
SPRING_BEAN_CLASS
JPA_ENTITY_CLASS
JACKSON_TYPE_NAME
JUNIT_CLASS_NAME
REFLECTIVE_STRING_CANDIDATE
```

## 15.2 Built-in providers

### Service loader

Files:

```text
META-INF/services/<interface-fqn>
```

Content lines are provider class names.

On type/package rename:

* Rename file if service interface type moved.
* Rewrite provider implementation lines.
* Remove provider lines if class is deleted.

### XML

Support conservative exact class references in attributes/text:

```xml
<bean class="com.old.Foo"/>
```

Do not rewrite arbitrary text by default.

### Properties/YAML/JSON

Rewrite exact class names only:

```properties
handler.class=com.old.Foo
```

```yaml
handlerClass: com.old.Foo
```

```json
{ "handlerClass": "com.old.Foo" }
```

### Reflection candidates

Scan but do not edit by default:

```java
Class.forName("com.old.Foo")
```

If exact full class name is a string literal and user enables:

```yaml
rewrite_reflective_string_literals: true
```

then edit with warning.

---

# 16. Framework plugin layer

V3 should not hardcode every framework into core refactoring. Add a plugin SPI.

```java
public interface FrameworkPlugin {
    String id();
    FrameworkDetectionResult detect(ProjectModel model, TransformationGraph graph);
    List<FrameworkReference> findReferences(TransformationGraph graph);
    List<TransformationStep> participate(SymbolChange change, TransformationContext context);
}
```

> **Shipped scope — the full SPI is live.** The read-only detection half ships as `frameworks.detect` (which
> frameworks are present, with annotation-count evidence) and `frameworks.findReferences` (framework-significant
> references to a target type), both backed by exact compiler-resolved annotation facts (never package-name
> heuristics). The `participate(...)` method above — letting a plugin contribute `TransformationStep`s to a
> rename/delete — is **implemented and live**: `FrameworkPlugin` declares `participate(SymbolChange,
> TransformationContext)` and it is wired into the deletion and package planner paths through
> `FrameworkParticipationCoordinator`. Consequently the per-plugin **"Participate in"** lists and **"Rewrite …"**
> rules in §16.1–§16.4 below describe behavior the current build emits: the Spring/JPA/Jackson/JUnit rules
> block-delete framework-managed types, contribute resource edits/warnings, validate metadata, and treat test
> methods as roots — always making deletion *more* conservative, never more aggressive. Framework participation
> coexists with the package planners' `rewrite_resources` resource path (§15). Detection rules (the **"Detect"**
> lists) are shipped.

## 16.1 Spring plugin

Detect:

* Spring annotations on classpath.
* `@Component`, `@Service`, `@Repository`, `@Controller`.
* `@Bean`.
* `@Configuration`.
* `@RequestMapping` variants.
* `@Qualifier`.
* XML bean files if present.

Participate in:

* type rename
* package rename
* safe delete
* dead code scan

Rules:

* Treat annotated components as entry points.
* Treat `@Bean` methods as externally referenced.
* Do not delete request handlers by “no Java references” alone.
* Rewrite exact class names in XML bean definitions.
* Report string bean names as review-required.

## 16.2 Jakarta Persistence plugin

Detect:

* `@Entity`
* `@MappedSuperclass`
* `@Embeddable`
* `@NamedQuery`
* `persistence.xml`
* ORM XML files

Rules:

* Treat entities as framework entry points.
* On class rename, update exact entity class references in XML.
* Do not rewrite JPQL string queries by default; report candidates.
* On field encapsulation, warn if field/property access strategy may change.

## 16.3 Jackson plugin

Detect:

* `@JsonTypeName`
* `@JsonSubTypes`
* `@JsonProperty`
* `@JsonCreator`

Rules:

* Type rename should not change serialized type names unless explicitly requested.
* Field rename should warn when JSON property names remain stable or need migration.
* Encapsulate field should preserve Jackson access semantics.

## 16.4 JUnit plugin

Detect:

* JUnit 4/5 annotations.
* Test classes and methods.

Rules:

* Treat tests as roots depending on `include_tests`.
* Recipe support for selected JUnit 4 → JUnit 5 replacements.
* Avoid deleting test utilities if referenced by reflection/parameterized runners.

---

# 17. Impact reports

V3 refactors can be large. Serena tools should produce a compact report usable by agents.

Add:

```python
java_refactor_impact_report(workspace_id: str, ...)
```

Report sections:

```json
{
  "summary": {
    "operation": "renamePackage",
    "risk": "medium",
    "filesChanged": 129,
    "javaFilesMoved": 42,
    "resourceFilesChanged": 8,
    "newCompileErrors": 0
  },
  "semanticImpact": {
    "typesMoved": 42,
    "publicApisChanged": 12,
    "overridesAffected": 0,
    "callSitesChanged": 221
  },
  "resourceImpact": {
    "serviceLoaderFilesChanged": 2,
    "xmlFilesChanged": 4,
    "reflectionCandidatesNotChanged": 3
  },
  "tests": {
    "suggestedTestCommands": [
      "./gradlew :billing:test",
      "./mvnw -pl billing test"
    ],
    "likelyAffectedTests": [
      "com.acme.billing.OrderServiceTest"
    ]
  },
  "warnings": [...]
}
```

The report should be returned as JSON by default, with an optional human-readable compact summary.

---

# 18. Validation strategy for V3

V3 needs multi-layer validation.

## 18.1 Static validation

Always run:

1. Parse changed Java files.
2. Analyze affected source sets.
3. Check no new javac errors.
4. Check no dangling imports.
5. Check no stale package declarations.
6. Check moved files match package names.
7. Check resource references still point to resolvable classes when exact.

## 18.2 Build-tool validation

When enabled:

```yaml
java_refactor:
  v3:
    validation:
      run_build_tool_compile: false
      run_tests: false
```

If enabled:

* Gradle:

  * affected project `compileJava`
  * affected project `compileTestJava` if tests changed
* Maven:

  * affected module `test-compile`
  * optional `test`

Default should remain `false` to keep tool calls fast and avoid side effects.

## 18.3 Framework validation

Framework plugins can contribute checks:

```java
interface FrameworkValidationParticipant {
    List<ValidationFinding> validate(TransformationWorkspace workspace);
}
```

Examples:

* Spring plugin checks XML bean class names resolve.
* Service loader provider checks moved provider classes still implement service interface.
* JPA plugin checks `persistence.xml` class entries resolve.

## 18.4 Confidence levels

Every non-Java-source rewrite should carry a confidence:

```text
HIGH: exact FQN in known resource field
MEDIUM: exact FQN in generic XML/property value
LOW: arbitrary string literal/reflection candidate
```

Default apply policy:

```text
apply HIGH
preview MEDIUM unless configured
never auto-apply LOW
```

---

# 19. Formatting and file movement

V3 should improve formatting around large generated edits.

## 19.1 Import normalization

After package moves and recipe migrations:

* Remove obsolete imports.
* Add missing imports.
* Collapse FQNs when safe.
* Expand ambiguous simple names to FQNs.
* Preserve import groups.
* Preserve static import style.
* Avoid wildcard import churn.

## 19.2 Directory cleanup

After moves/deletes:

* Remove empty Java package directories.
* Do not remove resource directories unless explicitly empty and under known source root.
* Never remove build directories manually.
* Never remove generated-source directories by default.

## 19.3 File operation ordering

Apply order:

1. Text edits to files that remain in place.
2. Text edits to files that will move, staged against old content.
3. Create new files.
4. Rename/move files.
5. Delete files.
6. Remove empty directories.
7. Final hash verification.

This avoids applying edits to a path that has already moved.

---

# 20. V3 configuration

Add:

```yaml
java_refactor:
  v3:
    enabled: true

    transformations:
      max_open_workspaces: 8
      workspace_ttl_minutes: 60
      require_clean_revision_on_apply: true
      allow_multi_module_edits: true

    packages:
      rename_enabled: true
      move_enabled: true
      rewrite_module_info: true
      rewrite_resources: true
      rewrite_reflective_strings: false

    deletion:
      propagate_enabled: true
      public_api_policy: keep       # keep | warn | allow
      max_cascade_depth: 5
      include_tests_default: false
      delete_empty_packages: true

    class_refactors:
      extract_class_enabled: true
      extract_superclass_enabled: true
      replace_inheritance_with_delegation_enabled: true
      leave_delegates_default: true
      allow_public_api_change: false

    inline:
      deep_inline_enabled: true
      max_call_sites: 25
      introduce_temps_for_side_effects: true
      delete_inlined_method_default: false

    conversions:
      anonymous_to_lambda_enabled: true
      lambda_to_method_reference_enabled: true

    resources:
      enabled: true
      scan_xml: true
      scan_properties: true
      scan_yaml: true
      scan_json: true
      scan_service_loader: true
      auto_apply_confidence: high
      report_reflection_candidates: true

    frameworks:
      enabled: true
      spring: auto
      jakarta_persistence: auto
      jackson: auto
      junit: auto

    recipes:
      enabled: true
      allow_user_recipes: true
      builtins_enabled: true

    validation:
      javac_required: true
      run_build_tool_compile: false
      run_tests: false
      max_validation_seconds: 120
```

---

# 21. V3 implementation phases

## Phase V3-1: Transformation workspace engine

Deliverables:

* `TransformationWorkspaceManager`
* workspace/session composition
* workspace-level preview/apply/cancel
* workspace-level project revision guard
* impact report (fully computed, five-section)

Acceptance criteria:

* Can group multiple V2 sessions.
* Can preview combined edit.
* Can apply transactionally.
* Can cancel and evict workspace.
* Can produce file/edit stats.

## Phase V3-2: Transformation graph

Deliverables:

* `TransformationGraphBuilder`
* Java symbol graph
* resource reference graph (provider-backed, exact FQN)
* build graph (maven/gradle/plain, module-info)
* graph invalidation

Acceptance criteria:

* Builds graph for Maven, Gradle, plain Java.
* Maps packages to source roots.
* Maps top-level types to files.
* Finds exact resource FQN references.
* Caches and invalidates correctly.

## Phase V3-3: Package rename/move

Deliverables:

* `java_rename_package`
* `java_move_package`
* package declaration edits
* file moves
* import/static import/FQN rewrites
* module-info rewrite
* resource exact FQN rewrite

Acceptance criteria:

* Renames package with subpackages.
* Moves files to matching directories.
* Rewrites imports and static imports.
* Rewrites module-info exports/opens/provides/uses.
* Rewrites service-loader files.
* Reports reflective candidates.
* Validates with javac.

## Phase V3-4: Propagating safe delete

Deliverables:

* reachability graph
* public API boundary analyzer
* `java_find_dead_code`
* `java_propagate_safe_delete`

Acceptance criteria:

* Finds private dead code.
* Cascades deletions through only-deleted references.
* Blocks public/framework entry points.
* Removes service-loader entries for deleted providers.
* Produces graph-shaped preview.

## Phase V3-5: Resource providers and framework SPI

Deliverables:

* `ResourceReferenceProvider`
* XML/properties/YAML/JSON/service-loader providers
* `FrameworkPlugin` SPI
* Spring/JPA/Jackson/JUnit initial plugins

Acceptance criteria:

* Providers are independently testable.
* Package/type rename invokes providers.
* Dead-code scan respects framework roots.
* Refactors report low-confidence candidates without editing them.

## Phase V3-6: Extract class and extract superclass

Deliverables:

* dependency closure analysis
* new class/superclass synthesis
* constructor rewriting
* delegate generation
* usage rewriting option

Acceptance criteria:

* Extracts cohesive selected fields/methods.
* Leaves delegates by default.
* Validates constructor initialization.
* Refuses unsafe state/super/generic cases.

## Phase V3-7: Replace inheritance with delegation

Deliverables:

* inherited member analyzer
* delegate field synthesis
* forwarding method generation
* extends-clause removal
* constructor adaptation

Acceptance criteria:

* Works for simple superclass replacement.
* Preserves public methods via delegates.
* Refuses protected-field/super-call hazards.
* Validates with javac.

## Phase V3-8: Deep inline method

Deliverables:

* statement substitution engine
* temp variable planner
* local collision renamer
* block/expression call-site modes

Acceptance criteria:

* Inlines straight-line private methods.
* Introduces temps when safe.
* Avoids local collisions.
* Deletes method optionally when references are gone.
* Refuses early return/control-flow hazards.

## Phase V3-9: Anonymous/lambda conversions

Deliverables:

* anonymous-to-lambda planner
* lambda-to-method-reference planner
* functional interface compatibility checker

Acceptance criteria:

* Converts common anonymous classes safely.
* Refuses anonymous `this`/state cases.
* Converts simple lambdas to method references.
* Validates target functional interface.

## Phase V3-10: Recipe engine

Deliverables:

* recipe schema
* semantic match engine
* replacement template engine
* built-in recipe registry
* `java_scan_migration_opportunities`
* `java_apply_refactor_recipe`

Acceptance criteria:

* Finds recipe matches.
* Groups preview by rule.
* Applies safe exact replacements.
* Adds/removes imports.
* Classifies risky matches.

## Phase V3-11: Hardening and docs

Deliverables:

* docs page for Java refactor V3
* examples
* failure-mode guide
* performance tuning
* config reference
* migration notes from V1/V2

Acceptance criteria:

* All V3 tools have clear preview/apply examples.
* Every refusal code is documented.
* Large-repo performance is measured.
* JetBrains plugin is not required or referenced as an execution path.

---

# 22. Test matrix

## 22.1 Fixture repos

Add:

```text
test/resources/repos/java_refactor_v3/
  package-rename-basic/
  package-rename-modules/
  package-rename-resources/
  package-move-multisource/
  dead-code-cascade/
  extract-class/
  extract-superclass/
  inheritance-to-delegation/
  deep-inline/
  anonymous-to-lambda/
  lambda-to-method-reference/
  recipes/
  spring-lite/
  jpa-lite/
  service-loader/
  multi-module-maven/
  multi-project-gradle/
```

## 22.2 Package rename tests

* exact package rename
* subpackage rename
* move to existing parent package
* static imports
* FQNs in code
* Javadocs links
* `module-info.java exports`
* `module-info.java opens`
* `provides ... with`
* service-loader provider files
* XML bean class reference
* properties class reference
* reflection candidate warning
* target file collision refusal
* split package refusal

## 22.3 Propagating delete tests

* private method cascade
* private class cascade
* unused constructor
* blocked by public API
* blocked by Spring entry point
* delete service-loader provider line
* remove empty package directory
* max cascade depth refusal
* test-only references with `include_tests=false`

## 22.4 Extract class tests

* fields and methods move
* constructor initialization
* leave delegates
* external usage rewrite
* private dependency refusal
* selected dependency closure
* generic class refusal or handling
* synchronized method refusal
* source `super` refusal

## 22.5 Extract superclass tests

* one class extraction
* two sibling classes
* constructor propagation
* method abstracting
* field pull-up
* existing superclass refusal
* interface alternative suggestion

## 22.6 Replace inheritance tests

* simple superclass delegation
* public method forwarding
* constructor adaptation
* protected field refusal
* `super.method()` refusal
* sealed hierarchy refusal
* generic superclass case

## 22.7 Deep inline tests

* straight-line return
* void method inline
* multiple local declarations
* argument temp insertion
* local collision renaming
* expression context refusal
* early return refusal
* checked exception refusal

## 22.8 Conversion tests

* anonymous `Runnable` to lambda
* anonymous comparator to lambda
* anonymous class with field refusal
* anonymous class with `this` refusal
* lambda to static method reference
* lambda to instance method reference
* lambda argument transformation refusal

## 22.9 Recipe tests

* replace type
* replace method call
* replace constructor
* add import
* remove stale import
* refused dynamic dispatch case
* grouped preview
* recipe schema validation

---

# 23. Performance requirements

V3 graph building can be expensive. Add explicit targets.

## 23.1 Large-repo goals

For a repo with roughly:

```text
5,000 Java files
500 resource files
100 modules/source sets
```

Targets:

```text
cold graph build: under 60 seconds
warm graph build: under 10 seconds
package rename preview: under 30 seconds
dead code scan: under 60 seconds
single extract class preview: under 10 seconds
```

These are goals, not correctness constraints.

## 23.2 Incremental graph invalidation

Invalidate only:

* changed source file symbol subtree
* package graph for moved/renamed files
* resource references for changed resource file
* build graph for changed build files
* call graph edges from changed methods

Do not rebuild the full project for every V3 preview unless model files changed.

## 23.3 Memory limits

Add sidecar config:

```yaml
java_refactor:
  sidecar:
    xmx: "4G"
    graph_cache_max_mb: 1024
    resource_scan_max_file_mb: 2
```

Skip very large resource files by default and report them.

---

# 24. Safety model

V3 should classify every planned change.

```text
SAFE
REVIEW_REQUIRED
REFUSED
```

Apply policy:

```text
SAFE changes apply by default.
REVIEW_REQUIRED changes show in preview but do not apply unless allow_review_required=true.
REFUSED changes block the transformation.
```

Examples:

| Change                                        | Default classification                     |
| --------------------------------------------- | ------------------------------------------ |
| Java import rewrite from resolved type        | SAFE                                       |
| Java package declaration rewrite              | SAFE                                       |
| Service-loader exact provider rewrite         | SAFE                                       |
| XML `class="com.old.Foo"` rewrite             | SAFE or REVIEW_REQUIRED depending provider |
| String literal `Class.forName("com.old.Foo")` | REVIEW_REQUIRED                            |
| Concatenated reflective name                  | REFUSED for edit, warning only             |
| JPQL query string containing class name       | REVIEW_REQUIRED                            |
| Public API removal                            | REFUSED unless allowed                     |
| Build-file structural edit                    | REVIEW_REQUIRED                            |

---

# 25. What V3 should still not attempt

Keep these out of V3 unless explicitly scoped:

* Full semantic migration of Spring bean names across arbitrary SpEL.
* Full JPQL/HQL parser-based query rewrites.
* Android manifest/resource refactoring.
* Kotlin source rewrites.
* Scala/Groovy source rewrites.
* Arbitrary Gradle Kotlin DSL restructuring.
* Runtime reflection correctness.
* Public API binary compatibility guarantees.
* Distributed build/test execution orchestration.
* Whole-framework upgrades such as full Spring Boot 2 → 3 migration.
* Arbitrary control-flow-preserving inline/extract transformations.

These belong to V4+ or external recipe ecosystems.

---

# 26. V3 acceptance criteria

V3 is complete when Serena can safely do the following without JetBrains:

| Capability                          | Required behavior                                                                           |
| ----------------------------------- | ------------------------------------------------------------------------------------------- |
| Rename package                      | Move files, edit package declarations, imports, FQNs, module-info, resources, and validate. |
| Move package                        | Relocate package between source roots/packages with collision detection.                    |
| Propagate delete                    | Delete private/internal dead code cascades while respecting public/framework roots.         |
| Find dead code                      | Produce confidence-ranked dead-code candidates with reasons.                                |
| Extract class                       | Move selected state/behavior into a new class with delegates by default.                    |
| Extract superclass                  | Generate superclass and update subclasses under conservative constraints.                   |
| Replace inheritance with delegation | Generate delegate field and forwarding methods safely.                                      |
| Deep inline method                  | Inline straight-line private methods with temp introduction and collision handling.         |
| Anonymous to lambda                 | Convert functional anonymous classes when semantics are preserved.                          |
| Lambda to method reference          | Convert simple forwarding lambdas.                                                          |
| Resource-aware rewrite              | Update exact class/package references in known resource formats.                            |
| Framework-aware blocking            | Treat Spring/JPA/Jackson/JUnit entry points and metadata as refactor participants.          |
| Recipe engine                       | Apply exact semantic API migration rules with grouped preview and validation.               |
| Impact report                       | Summarize Java, resource, API, test, and risk impact for agent use.                         |

Every V3 tool must support:

* preview-first mode
* structured refusal reasons
* risk classification
* file/resource impact summary
* transactionality
* project-revision guard
* javac validation
* no JetBrains dependency

---

The highest-value V3 feature is **package/module/resource-aware rename and move**. It converts a fragile multi-hour agent workflow into a previewable, validated transformation while preserving the no-JetBrains requirement.
