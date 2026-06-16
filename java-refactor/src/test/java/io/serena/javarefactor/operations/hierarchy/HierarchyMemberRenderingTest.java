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
 * G005 (HB-5): proves the V2 hierarchy member rendering is anchored on javac source ranges and semantic descriptors —
 * parameter text comes from the parameters' source ranges, the modifier/type-parameter prefix comes from the slice up to
 * the javac return-type / field-type position, and the member span comes from javac's declaration range. Each case is a
 * declaration shape that a text/regex/brace approach (the old {@code parameterText}/{@code modifierPrefix}/brace scanner)
 * mishandles: multiline generic return types, type-USE annotations on the return type, an annotation interleaved between
 * the modifiers and the type, and braces inside a comment AND a string literal in the body. The planner is run in preview
 * mode and the emitted workspace-edit JSON is asserted on.
 *
 * <p>Targets are selected by precise one-based {@code line}/{@code column} so line-based selection resolves the single
 * member on each line and the {@code SemanticTargetGate} is a verified no-op.
 */
class HierarchyMemberRenderingTest {

    /** A multiline generic return type renders verbatim in the pulled-up declaration; the prefix never swallows the type. */
    @Test
    void rendersMultilineGenericReturnType(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "import java.util.List;\npublic class Base {\n}\n");
        files.put(
                "Child.java",
                "import java.util.List;\n"
                        + "public class Child extends Base {\n"
                        + "    java.util.List<\n"
                        + "            String> labels() {\n"
                        + "        return java.util.Collections.emptyList();\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "labels", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // The verbatim javac-sliced declaration carries the multiline generic return type and the method name unbroken.
        assertTrue(json.contains("labels()"), json);
        assertTrue(json.contains("String> labels"), json);
    }

    /**
     * A type-USE annotation on the return type must stay with the type in the rendered member and must NOT be mistaken for
     * a modifier. The pull-up renders the method as an abstract declaration in the interface, whose signature is built from
     * the javac return type plus the parameter source range — proving the modifier prefix derivation excluded the type.
     */
    @Test
    void rendersTypeUseAnnotationOnReturnType(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "Anno.java",
                "import java.lang.annotation.*;\n"
                        + "@Target(ElementType.TYPE_USE)\n"
                        + "@Retention(RetentionPolicy.RUNTIME)\n"
                        + "public @interface Anno {\n}\n");
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    @Anno String label(int n) {\n"
                        + "        return \"x\" + n;\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "label", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // The moved concrete declaration (verbatim javac slice) keeps the type-use annotation glued to the return type,
        // and the parameter list (from the parameter source range) is reproduced exactly.
        assertTrue(json.contains("@Anno String label(int n)"), json);
    }

    /**
     * An annotation placed BETWEEN the modifiers and the type (e.g. {@code public @Anno int}) must be preserved and the
     * modifier prefix must still capture {@code public}. The old {@code modifierPrefix} regex (a fixed modifier-keyword
     * alternation) could not represent an interleaved annotation; the javac type-position slice does.
     */
    @Test
    void rendersAnnotationBetweenModifiersAndType(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "Anno.java",
                "import java.lang.annotation.*;\n"
                        + "@Target(ElementType.TYPE_USE)\n"
                        + "@Retention(RetentionPolicy.RUNTIME)\n"
                        + "public @interface Anno {\n}\n");
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    public @Anno int score(int n) {\n"
                        + "        return n;\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Child.java"), "Child.java", "score", "Base");
        fields.put("confirmPublicApi", Boolean.TRUE);
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // The relocated declaration keeps `public @Anno int` verbatim with the parameter list intact.
        assertTrue(json.contains("public @Anno int score(int n)"), json);
    }

    /**
     * A method body that contains {@code {} and {@code }} inside a line comment AND inside a string literal must not
     * terminate the member early. The javac declaration range bounds the member regardless of braces in comments/strings,
     * so the whole body (including the real closing brace) is relocated and the trailing field is left behind.
     */
    @Test
    void rendersBodyWithBracesInsideCommentAndStringLiteral(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    String render() {\n"
                        + "        // a closing brace } in a comment must not end the method {\n"
                        + "        String s = \"a } b { c\";\n"
                        + "        return s;\n"
                        + "    }\n"
                        + "\n"
                        + "    int trailing = 7;\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "render", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // The complete body (with the string literal and its real closing brace) is moved; the brace-in-comment/string
        // never truncated the member.
        assertTrue(json.contains("a } b { c"), json);
        assertTrue(json.contains("return s;"), json);
        // The trailing field below the method is NOT dragged into the move (member ended at the javac declaration end).
        assertFalse(json.contains("int trailing"), json);
    }

    /** A varargs parameter list is reproduced verbatim from the parameter source ranges (no re-parsing/brace counting). */
    @Test
    void rendersVarargsParameterListVerbatim(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    String join(String separator, String... parts) {\n"
                        + "        return String.join(separator, parts);\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "join", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("join(String separator, String... parts)"), json);
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
