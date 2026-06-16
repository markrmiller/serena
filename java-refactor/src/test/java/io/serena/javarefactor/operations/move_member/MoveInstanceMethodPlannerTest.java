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
 * Acceptance + refusal coverage for the V2 instance-method move (G008, hard blocker 8), exercised through the preserved
 * {@link MoveMemberPlanner#moveInstanceMethod} entry point that delegates to {@link MoveInstanceMethodPlanner}. Each case
 * runs the real planner against a javac-backed temp project and asserts on the planned preview JSON, so the G008
 * behaviours — javac-backed receiver/argument reorder-safety, AST-based compound-receiver body rewrite, config-driven
 * delegate / call-site-rewrite defaults, the three receiver strategies, method-reference safety, and moved-body import
 * transplant — are proven without an apply/build cycle.
 */
class MoveInstanceMethodPlannerTest {

    // ── Receiver strategies (accepted) ───────────────────────────────────────────────────────────────────────────

    /** G008: the target-FIELD strategy makes a source field the new receiver and rewrites the field-qualified call. */
    @Test
    void moveInstanceMethodTargetFieldStrategy(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int compute(int amount) {\n"
                + "        return helper.scale(amount);\n"
                + "    }\n"
                + "    int run(Source s) {\n"
                + "        return s.compute(5);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // the field becomes the new receiver: its in-body `helper.scale` qualifier is dropped
        assertTrue(json.contains("return scale(amount);"), json);
        // the call site `s.compute(5)` is requalified onto `s.helper`
        assertTrue(json.contains("s.helper.compute(5)"), json);
    }

    /** G008: the explicit-RECEIVER strategy accepts a simple navigation receiver and rewrites the call onto it. */
    @Test
    void moveInstanceMethodExplicitReceiverStrategy(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target target;\n"
                + "    public int compute(int amount) {\n"
                + "        return target.scale(amount);\n"
                + "    }\n"
                + "    int run(Source s) {\n"
                + "        return s.compute(5);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetReceiver", "target");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("return scale(amount);"), json);
        assertTrue(json.contains("target.compute(5)"), json);
    }

    // ── Receiver/argument safety from javac semantics (G008 req 1) ───────────────────────────────────────────────

    /**
     * G003: a non-simple explicit targetReceiver (here a call) is refused before any edit is planned. A detached
     * receiver string has no resolvable TreePath, so its reorder safety can never be AST-proven and it is never admitted
     * via a detached purity classification.
     */
    @Test
    void moveInstanceMethodRefusesSideEffectingExplicitReceiver(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public int compute(int amount) { return amount; }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetReceiver", "make()"); // a call: non-simple, no resolvable TreePath, refused
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"code\":\"non_simple_receiver_unsupported\""), json);
    }

    /**
     * G002: a direct apply=true that is refused must route through the canonical refusal envelope — {@code applied} is
     * ALWAYS false (the previous hand-rolled {@code refusedJson} echoed the incoming apply flag, so an apply=true refusal
     * could report {@code applied:true}) and {@code mode} reflects the actual requested "apply".
     */
    @Test
    void directApplyRefusalReportsApplyModeAndNeverApplied(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public int compute(int amount) { return amount; }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetReceiver", "make()"); // non-simple receiver: refused
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, true);

        assertTrue(json.contains("\"code\":\"non_simple_receiver_unsupported\""), json);
        assertTrue(json.contains("\"mode\":\"apply\""), json);
        assertTrue(json.contains("\"applied\":false"), json);
        assertFalse(json.contains("\"applied\":true"), json);
    }

    /**
     * G003 matrix: every non-simple explicit receiver shape — a cast, an array access, an allocation, a parenthesized
     * expression, a field read off a call, and an arithmetic expression — is refused with {@code
     * non_simple_receiver_unsupported}, because none has a resolvable AST range to prove evaluation-order safety. This
     * replaces the former {@code ExpressionPurityAnalyzer.classify(String)} allow path that string-green-lit "pure"
     * detached receivers such as {@code (Target) raw} and {@code targets[0]}.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "(Target) raw",
        "targets[0]",
        "new Target()",
        "(target)",
        "make().target",
        "a + b"
    })
    void moveInstanceMethodRefusesNonSimpleExplicitReceiver(String receiver, @TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target target;\n"
                + "    public int compute(int amount, Object raw, Target[] targets, int a, int b) { return amount; }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields =
                fields("src/demo/Source.java", source, "compute(int amount, Object raw, Target[] targets, int a, int b)");
        fields.put("targetReceiver", receiver);
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"code\":\"non_simple_receiver_unsupported\""), json);
    }

    /** G008: an existing call site whose receiver is a method CALL (UNKNOWN, not reorder-safe) is refused. */
    @Test
    void moveInstanceMethodRefusesCallReceiverAtCallSite(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int compute(int amount) {\n"
                + "        return helper.scale(amount);\n"
                + "    }\n"
                + "    static Source make() { return new Source(); }\n"
                + "    int run() {\n"
                + "        return make().compute(5);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        // the receiver `make()` is a call -> not provably reorder-safe -> refuse (replaces the old `contains("(")` heuristic)
        assertTrue(json.contains("\"code\":\"SIDE_EFFECTING_RECEIVER_EXPRESSION\""), json);
    }

    /** G008: an existing call site whose receiver is an assignment ({@code (h = other).m()}) is refused. */
    @Test
    void moveInstanceMethodRefusesAssignmentReceiverAtCallSite(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int compute(int amount) {\n"
                + "        return helper.scale(amount);\n"
                + "    }\n"
                + "    int run(Source h, Source other) {\n"
                + "        return (h = other).compute(5);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"code\":\"SIDE_EFFECTING_RECEIVER_EXPRESSION\""), json);
    }

    /** G008: an existing call site whose receiver uses post-increment ({@code arr[i++].m()}) is refused. */
    @Test
    void moveInstanceMethodRefusesArrayIndexIncrementReceiverAtCallSite(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int compute(int amount) {\n"
                + "        return helper.scale(amount);\n"
                + "    }\n"
                + "    int run(Source[] arr, int i) {\n"
                + "        return arr[i++].compute(5);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"code\":\"SIDE_EFFECTING_RECEIVER_EXPRESSION\""), json);
    }

    /** G008: a target-parameter argument that is a method call (UNKNOWN) is refused, not just SIDE_EFFECTING ones. */
    @Test
    void moveInstanceMethodRefusesUnknownTargetParameterArgument(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public int combine(int amount, Target t) {\n"
                + "        return t.scale(amount);\n"
                + "    }\n"
                + "    static Target make() { return new Target(); }\n"
                + "    int run() {\n"
                + "        return combine(5, make());\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "combine(int amount, Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");
        // keep the delegate disabled so the call site is actually rewritten and the promotion safety is exercised
        fields.put("keepDelegate", false);

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        // `make()` is an UNKNOWN call result -> not provably reorder-safe -> refused (old screen only caught SIDE_EFFECTING)
        assertTrue(json.contains("\"code\":\"SIDE_EFFECTING_RECEIVER_EXPRESSION\""), json);
    }

    /** G008: a target-parameter argument that is a pre-increment (SIDE_EFFECTING) is refused. */
    @Test
    void moveInstanceMethodRefusesIncrementTargetParameterArgument(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public int combine(int amount, int t) {\n"
                + "        return amount + t;\n"
                + "    }\n"
                + "    int run(int i) {\n"
                + "        return combine(5, ++i);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "combine(int amount, int t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");
        fields.put("keepDelegate", false);

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"code\":\"SIDE_EFFECTING_RECEIVER_EXPRESSION\""), json);
    }

    // ── Config-driven defaults (G008 req 3) ──────────────────────────────────────────────────────────────────────

    /** G008: with NOTHING set, leave_delegate_default=true makes keepDelegate default TRUE — a delegate is kept. */
    @Test
    void moveInstanceMethodKeepsDelegateByDefault(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public int combine(int amount, Target t) {\n"
                + "        return t.scale(amount);\n"
                + "    }\n"
                + "    int run(Target other) {\n"
                + "        return combine(5, other);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "combine(int amount, Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");
        // deliberately set neither keepDelegate nor rewriteCallSites

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // default leave_delegate_default=true -> a delegate is kept in the source
        assertTrue(json.contains("MOVE_INSTANCE_METHOD_DELEGATE"), json);
        assertFalse(json.contains("MOVE_INSTANCE_METHOD_REMOVE"), json);
    }

    /** G008: with NOTHING set, rewrite_call_sites_default=true makes call-site rewriting default TRUE. */
    @Test
    void moveInstanceMethodRewritesCallSitesByDefault(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public int combine(int amount, Target t) {\n"
                + "        return t.scale(amount);\n"
                + "    }\n"
                + "    int run(Target other) {\n"
                + "        return combine(5, other);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "combine(int amount, Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // default rewrite_call_sites_default=true -> the external call site is rewritten onto the new receiver
        assertTrue(json.contains("MOVE_INSTANCE_CALL"), json);
        assertTrue(json.contains("other.combine(5)"), json);
    }

    // ── Method-reference safety (G008) ───────────────────────────────────────────────────────────────────────────

    /** G008: with delegate kept by default, a `this::m` capture is accepted because it still resolves to the delegate. */
    @Test
    void moveInstanceMethodAcceptsMethodReferenceUnderDefaultDelegate(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "public class Source {\n"
                + "    public int produce(Target t) { return 1; }\n"
                + "    Function<Target, Integer> capture() {\n"
                + "        return this::produce;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "produce(Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");
        // keepDelegate not set -> defaults TRUE -> the `this::produce` reference still resolves to the surviving delegate

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("MOVE_INSTANCE_METHOD_DELEGATE"), json);
        assertFalse(json.contains("method_reference_unsupported"), json);
    }

    /**
     * G008 (negative): a type-bound {@code Source::m} capture is refused with located evidence when the move removes the
     * declaration (a field-strategy move keeps no delegate, so the unbound reference would dangle).
     */
    @Test
    void moveInstanceMethodRefusesTypeBoundMethodReferenceWhenDeclarationRemoved(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.function.BiFunction;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int produce(int amount) {\n"
                + "        return helper.scale(amount);\n"
                + "    }\n"
                + "    BiFunction<Source, Integer, Integer> capture() {\n"
                + "        return Source::produce;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "produce(int amount)");
        fields.put("targetField", "helper"); // field strategy => parameter == null => no delegate retained
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"code\":\"method_reference_unsupported\""), json);
        // located evidence: file:line:col of the Source::produce capture
        assertTrue(json.contains("src/demo/Source.java:"), json);
    }

    /** G008 (negative): a bound {@code this::m} capture is refused with located evidence when the declaration is removed. */
    @Test
    void moveInstanceMethodRefusesThisBoundMethodReferenceWhenDeclarationRemoved(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int produce(int amount) {\n"
                + "        return helper.scale(amount);\n"
                + "    }\n"
                + "    Function<Integer, Integer> capture() {\n"
                + "        return this::produce;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "produce(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"code\":\"method_reference_unsupported\""), json);
        assertTrue(json.contains("src/demo/Source.java:"), json);
    }

    /** G008 (negative): an instance-bound {@code obj::m} capture is refused with located evidence when the declaration is removed. */
    @Test
    void moveInstanceMethodRefusesInstanceBoundMethodReferenceWhenDeclarationRemoved(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int produce(int amount) {\n"
                + "        return helper.scale(amount);\n"
                + "    }\n"
                + "    Function<Integer, Integer> capture(Source other) {\n"
                + "        return other::produce;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "produce(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"code\":\"method_reference_unsupported\""), json);
        assertTrue(json.contains("src/demo/Source.java:"), json);
    }

    /**
     * G008 (negative, cross-file): a method reference captured in ANOTHER file ({@code s::produce} in {@code Client.java})
     * is detected and refused with located evidence pointing at that file, proving cross-file method-reference detection.
     */
    @Test
    void moveInstanceMethodRefusesCrossFileMethodReferenceWhenDeclarationRemoved(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    public int produce(int amount) {\n"
                + "        return helper.scale(amount);\n"
                + "    }\n"
                + "}\n";
        String client = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "public class Client {\n"
                + "    Function<Integer, Integer> capture(Source s) {\n"
                + "        return s::produce;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(
                tmp, Map.of("demo/Source.java", source, "demo/Client.java", client, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "produce(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"code\":\"method_reference_unsupported\""), json);
        // located evidence names the OTHER file, not the source declaration's file
        assertTrue(json.contains("src/demo/Client.java:"), json);
    }

    /**
     * G008 (positive): with the delegate retained (keepDelegate default + a target-PARAMETER move), an unbound
     * {@code Source::m} capture is accepted — the retained delegate keeps the original name/signature, so the reference
     * still resolves and is left intact (no rewrite of the {@code ::} capture).
     */
    @Test
    void moveInstanceMethodAcceptsTypeBoundMethodReferenceUnderDelegate(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import java.util.function.BiFunction;\n"
                + "public class Source {\n"
                + "    public int produce(Target t) {\n"
                + "        return t.scale(1);\n"
                + "    }\n"
                + "    BiFunction<Source, Target, Integer> capture() {\n"
                + "        return Source::produce;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "produce(Target t)");
        fields.put("targetParameter", "t"); // parameter strategy + default keepDelegate => delegate retained
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("MOVE_INSTANCE_METHOD_DELEGATE"), json);
        assertFalse(json.contains("method_reference_unsupported"), json);
    }

    /**
     * G008 (positive, cross-file): a bound {@code s::produce} capture in ANOTHER file is accepted under the delegate path,
     * left intact (the {@code ::} capture is never rewritten into a {@code .produce(...)} invocation).
     */
    @Test
    void moveInstanceMethodAcceptsCrossFileMethodReferenceUnderDelegate(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public int produce(Target t) {\n"
                + "        return t.scale(1);\n"
                + "    }\n"
                + "}\n";
        String client = ""
                + "package demo;\n"
                + "import java.util.function.Function;\n"
                + "public class Client {\n"
                + "    Function<Target, Integer> capture(Source s) {\n"
                + "        return s::produce;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) { return amount; }\n"
                + "}\n";
        JavaProjectModel model = model(
                tmp, Map.of("demo/Source.java", source, "demo/Client.java", client, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "produce(Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("MOVE_INSTANCE_METHOD_DELEGATE"), json);
        assertFalse(json.contains("method_reference_unsupported"), json);
        // the cross-file `s::produce` capture is left intact — never rewritten into an `s.produce(...)` invocation
        assertFalse(json.contains("s.produce("), json);
    }

    // ── Moved-body imports (G008 req 4) ──────────────────────────────────────────────────────────────────────────

    /** G008: the moved body's source-only single-type import is transplanted into the target (field strategy). */
    @Test
    void moveInstanceMethodTransplantsBodyImportsForFieldStrategy(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package a;\n"
                + "import java.util.List;\n"
                + "public class Source {\n"
                + "    Helper helper;\n"
                + "    public List<String> describe() {\n"
                + "        return helper.values();\n"
                + "    }\n"
                + "}\n";
        String helper = ""
                + "package a;\n"
                + "public class Helper {\n"
                + "    public java.util.List<String> values() { return null; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("a/Source.java", source, "a/Helper.java", helper));
        Map<String, Object> fields = fields("src/a/Source.java", source, "describe()");
        fields.put("targetField", "helper");
        fields.put("targetType", "a.Helper");
        fields.put("targetRelativePath", "src/a/Helper.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"kind\":\"MOVE_INSTANCE_METHOD_IMPORT\""), json);
        assertTrue(json.contains("import java.util.List;"), json);
    }

    // ── G020 (B4): private source dependency access planning ─────────────────────────────────────────────────────

    /** A referenced private STATIC helper needs no receiver, so it is widened in place (same package → package-private). */
    @Test
    void moveInstanceMethodWidensReferencedPrivateStaticHelper(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    private static int scale(int amount) { return amount * 2; }\n"
                + "    public int compute(int amount) {\n"
                + "        return helper.absorb(scale(amount));\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int absorb(int v) { return v; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");
        fields.put("allowAccessWidening", true);

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("MOVE_INSTANCE_METHOD_WIDEN"), json);
    }

    /** A referenced private INSTANCE field is reached through the source `this`; no widening can supply it → refuse. */
    @Test
    void moveInstanceMethodRefusesReferencedPrivateInstanceField(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    private int counter = 1;\n"
                + "    public int compute(int amount) {\n"
                + "        return helper.absorb(amount + counter);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int absorb(int v) { return v; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");
        fields.put("allowAccessWidening", true);

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"source_state_required\""), json);
        assertTrue(json.contains("counter"), json);
    }

    /** Cross-package widening of a private static dependency is refused by default (no allowAccessWidening opt-in). */
    @Test
    void moveInstanceMethodRefusesCrossPackagePrivateStaticWithoutOptIn(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import other.Target;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    private static int scale(int amount) { return amount * 2; }\n"
                + "    public int compute(int amount) {\n"
                + "        return helper.absorb(scale(amount));\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package other;\n"
                + "public class Target {\n"
                + "    public int absorb(int v) { return v; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "other/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "other.Target");
        fields.put("targetRelativePath", "src/other/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"access_widening_not_confirmed\""), json);
    }

    /** With the opt-in, the cross-package private static dependency is widened (to public) so the move is source-valid. */
    @Test
    void moveInstanceMethodWidensCrossPackagePrivateStaticWhenOptedIn(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import other.Target;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    private static int scale(int amount) { return amount * 2; }\n"
                + "    public int compute(int amount) {\n"
                + "        return helper.absorb(scale(amount));\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package other;\n"
                + "public class Target {\n"
                + "    public int absorb(int v) { return v; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "other/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "other.Target");
        fields.put("targetRelativePath", "src/other/Target.java");
        fields.put("allowAccessWidening", true);

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("MOVE_INSTANCE_METHOD_WIDEN"), json);
    }

    /** A security-sensitive private member is refused for cross-package widening even with allowAccessWidening. */
    @Test
    void moveInstanceMethodRefusesSecuritySensitivePrivateWidening(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "import other.Target;\n"
                + "public class Source {\n"
                + "    Target helper;\n"
                + "    private static int secretToken = 42;\n"
                + "    public int compute(int amount) {\n"
                + "        return helper.absorb(amount + secretToken);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package other;\n"
                + "public class Target {\n"
                + "    public int absorb(int v) { return v; }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "other/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "compute(int amount)");
        fields.put("targetField", "helper");
        fields.put("targetType", "other.Target");
        fields.put("targetRelativePath", "src/other/Target.java");
        fields.put("allowAccessWidening", true);

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);
        assertTrue(json.contains("\"code\":\"security_sensitive_private_widening\""), json);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────

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
