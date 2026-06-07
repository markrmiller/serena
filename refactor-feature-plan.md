## Target design

Add a **Java-only, compiler-backed refactoring engine** to Serena as an optional LSP-mode enhancement. Do not use the JetBrains plugin or JetBrains APIs. Keep the existing Java LSP/JDTLS backend for navigation, diagnostics, and general Serena compatibility, but route selected Java refactorings through a new `javac`-based service that computes exact project edits.

This fits Serena’s architecture because Serena already exposes semantic retrieval, editing, and refactoring tools through MCP, with LSP as the default/free backend and JetBrains as the richer proprietary backend. The current capability gap is explicit: language-server mode has symbol rename, while JetBrains has richer move/inline/delete workflows.   

The key implementation decision is: **build a JVM sidecar, not Python bindings around `javac`.** Serena is a Python package, while `JavaCompiler`, `JavacTask`, `Trees`, `Elements`, and `Types` are Java APIs. The clean design is a long-lived Java process managed by Serena, speaking JSON over stdio. Python tools request refactor previews; the JVM sidecar returns a structured workspace edit; Serena validates and applies it transactionally.

---

## 1. Architecture overview

### Existing Serena path to preserve

Today, Java uses Serena’s LSP backend. The `Language` enum has `JAVA`, Java source files are matched by `.java`, and Java maps to `EclipseJDTLS`.   

Current symbolic edit tools already include LSP rename and safe delete. `RenameSymbolTool` delegates to `LanguageServerCodeEditor.rename_symbol`, which asks the language server for a `WorkspaceEdit`. `SafeDeleteSymbol` asks for references and deletes only if none are found.   The LSP editor already knows how to translate and apply LSP workspace edits with text edits and file renames. 

Do not delete this path. Add a Java refactoring layer beside it.

### New high-level path

```text
Serena MCP tool
  ↓
Python JavaRefactorClient
  ↓ stdio JSON-RPC / NDJSON
Java sidecar: serena-java-refactor
  ↓
JavaProjectModel + JavacSession + RefactorPlanner
  ↓
RefactorWorkspaceEdit JSON
  ↓
Python transactional workspace-edit applier
  ↓
files on disk + optional JDTLS diagnostics
```

### New Python modules

Add:

```text
src/serena/java_refactor/
  __init__.py
  client.py
  manager.py
  models.py
  workspace_edit.py
  project_model_cache.py

src/serena/tools/java_refactor_tools.py
```

Then import the tool module from `src/serena/tools/__init__.py`, which already re-exports tool modules from that package. 

Add a component helper similar to `create_ls_code_editor()`:

```python
def create_java_refactor_client(self) -> JavaRefactorClient:
    ...
```

Serena’s `Component` base already provides helper factories for LSP symbol retrieval and code editors, including an LSP/JB switch in `create_code_editor()`. The Java refactor client should follow this style but require `LanguageBackend.LSP` plus an active Java language server or Java project. 

### New Java sidecar

Add a JVM subproject:

```text
java-refactor/
  build.gradle.kts
  src/main/java/io/serena/javarefactor/
    Main.java
    protocol/
    project/
    compiler/
    ast/
    edits/
    rename/
    safedelete/
    move/
    inline/
  src/test/java/...
```

Release packaging should include the built jar in the Python wheel. The current wheel configuration includes Python packages from `src/serena`, `src/interprompt`, and `src/solidlsp`; the plan should add either a wheel resource include for the sidecar jar or a first-run download/cache path. 

Recommended release behavior:

```toml
[tool.hatch.build.targets.wheel.force-include]
"java-refactor/build/libs/serena-java-refactor-all.jar" = "src/serena/resources/java-refactor/serena-java-refactor-all.jar"
```

For editable development, let `JavaRefactorManager` prefer:

1. `SERENA_JAVA_REFACTOR_JAR`
2. repo-local `java-refactor/build/libs/...jar`
3. bundled wheel resource

---

## 2. Compiler pipeline

The rough approach is correct.

Use `JavaCompiler.getTask(...)` to create a `CompilationTask`, cast it to `JavacTask`, call `parse()`, then call `analyze()`. Oracle’s API docs describe `JavacTask` as javac-specific access on top of `JavaCompiler.CompilationTask`; `parse()` returns syntax trees, and `analyze()` completes semantic analysis. ([Oracle Docs][1])

Use `Trees.instance(task)` to connect AST paths to semantic entities and source positions. `Trees` is the bridge between compiler tasks, processing elements, and tree APIs; it exposes methods such as `getElement`, `getTypeMirror`, `getSourcePositions`, `getScope`, and accessibility checks. ([Oracle Docs][2])

Skeleton:

```java
JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

StandardJavaFileManager fileManager =
    compiler.getStandardFileManager(diagnostics, Locale.getDefault(), charset);

Iterable<? extends JavaFileObject> units =
    fileManager.getJavaFileObjectsFromPaths(sourceFiles);

JavaCompiler.CompilationTask rawTask =
    compiler.getTask(
        null,
        fileManager,
        diagnostics,
        javacOptions,
        null,
        units
    );

JavacTask task = (JavacTask) rawTask;

Iterable<? extends CompilationUnitTree> asts = task.parse();
task.analyze();

Trees trees = Trees.instance(task);
Elements elements = task.getElements();
Types types = task.getTypes();
SourcePositions positions = trees.getSourcePositions();
```

Reuse `StandardJavaFileManager` where practical; the JavaCompiler docs explicitly call out that a standard file manager can be shared across compiler tasks, which is useful for caching jar scans and filesystem work. ([Oracle Docs][3])

---

## 3. Project model: the non-negotiable foundation

The hard part is not `JavacTask`; it is creating correct compiler inputs.

Add a `JavaProjectModel` built by the JVM sidecar and cached by Serena:

```java
record JavaProjectModel(
    Path projectRoot,
    List<SourceSet> sourceSets,
    List<Path> allJavaFiles,
    List<Path> classpath,
    List<Path> modulePath,
    List<Path> generatedSourceRoots,
    Optional<String> release,
    Optional<String> source,
    Optional<String> target,
    Charset encoding,
    boolean modular,
    List<String> javacOptions,
    List<Path> invalidationFiles
) {}
```

Each `SourceSet`:

```java
record SourceSet(
    String name,                 // main, test, generated, module name, etc.
    List<Path> sourceRoots,
    List<Path> outputDirs,
    List<Path> compileClasspath,
    List<Path> modulePath,
    List<String> javacOptions
) {}
```

### Build tool discovery

Implement in this order:

1. **Explicit Serena config**

   ```yaml
   java_refactor:
     enabled: true
     java_home: null
     source_roots: []
     classpath: []
     module_path: []
     release: null
     source: null
     target: null
     annotation_processing: "none"
     allow_incomplete_analysis: false
   ```

2. **Gradle**
   Use an init script that prints a JSON model:

   * `sourceSets.main.allJava.srcDirs`
   * `sourceSets.test.allJava.srcDirs`
   * `compileClasspath`
   * `annotationProcessorPath`
   * `destinationDirectory`
   * Java toolchain language version
   * generated source dirs

   Do not modify the project.

3. **Maven**
   Use Maven to derive:

   * source roots
   * test source roots
   * compile/test classpaths
   * source/target/release
   * generated sources
   * module-info presence

4. **Plain javac fallback**
   Detect conventional roots:

   ```text
   src/main/java
   src/test/java
   src
   ```

5. **JDTLS hints**
   Serena already has detailed Java LS settings for Maven, Gradle, Lombok, generated code, and JDTLS runtime behavior. Reuse those user settings where relevant, but do not call JetBrains. 

### Cache invalidation

Cache the project model under Serena’s project data path. Invalidate on changes to:

```text
pom.xml
**/pom.xml
build.gradle
build.gradle.kts
settings.gradle
settings.gradle.kts
gradle.properties
mvnw
gradlew
.module-info.java
**/*.java
.serena/project.yml
```

For source files, keep per-file hashes. For project model files, invalidate the whole model.

### Compiler options

Default options:

```text
-proc:none
-Xlint:none
-encoding <project encoding>
```

Use `-proc:none` initially. Annotation processors can be enabled later, but running arbitrary project processors during a refactor preview is slower and riskier. Add an opt-in:

```yaml
java_refactor:
  annotation_processing: "none"   # none | classpath | project
```

For modular projects:

```text
--module-path ...
--module-source-path ...
--add-modules ...
```

For non-modular projects:

```text
-classpath ...
-sourcepath ...
```

### Incomplete project behavior

Default behavior should be conservative:

```text
If semantic attribution has unresolved ERROR diagnostics, preview may be shown,
but apply is refused unless allow_incomplete_analysis=true.
```

This matters because a broken classpath can make `trees.getElement(path)` return `null` or a different element than expected.

---

## 4. Sidecar protocol

Use JSON-RPC-like messages over stdio. Keep it line-delimited to avoid a dependency on an HTTP server.

### Initialize

```json
{
  "id": 1,
  "method": "initialize",
  "params": {
    "projectRoot": "/repo",
    "encoding": "UTF-8",
    "javaHome": null,
    "ignoredPatterns": ["target/**", "build/**"],
    "config": {
      "buildToolModel": "auto",
      "annotationProcessing": "none",
      "allowIncompleteAnalysis": false
    }
  }
}
```

### Status

Expose a Serena MCP tool:

```python
class JavaRefactorStatusTool(Tool, ToolMarkerSymbolicRead, ToolMarkerOptional):
    def apply(self, refresh: bool = False) -> str: ...
```

Response should include:

```json
{
  "status": "ready",
  "jdk": "21.0.x",
  "buildTool": "gradle",
  "sourceSets": 4,
  "javaFiles": 1832,
  "classpathEntries": 241,
  "lastModelRefreshMs": 1284,
  "semanticErrors": 0
}
```

### Preview/apply split

Every refactoring tool should default to preview.

```python
def apply(..., preview: bool = True, validate: bool = True) -> str:
    ...
```

The JVM sidecar returns a `RefactorWorkspaceEdit`; Python applies it only when `preview=False`.

### Edit model

Do not directly reuse LSP `WorkspaceEdit` internally. Use a Serena-specific model with hashes and file operations:

```json
{
  "summary": "Rename method OrderService.calculateTotal to computeTotal",
  "preconditions": [],
  "warnings": [],
  "changes": [
    {
      "path": "src/main/java/com/acme/OrderService.java",
      "oldSha256": "...",
      "edits": [
        {
          "startOffset": 1234,
          "endOffset": 1248,
          "newText": "computeTotal",
          "kind": "METHOD_DECLARATION"
        }
      ]
    }
  ],
  "fileOperations": [
    {
      "kind": "rename",
      "oldPath": "src/main/java/com/acme/Foo.java",
      "newPath": "src/main/java/com/acme/Bar.java"
    }
  ],
  "stats": {
    "filesChanged": 7,
    "textEdits": 19,
    "referencesUpdated": 18
  }
}
```

Use offsets generated against the exact file contents. Python must verify `oldSha256` before applying.

### Transactional application

Add:

```text
src/serena/java_refactor/workspace_edit.py
```

Responsibilities:

1. Reject paths outside the project root.
2. Verify file hashes.
3. Reject overlapping edits.
4. Sort edits descending by offset.
5. Stage all changed file contents in memory.
6. Apply file creates/renames/deletes after text staging.
7. On failure, restore backups.
8. Preserve Serena project encoding and line endings.

The existing LSP edit application is embedded in `LanguageServerCodeEditor`; this plan should extract or mirror its workspace-edit mechanics so Java refactors are not coupled to an open LSP buffer. 

---

## 5. Tool surface

Start with Java-specific tools. Do not silently change the existing generic `rename_symbol` behavior until the implementation has enough coverage.

### New tools

```python
class JavaSemanticRenameTool(EditingToolWithDiagnostics, ToolMarkerSymbolicEdit, ToolMarkerOptional, ToolMarkerBeta):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        new_name: str,
        preview: bool = True,
        include_javadocs: bool = False,
        include_comments: bool = False,
        validate: bool = True,
    ) -> str: ...

class JavaSafeDeleteTool(EditingToolWithDiagnostics, ToolMarkerSymbolicEdit, ToolMarkerOptional, ToolMarkerBeta):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        preview: bool = True,
        allow_public_api_delete: bool = False,
        validate: bool = True,
    ) -> str: ...

class JavaMoveTopLevelTypeTool(EditingToolWithDiagnostics, ToolMarkerSymbolicEdit, ToolMarkerOptional, ToolMarkerBeta):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        target_package: str = "",
        target_directory: str = "",
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...

class JavaInlineLocalVariableTool(EditingToolWithDiagnostics, ToolMarkerSymbolicEdit, ToolMarkerOptional, ToolMarkerBeta):
    def apply(
        self,
        name_path: str,
        relative_path: str,
        preview: bool = True,
        validate: bool = True,
    ) -> str: ...
```

Serena’s tool system derives names from class names and uses docstrings/type metadata for MCP tool descriptions, so these classes should follow the existing `Tool.apply(...)` pattern.  The `apply_ex` wrapper already handles active-project checks, exception handling, timeouts, usage recording, and cache saving, so the Java tools should stay inside this framework. 

### Generic dispatch

The stable Java surface includes both operation-specific tools and a generic `java_refactor_symbol` dispatcher. The dispatcher deterministically routes `rename`/`semantic_rename`, `safe_delete`, `move_top_level_type`, `inline_local_variable`, and `inline_constant` to the same sidecar-backed preview/apply paths as the specific tools, preserving target identity hints, validation, and dangerous-operation opt-ins.

The existing `rename_symbol`/`safe_delete_symbol` tools can also route Java files into the sidecar when `java_refactor.route_generic_rename` / `java_refactor.route_generic_safe_delete` are enabled. Current LSP rename remains the fallback only when the sidecar is unavailable; hard semantic refusals are returned verbatim and do not fall through to LSP.

---

## 6. Symbol targeting

Serena currently targets symbols by `name_path` and `relative_path`. Keep that UX.

Internally, resolve to a source position before calling the sidecar:

1. Use existing LSP symbol retrieval to find the symbol in `relative_path`.
2. Extract its identifier line/column.
3. Send `relativePath`, `line`, `column`, and optional name/kind/signature hints to the sidecar.

This mirrors the existing rename path, where Serena finds a unique symbol, validates its position, then asks the backend for a rename edit. 

The sidecar should also support direct `line`/`column` requests for future tools:

```json
{
  "target": {
    "relativePath": "src/main/java/com/acme/Foo.java",
    "line": 42,
    "column": 17,
    "namePathHint": "Foo/bar[0]"
  }
}
```

### Sidecar target resolution algorithm

For a given file and position:

1. Find the `CompilationUnitTree`.
2. Walk with `TreePathScanner`.
3. Find the smallest `TreePath` whose source range contains the position.
4. Prefer identifier-bearing nodes:

   * `IdentifierTree`
   * `MemberSelectTree`
   * `VariableTree`
   * `MethodTree`
   * `ClassTree`
   * `ImportTree`
5. Call `trees.getElement(path)`.
6. Canonicalize the result.

Canonical keys:

```text
TYPE       com.acme.Foo
FIELD      com.acme.Foo#count
METHOD     com.acme.Foo#bar(java.lang.String,int)
CTOR       com.acme.Foo#<init>(java.lang.String)
LOCAL      file + declaration start offset + simple name
PARAMETER  enclosing executable key + declaration start offset + simple name
PACKAGE    com.acme
```

Do not rely on `Element` object identity across separate compiler tasks. Within one task, `Element` equality is useful; across source sets or refreshes, use stable keys.

---

## 7. Reference index

Build a semantic reference finder independent of LSP.

### Per compiler task

For each compilation unit:

```java
class ReferenceScanner extends TreePathScanner<Void, Void> {
    @Override
    public Void visitIdentifier(IdentifierTree node, Void unused) {
        Element e = trees.getElement(getCurrentPath());
        maybeRecord(e, node);
        return super.visitIdentifier(node, unused);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
        Element e = trees.getElement(getCurrentPath());
        maybeRecord(e, node);
        return super.visitMemberSelect(node, unused);
    }

    @Override
    public Void visitVariable(VariableTree node, Void unused) { ... }

    @Override
    public Void visitMethod(MethodTree node, Void unused) { ... }

    @Override
    public Void visitClass(ClassTree node, Void unused) { ... }

    @Override
    public Void visitImport(ImportTree node, Void unused) { ... }
}
```

### Identifier span extraction

`SourcePositions` gives tree spans, not always the exact simple-name token. For example, a `MemberSelectTree` for `foo.bar` spans the whole expression; the edit should touch only `bar`.

Implement `IdentifierSpanFinder`:

```java
record IdentifierSpan(Path file, int startOffset, int endOffset, String oldText) {}
```

Rules:

| Node                    | Span rule                                                                    |
| ----------------------- | ---------------------------------------------------------------------------- |
| `ClassTree`             | simple class/interface/enum/record name token after modifiers/type keyword   |
| `MethodTree`            | method name token; constructor name is handled as class/type rename          |
| `VariableTree`          | variable simple name token                                                   |
| `IdentifierTree`        | full identifier token                                                        |
| `MemberSelectTree`      | simple name after final dot                                                  |
| `ImportTree`            | imported simple name, or qualified prefix when package/type move requires it |
| `MemberReferenceTree`   | referenced method/type name around `::`                                      |
| `NewClassTree`          | constructor/type name in `new Foo(...)`                                      |
| `ParameterizedTypeTree` | raw type span, not type argument span                                        |

Add tests for comments, strings, Unicode identifiers, CRLF, tabs, and nested generic syntax.

---

## 8. Semantic rename plan

### Supported in v1

| Target                      | v1 behavior                                                                                                                                                                              |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Local variable              | Rename declaration and semantic uses in same method/block.                                                                                                                               |
| Parameter                   | Rename declaration and semantic uses. For overridden/interface/abstract/cross-source-set project methods, rename corresponding parameter declaration/use spans across the editable hierarchy; refuse only when a related declaration is external to editable sources (for example a JDK/dependency method). |
| Field                       | Rename declaration, reads/writes, member selects, static imports, normal imports as needed.                                                                                              |
| Method                      | Rename declaration, calls, method references, overrides/implementations.                                                                                                                 |
| Class/interface/enum/record | Rename declaration, constructor names, type references, imports, static imports; rename file for public top-level type.                                                                  |
| Nested type                 | Rename declaration and references; no file rename.                                                                                                                                       |

### Name validation

Implement:

```java
boolean isJavaIdentifier(String s)
boolean isJavaKeyword(String s)
boolean validPackageName(String s)
```

Rules:

* Variables/fields/methods/classes must be Java identifiers and not keywords.
* Type rename must not produce a sibling type collision.
* Public top-level type rename must plan a `.java` file rename.
* Constructor rename is not allowed directly; class rename updates constructors.

### Method hierarchy handling

For method rename:

1. Resolve target `ExecutableElement`.
2. If private or static, rename only that method’s semantic references.
3. Otherwise build an override group:

   * overridden methods in supertypes
   * overriding methods in subtypes
   * interface declarations
   * implementation methods
4. Rename declarations and all references resolving to any method in the group.
5. Reject if the new name would collide with an existing overload in any affected type in a way that changes resolution.

Use `Elements.overrides(...)`, `Types`, erased signatures, and containing type traversal.

### Field handling

For field rename:

1. Resolve `VariableElement`.
2. Rename declaration.
3. Rename all field accesses where `trees.getElement(path)` resolves to the same field key.
4. Handle:

   * `this.field`
   * `super.field`
   * `TypeName.staticField`
   * static imports
   * method references only when applicable
5. Reject when a same-scope field with `new_name` already exists.

### Type handling

For top-level type rename:

1. Rename the type declaration.
2. Rename constructors.
3. Rename type references.
4. Update imports.
5. Update static imports whose qualifier contains the old type name.
6. If public top-level type and file name matches old type, add file rename:

   ```text
   Foo.java -> Bar.java
   ```
7. Reject if the new file exists.

### Conflict checks

Before returning edits:

* Duplicate member name in same class.
* Duplicate top-level type in target package.
* Local variable shadowing in same scope.
* Method overload ambiguity.
* Override group inconsistency.
* Import conflict: another imported type has the new simple name.
* File rename target exists.
* Target belongs to dependency jar or generated source outside editable roots.

### Post-validation

After staging edits in memory:

1. Re-run javac parse/analyze on changed source sets.
2. Compare diagnostics.
3. Reject if new `ERROR` diagnostics appear.
4. For rename, verify old target key is no longer referenced from source, except where the old text remains in comments/strings and those modes are off.

### Javadocs and comments

Default:

```text
include_javadocs=false
include_comments=false
```

Later:

* Use `DocTrees` for `{@link Foo#bar(...)}` and `@see`.
* Never do raw comment search by default.
* For comments/text occurrences, require explicit opt-in and return warnings.

---

## 9. Safe delete plan

The existing LSP safe-delete behavior is a good v0 model: find references; if any exist, refuse; otherwise delete the symbol.  The Java compiler-backed version should be stricter and more precise.

### Supported in v1

| Target               | v1 behavior                                                                              |
| -------------------- | ---------------------------------------------------------------------------------------- |
| Private method       | Delete if no semantic references and not overriding/overridden.                          |
| Private field        | Delete if no semantic references.                                                        |
| Local variable       | Delete declaration if no semantic uses.                                                  |
| Parameter            | Refuse initially, except private method parameter with no uses and no override relation. |
| Nested class         | Delete if no references.                                                                 |
| Top-level class      | Delete declaration or file if no references.                                             |
| Public/protected API | Refuse by default unless `allow_public_api_delete=true`.                                 |

### Deletion span rules

Each deletion needs a source span wider than the identifier:

* Include annotations.
* Include modifiers.
* Include preceding Javadocs only when directly attached.
* Include trailing newline.
* Preserve surrounding blank-line style.
* For fields:

  * If declaration has multiple declarators, refuse in v1:

    ```java
    int a, b;
    ```
  * Later support splitting.
* For methods/classes:

  * Delete full declaration body.
  * Refuse if parser cannot find balanced body span.

### Reference check

Use the semantic reference index:

```text
references = findReferences(targetKey, includeDeclaration=false)
```

For methods, also check hierarchy:

* If method overrides or is overridden, refuse unless deleting the whole override group is explicitly requested.
* If method is interface/abstract, refuse by default.

### Result format on refusal

Return actionable locations:

```json
{
  "canDelete": false,
  "reason": "Symbol is referenced",
  "references": [
    {
      "path": "src/main/java/com/acme/OrderController.java",
      "line": 57,
      "column": 21,
      "containingSymbol": "OrderController/createOrder",
      "snippet": "total = service.calculateTotal(order);"
    }
  ]
}
```

---

## 10. Move top-level class/file plan

This is feasible but should be constrained.

### v1 scope

Support:

```text
Move one top-level type from one source root to another package/directory.
```

Do not support in v1:

* Moving multiple classes at once.
* Moving inner classes out to top-level.
* Moving arbitrary files/directories.
* Moving methods/fields between classes.
* Module/package restructuring with automatic `module-info.java` exports/opens updates.

### Inputs

```python
java_move_top_level_type(
    name_path: str,
    relative_path: str,
    target_package: str = "",
    target_directory: str = "",
    preview: bool = True
)
```

Exactly one of `target_package` or `target_directory` should be provided.

### Algorithm

1. Resolve `TypeElement`.
2. Verify it is top-level.
3. Determine current package.
4. Determine target source root and target package.
5. Validate:

   * target package syntax
   * target directory under a known source root
   * no same simple name in target package
   * target file does not exist
6. Plan file move:

   ```text
   src/main/java/com/old/Foo.java
   -> src/main/java/com/newpkg/Foo.java
   ```
7. Edit package declaration:

   * Replace `package com.old;`
   * Or insert package declaration if moving from default package.
8. Update references:

   * Imports of `com.old.Foo` → `com.newpkg.Foo`
   * Static imports of `com.old.Foo.X` → `com.newpkg.Foo.X`
   * Fully qualified references `com.old.Foo` → `com.newpkg.Foo`
   * References from same old package may need new import.
   * References from same new package may need old import removed.
9. Organize imports minimally:

   * Remove exact obsolete import.
   * Add exact new import only if simple name is used from a different package.
   * Preserve existing import order where possible.
10. Validate with javac.

### Module handling

If `module-info.java` mentions the old package, v1 should refuse with a clear message:

```text
Cannot move com.old.Foo because module-info.java exports/opens com.old.
Automatic module-info package rewriting is not implemented yet.
```

Later versions can support this when the move affects all types in a package.

---

## 11. Inline local variable / constant plan

This should be intentionally conservative.

### Inline local variable v1

Allow only:

```java
final int x = 3;
return x + 1;
```

or effectively final:

```java
int x = a + b;
return x;
```

when initializer is pure.

Reject:

* multiple declarators
* initializer with method calls
* assignment/update to the variable
* variable captured in lambda/anonymous class if scope analysis is uncertain
* initializer with side effects:

  * assignment
  * increment/decrement
  * method invocation
  * constructor invocation
  * array creation with nontrivial expressions
* uses where parenthesization cannot be safely determined

### Algorithm

1. Resolve target local variable.
2. Find declaration and initializer.
3. Verify single declaration.
4. Verify effectively final:

   * no assignment to same `Element`
   * no update expression
5. Verify initializer purity.
6. For each use:

   * compute replacement expression text
   * parenthesize according to parent expression precedence
7. Delete declaration statement.
8. Validate.

### Inline constant v1

Allow:

```java
private static final int LIMIT = 10;
private static final String NAME = "x";
```

when initializer is a Java compile-time constant expression.

Replace semantic references with the initializer expression, then optionally delete the constant if private and no references remain.

Public constants should default to preview-only because they may be part of external API or used reflectively.

---

## 12. Inline method: defer, but lay groundwork

Do not implement general inline method in the first rich-refactor release.

A safe staged path:

| Stage | Scope                                                                                                    |
| ----- | -------------------------------------------------------------------------------------------------------- |
| v0    | Preview only for `private static` method with one `return expr;`.                                        |
| v1    | Apply for `private static` single-expression methods, no type params, no throws, no side-effecting args. |
| v2    | Instance methods with `this` substitution.                                                               |
| v3    | Generic methods, overload disambiguation, checked exceptions.                                            |
| v4    | Statements, early returns, control flow, comments.                                                       |

General inline method is where most hidden semantic hazards live: evaluation order, receiver side effects, overload resolution, generics, `this`/`super`, exceptions, local name capture, comments, and formatting.

---

## 13. Integration with JDTLS

Use JDTLS as a companion, not as the refactoring authority.

Serena’s Java backend already uses Eclipse JDTLS, supports implementation requests, and advertises rename-related capabilities in its initialization.  

Recommended usage:

* Keep JDTLS for:

  * existing `find_symbol`
  * existing `find_referencing_symbols`
  * diagnostics
  * hover/info
  * fallback LSP rename
* Use the javac sidecar for:

  * semantic rename previews
  * richer safe delete
  * top-level type move
  * inline local/constant
* After applying javac edits:

  * notify/restart/update JDTLS only if needed
  * optionally call existing diagnostics tools

Do not call:

```python
serena.jetbrains.*
JetBrainsPluginClient
JetBrainsCodeEditor
```

---

## 14. Concrete implementation phases

### Phase 0 — Scaffolding

Deliverables:

```text
java-refactor/ Gradle project
src/serena/java_refactor/client.py
src/serena/java_refactor/manager.py
src/serena/java_refactor/models.py
src/serena/tools/java_refactor_tools.py
```

Add one tool:

```python
java_refactor_status(refresh: bool = False)
```

Acceptance criteria:

* Sidecar starts.
* `initialize` succeeds.
* Project root and config are passed correctly.
* Sidecar exits cleanly when Serena stops.
* Tool timeout works through Serena’s normal `apply_ex` path.

### Phase 1 — Project model

Deliverables:

* Maven model reader.
* Gradle model reader.
* Plain source-root fallback.
* Cache and invalidation.
* Compiler option generation.
* Clear error reporting.

Acceptance criteria:

* Works on:

  * simple single-file project
  * Maven project
  * Gradle project
  * multi-source-set project
  * project with `module-info.java`
* Reports classpath/model errors before refactoring.
* No files are edited.

### Phase 2 — AST and semantic index

Deliverables:

* `JavacSession`
* `TargetResolver`
* `ReferenceScanner`
* `IdentifierSpanFinder`
* `SemanticKey`

Acceptance criteria:

* Resolve class, method, field, local, parameter.
* Find references across files.
* Correctly distinguish overloaded methods.
* Correctly distinguish same simple name in different scopes.
* Correctly handle imports and static imports.
* Golden tests for source spans.

### Phase 3 — Semantic rename

Deliverables:

```python
java_semantic_rename(...)
```

Supported initially:

* locals
* parameters
* fields
* private/static methods
* normal instance methods with override group support
* classes/interfaces/enums/records
* public top-level type file rename

Acceptance criteria:

* Preview shows all touched files and edits.
* Apply is transactional.
* No accidental string/comment edits by default.
* Overloaded methods are handled by semantic identity.
* Override methods are renamed consistently.
* Post-javac validation passes or apply is refused.

### Phase 4 — Safe delete

Deliverables:

```python
java_safe_delete(...)
```

Supported initially:

* private methods
* private fields
* locals
* nested types
* top-level types

Acceptance criteria:

* Refuses if semantic references exist.
* Shows exact references.
* Refuses public/protected API by default.
* Deletes attached Javadoc/annotations safely.
* Refuses ambiguous multi-declarator fields.

### Phase 5 — Move top-level type

Deliverables:

```python
java_move_top_level_type(...)
```

Acceptance criteria:

* Moves file.
* Rewrites package declaration.
* Rewrites imports/static imports/FQNs.
* Refuses target name collisions.
* Refuses unsupported `module-info.java` package edits.
* Validates with javac.

### Phase 6 — Inline local/constant

Deliverables:

```python
java_inline_local_variable(...)
java_inline_constant(...)
```

Acceptance criteria:

* Only pure/effectively-final cases apply.
* Unsafe cases produce clear refusals.
* Parentheses preserve semantics.
* Declaration is removed cleanly.
* javac validation passes.

### Phase 7 — Tool unification

After the Java-specific tools are stable:

* Add config:

  ```yaml
  java_refactor:
    route_generic_rename: true
    route_generic_safe_delete: true
  ```
* Route existing `rename_symbol` and `safe_delete_symbol` through the Java engine for Java files.
* Keep LSP fallback.

---

## 15. Test matrix

### Java fixtures

Create:

```text
test/resources/repos/java_refactor/
  plain/
  maven-basic/
  gradle-basic/
  multi-module-maven/
  multi-source-set-gradle/
  modules/
  lombok-lite/
```

### Rename cases

* local variable shadowing
* parameter vs field same name
* overloaded methods
* overridden interface method
* superclass method
* static method
* private method
* field access via `this`
* field access via class qualifier
* static import
* wildcard import
* nested class
* record
* enum
* annotation type
* constructor rename through class rename
* public top-level type file rename
* CRLF file

### Safe delete cases

* unused private method
* used private method
* method referenced by method reference
* field used in annotation value
* local variable unused
* field with multi-declarator refusal
* public API refusal
* top-level file delete

### Move cases

* import rewrite
* same package to different package
* different source root
* static imports
* FQN references
* target class exists refusal
* module-info refusal

### Inline cases

* literal initializer
* arithmetic initializer
* precedence requiring parentheses
* assignment-after-init refusal
* method-call initializer refusal
* multi-declarator refusal
* captured variable case

### Serena integration tests

* Tool appears only when configured.
* Preview does not modify files.
* Apply modifies expected files.
* Hash mismatch refuses apply.
* Sidecar crash returns clean tool error.
* Existing LSP rename remains available as fallback.
* JDTLS caches/diagnostics still work after edits.

---

## 16. Safety rules

Default to refusing, not guessing.

Apply only when all are true:

```text
project model valid
target resolved to exactly one semantic element
all edit spans are exact
no overlapping edits
no file hash changed since preview
no unsupported generated/dependency source
no new javac ERROR diagnostics
```

Refuse with structured reasons:

```json
{
  "status": "refused",
  "reason": "NEW_NAME_COLLIDES",
  "message": "Method computeTotal(String) already exists in OrderService.",
  "locations": [...]
}
```

Use warning-only preview for cases involving:

* reflection
* string-based class names
* service loader files
* Spring/XML/resource references
* generated code
* annotation processors disabled
* incomplete classpath

---

## 17. Configuration

Add project config:

```yaml
java_refactor:
  enabled: true
  preview_default: true
  build_tool_model: auto          # auto | explicit | maven | gradle | plain
  java_home: null
  annotation_processing: none     # none | classpath | project
  allow_incomplete_analysis: false
  route_generic_rename: false
  route_generic_safe_delete: false
  include_javadocs_default: false
  include_comments_default: false
  max_files: 10000
  max_heap: "2G"
  validate_after_preview: true
  validate_before_apply: true
```

Also allow reuse of Java LS settings where applicable, especially Maven/Gradle user homes and wrapper preferences, since Serena already exposes those for Java LS configuration. 

---

## 18. Concrete commit breakdown

### Commit 1: Sidecar skeleton and status tool

* Add Java subproject.
* Add Python client/manager.
* Add `java_refactor_status`.
* Add release packaging for jar.
* Add docs explaining that this is LSP-mode Java refactoring, not JetBrains.

### Commit 2: Project model and compiler session

* Build Maven/Gradle/plain models.
* Implement `JavacSession`.
* Add diagnostics capture.
* Add cache invalidation.

### Commit 3: Target and reference APIs

* Implement `resolveTarget`.
* Implement `findReferences`.
* Add internal-only Python call wrappers.
* Add golden tests.

### Commit 4: Semantic rename

* Implement preview/apply.
* Start with locals, fields, methods, types.
* Add file rename for public top-level type.
* Add validation.

### Commit 5: Safe delete

* Implement strict delete.
* Add reference reporting.
* Integrate existing delete span logic with Java AST ranges.

### Commit 6: Move top-level type

* Package declaration edit.
* File operation.
* Import/FQN rewrites.
* Refuse module-info edge cases.

### Commit 7: Inline local/constant

* Conservative pure-expression inlining.
* Parenthesization.
* Declaration deletion.

### Commit 8: Generic tool routing

* Config-gated routing from existing `rename_symbol`.
* Config-gated routing from existing `safe_delete_symbol`.
* Keep LSP fallback.

---

## 19. Expected capability outcome

| Refactor                                | First supported version | Expected reliability                            |
| --------------------------------------- | ----------------------: | ----------------------------------------------- |
| Semantic rename: local/field/type       |                Commit 4 | High                                            |
| Semantic rename: methods with hierarchy |Commit 4, hardened later | Medium/high                                     |
| Safe delete private/local/type          |                Commit 5 | High when classpath complete                    |
| Move top-level type/file                |                Commit 6 | Medium/high under constraints                   |
| Inline local variable                   |                Commit 7 | High for constrained pure cases                 |
| Inline constant                         |                Commit 7 | Medium/high for private compile-time constants  |
| Move method/field between classes       |                   Later | Medium only after receiver/access model exists  |
| General inline method                   |                   Later | Hard; implement only staged constrained subsets |

The main engineering risk is not AST access. `JavacTask` and `Trees` provide the needed compiler hooks. The main risk is **accurate project modeling plus conservative edit planning**. Start with preview-first Java-specific tools, validate with javac before apply, and only later route Serena’s generic tools through the new Java engine.

[1]: https://docs.oracle.com/en/java/javase/11/docs/api/jdk.compiler/com/sun/source/util/JavacTask.html "JavacTask (Java SE 11 & JDK 11 )"
[2]: https://docs.oracle.com/en/java/javase/11/docs/api/jdk.compiler/com/sun/source/util/Trees.html "Trees (Java SE 11 & JDK 11 )"
[3]: https://docs.oracle.com/en/java/javase/11/docs/api/java.compiler/javax/tools/JavaCompiler.html "JavaCompiler (Java SE 11 & JDK 11 )"
