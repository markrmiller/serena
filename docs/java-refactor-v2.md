# Java refactor V2 sessions

Java refactor V2 extends Serena's optional javac sidecar with preview-first refactoring sessions. It does **not** replace JDTLS: navigation, diagnostics, ordinary LSP rename, and fallback paths continue to use Serena's existing Java language-server integration. V2 never uses JetBrains APIs.

## Architecture

- **JDTLS remains the default authority** for normal Java language features and generic fallback behavior.
- **The sidecar is opt-in** through the project `java_refactor.enabled` configuration, exactly like the V1 Java refactor tools.
- **Sessions are preview-first**: `createSession` returns a workspace edit, a session id, touched files, source revisions, and diagnostics/refusal details without applying changes.
- **Apply is revision guarded**: `applySession` refuses when a touched file changed after the session was created, so stale previews cannot overwrite concurrent edits.
- **Cancel is explicit**: `cancelSession` releases a stored preview without changing the workspace.

## Capability contract

Every operation below is a **fully supported** V2 operation: within the contract stated in its row it produces a complete, compiler-validated edit, and every shape outside that contract returns a **structured refusal with located evidence** — never a partial or best-effort edit. "beta" marks the V2 surface as newly released, not as incomplete: there is no "conservative subset" hidden behind these rows. The only shapes V2 does not edit are the design-approved V3+ exclusions listed under [V2/V3 boundary](#v2v3-boundary); those are reported as refusals, not as silently narrowed behavior.

The Python manager only dispatches an operation whose sidecar capability status is `supported`; an operation that cannot meet the contract below is not exposed as callable rather than being offered with a caveat.

| Capability | Level | V2 contract |
| --- | --- | --- |
| `semanticRename` | stable | Existing V1 semantic rename path (override/implementation group + references). |
| `safeDelete` | stable | Existing V1 safe-delete path. |
| `moveTopLevelType` | stable | Existing V1 top-level type move path. |
| `inlineLocalVariable` | stable | Existing V1 local/constant inline path. |
| `refactorSessions` | beta | Stateful preview/apply/cancel wrapper with project-revision guards and a validated before/after diagnostic delta. |
| `changeSignature` | beta | Rename and add/remove/reorder/retype parameters with a default value or supplied argument; rewrites declaration, override/implementation group, and all call sites including constructor, qualified, and cross-file sites; applies return-type conversion at value sites. Refuses ambiguous overload resolution after the change and method-reference sites whose functional shape (arity) would change. |
| `introduceParameter` | beta | Promote a selected expression to a new parameter and thread the original expression through every call site (constructor and cross-file included). Refuses selections whose evaluation order or side effects cannot be proven reorder-safe. |
| `moveStaticMember` | beta | Relocate a static method/field to another editable type, rewriting qualified and unqualified references and transferring imports. Refuses an erased-signature collision in the target and gates visibility widening behind explicit confirmation. |
| `moveInstanceMethod` | beta | Relocate an instance method onto a receiver supplied as a target parameter, a target field, or a simple navigation receiver (identifier or dotted field access); rewrites call sites or retains a delegate. Refuses non-simple receivers, `super`/`synchronized`-on-`this`/source-instance-state/type-variable blockers, and method-reference sites unless a delegate is retained. |
| `pullUpMember` | beta | Transfer a member to a direct supertype (concrete or abstract method, interface default/static method, constant field) or convert it to an abstract declaration, transferring imports and adding `@Override` where required. Refuses target collisions, incompatible sibling/covariant/generic overrides, source-only body dependencies, and gates public-API widening behind confirmation. |
| `pushDownMember` | beta | Copy a member into selected direct subtypes (keeping the source) or move it (removing the source), transferring imports. Refuses target collisions and call sites that would no longer resolve after source removal. |
| `extractMethod` | beta | Extract a complete-statement selection into a new method with scope-aware, guaranteed-unique synthesized names. Refuses selections with control flow that produces outputs and selections that are not a complete extractable statement range. |
| `extractInterface` | beta | Extract public instance methods into a new interface and add the `implements` clause, transferring imports and preserving covariant/generic signatures. |
| `introduceField` | beta | Extract an initializer to a private final field, qualifying the replacement (`field`/`this.field`/`Type.FIELD`) from javac scope/binding facts. Refuses initializers that throw checked exceptions and non-eligible initializers per the field policy. |
| `encapsulateField` | beta | Generate JavaBean accessors and rewrite direct uses (including compound assignment) within the declaring file. Refuses accessor name collisions. |
| `inlineMethod` | beta | Inline a private/static method whose body is a single return expression or a single throw, propagating checked-exception compatibility. Refuses method-reference call sites, evaluation-order/duplication hazards, and bodies javac cannot model (returns a refusal instead of a textual approximation). |

## Policy gates

V2 sessions refuse generated and Lombok-managed sources by default.

Generated-source refusal triggers on generated source-root/path markers and `@Generated` markers. Lombok refusal triggers on Lombok imports or common Lombok type markers such as `@Data`, `@Value`, `@Getter`, `@Setter`, and `@Builder`.

Use `allowGenerated: true` or `allowLombok: true` only when the caller intentionally accepts that policy risk for a specific request. The opt-in is per operation request, not a project-wide bypass.

Build-model fidelity is part of the contract: an operation that touches a source set whose compile classpath could not be proven (e.g. an unmodelable Gradle source set with an unresolvable dependency) refuses apply with `classpath_unproven_apply_refused`, and an operation against a source set whose `--release` is newer than the sidecar JDK refuses apply with `incomplete_analysis_apply_refused` — both fail closed rather than editing against an incomplete model. See [Java refactoring sidecar](java-refactor-sidecar.md) for the build-model extraction contract.

## Python tool examples

The Python MCP tools keep the same preview-first shape as the session protocol:

```python
java_change_signature(
    relative_path="src/main/java/demo/App.java",
    line=12,
    column=17,
    new_name="format",
    parameters=[{"name": "name", "type": "String"}],
)

java_extract_method(
    relative_path="src/main/java/demo/App.java",
    new_method_name="printHeader",
    selection={"startLine": 20, "startColumn": 9, "endLine": 20, "endColumn": 38},
)

java_inline_method(
    relative_path="src/main/java/demo/App.java",
    method_name="doubleValue",
)
```

A high-level V2 operation tool always creates a preview session; mutation requires an explicit `java_apply_refactor_session` call with the returned session id. Review the returned workspace edit and the validated diagnostic delta before applying.

## V2 acceptance matrix

The committed fixture matrix covers plain Java, Maven, Gradle (including included builds, toolchain/release differences, Kotlin output dirs, and source sets beyond main/test), modules, multi-module Maven, generated-code policy, and Lombok-lite policy scenarios under `test/resources/repos/java_refactor/`.

The executable acceptance matrix in `test/serena/test_java_refactor_acceptance_matrix.py` is **behavioral**: each hard design requirement maps to one or more tests that assert a concrete planned edit or a precise refusal (positive and negative cases), and CI fails if a mapped test is missing or degenerates into an existence-only stub. It pins, per operation, the merge-proof obligations (constructor/qualified/cross-file call sites, side-effect and duplication policy, shadowing, compound-assignment policy, checked-exception propagation, method-reference refusal, import transfer/cleanup, and the public-API confirmation order), plus the shared import contract, session lifecycle, stale-revision refusal, diagnostics, and the build-model coverage above.

## V2/V3 boundary

V2 does not promise full IDE-equivalent coverage. The following are the **only** design-approved exclusions; each is reported as a structured refusal, not as a silently narrowed edit, and is deferred to V3+ once it has semantic resolution, preview/apply safety, and acceptance-matrix coverage:

- arbitrary control-flow-preserving extraction (extract method beyond a complete-statement range that produces outputs);
- full statement/loop/try/catch/generic method inlining (inline beyond a single return expression or single throw);
- arbitrary instance-field introduction (beyond the supported initializer field policy);
- package moves (moving a whole package);
- full `module-info.java` rewriting;
- Spring/XML/other resource-aware rewrites;
- reflection- and string-occurrence rewriting by default.

Any operation that cannot yet meet its V2 contract above is kept out of the callable surface (not exposed as `supported`) rather than being documented as a narrower V2.
