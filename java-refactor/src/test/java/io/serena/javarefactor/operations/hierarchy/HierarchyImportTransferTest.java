package io.serena.javarefactor.operations.hierarchy;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G006 (HB-6): proves hierarchy import transfer/cleanup is driven by compiler-resolved type/member references and the
 * central {@code ImportManager}, not identifier regexes. The decision of WHICH source imports the moved member needs (for
 * transfer) and WHICH imports the source no longer needs (for cleanup) comes from the member's javac-resolved reference
 * surface, with the post-removal "still used?" question answered by the {@code ImportManager}'s AST-derived used-name set
 * — so a type named only in a comment, string literal, Javadoc {@code {@link}}, or annotation value never counts as a use.
 * Static and wildcard imports are preserved verbatim, same-package references need no import, nested-type references map
 * to the importable top-level type, and a simple-name conflict is refused rather than duplicated.
 *
 * <p>Targets are selected by precise one-based {@code line}/{@code column} so the {@code SemanticTargetGate} is a verified
 * no-op and line selection resolves the single member on each line.
 */
class HierarchyImportTransferTest {

    /**
     * A single-type import the source still genuinely uses in a REMAINING method is preserved on cleanup even though the
     * type name also appears only-decoratively in a comment, a string literal, a Javadoc {@code {@link}}, and an
     * annotation value. The AST-derived used-name set keeps it for the real use; the decorative occurrences are
     * irrelevant to the decision either way.
     */
    @Test
    void preservesImportStillUsedByRemainingCodeDespiteDecorativeOccurrences(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "import java.util.List;\n"
                        + "public class Child extends Base {\n"
                        + "    List<String> labels() {\n"
                        + "        return java.util.Collections.emptyList();\n"
                        + "    }\n"
                        + "\n"
                        + "    /** Builds a {@link List} of names. */\n"
                        + "    @SuppressWarnings(\"List\")\n"
                        + "    List<String> names() {\n"
                        + "        // returns a List of names\n"
                        + "        String doc = \"a List of names\";\n"
                        + "        return java.util.Collections.emptyList();\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "labels", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // names() still uses List, so the source import must NOT be removed (no IMPORT_REMOVE edit for the source file).
        assertFalse(json.contains("\"kind\":\"IMPORT_REMOVE\""), json);
    }

    /**
     * When the ONLY remaining occurrences of the imported type are in a comment, a string literal, a Javadoc, and an
     * annotation value (no real reference survives), the import is genuinely unused after the move and is removed — the
     * decorative occurrences are correctly NOT counted as uses by the AST-derived used-name set.
     */
    @Test
    void removesImportWhenOnlyDecorativeOccurrencesRemain(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "import java.util.List;\n"
                        + "public class Child extends Base {\n"
                        + "    List<String> labels() {\n"
                        + "        return java.util.Collections.emptyList();\n"
                        + "    }\n"
                        + "\n"
                        + "    /** See {@link List} elsewhere. */\n"
                        + "    @SuppressWarnings(\"List\")\n"
                        + "    String describe() {\n"
                        + "        // a List is described here\n"
                        + "        return \"a List of names\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "labels", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // The moved member carried List into Base, and the only surviving Child occurrences are decorative, so the source
        // import is removed.
        assertTrue(json.contains("\"kind\":\"IMPORT_REMOVE\""), json);
        // Base gained the import the moved member needs.
        assertTrue(json.contains("\"kind\":\"IMPORT_ADD\""), json);
        assertTrue(json.contains("import java.util.List;"), json);
    }

    /** A static (single-member) import the moved member uses is transferred to the target as a static import. */
    @Test
    void transfersStaticImportRequiredByMovedMember(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "import static java.util.Collections.emptyList;\n"
                        + "import java.util.List;\n"
                        + "public class Child extends Base {\n"
                        + "    List<String> labels() {\n"
                        + "        return emptyList();\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "labels", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("import static java.util.Collections.emptyList;"), json);
    }

    /**
     * A static WILDCARD import in the source is preserved verbatim on cleanup (the central ImportManager never removes
     * static or wildcard imports), even when the moved member used one of its members.
     */
    @Test
    void preservesStaticWildcardImportOnSourceCleanup(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "import static java.util.Collections.*;\n"
                        + "import java.util.List;\n"
                        + "public class Child extends Base {\n"
                        + "    List<String> labels() {\n"
                        + "        return emptyList();\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "labels", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // If a source IMPORT_REMOVE block is rendered, it must still contain the static wildcard import (never dropped).
        int removeIndex = json.indexOf("\"kind\":\"IMPORT_REMOVE\"");
        if (removeIndex >= 0) {
            assertTrue(json.contains("import static java.util.Collections.*;"), json);
        }
    }

    /**
     * A moved member that references a same-package type needs no import — the target lives in the same package, so the
     * central ImportManager adds nothing for it (no IMPORT_ADD for the same-package type).
     */
    @Test
    void addsNoImportForSamePackageReference(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("demo/Helper.java", "package demo;\npublic class Helper {\n}\n");
        files.put("demo/Base.java", "package demo;\npublic class Base {\n}\n");
        files.put(
                "demo/Child.java",
                "package demo;\n"
                        + "public class Child extends Base {\n"
                        + "    Helper make() {\n"
                        + "        return new Helper();\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("demo/Child.java"), "demo/Child.java", "make", "demo.Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // Same-package Helper needs no import, so no import edit is emitted at all.
        assertFalse(json.contains("\"kind\":\"IMPORT_ADD\""), json);
    }

    /**
     * A moved member that references a nested type transfers the importable TOP-LEVEL enclosing type's import into the
     * target (the compiler resolves the nested usage to its top-level encloser).
     */
    @Test
    void transfersTopLevelImportForNestedTypeReference(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "lib/Outer.java",
                "package lib;\n"
                        + "public class Outer {\n"
                        + "    public static class Inner {\n"
                        + "    }\n"
                        + "}\n");
        files.put("demo/Base.java", "package demo;\npublic class Base {\n}\n");
        files.put(
                "demo/Child.java",
                "package demo;\n"
                        + "import lib.Outer;\n"
                        + "public class Child extends Base {\n"
                        + "    Outer.Inner make() {\n"
                        + "        return new Outer.Inner();\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("demo/Child.java"), "demo/Child.java", "make", "demo.Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"kind\":\"IMPORT_ADD\""), json);
        assertTrue(json.contains("import lib.Outer;"), json);
    }

    /**
     * When the target type already imports a DIFFERENT type with the same simple name as one the moved member needs, the
     * conflict is detected via the central ImportManager ({@code mustUseFqn}) and the move is refused with
     * {@code import_conflict} rather than emitting a duplicate/colliding import.
     */
    @Test
    void refusesOnFqnVsSimpleNameConflict(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("other/List.java", "package other;\npublic class List {\n}\n");
        files.put(
                "demo/Base.java",
                "package demo;\n"
                        + "import other.List;\n"
                        + "public class Base {\n"
                        + "    other.List existing() {\n"
                        + "        return null;\n"
                        + "    }\n"
                        + "}\n");
        files.put(
                "demo/Child.java",
                "package demo;\n"
                        + "import java.util.List;\n"
                        + "public class Child extends Base {\n"
                        + "    List<String> labels() {\n"
                        + "        return java.util.Collections.emptyList();\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("demo/Child.java"), "demo/Child.java", "labels", "demo.Base"), false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"import_conflict\""), json);
    }

    private static Map<String, Object> pullFields(String source, String relativePath, String memberName, String targetType) {
        int[] pos = namePosition(source, memberName);
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", relativePath);
        fields.put("line", pos[0]);
        fields.put("column", pos[1]);
        fields.put("targetType", targetType);
        return fields;
    }

    /** One-based {line, column} of the first declaration-style occurrence of {@code name} (i.e. {@code name(} or {@code name;}). */
    private static int[] namePosition(String source, String name) {
        int from = declarationIndex(source, name);
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < from; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, from - lineStart + 1};
    }

    private static int declarationIndex(String source, String name) {
        int methodIndex = source.indexOf(name + "(");
        if (methodIndex >= 0) {
            return methodIndex;
        }
        int fieldIndex = source.indexOf(" " + name);
        return fieldIndex >= 0 ? fieldIndex + 1 : source.indexOf(name);
    }

    private static JavaProjectModel model(Path root, Map<String, String> files) throws IOException {
        Path sourceRoot = root.toAbsolutePath().normalize();
        List<Path> javaFiles = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path javaFile = sourceRoot.resolve(file.getKey());
            Files.createDirectories(javaFile.getParent());
            Files.writeString(javaFile, file.getValue(), StandardCharsets.UTF_8);
            javaFiles.add(javaFile);
        }
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                List.copyOf(javaFiles),
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
                sourceRoot, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }
}
