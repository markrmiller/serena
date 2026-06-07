## Assumption for V2

This V2 plan assumes V1 already shipped these foundations:

* A Java sidecar process managed by Serena.
* A `JavacTask`/`Trees` compiler pipeline.
* Project-model discovery for Maven, Gradle, plain Java, and basic modules.
* Semantic target resolution from Serena `name_path + relative_path`.
* A semantic reference index.
* Transactional preview/apply workspace edits.
* Java-specific tools for semantic rename, safe delete, move top-level type, inline local, and inline constant.
* Existing JDTLS remains the normal Java LSP backend; the javac sidecar is used only for refactoring intelligence.

The current Serena architecture makes this feasible because symbolic tools are already exposed as MCP tools, LSP is the default free backend, JetBrains is an optional richer backend, and Java currently maps to Eclipse JDTLS in the language-server layer.   

---

# V2 objective

V2 should move from “safe compiler-backed edits” to **IDE-grade Java refactoring workflows** for the common structural operations agents need during real code changes:

1. Change method signature.
2. Move static method/field.
3. Move instance method with receiver rewrite.
4. Pull up / push down members.
5. Extract method.
6. Extract interface.
7. Introduce parameter / field.
8. Encapsulate field.
9. Expanded inline method for constrained cases.
10. Refactor preview sessions with stable IDs and incremental apply.

V2 should still avoid JetBrains entirely. JDTLS may remain a companion for diagnostics and existing LSP behaviors, but V2 refactoring decisions should be computed by the javac sidecar.

---

# 1. V2 architectural changes

## 1.1 Promote the sidecar from “refactor executor” to “refactor session engine”

V1 likely has request/response commands such as:

```json
{
  "method": "rename",
  "params": { ... }
}
```

V2 should add **stateful preview sessions**:

```json
{
  "id": 10,
  "method": "refactor.createSession",
  "params": {
    "operation": "changeSignature",
    "target": {
      "relativePath": "src/main/java/com/acme/OrderService.java",
      "line": 42,
      "column": 17,
      "namePathHint": "OrderService/calculateTotal[0]"
    },
    "arguments": {
      "newName": "calculate",
      "parameters": [
        { "oldIndex": 0, "name": "order", "type": "Order" },
        { "new": true, "name": "currency", "type": "Currency", "defaultValue": "Currency.USD" }
      ],
      "returnType": "Money"
    }
  }
}
```

Response:

```json
{
  "sessionId": "jr-20260607-000001",
  "summary": "Change signature OrderService.calculateTotal(Order) -> calculate(Order, Currency): Money",
  "status": "previewReady",
  "preconditions": [],
  "warnings": [
    {
      "code": "DEFAULT_ARGUMENT_USED",
      "message": "Added parameter currency uses default value Currency.USD at 14 call sites."
    }
  ],
  "preview": {
    "filesChanged": 9,
    "textEdits": 37,
    "fileOperations": 0,
    "diagnosticDelta": {
      "newErrors": 0,
      "newWarnings": 1
    }
  }
}
```

Then Serena can ask for the preview:

```json
{
  "id": 11,
  "method": "refactor.getSessionEdit",
  "params": {
    "sessionId": "jr-20260607-000001",
    "format": "serenaWorkspaceEdit"
  }
}
```

And apply later:

```json
{
  "id": 12,
  "method": "refactor.applySession",
  "params": {
    "sessionId": "jr-20260607-000001",
    "expectedProjectRevision": "..."
  }
}
```

The apply should still be performed by Python’s transactional edit applier unless the edit needs JVM-side recomputation immediately before apply.

## 1.2 Add project revision tokens

V1’s file-level hashes are enough for simple edits. V2 multi-step refactors need a stronger revision token:

```java
record ProjectRevision(
    String modelHash,
    Map<Path, String> sourceFileHashes,
    Instant createdAt,
    List<Path> invalidationInputs
) {}
```

Each session stores:

```java
record RefactorSession(
    String sessionId,
    ProjectRevision revision,
    RefactorOperation operation,
    RefactorPlan plan,
    RefactorWorkspaceEdit edit,
    ValidationReport validation
) {}
```

Apply is refused if:

* Any touched file hash changed.
* The build model hash changed.
* Relevant generated-source roots changed.
* The target no longer resolves to the same semantic key.

## 1.3 Add a sidecar capability registry

V2 tools should query sidecar capabilities at startup:

```json
{
  "method": "capabilities"
}
```

Response:

```json
{
  "capabilities": {
    "rename": "stable",
    "safeDelete": "stable",
    "moveTopLevelType": "stable",
    "inlineLocalVariable": "stable",
    "changeSignature": "beta",
    "moveStaticMember": "beta",
    "moveInstanceMethod": "experimental",
    "pullUpMember": "beta",
    "pushDownMember": "experimental",
    "extractMethod": "beta",
    "extractInterface": "beta",
    "encapsulateField": "beta"
  },
  "javac": {
    "runtimeJdk": "21",
    "supportsPreview": false
  }
}
```

Use this to dynamically enable optional Serena tools.

---

# 2. Python tool surface for V2

Add a new module:

```text
src/serena/tools/java_refactor_v2_tools.py
```

Then export it from:

```text
src/serena/tools/__init__.py
```

Serena already uses this package-level import pattern for tool discovery. 

## 2.1 New MCP tools

### `java_change_signature`

```python
class JavaChangeSignatureTool(EditingToolWithDiagnostics, ToolMarkerSymbolicEdit, ToolMarkerOptional, ToolMarkerBeta):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        new_name: str | None = None,
        new_return_type: str | None = None,
        parameters_json: str = "[]",
        update_overrides: bool = True,
        default_values_json: str = "{}",
        preview: bool = True,
        validate: bool = True,
    ) -> str:
        ...
```

`parameters_json` should describe final parameter order:

```json
[
  {
    "old_index": 0,
    "name": "order",
    "type": "Order"
  },
  {
    "new": true,
    "name": "currency",
    "type": "Currency",
    "default_value": "Currency.USD"
  }
]
```

### `java_move_static_member`

```python
class JavaMoveStaticMemberTool(...):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        target_type: str,
        new_name: str | None = None,
        preview: bool = True,
        validate: bool = True,
    ) -> str:
        ...
```

### `java_move_instance_method`

```python
class JavaMoveInstanceMethodTool(...):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        target_parameter_name: str,
        new_name: str | None = None,
        leave_delegate: bool = True,
        preview: bool = True,
        validate: bool = True,
    ) -> str:
        ...
```

### `java_pull_up_member`

```python
class JavaPullUpMemberTool(...):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        target_supertype: str,
        make_abstract: bool = False,
        leave_delegate: bool = False,
        preview: bool = True,
        validate: bool = True,
    ) -> str:
        ...
```

### `java_push_down_member`

```python
class JavaPushDownMemberTool(...):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        target_subtypes_json: str = "[]",
        remove_from_source: bool = True,
        preview: bool = True,
        validate: bool = True,
    ) -> str:
        ...
```

### `java_extract_method`

```python
class JavaExtractMethodTool(...):
    def apply(
        self,
        relative_path: str,
        start_line: int,
        start_col: int,
        end_line: int,
        end_col: int,
        new_method_name: str,
        visibility: str = "private",
        make_static: bool | None = None,
        preview: bool = True,
        validate: bool = True,
    ) -> str:
        ...
```

### `java_extract_interface`

```python
class JavaExtractInterfaceTool(...):
    def apply(
        name_path: str,
        relative_path: str,
        interface_name: str,
        target_package: str | None = None,
        members_json: str = "[]",
        replace_usages: bool = False,
        preview: bool = True,
        validate: bool = True,
    ) -> str:
        ...
```

### `java_encapsulate_field`

```python
class JavaEncapsulateFieldTool(...):
    def apply(
        name_path: str,
        relative_path: str,
        getter_name: str | None = None,
        setter_name: str | None = None,
        setter: bool = True,
        update_usages: bool = True,
        preview: bool = True,
        validate: bool = True,
    ) -> str:
        ...
```

The tools should use Serena’s existing `Tool.apply(...)` conventions because `apply_ex` already provides active-project checks, timeout handling, error wrapping, tool-usage recording, and cache saving.  

---

# 3. V2 sidecar packages

Add these Java packages:

```text
java-refactor/src/main/java/io/serena/javarefactor/
  session/
    RefactorSessionManager.java
    ProjectRevision.java
    SessionStore.java

  operations/
    change_signature/
      ChangeSignaturePlanner.java
      MethodSignatureModel.java
      CallSiteRewriter.java
      OverrideSignatureUpdater.java

    move_member/
      MoveStaticMemberPlanner.java
      MoveInstanceMethodPlanner.java
      ReceiverRewritePlanner.java
      AccessAdjustmentPlanner.java

    hierarchy/
      TypeHierarchyIndex.java
      PullUpPlanner.java
      PushDownPlanner.java
      OverrideGroupResolver.java

    extract_method/
      ExtractMethodPlanner.java
      SelectionAnalyzer.java
      DataFlowAnalyzer.java
      ControlFlowAnalyzer.java
      MethodBodySynthesizer.java

    extract_interface/
      ExtractInterfacePlanner.java
      InterfaceFileSynthesizer.java
      TypeUsageRewriter.java

    encapsulate_field/
      EncapsulateFieldPlanner.java
      AccessorSynthesizer.java
      FieldAccessRewriter.java

    inline_method/
      InlineMethodPlanner.java
      SubstitutionEngine.java
      EvaluationOrderGuard.java
```

---

# 4. Shared V2 semantic infrastructure

V2 needs several general-purpose analyses. Implement these before adding individual refactorings.

## 4.1 Type hierarchy index

Create:

```java
final class TypeHierarchyIndex {
    Map<TypeKey, TypeInfo> typesByKey;
    Map<TypeKey, Set<TypeKey>> directSubtypes;
    Map<TypeKey, Set<TypeKey>> directSupertypes;
    Map<MethodKey, Set<MethodKey>> overrideGroups;
}
```

For each `TypeElement`:

* Record superclass.
* Record interfaces.
* Record permitted subclasses for sealed types.
* Record nested types.
* Record methods and fields.
* Record visibility and modifiers.
* Record source location.

Use this for:

* Change signature.
* Rename hardening.
* Pull up.
* Push down.
* Move method.
* Extract interface.

## 4.2 Method body model

Create a normalized representation:

```java
record MethodBodyModel(
    MethodKey method,
    TreePath bodyPath,
    List<StatementTree> statements,
    Set<ElementKey> reads,
    Set<ElementKey> writes,
    Set<ElementKey> calls,
    Set<ElementKey> referencedTypes,
    boolean hasThis,
    boolean hasSuper,
    boolean hasReturn,
    boolean hasThrow,
    boolean hasSynchronized,
    boolean hasLambdaOrAnonymousClass
) {}
```

Use `TreePathScanner` to collect:

* variable reads/writes
* field reads/writes
* method calls
* constructor calls
* `this`
* `super`
* control-flow exits
* checked exceptions
* lambdas and anonymous classes

## 4.3 Expression purity and evaluation-order analysis

V1 inline-local likely has a simple purity checker. V2 should generalize it:

```java
enum Purity {
    PURE,
    ALLOCATION_ONLY,
    UNKNOWN,
    SIDE_EFFECTING
}
```

Classify:

| Expression                | Purity                                                 |
| ------------------------- | ------------------------------------------------------ |
| literals                  | `PURE`                                                 |
| local/field reads         | `PURE` if final/effectively final, otherwise `UNKNOWN` |
| arithmetic on pure values | `PURE`                                                 |
| object creation           | `ALLOCATION_ONLY`                                      |
| method call               | `UNKNOWN` unless whitelisted                           |
| assignment/update         | `SIDE_EFFECTING`                                       |
| array write               | `SIDE_EFFECTING`                                       |
| method reference          | `UNKNOWN`                                              |

Use this for:

* Extract method parameter ordering.
* Inline method.
* Move instance method receiver rewriting.
* Change signature default-argument insertion.

## 4.4 Import manager

V1 likely performs exact import rewrites. V2 needs a real import manager:

```java
final class ImportRewritePlanner {
    ImportPlan planImports(CompilationUnitTree unit, Set<TypeUse> requiredTypes);
}
```

Capabilities:

* Add imports.
* Remove unused imports.
* Preserve static imports.
* Preserve wildcard imports unless they conflict.
* Avoid adding imports for `java.lang`.
* Avoid adding imports for same-package types.
* Detect ambiguous simple names.
* Support FQN fallback when ambiguity exists.
* Keep style:

  * groups
  * blank lines
  * static imports before/after normal imports based on existing file style
  * alphabetical order if existing file is ordered

V2 refactors will otherwise produce ugly or ambiguous code.

## 4.5 Access planner

Moving members and extracting methods frequently breaks visibility. Add:

```java
final class AccessAdjustmentPlanner {
    List<AccessChange> requiredAccessChanges(RefactorPlan plan);
}
```

Rules:

* Prefer no access widening.
* Prefer package-private over protected over public.
* Never widen public API silently; warn.
* Refuse when widening would expose private security-sensitive members unless explicitly allowed.
* For private nested access synthetic bridges, do not rely on compiler behavior; make source-level access valid.

---

# 5. Change signature

This should be the flagship V2 refactor.

## 5.1 Supported V2 scope

Support:

* Rename method while changing signature.
* Reorder parameters.
* Rename parameters.
* Add parameters with default values at call sites.
* Remove unused parameters.
* Change return type only when body expression is compatible or explicit conversion is supplied.
* Apply consistently across override group.
* Update call sites.
* Update method references where safe.
* Update constructors in a constrained path.

Defer:

* Varargs conversion.
* Generic type parameter changes.
* Checked exception list editing.
* Public API binary compatibility modeling.
* Annotation-value method changes.
* Complex method-reference adaptation.

## 5.2 Input model

```json
{
  "newName": "calculate",
  "newReturnType": "Money",
  "parameters": [
    {
      "oldIndex": 0,
      "name": "order",
      "type": "Order"
    },
    {
      "new": true,
      "name": "currency",
      "type": "Currency",
      "defaultValue": "Currency.USD"
    }
  ],
  "removedParameterPolicy": "requireUnused",
  "updateOverrides": true
}
```

## 5.3 Algorithm

1. Resolve target to `ExecutableElement`.
2. Reject if target is:

   * native method
   * annotation type member
   * synthetic/generated
   * dependency source
   * unresolved ERROR type
3. Build method group:

   * target method
   * overridden declarations
   * overriding methods
   * interface implementations
4. Validate parameter plan:

   * all old parameters accounted for or explicitly removed
   * no duplicate names
   * valid Java identifiers
   * valid types resolvable in each declaring source file
   * removed parameters unused in every method body unless explicit default/deletion strategy exists
5. Rewrite declarations:

   * method name
   * return type
   * parameter list
   * receiver annotations/type annotations preserved
   * annotations/modifiers preserved
6. Rewrite call sites:

   * positional argument reorder
   * added default argument expression
   * removed arguments dropped only if purity permits
7. Rewrite constructor call sites if constructor signature changed:

   * `new Foo(...)`
   * `this(...)`
   * `super(...)`
8. Rewrite method references:

   * `this::oldName`
   * `Type::oldName`
   * `obj::oldName`
   * only if arity still matches the functional interface
   * otherwise refuse and list locations
9. Update imports needed by new parameter/default types.
10. Validate with javac.

## 5.4 Call-site rewrite examples

### Add parameter

Before:

```java
service.calculateTotal(order);
```

After:

```java
service.calculate(order, Currency.USD);
```

### Reorder parameters

Before:

```java
foo.copy(source, target, options);
```

After:

```java
foo.copy(target, source, options);
```

### Remove parameter

Only if safe:

```java
foo.render(model, unusedContext);
```

After:

```java
foo.render(model);
```

Reject if removed argument is side-effecting:

```java
foo.render(model, contextFactory.create());
```

unless the user explicitly chooses to preserve side effects:

```java
Context ignored = contextFactory.create();
foo.render(model);
```

V2 should initially refuse that case.

## 5.5 Refusal cases

Return structured errors:

```json
{
  "status": "refused",
  "code": "METHOD_REFERENCE_ARITY_CHANGE",
  "message": "Cannot update method reference OrderService::calculateTotal because the target functional interface would no longer match.",
  "locations": [...]
}
```

Other refusal codes:

```text
OVERRIDE_GROUP_INCOMPLETE
REMOVED_PARAMETER_STILL_USED
DEFAULT_ARGUMENT_UNRESOLVED
CALL_SITE_ARGUMENT_HAS_SIDE_EFFECTS
RETURN_TYPE_INCOMPATIBLE
AMBIGUOUS_OVERLOAD_AFTER_CHANGE
PUBLIC_API_CHANGE_REQUIRES_CONFIRMATION
```

---

# 6. Move static method/field

## 6.1 Supported V2 scope

Support moving:

* `static` methods
* `static final` constants
* private/package-private static helpers
* public static methods with warnings
* static nested helper types only as a later extension

Defer:

* moving instance fields
* moving static initializers
* moving members with complicated private dependencies unless access adjustment is enabled

## 6.2 Algorithm

1. Resolve target member.
2. Resolve target type.
3. Validate:

   * target member is static
   * target type is source-editable
   * target type does not already define conflicting member
   * member does not reference private members of original class unless:

     * those members are also moved, or
     * access widening is allowed
4. Cut declaration span from source type.
5. Insert declaration into target type:

   * preserve Javadoc and annotations
   * choose insertion point by member kind and style
6. Rewrite references:

   * `OldType.member` → `NewType.member`
   * static imports
   * unqualified references inside old class may need `NewType.member`
   * unqualified references inside new class may remain unqualified
7. Update imports.
8. Validate.

## 6.3 Tool output

Preview should explicitly show dependency issues:

```json
{
  "warnings": [
    {
      "code": "ACCESS_WIDENING_REQUIRED",
      "message": "Moved method uses private helper parseAmount in OrderUtils. Refactor would change helper visibility to package-private."
    }
  ]
}
```

Default should be to **refuse access widening** unless requested:

```python
allow_access_widening: bool = False
```

---

# 7. Move instance method

This is the highest-risk V2 feature. Keep it constrained.

## 7.1 Supported V2 scope

Support moving an instance method from class `A` to class `B` when:

* `B` is the type of one of the method parameters, or
* `B` is the type of a field read from `this`, or
* the user explicitly supplies a target receiver expression strategy.

Initial recommended scope:

```java
class OrderService {
    Money calculate(Order order) {
        return order.getPrice().multiply(order.getQuantity());
    }
}
```

Move to `Order`:

```java
class Order {
    Money calculate() {
        return getPrice().multiply(getQuantity());
    }
}
```

Call sites:

```java
service.calculate(order)
```

become:

```java
order.calculate()
```

## 7.2 Algorithm

1. Resolve method.
2. Analyze method body:

   * references to `this`
   * fields/methods of source class
   * parameters
   * thrown exceptions
   * type parameters
3. Resolve target receiver:

   * parameter named `target_parameter_name`, or
   * explicit target type
4. Determine moved method signature:

   * remove target receiver parameter
   * preserve other parameters
   * preserve return type
   * preserve throws
5. Rewrite method body:

   * target parameter references may become `this`
   * source `this` references require a source-class parameter, or cause refusal
6. Insert method into target class.
7. Optionally leave delegate in source class:

   ```java
   Money calculate(Order order) {
       return order.calculate();
   }
   ```
8. Rewrite call sites:

   * `source.calculate(order, x)` → `order.calculate(x)`
   * `this.calculate(order)` → `order.calculate()`
9. Update imports.
10. Validate.

## 7.3 Refusal cases

Refuse by default if method body uses:

* source class private fields
* source class private methods
* `super`
* synchronization on `this`
* source class type parameters not available in target
* protected access that would change semantics
* overloads that become ambiguous after receiver rewrite
* target receiver expression with side effects

Example refusal:

```json
{
  "code": "SOURCE_THIS_REQUIRED",
  "message": "Cannot move method because it reads field OrderService.taxPolicy. Enable leave_delegate or supply a source parameter strategy."
}
```

## 7.4 Optional delegate mode

Default:

```python
leave_delegate=True
```

This reduces breakage:

```java
// old class
Money calculate(Order order) {
    return order.calculate();
}
```

Then call-site rewriting can be optional:

```python
rewrite_call_sites: bool = True
leave_delegate: bool = True
```

---

# 8. Pull up member

## 8.1 Supported V2 scope

Support pulling up:

* methods
* constants
* fields only if static final or no initialization complexity
* abstract method declarations
* interface method declarations

Targets:

* superclass
* interface

## 8.2 Method pull-up algorithm

1. Resolve member in subclass.
2. Resolve target supertype.
3. Validate target is actual supertype.
4. Determine whether method can be concrete in target:

   * if body only uses members available in target, move concrete method
   * otherwise, create abstract declaration in target and keep implementation in subclass
5. Check sibling subclasses:

   * if they already define compatible method, treat as implementation
   * if concrete method is pulled up, ensure it compiles for all subclasses
6. Remove or keep source method depending on mode:

   * concrete move: remove from subclass
   * abstract declaration: keep implementation
7. Add `@Override` where appropriate.
8. Update imports.
9. Validate.

## 8.3 Field pull-up algorithm

Support only:

```java
protected static final String KIND = "x";
```

or simple instance fields when no constructor/init semantics are involved.

Refuse if:

* field initializer references subclass members
* field is assigned outside declaration
* field name collides in supertype
* serialization impact is detected and user has not confirmed

## 8.4 Interface target

If target is interface:

* Methods become abstract unless default method is explicitly requested.
* Fields become `public static final`.
* Visibility is adjusted to Java interface rules.
* Reject private implementation details.

---

# 9. Push down member

## 9.1 Supported V2 scope

Support pushing down:

* abstract methods
* concrete methods
* static constants
* fields only under strict constraints

## 9.2 Algorithm

1. Resolve source member.
2. Enumerate direct/indirect subtypes from `TypeHierarchyIndex`.
3. If `target_subtypes_json` is empty, default to all direct subtypes.
4. For each target subtype:

   * check name collision
   * check access to referenced members
   * compute required imports
5. Insert member into selected subtypes.
6. Remove from source type if `remove_from_source=true`.
7. Rewrite call sites only if necessary:

   * references through source type may no longer compile if member removed
   * if call receiver static type is source type, refuse or insert cast only if explicitly allowed
8. Validate.

## 9.3 Conservative default

For V2, default to:

```python
remove_from_source=False
```

This creates duplicated implementation in subtypes while preserving old API. Later, allow removal once call-site typing is fully safe.

---

# 10. Extract method

This is the largest V2 feature. It needs selection, data-flow, control-flow, synthesis, and validation.

## 10.1 Supported V2 scope

Support selections that are:

* whole statements only
* within a single method/constructor/initializer
* not crossing lambda/class boundaries
* not containing unresolved code
* not containing `break`/`continue` crossing outside selection
* not containing multiple return paths initially

Support return modes:

| Selection behavior                              | V2 action            |
| ----------------------------------------------- | -------------------- |
| no output variables                             | new `void` method    |
| one output variable assigned                    | return that variable |
| one expression selected                         | return expression    |
| selected statements return from original method | initially refuse     |
| multiple output variables                       | refuse initially     |

## 10.2 Selection normalization

Input is a range:

```text
relative_path, start_line, start_col, end_line, end_col
```

Sidecar normalizes to complete AST nodes:

```java
record NormalizedSelection(
    TreePath enclosingMethod,
    List<StatementTree> selectedStatements,
    int startOffset,
    int endOffset
) {}
```

If the range cuts through a statement, return:

```json
{
  "status": "refused",
  "code": "SELECTION_NOT_STATEMENT_ALIGNED",
  "message": "Selection must contain complete Java statements.",
  "suggestedRanges": [...]
}
```

## 10.3 Data-flow analysis

For selected statements:

```java
record ExtractMethodDataFlow(
    List<Variable> inputs,
    List<Variable> outputs,
    Set<Variable> declaredInside,
    Set<Variable> usedAfterSelection,
    Set<Variable> assignedInside,
    boolean usesThis,
    boolean usesSuper,
    Set<TypeMirror> thrownCheckedExceptions
) {}
```

Inputs:

* variables read inside selection but declared outside

Outputs:

* variables assigned inside selection and used after selection

Declared-inside:

* locals declared in selection

## 10.4 Method synthesis

Example:

Before:

```java
Money subtotal = order.subtotal();
Money tax = taxPolicy.calculate(order);
Money total = subtotal.add(tax);
return total;
```

Select first three statements. Extract:

```java
private Money calculateTotal(Order order) {
    Money subtotal = order.subtotal();
    Money tax = taxPolicy.calculate(order);
    Money total = subtotal.add(tax);
    return total;
}
```

Replace selection:

```java
Money total = calculateTotal(order);
return total;
```

If output variable already declared before selection:

```java
total = calculateTotal(order);
```

## 10.5 Static decision

If enclosing method is static, extracted method must be static.

If selection does not use instance state and user passes `make_static=true`, make it static.

Otherwise preserve instance method.

## 10.6 Insertion point

Insert extracted method:

* after current method by default
* before next method if class style groups helpers below callers
* preserve indentation
* preserve blank lines

## 10.7 Validation

After extraction:

* javac compile validation.
* Verify selected statements no longer appear duplicated incorrectly.
* Verify method name collision avoided.
* Verify imports remain valid.

---

# 11. Extract interface

## 11.1 Supported V2 scope

Support extracting an interface from a class with selected public methods.

Inputs:

```json
{
  "interfaceName": "PricedOrder",
  "targetPackage": "com.acme.api",
  "members": [
    "getSubtotal()",
    "getTax()",
    "calculateTotal()"
  ],
  "replaceUsages": false
}
```

## 11.2 Algorithm

1. Resolve source class.
2. Resolve selected members.
3. Validate:

   * methods are instance methods
   * no private-only parameter/return types unless interface is in same package
   * no duplicate signatures
   * target interface file does not exist
4. Generate interface:

   ```java
   package com.acme.api;

   public interface PricedOrder {
       Money getSubtotal();
       Money getTax();
       Money calculateTotal();
   }
   ```
5. Add `implements PricedOrder` to class.
6. Add imports.
7. Optional usage replacement:

   * find variables/fields/parameters whose declared type can be narrowed to interface
   * only replace if all used members are in interface
   * avoid public API changes unless confirmed
8. Validate.

## 11.3 Usage replacement should be conservative

For:

```java
Order order = repository.load(id);
Money total = order.calculateTotal();
```

Replacement to interface type is safe only if:

* construction/assignment expression type is assignable to interface
* all subsequent member calls are declared on the interface
* no casts/reflection/serialization context detected

Default:

```python
replace_usages=False
```

---

# 12. Introduce parameter

This can be implemented as a wrapper around change signature.

## 12.1 Supported V2 scope

Selection is an expression inside a method.

Before:

```java
Money total = calculate(order, Currency.USD);
```

Introduce parameter `currency`:

```java
Money total = calculate(order, currency);
```

Method signature:

```java
Money calculate(Order order, Currency currency) { ... }
```

Call sites:

```java
calculate(order)
```

become:

```java
calculate(order, Currency.USD)
```

## 12.2 Algorithm

1. Resolve selected expression.
2. Infer type with `trees.getTypeMirror(path)`.
3. Validate expression purity or explicit allow side effects.
4. Replace selected expression with parameter name.
5. Delegate to change-signature engine to add parameter.
6. Validate.

Tool:

```python
class JavaIntroduceParameterTool(...):
    def apply(
        relative_path: str,
        start_line: int,
        start_col: int,
        end_line: int,
        end_col: int,
        parameter_name: str,
        preview: bool = True,
        validate: bool = True,
    ) -> str:
        ...
```

---

# 13. Introduce field

## 13.1 Supported V2 scope

Extract an expression to:

* private final field initialized in constructor
* private static final constant if expression is compile-time constant
* private field initialized inline if safe

## 13.2 Cases

### Compile-time constant

Before:

```java
if (count > 100) { ... }
```

After:

```java
private static final int MAX_COUNT = 100;

if (count > MAX_COUNT) { ... }
```

### Instance field

Before:

```java
Duration timeout = Duration.ofSeconds(30);
```

After:

```java
private final Duration timeout;

public Client() {
    this.timeout = Duration.ofSeconds(30);
}
```

This is risky. V2 should initially support only:

* class has exactly one constructor, or
* field can be initialized inline safely.

## 13.3 Refusal cases

Refuse if:

* expression references local variables for field initializer
* multiple constructors and no constructor strategy supplied
* expression has side effects
* field name collides
* target class initialization order would change

---

# 14. Encapsulate field

## 14.1 Supported V2 scope

For a field:

```java
public int count;
```

produce:

```java
private int count;

public int getCount() {
    return count;
}

public void setCount(int count) {
    this.count = count;
}
```

Rewrite usages:

```java
obj.count
```

to:

```java
obj.getCount()
obj.setCount(value)
```

## 14.2 Algorithm

1. Resolve `VariableElement`.
2. Validate field is not:

   * enum constant
   * synthetic/generated
   * record component field
   * volatile/concurrency-sensitive unless explicit
3. Generate getter/setter names:

   * boolean: `isX` or existing style
   * non-boolean: `getX`
   * setter: `setX`
4. Check method collisions.
5. Insert accessors.
6. Change field visibility.
7. Rewrite reads/writes:

   * read: `obj.field` → `obj.getField()`
   * write assignment: `obj.field = expr` → `obj.setField(expr)`
   * compound assignment: refuse initially
   * increment/decrement: refuse initially
   * within same class: default can keep direct access or rewrite based on option
8. Validate.

## 14.3 Conservative write handling

Support:

```java
obj.field = value;
```

Refuse:

```java
obj.field += value;
obj.field++;
++obj.field;
```

unless V3 adds expression-preserving transformations.

---

# 15. Expanded inline method

V1 deferred general inline method. V2 should support constrained, useful cases.

## 15.1 Supported V2 scope

Support methods that are:

* private or static
* non-overridden
* source-editable
* single return expression, or
* single expression statement for `void`
* no checked exceptions beyond caller context
* no `this` unless receiver substitution is trivial
* no `super`
* no type parameters initially

Examples:

```java
private int doubleCount(int count) {
    return count * 2;
}
```

Call:

```java
int x = doubleCount(n);
```

After:

```java
int x = n * 2;
```

## 15.2 Algorithm

1. Resolve method.
2. Verify no override group.
3. Parse method body model.
4. Build substitution map:

   ```text
   parameter -> argument text
   this -> receiver expression
   ```
5. Validate each argument:

   * side effects
   * multiple-use parameter
   * evaluation order
6. Parenthesize substitutions.
7. Replace call expression.
8. Optionally safe-delete method if no references remain.
9. Validate.

## 15.3 Side-effect guard

If parameter is used twice:

```java
private int sumTwice(int x) {
    return x + x;
}
```

Call:

```java
sumTwice(next())
```

Refuse because inlining would duplicate side effects:

```java
next() + next()
```

unless V3 adds temp-variable introduction.

---

# 16. Diagnostics and validation in V2

V1 validation can be simple “javac errors before/after.” V2 needs **diagnostic delta classification**.

Add:

```java
record DiagnosticDelta(
    List<DiagnosticInfo> before,
    List<DiagnosticInfo> after,
    List<DiagnosticInfo> newErrors,
    List<DiagnosticInfo> resolvedErrors,
    List<DiagnosticInfo> unchangedErrors,
    List<DiagnosticInfo> newWarnings
) {}
```

Apply rules:

* Always refuse new `ERROR`.
* Warn on new unchecked/rawtype warnings.
* Allow pre-existing errors if unchanged and config permits incomplete analysis.
* Refuse if changed files introduce parse errors.
* For multi-module projects, validate all affected source sets.

V2 should expose this in preview:

```json
{
  "diagnosticDelta": {
    "newErrors": 0,
    "resolvedErrors": 2,
    "newWarnings": 3
  }
}
```

Serena already has diagnostics tooling on the LSP side; V2 should keep javac validation as the source of truth for refactor apply, then optionally surface LSP diagnostics after applying. 

---

# 17. Build-system integration improvements for V2

V2 refactors depend on more accurate project modeling than V1.

## 17.1 Gradle

Add support for:

* multi-project builds
* included builds
* Java toolchains
* source sets beyond `main`/`test`
* generated sources
* annotation processor classpaths
* Kotlin mixed projects where Java source depends on Kotlin output

V2 should not compile Kotlin, but it should include Kotlin output dirs if Gradle exposes them.

## 17.2 Maven

Add support for:

* reactor modules
* profiles
* generated sources
* annotation processor paths
* `maven-compiler-plugin` release/source/target
* test classpath when refactoring tests

## 17.3 Explicit model override

Add:

```yaml
java_refactor:
  model:
    modules:
      - name: app-main
        source_roots:
          - app/src/main/java
        classpath:
          - app/build/classes/java/main
          - ~/.m2/repository/...
        release: 21
```

This is important for repos where build-tool execution is not allowed.

---

# 18. Generated code and Lombok policy

Serena’s Java LS already has explicit settings around Lombok-generated symbols and JDTLS generated-code visibility.  

For V2 javac refactors:

* Default: do not edit generated sources.
* Recognize generated roots from Maven/Gradle.
* Allow references from generated sources to block refactors.
* Add option:

  ```yaml
  java_refactor:
    generated_sources:
      read: true
      edit: false
  ```

Lombok:

* Use Lombok jar in compiler classpath when configured.
* Still do not assume Lombok-generated methods are source-editable.
* If a refactor touches Lombok-generated API, warn or refuse.

---

# 19. Formatting strategy

V1 can produce mechanically correct edits. V2 needs style preservation.

Add a lightweight formatter layer:

```java
final class JavaStyleProfile {
    int indentSize;
    boolean useTabs;
    String lineEnding;
    ImportOrdering importOrdering;
    BraceStyle braceStyle;
}
```

Infer from the target file:

* indentation unit
* blank lines between methods
* import order
* static import grouping
* final parameter style
* annotation placement

Do not run a full formatter by default. Generate localized text that matches nearby code.

Optional config:

```yaml
java_refactor:
  formatting:
    use_external_formatter: false
    command: null
```

---

# 20. V2 configuration

Add:

```yaml
java_refactor:
  v2:
    enabled: true

    sessions:
      max_open_sessions: 16
      session_ttl_minutes: 30
      require_revision_match_on_apply: true

    change_signature:
      enabled: true
      allow_public_api_change: false
      allow_removed_side_effecting_arguments: false
      update_overrides_default: true

    move_member:
      enabled: true
      allow_access_widening: false
      leave_delegate_default: true
      rewrite_call_sites_default: true

    hierarchy:
      enabled: true
      allow_public_api_change: false

    extract_method:
      enabled: true
      allow_multiple_outputs: false
      allow_control_flow_exits: false

    extract_interface:
      enabled: true
      replace_usages_default: false

    encapsulate_field:
      enabled: true
      rewrite_internal_usages_default: false
      refuse_compound_assignments: true

    inline_method:
      enabled: true
      max_call_sites: 100
      delete_inlined_method_default: false
```

---

# 21. V2 test plan

## 21.1 New fixture repos

Add:

```text
test/resources/repos/java_refactor_v2/
  change-signature-basic/
  change-signature-hierarchy/
  move-static-member/
  move-instance-method/
  pull-up-push-down/
  extract-method/
  extract-interface/
  encapsulate-field/
  inline-method/
  multi-module-gradle/
  multi-module-maven/
  mixed-generated-sources/
```

## 21.2 Change signature tests

* Add parameter with default.
* Remove unused parameter.
* Refuse removing used parameter.
* Reorder parameters.
* Rename method and parameter together.
* Override group update.
* Interface method update.
* Constructor update.
* Method reference refusal.
* Overload ambiguity refusal.
* Static import unchanged where irrelevant.
* Import insertion for new parameter type.

## 21.3 Move member tests

* Move static method.
* Move static field.
* Update static imports.
* Refuse private dependency.
* Allow access widening when enabled.
* Move instance method to parameter type.
* Leave delegate.
* Rewrite call sites.
* Refuse `super` usage.
* Refuse receiver side-effect case.

## 21.4 Hierarchy tests

* Pull concrete method to superclass.
* Pull abstract declaration to interface.
* Pull static constant.
* Push method to all subclasses.
* Push method to selected subclass.
* Refuse call-site type break.
* Refuse target collision.

## 21.5 Extract method tests

* No output variable.
* One output variable.
* Expression extraction.
* Uses fields and parameters.
* Static enclosing method.
* Throws checked exception.
* Refuse partial statement.
* Refuse multiple outputs.
* Refuse break/continue crossing boundary.
* Preserve comments inside selection.

## 21.6 Extract interface tests

* Create interface in same package.
* Create interface in new package.
* Add implements clause.
* Import interface.
* Replace usage where safe.
* Refuse private return type crossing package.
* Refuse duplicate method signatures.

## 21.7 Encapsulate field tests

* Public field to private with getter/setter.
* Boolean getter style.
* Read rewrite.
* Assignment rewrite.
* Refuse compound assignment.
* Preserve annotations.
* Detect existing accessor collision.

## 21.8 Inline method tests

* Single return expression.
* Static helper.
* Receiver substitution.
* Refuse duplicate side-effect argument.
* Parenthesize expressions.
* Delete method when no references remain.

---

# 22. Commit breakdown for V2

## Commit V2-1: Refactor sessions

* Add `RefactorSessionManager`.
* Add session create/get/apply/cancel protocol.
* Add project revision tokens.
* Add Python session client.
* Add preview session output formatting.

## Commit V2-2: Shared analyses

* Add `TypeHierarchyIndex`.
* Add `MethodBodyModel`.
* Add generalized purity/evaluation-order analysis.
* Add import manager.
* Add access planner.

## Commit V2-3: Change signature

* Implement declaration rewriting.
* Implement call-site rewriting.
* Implement override group updates.
* Implement default values.
* Add Serena tool.

## Commit V2-4: Move static member

* Implement static method/field move.
* Implement reference rewrites.
* Implement access adjustment warnings/refusals.
* Add Serena tool.

## Commit V2-5: Move instance method

* Implement constrained move-to-parameter-type.
* Implement delegate generation.
* Implement receiver call-site rewrites.
* Add Serena tool as experimental.

## Commit V2-6: Pull up / push down

* Implement hierarchy planners.
* Add conservative default modes.
* Add tools.

## Commit V2-7: Extract method

* Implement selection analyzer.
* Implement data-flow/control-flow.
* Implement method body synthesis.
* Add tool.

## Commit V2-8: Extract interface

* Implement interface generation.
* Implement selected member handling.
* Implement optional usage narrowing.
* Add tool.

## Commit V2-9: Introduce parameter / field

* Implement introduce parameter as change-signature wrapper.
* Implement conservative introduce field.
* Add tools.

## Commit V2-10: Encapsulate field and inline method expansion

* Implement accessors.
* Implement field access rewrites.
* Implement constrained inline method.
* Add tools.

## Commit V2-11: Hardening and generic routing

* Add config-gated routing for selected V2 operations.
* Improve diagnostics delta.
* Improve generated-source policy.
* Add docs and examples.

---

# 23. V2 acceptance criteria

V2 is complete when Serena can perform these safely on real Java repos:

| Refactor             | Required V2 behavior                                                |
| -------------------- | ------------------------------------------------------------------- |
| Change signature     | Update declarations, overrides, call sites, imports, and validate.  |
| Move static member   | Move source declaration, rewrite references/imports, validate.      |
| Move instance method | Support parameter-target move with delegate and call-site rewrite.  |
| Pull up member       | Support concrete/abstract method pull-up with hierarchy checks.     |
| Push down member     | Support conservative copy/push with collision checks.               |
| Extract method       | Support statement selections with zero or one output variable.      |
| Extract interface    | Generate interface, add implements, optionally narrow safe usages.  |
| Introduce parameter  | Replace selected expression and update method signature/call sites. |
| Encapsulate field    | Generate accessors and rewrite simple reads/writes.                 |
| Inline method        | Inline private/static single-expression methods safely.             |

Every operation must provide:

* preview-first behavior
* structured refusal reasons
* exact changed-file list
* transactional apply
* project-revision guard
* javac validation
* import management
* no JetBrains dependency

---

# 24. What V2 should still not attempt

Do not include these in V2 unless V2 becomes too broad:

* General method extraction with multiple output variables.
* Control-flow-preserving extract method for arbitrary `return`, `break`, `continue`.
* Full inline method with statements, loops, try/catch, generics, and temp introduction.
* Move arbitrary instance fields.
* Move packages/directories.
* Convert anonymous class to lambda.
* Convert lambda to method reference.
* Full module-info rewrite.
* Spring/XML/resource-aware refactors.
* Reflection/string occurrence rewriting by default.

Those are V3+.

---

# 25. Practical priority order

If V2 must be narrowed, implement in this order:

1. Refactor sessions and project revisioning.
2. Shared type hierarchy/import/access analyses.
3. Change signature.
4. Extract method.
5. Move static member.
6. Encapsulate field.
7. Extract interface.
8. Move instance method.
9. Pull up / push down.
10. Expanded inline method.

The two most valuable V2 features for coding agents are **change signature** and **extract method**. They unlock larger edits that are currently fragile with search/replace and manual text surgery.
