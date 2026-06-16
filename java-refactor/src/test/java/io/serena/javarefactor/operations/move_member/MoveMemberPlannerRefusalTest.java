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
 * Deterministic refusal coverage for the V2 semantic member-move planner ({@link MoveMemberPlanner}) backing
 * G012 (semantic static-member move) and G013 (semantic instance-method move). Each case runs the real planner
 * against a javac-backed temp project and asserts the exact refusal code emitted BEFORE any edit is planned, so the
 * conservative guards (static/instance kind, receiver strategy, super dispatch, protected receiver semantics) are
 * proven without needing an apply/build cycle.
 *
 * <p>Targets are selected by precise one-based {@code line}/{@code column} with no identity hints, so the
 * {@code SemanticTargetGate} is a verified no-op and line-based selection resolves the single member on each line.
 */
class MoveMemberPlannerRefusalTest {

    @Test
    void moveStaticMemberRefusesInstanceField(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    int counter;\n"
                + "}\n";
        String dst = ""
                + "package demo;\n"
                + "public class Dst {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src, "demo/Dst.java", dst));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "counter");
        fields.put("targetType", "demo.Dst");
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);
        assertTrue(json.contains("\"code\":\"not_static_member\""), json);
    }

    /** G015: a cross-package move that would widen a package-private static member is refused unless opted in. */
    @Test
    void moveStaticMemberRefusesAccessWideningByDefault(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package a;\n"
                + "public class Src {\n"
                + "    static int hidden() { return 1; }\n"
                + "}\n";
        String dst = ""
                + "package b;\n"
                + "public class Dst {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("a/Src.java", src, "b/Dst.java", dst));
        Map<String, Object> fields = fields("src/a/Src.java", src, "hidden()");
        fields.put("targetType", "b.Dst");
        fields.put("targetRelativePath", "src/b/Dst.java");
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);
        assertTrue(json.contains("\"code\":\"access_widening_not_allowed\""), json);
    }

    /**
     * HB-08: a static move that depends on a private source member is no longer blanket-refused. By default
     * (no allow_access_widening) the plan-wide access analyzer refuses with the structured
     * {@code access_widening_not_confirmed} code, naming the referenced private member whose access must change.
     */
    @Test
    void moveStaticMemberRefusesPrivateDependencyWideningUnlessConfirmed(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    private static int secret() { return 7; }\n"
                + "    public static int exposed() { return secret(); }\n"
                + "}\n";
        String dst = ""
                + "package demo;\n"
                + "public class Dst {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src, "demo/Dst.java", dst));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "exposed()");
        fields.put("targetType", "demo.Dst");
        fields.put("targetRelativePath", "src/demo/Dst.java");
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);
        assertTrue(json.contains("\"code\":\"access_widening_not_confirmed\""), json);
        assertTrue(json.contains("secret"), json);
    }

    /**
     * HB-08: with allow_access_widening the plan-wide analyzer widens the referenced private source member
     * (minimal legal widening: same-package private -&gt; package-private) at its own declaration so the
     * relocated body stays source-valid, instead of refusing the move.
     */
    @Test
    void moveStaticMemberWidensReferencedPrivateDependencyWhenConfirmed(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    private static int secret() { return 7; }\n"
                + "    public static int exposed() { return secret(); }\n"
                + "}\n";
        String dst = ""
                + "package demo;\n"
                + "public class Dst {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src, "demo/Dst.java", dst));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "exposed()");
        fields.put("targetType", "demo.Dst");
        fields.put("targetRelativePath", "src/demo/Dst.java");
        fields.put("allowAccessWidening", true);
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);
        assertFalse(json.contains("\"code\":\"access_widening_not_confirmed\""), json);
        assertFalse(json.contains("\"code\":\"private_dependency_unsupported\""), json);
        // The referenced private member is widened in place via a structured MOVE_STATIC_MEMBER_ACCESS edit.
        assertTrue(json.contains("MOVE_STATIC_MEMBER_ACCESS"), json);
    }

    @Test
    void moveInstanceMethodRefusesStaticMethod(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    public static int f() { return 1; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "f()");
        fields.put("targetParameter", "ignored");
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"not_instance_method\""), json);
    }

    @Test
    void moveInstanceMethodRefusesMissingReceiver(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    public int g() { return 1; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "g()");
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"missing_target_receiver\""), json);
    }

    @Test
    void moveInstanceMethodRefusesProtectedReceiverSemanticChange(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    protected int h(String x) { return 1; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "h(String");
        fields.put("targetParameter", "x");
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"protected_access_semantic_change\""), json);
    }

    @Test
    void moveInstanceMethodRefusesSuperDispatch(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    public int s(String x) { return super.hashCode(); }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "s(String");
        fields.put("targetParameter", "x");
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"super_reference_unsupported\""), json);
    }

    @Test
    void moveInstanceMethodRefusesSynchronizedMethodReceiverSemanticChange(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    public synchronized int sy(String x) { return 1; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "sy(String");
        fields.put("targetParameter", "x");
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"synchronized_receiver_unsupported\""), json);
    }

    @Test
    void moveInstanceMethodRefusesSynchronizedOnThisBlock(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    public int sb(String x) { synchronized (this) { return 1; } }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "sb(String");
        fields.put("targetParameter", "x");
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"synchronized_receiver_unsupported\""), json);
    }

    /** G008: a `this::oldMethod` capture is refused (not silently skipped) when no delegate remains, with a location. */
    @Test
    void moveInstanceMethodRefusesThisMethodReference(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "public class Src {\n"
                + "    public int produce(Target t) { return 1; }\n"
                + "    Function<Target, Integer> capture() {\n"
                + "        return this::produce;\n"
                + "    }\n"
                + "}\n";
        String dst = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src, "demo/Target.java", dst));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "produce(Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");
        // G008: keepDelegate now defaults TRUE (leave_delegate_default=true), so the method reference would resolve to the
        // surviving delegate and be accepted. Disable the delegate explicitly to exercise the no-delegate refusal path.
        fields.put("keepDelegate", false);
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"method_reference_unsupported\""), json);
        assertTrue(json.contains("Src.java:"), json);
    }

    /** G008: an unbound `Src::oldMethod` capture is refused (not silently skipped) when no delegate remains. */
    @Test
    void moveInstanceMethodRefusesUnboundTypeMethodReference(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "import java.util.function.BiFunction;\n"
                + "public class Src {\n"
                + "    public int produce(Target t) { return 1; }\n"
                + "    BiFunction<Src, Target, Integer> ref() {\n"
                + "        return Src::produce;\n"
                + "    }\n"
                + "}\n";
        String dst = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Src.java", src, "demo/Target.java", dst));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "produce(Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");
        // G008: disable the now-default delegate so the no-delegate method-reference refusal path is exercised.
        fields.put("keepDelegate", false);
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"method_reference_unsupported\""), json);
        assertTrue(json.contains("Src.java:"), json);
    }

    /** G008: a bound `obj::oldMethod` capture from another file is refused with a located reference to that file. */
    @Test
    void moveInstanceMethodRefusesObjectMethodReferenceInCaller(@TempDir Path tmp) throws IOException {
        String src = ""
                + "package demo;\n"
                + "public class Src {\n"
                + "    public int produce(Target t) { return 1; }\n"
                + "}\n";
        String caller = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "public class Caller {\n"
                + "    Function<Target, Integer> wire(Src obj) {\n"
                + "        return obj::produce;\n"
                + "    }\n"
                + "}\n";
        String dst = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(
                tmp, Map.of("demo/Src.java", src, "demo/Caller.java", caller, "demo/Target.java", dst));
        Map<String, Object> fields = fields("src/demo/Src.java", src, "produce(Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");
        // G008: disable the now-default delegate so the no-delegate method-reference refusal path is exercised.
        fields.put("keepDelegate", false);
        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"method_reference_unsupported\""), json);
        assertTrue(json.contains("Caller.java:"), json);
    }

    /** Build the base fields map (relativePath + precise one-based line/column of the first {@code token}). */
    private static Map<String, Object> fields(String relativePath, String source, String token) {
        int[] pos = positionOf(source, token);
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", relativePath);
        fields.put("line", pos[0]);
        fields.put("column", pos[1]);
        return fields;
    }

    /** One-based {line, column} of the first occurrence of {@code token} in {@code source}. */
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

    /** A single-source-set javac-backed model containing every {@code relativeToSourceRoot -> content} file. */
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
