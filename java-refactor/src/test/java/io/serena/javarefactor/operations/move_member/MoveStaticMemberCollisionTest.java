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
 * HB-3: semantic {@code moveStaticMember} overload-collision detection. The planner must refuse a move into a target
 * type that already declares a method of the same name and the same ERASED parameter signature (JLS §8.4.2), and it
 * must reach that decision through javac type identity ({@link io.serena.javarefactor.compiler.SemanticIndex#sameErasedParameterTypes})
 * — NOT by comparing a resolved {@code asType().toString()} against parsed source text. The legacy text comparison
 * silently missed a true clash whenever the two sides spelled the same type differently, so these cases pin the three
 * spelling families the reviewer demands (simple-vs-FQN, generic instantiation vs erasure, annotation/formatting) and a
 * negative control proving a genuinely different erasure is still permitted as a legal overload.
 */
class MoveStaticMemberCollisionTest {

    /** A target parameter spelled {@code java.lang.String} collides with a moved parameter spelled {@code String}. */
    @Test
    void simpleVsFullyQualifiedParameterCollisionRefused(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    public static String fmt(String s) { return s; }\n"
                + "}\n";
        String dst = ""
                + "package demo;\n"
                + "public class Dst {\n"
                + "    public static String fmt(java.lang.String s) { return s; }\n"
                + "}\n";
        String json = moveStatic(tmp, src, dst, "fmt");
        assertTrue(json.contains("\"code\":\"target_member_exists\""), json);
    }

    /** {@code List<String>} and {@code List<Integer>} erase to the same {@code List}, so the overload clashes. */
    @Test
    void genericInstantiationErasesToSameSignatureRefused(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "import java.util.List;\n"
                + "public class Src {\n"
                + "    public static int count(List<String> xs) { return xs.size(); }\n"
                + "}\n";
        String dst = ""
                + "package demo;\n"
                + "import java.util.List;\n"
                + "public class Dst {\n"
                + "    public static int count(List<Integer> xs) { return xs.size(); }\n"
                + "}\n";
        String json = moveStatic(tmp, src, dst, "count");
        assertTrue(json.contains("\"code\":\"target_member_exists\""), json);
    }

    /**
     * A parameter annotation and array-bracket spacing differ textually between the two declarations, but javac erases
     * both to {@code java.lang.String[]}. The legacy text comparison would have missed this clash; the erasure check
     * refuses it.
     */
    @Test
    void annotationAndFormattingDifferencesStillCollideRefused(@TempDir Path tmp) throws IOException {
        String ann = ""
                + "package demo;\n"
                + "import java.lang.annotation.*;\n"
                + "@Target(ElementType.TYPE_USE)\n"
                + "@Retention(RetentionPolicy.RUNTIME)\n"
                + "public @interface NonNull {}\n";
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    public static void join(@NonNull String[] parts) { }\n"
                + "}\n";
        String dst = ""
                + "package demo;\n"
                + "public class Dst {\n"
                + "    public static void join(String [] parts) { }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/NonNull.java", ann, "demo/Src.java", src, "demo/Dst.java", dst));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "join");
        fields.put("targetType", "demo.Dst");
        fields.put("targetRelativePath", "src/demo/Dst.java");
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);
        assertTrue(json.contains("\"code\":\"target_member_exists\""), json);
    }

    /**
     * Negative control: a genuinely different erasure ({@code int} vs {@code String}) is a legal overload and must NOT
     * be refused as a collision, proving the erasure check does not over-refuse.
     */
    @Test
    void differentErasureIsLegalOverloadNotRefused(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    public static String render(int value) { return Integer.toString(value); }\n"
                + "}\n";
        String dst = ""
                + "package demo;\n"
                + "public class Dst {\n"
                + "    public static String render(String value) { return value; }\n"
                + "}\n";
        String json = moveStatic(tmp, src, dst, "render");
        assertFalse(json.contains("\"code\":\"target_member_exists\""), json);
        assertFalse(json.contains("\"code\":\"AMBIGUOUS_OVERLOAD_AFTER_MOVE\""), json);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private static String moveStatic(Path tmp, String src, String dst, String memberToken) throws IOException {
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src, "demo/Dst.java", dst));
        Map<String, Object> fields = fields("src/demo/Src.java", src, memberToken);
        fields.put("targetType", "demo.Dst");
        fields.put("targetRelativePath", "src/demo/Dst.java");
        return new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);
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
