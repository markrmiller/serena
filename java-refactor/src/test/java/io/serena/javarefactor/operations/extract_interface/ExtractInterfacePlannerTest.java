package io.serena.javarefactor.operations.extract_interface;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the V2 extract-interface planner finishes its Blocker&nbsp;13 contract:
 * <ul>
 *   <li>the inheritance edit is a generics-aware, position-based insertion (an {@code extends} inside a type-parameter
 *       bound is never mistaken for the inheritance clause), not a fragile whole-tail keyword replace;</li>
 *   <li>the extracted interface file is placed under the project model's configured source root rather than a
 *       path guessed by stripping package segments;</li>
 *   <li>usage narrowing refuses unsafe (cast/reflection/serialization-shaped) reference sites.</li>
 * </ul>
 * The integration cases drive the real {@link ExtractInterfacePlanner} over a javac-backed temp project, mirroring the
 * sidecar's resolution path; the unit cases exercise the pure insertion helper directly.
 */
class ExtractInterfacePlannerTest {

    // ── Generics-aware inheritance insertion (pure helper) ──────────────────────

    @Test
    void insertsFreshImplementsClauseOnBareClass() {
        assertEquals(
                "class Foo implements Named {",
                applyInsertion("class Foo {", "Foo", "class", "Named"));
    }

    @Test
    void appendsImplementsAfterExistingExtendsClauseOnClass() {
        assertEquals(
                "class Foo extends Base implements Named {",
                applyInsertion("class Foo extends Base {", "Foo", "class", "Named"));
    }

    @Test
    void appendsToExistingImplementsListOnClass() {
        assertEquals(
                "class Foo implements A, B, Named {",
                applyInsertion("class Foo implements A, B {", "Foo", "class", "Named"));
    }

    @Test
    void doesNotMistakeGenericBoundExtendsForTheInheritanceClauseOnClass() {
        // The only `extends` here lives inside the type-parameter bound; for a class the keyword is `implements`,
        // so a fresh clause must be added and the bound left intact.
        assertEquals(
                "class Box<T extends Comparable<T>> implements Named {",
                applyInsertion("class Box<T extends Comparable<T>> {", "Box", "class", "Named"));
    }

    @Test
    void doesNotMistakeGenericBoundExtendsForTheInheritanceClauseOnInterface() {
        // For an interface the keyword is `extends`. The bound's `extends` sits at angle-depth > 0 and must be
        // skipped, producing a fresh top-level `extends Named` clause rather than corrupting the bound.
        assertEquals(
                "interface Box<T extends Comparable<T>> extends Named {",
                applyInsertion("interface Box<T extends Comparable<T>> {", "Box", "interface", "Named"));
    }

    @Test
    void appendsToExistingInterfaceExtendsListSkippingGenericBound() {
        assertEquals(
                "interface Box<T extends Comparable<T>> extends A, Named {",
                applyInsertion("interface Box<T extends Comparable<T>> extends A {", "Box", "interface", "Named"));
    }

    @Test
    void insertionOffsetIsZeroWidthAndAfterTheLastInheritanceToken() {
        String header = "class Foo extends Base {";
        int tailStart = header.indexOf("Foo") + "Foo".length();
        String tail = header.substring(tailStart, header.lastIndexOf('{'));
        ExtractInterfacePlanner.InheritanceInsertion insertion =
                ExtractInterfacePlanner.inheritanceInsertion("class", tail, tailStart, "Named");
        // Immediately after "Base".
        assertEquals(header.indexOf("Base") + "Base".length(), insertion.offset());
        assertEquals(" implements Named", insertion.text());
    }

    // ── Integration: model-aware placement + implements edit ────────────────────

    @Test
    void extractsInterfaceAddingImplementsAndPlacesFileUnderModelSourceRoot(@TempDir Path tmp) throws IOException {
        Path sourceRoot = tmp.resolve("src");
        Path pkgDir = sourceRoot.resolve("com/app");
        Files.createDirectories(pkgDir);
        Path source = pkgDir.resolve("InterfaceSource.java");
        Files.writeString(source, ""
                + "package com.app;\n"
                + "public class InterfaceSource {\n"
                + "    public String value(String prefix) { return prefix + \" v\"; }\n"
                + "}\n", StandardCharsets.UTF_8);

        JavaProjectModel model = model(tmp, sourceRoot, List.of(source));
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", "src/com/app/InterfaceSource.java");
        fields.put("interfaceName", "ExtractedValue");
        fields.put("targetPackage", "com.api");
        fields.put("members", List.of("value"));

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("implements ExtractedValue"), json);
        // Model-aware: interface lands under the configured source root's target package, not a guessed path.
        assertTrue(json.contains("src/com/api/ExtractedValue.java"), json);
        assertTrue(json.contains("package com.api;"), json);
    }

    // ── Integration: extracted method returning a nested/generic type imports every component ──

    @Test
    void extractsInterfaceMethodReturningNestedGenericImportsEveryComponent(@TempDir Path tmp) throws IOException {
        Path sourceRoot = tmp.resolve("src");
        Path pkgDir = sourceRoot.resolve("com/app");
        Files.createDirectories(pkgDir);
        Path source = pkgDir.resolve("InterfaceSource.java");
        Files.writeString(source, ""
                + "package com.app;\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "public class InterfaceSource {\n"
                + "    public Map<String, List<Integer>> lookup() { return null; }\n"
                + "}\n", StandardCharsets.UTF_8);

        JavaProjectModel model = model(tmp, sourceRoot, List.of(source));
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", "src/com/app/InterfaceSource.java");
        fields.put("interfaceName", "Lookup");
        fields.put("targetPackage", "com.api");
        fields.put("members", List.of("lookup"));

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // The source gains an implements clause referencing the new interface (single-type, deep-planned reference).
        assertTrue(json.contains("implements Lookup"), json);
        // The generated interface file imports every component of the nested generic return type and renders it simple.
        assertTrue(json.contains("import java.util.List;"), json);
        assertTrue(json.contains("import java.util.Map;"), json);
        assertTrue(json.contains("Map<String, List<Integer>> lookup()"), json);
    }

    // ── Integration: cast/serialization-shaped usage narrowing is refused ───────

    @Test
    void refusesUnsafeCastUsageNarrowing(@TempDir Path tmp) throws IOException {
        Path sourceRoot = tmp.resolve("src");
        Files.createDirectories(sourceRoot);
        Path source = sourceRoot.resolve("InterfaceSource.java");
        Files.writeString(source, ""
                + "public class InterfaceSource {\n"
                + "    public String value() { return \"v\"; }\n"
                + "}\n", StandardCharsets.UTF_8);
        Path usage = sourceRoot.resolve("UseInterfaceSource.java");
        Files.writeString(usage, ""
                + "public class UseInterfaceSource {\n"
                + "    public Object call() {\n"
                + "        InterfaceSource source = new InterfaceSource();\n"
                + "        return (Object) source;\n" // non-method (cast) use of the variable => unsafe
                + "    }\n"
                + "}\n", StandardCharsets.UTF_8);

        JavaProjectModel model = model(tmp, sourceRoot, List.of(source, usage));
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", "src/InterfaceSource.java");
        fields.put("interfaceName", "ExtractedValue");
        fields.put("members", List.of("value"));
        fields.put("replaceUsages", true);

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"unsafe_usage_replacement\""), json);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** Applies the computed inheritance insertion to {@code header} and returns the rewritten declaration line. */
    private static String applyInsertion(String header, String typeName, String kind, String reference) {
        int tailStart = header.indexOf(typeName) + typeName.length();
        int tailEnd = header.lastIndexOf('{');
        String tail = header.substring(tailStart, tailEnd);
        ExtractInterfacePlanner.InheritanceInsertion insertion =
                ExtractInterfacePlanner.inheritanceInsertion(kind, tail, tailStart, reference);
        return header.substring(0, insertion.offset()) + insertion.text() + header.substring(insertion.offset());
    }

    private static JavaProjectModel model(Path root, Path sourceRoot, List<Path> javaFiles) {
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                new ArrayList<>(javaFiles),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "17",
                null,
                null,
                "UTF-8",
                false,
                "none",
                List.of(),
                false,
                List.of("-source", "17", "-target", "17", "-encoding", "UTF-8"),
                List.of(),
                List.of());
        return new JavaProjectModel(
                root, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }
}
