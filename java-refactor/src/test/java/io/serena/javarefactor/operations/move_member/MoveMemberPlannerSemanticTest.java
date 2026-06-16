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
 * Acceptance coverage for the semantic member-move planner ({@link MoveMemberPlanner}) backing the Phase-2 move goals
 * G012 (transplant static-move body imports), G013 (safe static wildcard removal), G014 (positional target-parameter
 * call rewriting), G015 (AST-proven body rewrite), G016 (complete source-state analysis), and G017 (transplant
 * instance-move body imports). Each case runs the real planner against a javac-backed temp project and asserts on the
 * planned preview JSON (edit {@code newText}/{@code kind} or the refusal code), so the behaviours are proven without an
 * apply/build cycle.
 */
class MoveMemberPlannerSemanticTest {

    /** G014: the receiver binds to the argument at the target parameter's POSITION, even a differently named one. */
    @Test
    void moveInstanceMethodRewritesTargetParameterAtNonZeroPositionWithDifferentArgumentName(@TempDir Path tmp)
            throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public int combine(int amount, Target t) {\n"
                + "        return t.scale(amount);\n"
                + "    }\n"
                + "    void run(Target other) {\n"
                + "        int value = combine(5, other);\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int scale(int amount) {\n"
                + "        return amount;\n"
                + "    }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "combine(int amount, Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // the second argument (a differently named variable) becomes the receiver; the remaining argument is preserved
        assertTrue(json.contains("other.combine(5)"), json);
        // the moved declaration drops the receiver parameter and rewrites its in-body uses to the new `this`
        assertTrue(json.contains("int combine(int amount)"), json);
        assertTrue(json.contains("return scale(amount);"), json);
    }

    /** G015: with allow_access_widening, a cross-package package-private static member is widened to public and moved. */
    @Test
    void moveStaticMemberWidensAccessWhenAllowed(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package a;\n"
                + "public class Source {\n"
                + "    static int hidden() { return 1; }\n"
                + "}\n";
        String target = ""
                + "package b;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("a/Source.java", source, "b/Target.java", target));
        Map<String, Object> fields = fields("src/a/Source.java", source, "hidden()");
        fields.put("targetType", "b.Target");
        fields.put("targetRelativePath", "src/b/Target.java");
        fields.put("allowAccessWidening", true);

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // the moved declaration is widened to public so the cross-package relocation stays source-valid
        assertTrue(json.contains("public static int hidden()"), json);
        assertTrue(json.contains("\"publicApiWidening\":true"), json);
    }

    /** G004 (§6.3): an opted-in widening move emits a structured ACCESS_WIDENING_REQUIRED warning alongside the strings. */
    @Test
    void moveStaticMemberEmitsStructuredAccessWideningWarning(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package a;\n"
                + "public class Source {\n"
                + "    static int hidden() { return 1; }\n"
                + "}\n";
        String target = ""
                + "package b;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("a/Source.java", source, "b/Target.java", target));
        Map<String, Object> fields = fields("src/a/Source.java", source, "hidden()");
        fields.put("targetType", "b.Target");
        fields.put("targetRelativePath", "src/b/Target.java");
        fields.put("allowAccessWidening", true);

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // the structured warnings array carries the design §6.3 {code, message} schema for the widening preview
        assertTrue(json.contains("\"structuredWarnings\":["), json);
        assertTrue(json.contains("\"code\":\"ACCESS_WIDENING_REQUIRED\""), json);
        // the plain string warnings array is left intact (additive change)
        assertTrue(json.contains("\"warnings\":["), json);
    }

    /** G004: a same-package move that needs no widening emits no structured warning. */
    @Test
    void moveStaticMemberOmitsStructuredWarningWhenNoWideningNeeded(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public static int value() { return 1; }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "value()");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("ACCESS_WIDENING_REQUIRED"), json);
        assertFalse(json.contains("\"structuredWarnings\""), json);
    }

    /** G015: an `import static Source.member;` in a caller is rewritten to the target type's static import. */
    @Test
    void moveStaticMemberRewritesExplicitStaticImport(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public static int value() { return 1; }\n"
                + "}\n";
        String caller = ""
                + "package other;\n"
                + "import static demo.Source.value;\n"
                + "public class Caller {\n"
                + "    int get() { return value(); }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(
                tmp, Map.of("demo/Source.java", source, "other/Caller.java", caller, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "value()");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"kind\":\"MOVE_STATIC_MEMBER_STATIC_IMPORT\""), json);
        assertTrue(json.contains("import static demo.Target.value;"), json);
    }

    /** G015: moving a static FIELD (not a method) inserts the field into the target and rewrites its reference. */
    @Test
    void moveStaticFieldInsertsAndRewritesReference(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public static final int LIMIT = 5;\n"
                + "}\n";
        String caller = ""
                + "package demo;\n"
                + "public class Caller {\n"
                + "    int get() { return Source.LIMIT; }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(
                tmp, Map.of("demo/Source.java", source, "demo/Caller.java", caller, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "LIMIT = 5");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"kind\":\"MOVE_STATIC_MEMBER_INSERT\""), json);
        assertTrue(json.contains("public static final int LIMIT = 5;"), json);
        // the cross-type reference is requalified to the new home
        assertTrue(json.contains("Target.LIMIT"), json);
    }

    /** G015: moving a PUBLIC static member emits the designed public-API-surface relocation warning. */
    @Test
    void moveStaticMemberEmitsPublicApiWarning(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public static int api() { return 1; }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "api()");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("relocates the public static member 'api'"), json);
        assertTrue(json.contains("changing the public API surface"), json);
    }

    /** G012: a static move transplants the source-only single-type imports the moved body needs into the target. */
    @Test
    void moveStaticMemberTransplantsBodyImportsToTarget(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package a;\n"
                + "import java.util.ArrayList;\n"
                + "import java.util.List;\n"
                + "public class Source {\n"
                + "    public static List<String> make() {\n"
                + "        return new ArrayList<>();\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package b;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("a/Source.java", source, "b/Target.java", target));
        Map<String, Object> fields = fields("src/a/Source.java", source, "make(");
        fields.put("targetType", "b.Target");
        fields.put("targetRelativePath", "src/b/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"kind\":\"MOVE_STATIC_MEMBER_IMPORT\""), json);
        assertTrue(json.contains("import java.util.List;"), json);
        assertTrue(json.contains("import java.util.ArrayList;"), json);
    }

    /** G002/G012: a static move transplants the source-only STATIC imports the moved body uses into the target. */
    @Test
    void moveStaticMemberTransplantsStaticBodyImportsToTarget(@TempDir Path tmp) throws IOException {
        String util = ""
                + "package a;\n"
                + "public class Util {\n"
                + "    public static int helper() { return 9; }\n"
                + "}\n";
        String source = ""
                + "package a;\n"
                + "import static a.Util.helper;\n"
                + "public class Source {\n"
                + "    public static int make() {\n"
                + "        return helper();\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package b;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(
                tmp, Map.of("a/Util.java", util, "a/Source.java", source, "b/Target.java", target));
        Map<String, Object> fields = fields("src/a/Source.java", source, "make(");
        fields.put("targetType", "b.Target");
        fields.put("targetRelativePath", "src/b/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // the static import the moved body relies on is carried into the target so helper() still resolves there
        assertTrue(json.contains("import static a.Util.helper;"), json);
    }

    /** G017: an instance move transplants the source-only single-type imports the moved body needs into the target. */
    @Test
    void moveInstanceMethodTransplantsBodyImportsToTarget(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package a;\n"
                + "import java.util.List;\n"
                + "public class Source {\n"
                + "    public List<String> describe(Helper h) {\n"
                + "        return h.values();\n"
                + "    }\n"
                + "}\n";
        // Helper references List only via a fully-qualified name, so it carries no java.util.List import to inherit.
        String helper = ""
                + "package a;\n"
                + "public class Helper {\n"
                + "    public java.util.List<String> values() {\n"
                + "        return null;\n"
                + "    }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("a/Source.java", source, "a/Helper.java", helper));
        Map<String, Object> fields = fields("src/a/Source.java", source, "describe(Helper h)");
        fields.put("targetParameter", "h");
        fields.put("targetType", "a.Helper");
        fields.put("targetRelativePath", "src/a/Helper.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"kind\":\"MOVE_INSTANCE_METHOD_IMPORT\""), json);
        assertTrue(json.contains("import java.util.List;"), json);
    }

    /** G013: the source static wildcard is preserved when other static members of the source may still need it. */
    @Test
    void moveStaticMemberKeepsWildcardWhenOtherStaticMembersRemain(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "class Source {\n"
                + "    static final int VALUE = 1;\n"
                + "    static final int OTHER = 2;\n"
                + "}\n";
        String caller = ""
                + "package demo;\n"
                + "import static demo.Source.*;\n"
                + "class Caller {\n"
                + "    int get() {\n"
                + "        return VALUE + OTHER;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "class Target {\n"
                + "}\n";
        JavaProjectModel model = model(
                tmp, Map.of("demo/Source.java", source, "demo/Caller.java", caller, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "VALUE = 1");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // OTHER still resolves through the wildcard, so it must NOT be removed
        assertFalse(json.contains("MOVE_STATIC_MEMBER_STALE_WILDCARD_IMPORT"), json);
        assertTrue(json.contains("Target.VALUE"), json);
    }

    /** G013: the source static wildcard is removed when the moved member is the sole static member of the source. */
    @Test
    void moveStaticMemberRemovesWildcardWhenSoleStaticMember(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "class Source {\n"
                + "    static final int VALUE = 1;\n"
                + "}\n";
        String caller = ""
                + "package demo;\n"
                + "import static demo.Source.*;\n"
                + "class Caller {\n"
                + "    int get() {\n"
                + "        return VALUE;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "class Target {\n"
                + "}\n";
        JavaProjectModel model = model(
                tmp, Map.of("demo/Source.java", source, "demo/Caller.java", caller, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "VALUE = 1");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveStaticMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("MOVE_STATIC_MEMBER_STALE_WILDCARD_IMPORT"), json);
    }

    /** G015: the AST body rewrite ignores the receiver name inside comments and string literals. */
    @Test
    void moveInstanceMethodBodyRewriteIgnoresReceiverNameInCommentsAndStrings(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public String wrap(Target t) {\n"
                + "        // t.skip should be ignored\n"
                + "        String s = \"t.literal\";\n"
                + "        return t.value() + s;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public String value() {\n"
                + "        return \"x\";\n"
                + "    }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "wrap(Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // only the genuine receiver use is rewritten
        assertTrue(json.contains("return value() + s;"), json);
        // the comment and the string literal keep their literal "t." text untouched
        assertTrue(json.contains("t.skip should be ignored"), json);
        assertTrue(json.contains("t.literal"), json);
    }

    /** G016: a move is refused precisely when the body reads NON-private source instance state through implicit this. */
    @Test
    void moveInstanceMethodRefusesSourceInstanceFieldDependency(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    int count;\n"
                + "    public int total(Target t) {\n"
                + "        return t.base() + count;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "    public int base() {\n"
                + "        return 1;\n"
                + "    }\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "total(Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"code\":\"source_state_required\""), json);
    }

    /** G016: a move is refused when the source {@code this} escapes as a value rather than as a member qualifier. */
    @Test
    void moveInstanceMethodRefusesSourceThisEscape(@TempDir Path tmp) throws IOException {
        String source = ""
                + "package demo;\n"
                + "public class Source {\n"
                + "    public Source self(Target t) {\n"
                + "        return this;\n"
                + "    }\n"
                + "}\n";
        String target = ""
                + "package demo;\n"
                + "public class Target {\n"
                + "}\n";
        JavaProjectModel model = model(tmp, Map.of("demo/Source.java", source, "demo/Target.java", target));
        Map<String, Object> fields = fields("src/demo/Source.java", source, "self(Target t)");
        fields.put("targetParameter", "t");
        fields.put("targetType", "demo.Target");
        fields.put("targetRelativePath", "src/demo/Target.java");

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"code\":\"source_this_escape_unsupported\""), json);
    }

    /** G008: a method reference is accepted (not refused) when a delegate is kept, because it still resolves to it. */
    @Test
    void moveInstanceMethodKeepsMethodReferenceWhenDelegateRemains(@TempDir Path tmp) throws IOException {
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
        fields.put("keepDelegate", true);
        fields.put("rewriteCallSites", false);

        String json = new MoveMemberPlanner(tmp.toAbsolutePath().normalize(), model).moveInstanceMethod(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // a delegate with the original signature is left behind so `this::produce` still resolves; no refusal
        assertTrue(json.contains("MOVE_INSTANCE_METHOD_DELEGATE"), json);
        assertFalse(json.contains("method_reference_unsupported"), json);
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
