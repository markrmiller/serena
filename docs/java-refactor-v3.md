# Java refactor V3 transformation platform

Java refactor V3 layers a **whole-repo transformation platform** on top of the V1 compiler-backed sidecar
(see [Java refactoring sidecar](java-refactor-sidecar.md)) and the V2 preview-first refactor sessions (see
[Java refactor V2 sessions](java-refactor-v2.md)). Where V2 operates one file/symbol group at a time, V3
composes graph-aware transformations across an entire project: package moves, propagating safe delete,
resource-aware rewrites, extract/inline/delegation refactorings, anonymous/lambda conversions, and
named migration recipes. V3 **never uses JetBrains/IntelliJ APIs** — every operation is implemented against
Serena's own project graph and the javac sidecar, and this is enforced by an executable check (below).

## Cross-cutting contract

Every V3 tool satisfies the same eight invariants — including `javacValidated`, from which no tool is exempt.
The acceptance matrix (`serena.java_refactor_v3.reports.acceptance.acceptance_matrix`) asserts each one per
tool. The first seven are architectural guarantees shared by every tool; `javacValidated` is satisfied in one
of two compiler-backed ways, recorded per row as its **provenance**: edit-emitting tools via a real javac
before/after diagnostic *delta* over the staged edit (`javac-delta`), and read-only scans/reports via analysis
derived from real javac *facts* — the sidecar's `Trees`/`Elements` model rather than text heuristics
(`javac-facts`). A read-only tool runs no before/after delta but is still compiler-backed, so it honestly
carries `javacValidated`.

| Invariant | Meaning |
| --- | --- |
| `previewFirst` | Every operation returns a workspace edit (text edits + file operations) without touching disk; mutation is a separate, explicit apply. |
| `structuredRefusals` | A declined operation returns a `{code, message}` refusal whose `code` is registered in the central registry — never a partial or best-effort edit. |
| `riskClassification` | Each result carries `SAFE`, `REVIEW_REQUIRED`, or `REFUSED`. Boundary-crossing or heuristic edits are `REVIEW_REQUIRED`. |
| `impactSummary` | A whole-repo impact report (Java/resources/API/tests/risk) is available for any composed edit. |
| `transactional` | Apply goes through `TransactionalWorkspaceEditApplier`: all edits commit or none do, with rollback on failure. |
| `revisionGuard` | Edits carry `old_hash` preconditions; apply refuses when a touched file changed since the preview was planned. |
| `javacValidated` | Every V3 tool is compiler-backed. Edit-emitting tools are validated by a real javac before/after diagnostic *delta* over the staged edit (sidecar package ops via the compiler-backed preview; the rest via the manager's javac bridge); a new compiler error refuses the edit and writes nothing (`javac-delta` provenance). Read-only scans/reports emit no edit, so they satisfy this via real javac *facts* — analysis over the sidecar's `Trees`/`Elements` model, never text heuristics (`javac-facts` provenance). The matrix records which provenance each tool uses. |
| `noJetBrains` | No execution path imports or shells out to JetBrains/IntelliJ. |

## Capability contract

| Goal | Tool | V3 contract |
| --- | --- | --- |
| G001 | `transformationWorkspace` | Compose multiple V2 sessions into one validated, revision-pinned plan; refuses cross-session file conflicts and stale revisions. |
| G002 | `transformationGraph` | Layered project graph: build/source roots, symbols, type hierarchy, call graph, resource references, and tests, cached per revision. |
| G003 | `renamePackage` / `movePackage` / `moveSourceRoot` | Rename a package, move a package to a new parent, or relocate a source root across the repo, rewriting declarations, imports, and references. |
| G004 | `propagatingSafeDelete` / `deadCodeScan` | Delete a symbol and its now-unreferenced dependents; refuses when a live or resource-wired reference remains. |
| G005 | `resourceProviders` | Detect type references in XML/properties/YAML/JSON and `META-INF/services`, so resource-wired types are treated as live and edits are risk-flagged. |
| G006 | `extractClass` / `extractSuperclass` | Extract selected members into a new class or superclass; refuses state/generic/super hazards. |
| G007 | `replaceInheritanceWithDelegation` | Replace a superclass with a delegate field + forwarding methods; refuses sealed/abstract/generic/`super`-call hazards. |
| G008 | `deepInlineMethod` | Inline a private method and all its call sites; refuses recursion, loop/yield/early-return/checked-exception/argument-duplication hazards. |
| G009 | `convertAnonymousToLambda` / `convertLambdaToMethodReference` | Convert a functional-interface anonymous class to a lambda, or a pass-through lambda to a method reference; refuses state/`this`/`super`/arg-transform hazards. |
| G010 | `scanMigrationOpportunities` / `applyRefactorRecipe` | Scan or apply a declarative migration recipe (type/method/annotation/import rewrites, e.g. JUnit 4→5); built-in recipes are addressable by name. |
| G011 | `impactReport` | Whole-repo Java/resource/API/test/risk JSON summary for any composed edit. |

## Preview/apply examples

V3 keeps the V2 preview-first shape: a transformation returns a plan with a `workspace_edit`, a `risk`, and
either `accepted: true` or a structured `refusal`. Nothing is written until the edit is applied.

```python
from serena.java_refactor.workspace_edit import TransactionalWorkspaceEditApplier
from serena.java_refactor_v3.graph import TransformationGraphBuilder
from serena.java_refactor_v3.recipes import RecipeEngine, load_builtin_recipe
from serena.java_refactor_v3.reports import ImpactReportBuilder

graph = TransformationGraphBuilder(project_root).build("rev-1")

# 1) preview a named migration recipe (no disk writes)
recipe = load_builtin_recipe("junit4-to-junit5-annotations")
plan = RecipeEngine(project_root, graph).plan(recipe)
assert plan.accepted                      # or inspect plan.refusal.{code, message}
print(plan.risk, plan.grouped())          # risk + byRule/byFile/byRisk preview counts

# 2) inspect the whole-repo impact before applying
report = ImpactReportBuilder(project_root, graph).build(
    plan.workspace_edit, risk=plan.risk, operation="applyRefactorRecipe"
).to_dict()
print(report["api"]["boundaryCrossed"], report["tests"]["impacted"])

# 3) apply transactionally (all-or-nothing, revision-guarded by old_hash)
TransactionalWorkspaceEditApplier(project_root).apply(plan.workspace_edit)
```

## Impact reports

`ImpactReportBuilder(project_root, graph).build(workspace_edit, *, risk, operation)` produces a five-section
report so a reviewer can judge blast radius before applying:

- **java** — touched `.java` files with change kind (`modify`/`create`/`delete`/`rename`), edit count, and the
  declared types in each file.
- **resources** — touched non-Java files and the types they reference, plus `wiredTypeReferences`: resource
  files elsewhere (e.g. `META-INF/services`, Spring XML) that wire a changed type.
- **api** — main-source types touched and their cross-file `externalReferences` (resource/test); `boundaryCrossed`
  is true when a changed type is referenced from outside its own file.
- **tests** — tests that reference a touched type (`impacted`) and directly-edited test files (`touchedTestFiles`).
- **risk** — the roll-up: `level`, `apiAffected`/`resourcesAffected`/`testsAffected`, and human-readable `reasons`.

## Refusal-code registry

Every V3 refusal code is registered in one place. `serena.java_refactor_v3.reports.acceptance.all_refusal_codes()`
returns the complete `code -> description` catalogue (it imports every feature module, so the registry is fully
populated regardless of import order). Codes are grouped by feature: `workspace_*`, `package_*` / `source_root_*`,
`deadcode_*`, `extract_*`, `delegation_*`, `inline_*`, `anon_*` / `lambda_*`, and `recipe_*`. A refusal is always
returned as `{code, message}` and never as a partial edit.

## V3 acceptance matrix

`serena.java_refactor_v3.reports.acceptance.acceptance_matrix()` maps each shipped tool (G001–G011) to the eight
invariants above. Every tool claims all eight, including `javacValidated`; the matrix additionally records each
tool's **provenance** (`javac-delta` for edit-emitting tools, `javac-facts` for read-only scans and reports) so the
honest distinction is preserved without dropping the invariant. `test/serena/test_java_refactor_v3_reports.py`
asserts the matrix covers every goal, that every registered refusal code is documented, and that the no-JetBrains
guarantee holds. `test/serena/test_java_refactor_v3_acceptance_behavioral.py` independently verifies — from observed
runtime behavior, not the matrix's own booleans — the real risk taxonomy (`SAFE`/`REVIEW_REQUIRED`/`REFUSED`), the
populated refusal-code registry and `{code, message}` refusal shape, and that the provenance partition matches the
actual edit-emitting/read-only split of the tool surface. Per-feature behavior (positive edits and precise refusals)
is pinned by the `test/serena/test_java_refactor_v3_*.py` suites.

## No-JetBrains guarantee

`jetbrains_references()` scans the entire `serena.java_refactor_v3` Python tree for any `import`/`from` of an
intellij/jetbrains/idea module or any Java IntelliJ/JetBrains coordinate string, and must return an empty list.
The platform depends only on Serena's project graph and the javac sidecar.

## Performance

V3 builds the project graph once per revision and caches it; transformations and impact reports read that graph
rather than re-parsing. Impact-report construction is linear in the size of the workspace edit and the touched-type
fan-out. These guarantees are executable: `test/serena/test_java_refactor_v3_perf.py` asserts the graph cache is hit
on a repeated same-revision build (the graph is not re-materialized), holds impact-report construction over a large
synthetic repo (500 touched types) under a second, and checks that doubling the input does not grow construction
time super-linearly.
