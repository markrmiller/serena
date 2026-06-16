package io.serena.javarefactor.operations.move_member;

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
 * HB-4: the moveInstanceMethod super-dispatch, synchronized-on-receiver, source-type-variable, and this-escape
 * blockers must be derived from the method's resolved javac AST and symbol bindings, never from raw source text. These
 * cases pin both directions the reviewer demands:
 * <ul>
 *   <li>NO false miss — whitespace (`super . foo()`) and a real type-variable dependency still refuse;</li>
 *   <li>NO false refusal — a {@code super.}/{@code synchronized(this)} token in a comment or string literal, and a
 *       type-parameter name appearing only in Javadoc, do NOT block a safe move.</li>
 * </ul>
 */
class MoveInstanceMethodAstSafetyTest {

    /** `super . scale()` with spaces is a real super dispatch; text {@code contains("super.")} missed it, the AST does not. */
    @Test
    void spacedSuperDispatchIsRefused(@TempDir Path tmp) throws IOException {
        String base = ""
                + "package demo;\n"
                + "public class Base {\n"
                + "    public int scale(int a) { return a; }\n"
                + "}\n";
        String source = ""
                + "package demo;\n"
                + "public class Source extends Base {\n"
                + "    Target helper;\n"
                + "    @Override public int scale(int a) { return a + 1; }\n"
                + "    public int compute(int amount) {\n"
                + "        return super . scale(amount);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        String json = moveCompute(tmp, Map.of("demo/Base.java", base, "demo/Source.java", source, "demo/Target.java", target), source);
        assertTrue(json.contains("\"code\":\"super_reference_unsupported\""), json);
    }

    /** A {@code super.} mention inside a comment and a string literal is NOT a super dispatch; the move is allowed. */
    @Test
    void superTokenInCommentAndStringIsNotRefused(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int compute(int amount) {\n"
                + "        // this mentions super.foo() but never dispatches through super\n"
                + "        String note = \"super.bar()\";\n"
                + "        return helper.scale(amount) + note.length();\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        String json = moveCompute(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target), source);
        assertFalse(json.contains("\"code\":\"super_reference_unsupported\""), json);
        assertTrue(json.contains("\"accepted\":true"), json);
    }

    /** Qualified {@code Outer.super.m()} is a super dispatch too (the selected name is `super`, not the expression). */
    @Test
    void qualifiedOuterSuperDispatchIsRefused(@TempDir Path tmp) throws IOException {
        String base = ""
                + "package demo;\n"
                + "public class Base {\n"
                + "    public int seed() { return 1; }\n"
                + "}\n";
        String source = ""
                + "package demo;\n"
                + "public class Source extends Base {\n"
                + "    @Override public int seed() { return 2; }\n"
                + "    class Inner {\n"
                + "        Target helper;\n"
                + "        public int compute(int amount) {\n"
                + "            return Source.super.seed() + helper.scale(amount);\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Base.java", base, "demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(");
        fields.put("targetField", "helper");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"super_reference_unsupported\""), json);
    }

    /** A real {@code synchronized (this)} block (spaced) locks the source monitor and is refused via the AST. */
    @Test
    void synchronizedOnThisBlockIsRefused(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int compute(int amount) {\n"
                + "        synchronized ( this ) {\n"
                + "            return helper.scale(amount);\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        String json = moveCompute(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target), source);
        assertTrue(json.contains("\"code\":\"synchronized_receiver_unsupported\""), json);
    }

    /** The literal text {@code "synchronized(this)"} in a string is not a real lock; a text regex would over-refuse. */
    @Test
    void synchronizedTokenInStringIsNotRefused(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int compute(int amount) {\n"
                + "        String doc = \"synchronized(this)\";\n"
                + "        return helper.scale(amount) + doc.length();\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        String json = moveCompute(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target), source);
        assertFalse(json.contains("\"code\":\"synchronized_receiver_unsupported\""), json);
        assertTrue(json.contains("\"accepted\":true"), json);
    }

    /** A source type variable used in the moved signature must refuse; the new receiver type cannot supply it. */
    @Test
    void sourceTypeVariableInSignatureIsRefused(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source<T> {\n"
                + "    Target helper;\n"
                + "    public int compute(T value) {\n"
                + "        return helper.scale(1) + value.hashCode();\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        String json = moveCompute(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target), source);
        assertTrue(json.contains("\"code\":\"source_type_parameter_unsupported\""), json);
    }

    /** A source type-parameter NAME appearing only in Javadoc is not a dependency; a text scan would over-refuse. */
    @Test
    void typeParameterNameOnlyInJavadocIsNotRefused(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source<T> {\n"
                + "    Target helper;\n"
                + "    /**\n"
                + "     * Computes a value. Conceptually relates to T but uses no T-typed state here.\n"
                + "     * @param amount the input\n"
                + "     * @return the scaled amount\n"
                + "     */\n"
                + "    public int compute(int amount) {\n"
                + "        return helper.scale(amount);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        String json = moveCompute(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target), source);
        assertFalse(json.contains("\"code\":\"source_type_parameter_unsupported\""), json);
    }

    /** Passing the source {@code this} as a value (an argument) cannot be relocated to a new receiver; refuse. */
    @Test
    void sourceThisEscapeAsArgumentIsRefused(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int compute(int amount) {\n"
                + "        helper.consume(this);\n"
                + "        return helper.scale(amount);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "    public void consume(Source s) { }\n"
                + "}\n";
        String json = moveCompute(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target), source);
        assertTrue(json.contains("\"code\":\"source_this_escape_unsupported\""), json);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private static String moveCompute(Path tmp, Map<String, String> files, String source) throws IOException {
        JavaProjectModel model = model(tmp, files);
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(");
        fields.put("targetField", "helper");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");
        return new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
    }

    private static Map<String, Object> fields(String relativePath, String source, String token) {
        int[] pos = positionOf(source, token);
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", relativePath);
        fields.put("line", pos[0]);
        fields.put("column", pos[1]);
        return fields;
    }

    private static int[] positionOf(String source, String token) {
        int from = source.indexOf(token);
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

    private static JavaProjectModel model(Path root, Map<String, String> files) throws IOException {
        Path sourceRoot = root.resolve("src");
        List<Path> javaFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(files).entrySet()) {
            Path javaFile = sourceRoot.resolve(entry.getKey());
            Files.createDirectories(javaFile.getParent());
            Files.writeString(javaFile, entry.getValue(), StandardCharsets.UTF_8);
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
                root, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }
}
