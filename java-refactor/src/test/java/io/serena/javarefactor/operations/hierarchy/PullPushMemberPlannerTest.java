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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the V2 {@link PullPushMemberPlanner} performs semantic member identity, javac-backed body
 * compatibility, sibling/collision checks, and @Override + interface rendering against a real javac-backed temp project.
 * Each case runs the real planner in preview mode and asserts on the emitted workspace-edit JSON.
 */
class PullPushMemberPlannerTest {

    @Test
    void pullsConcreteMethodUpToSuperclass(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    String label() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "label", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("String label()"), json);
        assertTrue(json.contains("Base.java"), json);
    }

    @Test
    void refusesPublicMemberPullUpWithoutPublicApiConfirmation(@TempDir Path tmp) throws IOException {
        // G001: pulling up a public member changes the public API surface and is refused unless confirmed. With no
        // confirmation flag set (the default), the planner refuses.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    public String label() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "label", "Base"), false);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("PUBLIC_API_CONFIRMATION_REQUIRED"), json);
    }

    @Test
    void allowsPublicMemberPullUpWhenPublicApiConfirmed(@TempDir Path tmp) throws IOException {
        // With confirmPublicApi set (mapped from hierarchy.allow_public_api_change), the same public member pull-up is
        // admitted.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    public String label() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Child.java"), "Child.java", "label", "Base");
        fields.put("confirmPublicApi", Boolean.TRUE);
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
    }

    @Test
    void pullsMethodUpToInterfaceAsAbstractDeclarationWithOverride(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Named.java", "public interface Named {\n}\n");
        files.put(
                "Child.java",
                "public class Child implements Named {\n"
                        + "    public String label() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(confirmPublicApi(pullFields(files.get("Child.java"), "Child.java", "label", "Named")), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // Interface targets receive a body-less declaration; the retained override gains an @Override marker.
        assertTrue(json.contains("String label();"), json);
        assertTrue(json.contains("@Override"), json);
        assertTrue(json.contains("Named.java"), json);
    }

    @Test
    void pushesDownToSubtypesButRefusesOnCollision(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "Base.java",
                "public class Base {\n"
                        + "    String label() {\n"
                        + "        return \"base\";\n"
                        + "    }\n"
                        + "}\n");
        // Child already declares a compatible member -> push-down into it must refuse rather than duplicate.
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    String label() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pushFields(files.get("Base.java"), "Base.java", "label", List.of("Child"));
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pushDownMember(fields, false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"target_member_exists\""), json);
    }

    @Test
    void refusesPullUpWhenBodyDependsOnSourceOnlyMember(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    public String label() {\n"
                        + "        return helper();\n"
                        + "    }\n"
                        + "\n"
                        + "    String helper() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(confirmPublicApi(pullFields(files.get("Child.java"), "Child.java", "label", "Base")), false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"incompatible_member_body\""), json);
    }

    @Test
    void pushDownRemovalRefusesSourceTypedCallSite(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "Base.java",
                "public class Base {\n"
                        + "    String label() {\n"
                        + "        return \"base\";\n"
                        + "    }\n"
                        + "}\n");
        files.put("Child.java", "public class Child extends Base {\n}\n");
        // A caller statically typed to the source supertype would dangle once the member is removed from Base.
        files.put(
                "Use.java",
                "public class Use {\n"
                        + "    String run(Base base) {\n"
                        + "        return base.label();\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pushFields(files.get("Base.java"), "Base.java", "label", List.of("Child"));
        fields.put("removeFromSource", true);
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pushDownMember(fields, false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"unsafe_source_call_site\""), json);
    }

    @Test
    void pullUpDoesNotMisreadCommentAsSourceDependency(@TempDir Path tmp) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        // `helper` appears only in a comment/string; the javac body model must not treat it as a real dependency.
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    public String label() {\n"
                        + "        // helper is intentionally not called here\n"
                        + "        return \"helper\";\n"
                        + "    }\n"
                        + "\n"
                        + "    String helper() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(confirmPublicApi(pullFields(files.get("Child.java"), "Child.java", "label", "Base")), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("incompatible_member_body"), json);
    }

    @Test
    void pullsUpMethodWhenTargetDeclaresOnlyALegalOverload(@TempDir Path tmp) throws IOException {
        // G018: Base already declares label(int); pulling up label(String) is a legal overload, NOT a collision.
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "Base.java",
                "public class Base {\n"
                        + "    void label(int n) {\n"
                        + "    }\n"
                        + "}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    void label(String s) {\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "label", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("target_member_exists"), json);
        assertTrue(json.contains("label(String"), json);
    }

    @Test
    void refusesPullUpWhenTargetDeclaresTheSameSignature(@TempDir Path tmp) throws IOException {
        // G018: same erased signature in the target is a real collision (not an overload) and must refuse.
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "Base.java",
                "public class Base {\n"
                        + "    void label(String s) {\n"
                        + "    }\n"
                        + "}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    void label(String s) {\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "label", "Base"), false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"target_member_exists\""), json);
    }

    @Test
    void insertsIntoTargetTypeBodyNotTrailingTopLevelType(@TempDir Path tmp) throws IOException {
        // G019: the target file holds two top-level types; the member must be inserted at Base's closing brace, not the
        // file's last '}' (which belongs to the trailing type Other).
        Map<String, String> files = new LinkedHashMap<>();
        String baseFile = "public class Base {\n"
                + "}\n"
                + "\n"
                + "class Other {\n"
                + "}\n";
        files.put("Base.java", baseFile);
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    String label() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "label", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        long insertion = insertionOffset(json, "PULL_UP_INSERT");
        int otherStart = baseFile.indexOf("class Other");
        int baseBodyOpen = baseFile.indexOf('{');
        assertTrue(insertion > baseBodyOpen, "insertion " + insertion + " should be inside Base body: " + json);
        assertTrue(insertion < otherStart, "insertion " + insertion + " must precede trailing type Other at " + otherStart + ": " + json);
    }

    @Test
    void pushesDownWithRemovalWhenOnlySubtypeLocalCallsExist(@TempDir Path tmp) throws IOException {
        // G020: the only resolved call site is an unqualified call inside the subtype that receives the copy, so removal
        // from the source is safe. G021: stats must reflect the three real touched files (Base + two subtypes).
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "Base.java",
                "public class Base {\n"
                        + "    String label() {\n"
                        + "        return \"base\";\n"
                        + "    }\n"
                        + "}\n");
        files.put(
                "Child1.java",
                "public class Child1 extends Base {\n"
                        + "    String use() {\n"
                        + "        return label();\n"
                        + "    }\n"
                        + "}\n");
        files.put("Child2.java", "public class Child2 extends Base {\n}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pushFields(files.get("Base.java"), "Base.java", "label", List.of("Child1", "Child2"));
        fields.put("removeFromSource", true);
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pushDownMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // Real, derived stats: removal in Base + inserts into Child1 and Child2 = three touched files (G021).
        assertTrue(json.contains("\"touchedFileCount\":3"), json);
    }

    @Test
    void refusesPushDownRemovalForUnqualifiedCallOutsideAnySubtype(@TempDir Path tmp) throws IOException {
        // G020: an unqualified call inside the source type itself is decided from the resolved call site (no regex), and
        // would dangle once the member is removed, so removal must refuse.
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "Base.java",
                "public class Base {\n"
                        + "    String label() {\n"
                        + "        return \"base\";\n"
                        + "    }\n"
                        + "    String caller() {\n"
                        + "        return label();\n"
                        + "    }\n"
                        + "}\n");
        files.put("Child.java", "public class Child extends Base {\n}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pushFields(files.get("Base.java"), "Base.java", "label", List.of("Child"));
        fields.put("removeFromSource", true);
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pushDownMember(fields, false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"unsafe_source_call_site\""), json);
    }

    @Test
    void pullsConstantUpToInterfaceAsPublicStaticFinal(@TempDir Path tmp) throws IOException {
        // G009(a): a compile-time constant pulled into an interface is rendered public static final.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Named.java", "public interface Named {\n}\n");
        files.put(
                "Child.java",
                "public class Child implements Named {\n"
                        + "    static final String KIND = \"child\";\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "KIND", "Named"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("public static final String KIND"), json);
        assertTrue(json.contains("Named.java"), json);
    }

    @Test
    void refusesPullUpOfMultiDeclaratorConstantIntoInterfaceWithLocation(@TempDir Path tmp) throws IOException {
        // FIX 1: `static final int A = 1, B = 2;` is a single declaration with two declarators. Rendering it from the
        // member text would drag B into the interface alongside A (valid Java javac will not catch), so the pull-up must
        // refuse with a located code rather than silently promote the sibling declarator.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Named.java", "public interface Named {\n}\n");
        files.put(
                "Child.java",
                "public class Child implements Named {\n"
                        + "    static final int A = 1, B = 2;\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "A", "Named"), false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"multi_declarator_field_unsupported\""), json);
        assertTrue(json.contains("\"location\":{\"relativePath\":\"Child.java\""), json);
    }

    @Test
    void pullsSingleDeclaratorConstantUpToInterfaceUnaffectedByMultiDeclaratorGuard(@TempDir Path tmp) throws IOException {
        // FIX 1: a single-declarator `static final int A = 1;` is NOT a multi-declarator field and still succeeds,
        // rendering exactly one interface constant with the implicit modifiers made explicit.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Named.java", "public interface Named {\n}\n");
        files.put(
                "Child.java",
                "public class Child implements Named {\n"
                        + "    static final int A = 1;\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "A", "Named"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("public static final int A = 1"), json);
        assertFalse(json.contains("multi_declarator_field_unsupported"), json);
    }

    @Test
    void refusesPullUpOfInstanceFieldIntoInterfaceWithLocation(@TempDir Path tmp) throws IOException {
        // G009(b): a non-constant instance field cannot become an interface constant; refuse with a located code.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Named.java", "public interface Named {\n}\n");
        files.put(
                "Child.java",
                "public class Child implements Named {\n"
                        + "    String name;\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "name", "Named"), false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"interface_field_not_constant\""), json);
        assertTrue(json.contains("\"location\":{\"relativePath\":\"Child.java\""), json);
    }

    @Test
    void pullUpResolvesFullyQualifiedTargetAmongDuplicateSimpleNames(@TempDir Path tmp) throws IOException {
        // G010: two types share the simple name Base; a qualified request must bind to a.Base, never b.Base.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a/Base.java", "package a;\npublic class Base {\n}\n");
        files.put("b/Base.java", "package b;\npublic class Base {\n}\n");
        files.put(
                "a/Child.java",
                "package a;\n"
                        + "public class Child extends Base {\n"
                        + "    String label() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("a/Child.java"), "a/Child.java", "label", "a.Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("a/Base.java"), json);
        assertFalse(json.contains("b/Base.java"), json);
    }

    @Test
    void pullUpRefusesUnqualifiedAmbiguousTarget(@TempDir Path tmp) throws IOException {
        // G010: an unqualified Base is ambiguous when two packages both declare Base.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a/Base.java", "package a;\npublic class Base {\n}\n");
        files.put("b/Base.java", "package b;\npublic class Base {\n}\n");
        files.put(
                "a/Child.java",
                "package a;\n"
                        + "public class Child extends Base {\n"
                        + "    String label() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("a/Child.java"), "a/Child.java", "label", "Base"), false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"ambiguous_type\""), json);
    }

    @Test
    void pullUpResolvesUnqualifiedTargetWhenUnambiguous(@TempDir Path tmp) throws IOException {
        // G010: an unqualified simple name still resolves when only one type bears it.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    String label() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "label", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("Base.java"), json);
    }

    @Test
    void refusesFieldMoveInSerializableTypeWithoutConfirmation(@TempDir Path tmp) throws IOException {
        // G011(a): moving a field within a Serializable hierarchy without confirmation refuses with a located code.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "import java.io.Serializable;\npublic class Base implements Serializable {\n}\n");
        files.put(
                "Child.java",
                "import java.io.Serializable;\n"
                        + "public class Child extends Base implements Serializable {\n"
                        + "    int count;\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "count", "Base"), false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"serialization_impact\""), json);
        assertTrue(json.contains("\"location\":{\"relativePath\":\"Child.java\""), json);
    }

    @Test
    void allowsFieldMoveInSerializableTypeWithConfirmation(@TempDir Path tmp) throws IOException {
        // G011(b): the same move proceeds when the serialization impact is explicitly confirmed.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "import java.io.Serializable;\npublic class Base implements Serializable {\n}\n");
        files.put(
                "Child.java",
                "import java.io.Serializable;\n"
                        + "public class Child extends Base implements Serializable {\n"
                        + "    int count;\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Child.java"), "Child.java", "count", "Base");
        fields.put("confirmSerializationImpact", true);
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("serialization_impact"), json);
    }

    @Test
    void allowsFieldMoveInNonSerializableType(@TempDir Path tmp) throws IOException {
        // G011(c): a non-Serializable hierarchy is unaffected by the serialization guard.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    int count;\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "count", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("serialization_impact"), json);
    }

    @Test
    void pullUpTreatsSiblingCompatibleOverrideAsImplementationNotCollision(@TempDir Path tmp) throws IOException {
        // G002 (Branch C / spec §8.2.5): a sibling that already declares the same-signature method is a compatible
        // implementation of the pulled-up declaration, not a collision. Pulling Child.label() up to Base as an abstract
        // declaration must be accepted even though Sibling already defines label().
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public abstract class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    String label() {\n"
                        + "        return \"child\";\n"
                        + "    }\n"
                        + "}\n");
        files.put(
                "Sibling.java",
                "public class Sibling extends Base {\n"
                        + "    String label() {\n"
                        + "        return \"sibling\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Child.java"), "Child.java", "label", "Base");
        fields.put("makeAbstract", true);
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("sibling_member_collision"), json);
    }

    @Test
    void pullUpSiblingFieldCollisionIsRefusedAndLocated(@TempDir Path tmp) throws IOException {
        // G003 (Case C): a sibling subtype declaring the same FIELD is a genuine clash (Java has no field overloading);
        // the refusal must be located with the moving field's source coordinates.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    int count;\n"
                        + "}\n");
        files.put(
                "Sibling.java",
                "public class Sibling extends Base {\n"
                        + "    int count;\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "count", "Base"), false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"sibling_member_collision\""), json);
        assertTrue(json.contains("\"location\":{\"relativePath\":\"Child.java\""), json);
    }

    @Test
    void pullUpTargetMemberExistsRefusalIsLocated(@TempDir Path tmp) throws IOException {
        // G003 (Case C): the supertype field collision refusal must carry the moving member's source location.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n    int count;\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    int count;\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "count", "Base"), false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"target_member_exists\""), json);
        assertTrue(json.contains("\"location\":{\"relativePath\":\"Child.java\""), json);
    }

    @Test
    void pullUpConcreteMethodLeaveDelegateRetainsForwardingOverride(@TempDir Path tmp) throws IOException {
        // G002 (Branch D): leave_delegate on a concrete pull-up keeps a forwarding @Override stub in the source.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    public String greet() {\n"
                        + "        return \"hello\";\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Child.java"), "Child.java", "greet", "Base");
        fields.put("leaveDelegate", true);
        fields.put("confirmPublicApi", true); // public member: G001 public-API gate requires confirmation.
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        // the concrete method is inserted into Base
        assertTrue(json.contains("Base.java"), json);
        // a forwarding delegate stub (not a removal) is left in the source
        assertTrue(json.contains("\"kind\":\"PULL_UP_DELEGATE\""), json);
        assertTrue(json.contains("@Override"), json);
        assertTrue(json.contains("super.greet()"), json);
    }

    @Test
    void pullUpLeaveDelegateForwardsParametersUsingResolvedNames(@TempDir Path tmp) throws IOException {
        // G002 (Branch D): the delegate forwards the parameter simple names (resolved from the element), preserving the
        // declared parameter text in the signature so generics/varargs are reproduced verbatim.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    public int sum(int a, int b) {\n"
                        + "        return a + b;\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Child.java"), "Child.java", "sum", "Base");
        fields.put("leaveDelegate", true);
        fields.put("confirmPublicApi", true); // public member: G001 public-API gate requires confirmation.
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("super.sum(a, b)"), json);
    }

    @Test
    void pullUpLeaveDelegateOnVoidMethodForwardsWithoutReturn(@TempDir Path tmp) throws IOException {
        // G002 (Branch D): a void concrete method delegate emits `super.x(args);` with no `return` prefix; locks the
        // isVoid branch of buildDelegateBody so a regression cannot start prepending `return` to a void forward.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    public void log(String message) {\n"
                        + "        System.out.println(message);\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Child.java"), "Child.java", "log", "Base");
        fields.put("leaveDelegate", true);
        fields.put("confirmPublicApi", true); // public member: G001 public-API gate requires confirmation.
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"kind\":\"PULL_UP_DELEGATE\""), json);
        assertTrue(json.contains("super.log(message);"), json);
        // The void forward must NOT be wrapped in a return.
        assertFalse(json.contains("return super.log"), json);
    }

    @Test
    void pullUpLeaveDelegateOnVarargsMethodReproducesVarargsAndForwardsBySimpleName(@TempDir Path tmp) throws IOException {
        // G002 (Branch D): a varargs concrete method delegate reproduces the `T...` parameter text in the retained
        // signature and forwards by the parameter's simple name (never re-spreading the array). Locks the varargs path.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    public String join(String... parts) {\n"
                        + "        return String.join(\",\", parts);\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Child.java"), "Child.java", "join", "Base");
        fields.put("leaveDelegate", true);
        fields.put("confirmPublicApi", true); // public member: G001 public-API gate requires confirmation.
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"kind\":\"PULL_UP_DELEGATE\""), json);
        // Varargs parameter text is reproduced verbatim in the retained signature...
        assertTrue(json.contains("String... parts"), json);
        // ...and the call forwards by the simple parameter name (return-valued, so wrapped in a return).
        assertTrue(json.contains("return super.join(parts);"), json);
    }

    @Test
    void pullUpRefusesFieldAssignedOutsideDeclaration(@TempDir Path tmp) throws IOException {
        // G003 (Case A): a field written outside its declaration (here in a method) refuses with a located code.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    int count;\n"
                        + "    void bump() {\n"
                        + "        this.count = count + 1;\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "count", "Base"), false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"assigned_outside_declaration\""), json);
        assertTrue(json.contains("\"location\":{\"relativePath\":\"Child.java\""), json);
    }

    @Test
    void pullUpAllowsFieldNeverAssignedOutsideDeclaration(@TempDir Path tmp) throws IOException {
        // G003 (Case A negative): a plain field with no writes anywhere is unaffected by the assignment guard.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    int count;\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "count", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("assigned_outside_declaration"), json);
    }

    @Test
    void pullUpRefusesFieldInitializerReferencingSubclassConstant(@TempDir Path tmp) throws IOException {
        // G003 (Case B): a constant whose initializer reads a subclass-only constant cannot move to the supertype.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "public class Child extends Base {\n"
                        + "    static final int CHILD_BASE = 10;\n"
                        + "    static final int VALUE = CHILD_BASE + 1;\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "VALUE", "Base"), false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"initializer_references_subclass\""), json);
        assertTrue(json.contains("\"location\":{\"relativePath\":\"Child.java\""), json);
    }

    @Test
    void pullUpAcceptsCovariantReturnSiblingOverride(@TempDir Path tmp) throws IOException {
        // G009: pulling Source.make():String up to Base as an abstract declaration is accepted even though Sibling
        // declares make():AnimalShelter where the returned type is a subtype (covariant). The resolver proves the
        // sibling remains a legal override before any edit is emitted.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public abstract class Base {\n}\n");
        files.put("Shelter.java", "public class Shelter {\n}\n");
        files.put("PetShelter.java", "public class PetShelter extends Shelter {\n}\n");
        files.put(
                "Source.java",
                "public class Source extends Base {\n"
                        + "    Shelter make() {\n"
                        + "        return new Shelter();\n"
                        + "    }\n"
                        + "}\n");
        files.put(
                "Sibling.java",
                "public class Sibling extends Base {\n"
                        + "    PetShelter make() {\n"
                        + "        return new PetShelter();\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Source.java"), "Source.java", "make", "Base");
        fields.put("makeAbstract", true);
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("incompatible_covariant_return"), json);
    }

    @Test
    void pullUpRefusesIncompatibleCovariantReturnSiblingOverride(@TempDir Path tmp) throws IOException {
        // G009: Source.make():String pulled up to Base would make Sibling.make():Object an illegal override (Object is
        // not a subtype of String). The structured resolver refuses BEFORE emitting edits rather than leaving it to a
        // later javac compile.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public abstract class Base {\n}\n");
        files.put(
                "Source.java",
                "public class Source extends Base {\n"
                        + "    String make() {\n"
                        + "        return \"s\";\n"
                        + "    }\n"
                        + "}\n");
        files.put(
                "Sibling.java",
                "public class Sibling extends Base {\n"
                        + "    Object make() {\n"
                        + "        return new Object();\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Source.java"), "Source.java", "make", "Base");
        fields.put("makeAbstract", true);
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"incompatible_covariant_return\""), json);
        assertTrue(json.contains("\"location\":{\"relativePath\":\"Source.java\""), json);
    }

    @Test
    void pullUpRefusesGenericSubstitutionMismatchSiblingOverride(@TempDir Path tmp) throws IOException {
        // G009: Source.accept(List<String>) and Sibling.accept(List<Integer>) share the erased signature accept(List)
        // but are conflicting parameterizations. Pulling Source.accept up to Base would override Sibling only by
        // erasure; the resolver refuses with a structured code before edits.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "import java.util.List;\npublic abstract class Base {\n}\n");
        files.put(
                "Source.java",
                "import java.util.List;\n"
                        + "public class Source extends Base {\n"
                        + "    void accept(List<String> values) {\n"
                        + "    }\n"
                        + "}\n");
        files.put(
                "Sibling.java",
                "import java.util.List;\n"
                        + "public class Sibling extends Base {\n"
                        + "    void accept(List<Integer> values) {\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Source.java"), "Source.java", "accept", "Base");
        fields.put("makeAbstract", true);
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"generic_substitution_mismatch\""), json);
    }

    @Test
    void pullUpRefusesSiblingOverrideThatNarrowsVisibility(@TempDir Path tmp) throws IOException {
        // G009: pulling Source.run() (public) up to Base makes Sibling.run() (protected) a visibility-narrowing override,
        // which JLS forbids. The resolver refuses structurally before emitting edits.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public abstract class Base {\n}\n");
        files.put(
                "Source.java",
                "public class Source extends Base {\n"
                        + "    public void run() {\n"
                        + "    }\n"
                        + "}\n");
        files.put(
                "Sibling.java",
                "public class Sibling extends Base {\n"
                        + "    protected void run() {\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pullFields(files.get("Source.java"), "Source.java", "run", "Base");
        fields.put("makeAbstract", true);
        fields.put("confirmPublicApi", true); // public member: G001 gate is satisfied so the override check is reached.
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pullUpMember(fields, false);

        assertTrue(json.contains("\"accepted\":false"), json);
        assertTrue(json.contains("\"code\":\"incompatible_override_visibility\""), json);
    }

    @Test
    void pullUpTransfersImportRequiredByMovedMember(@TempDir Path tmp) throws IOException {
        // G008/G009: a moved member referencing an imported type carries that single-type import into the target, added
        // through the central ImportManager. Base lacks the import; after the pull-up it must gain it.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "public class Base {\n}\n");
        files.put(
                "Child.java",
                "import java.util.List;\n"
                        + "public class Child extends Base {\n"
                        + "    List<String> labels() {\n"
                        + "        return java.util.Collections.emptyList();\n"
                        + "    }\n"
                        + "}\n");
        JavaProjectModel model = model(tmp, files);

        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model)
                .pullUpMember(pullFields(files.get("Child.java"), "Child.java", "labels", "Base"), false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"kind\":\"IMPORT_ADD\""), json);
        assertTrue(json.contains("import java.util.List;"), json);
    }

    @Test
    void pushDownAcceptsCovariantReturnWhenNoConflict(@TempDir Path tmp) throws IOException {
        // G009: pushing Base.label():String down into a subtype that inherits no conflicting same-signature override is
        // accepted; the resolver finds no incompatible inherited declaration.
        Map<String, String> files = new LinkedHashMap<>();
        files.put(
                "Base.java",
                "public class Base {\n"
                        + "    String label() {\n"
                        + "        return \"base\";\n"
                        + "    }\n"
                        + "}\n");
        files.put("Child.java", "public class Child extends Base {\n}\n");
        JavaProjectModel model = model(tmp, files);

        Map<String, Object> fields = pushFields(files.get("Base.java"), "Base.java", "label", List.of("Child"));
        String json = new PullPushMemberPlanner(tmp.toAbsolutePath().normalize(), model).pushDownMember(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("incompatible_covariant_return"), json);
    }

    /**
     * Extracts the {@code startOffset} of the edit with the given {@code kind} from the workspace-edit JSON. Locates the
     * {@code kind} marker first, then reads the {@code startOffset} of the edit object that encloses it, so a multi-edit
     * payload (e.g. a REMOVE edit preceding the INSERT) cannot be confused with the wanted edit.
     */
    private static long insertionOffset(String json, String kind) {
        int kindIndex = json.indexOf("\"kind\":\"" + kind + "\"");
        assertTrue(kindIndex >= 0, "no " + kind + " edit in " + json);
        int objectStart = json.lastIndexOf("{\"startOffset\":", kindIndex);
        assertTrue(objectStart >= 0, "malformed edit object for " + kind + " in " + json);
        Matcher matcher = Pattern.compile("\\{\"startOffset\":(\\d+)").matcher(json);
        assertTrue(matcher.find(objectStart), "could not read startOffset for " + kind + " in " + json);
        return Long.parseLong(matcher.group(1));
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

    /** Adds the G001 public-API confirmation flag to a fields map (for moves of public/protected members). */
    private static Map<String, Object> confirmPublicApi(Map<String, Object> fields) {
        fields.put("confirmPublicApi", true);
        return fields;
    }

    private static Map<String, Object> pushFields(String source, String relativePath, String memberName, List<String> targetTypes) {
        int[] pos = namePosition(source, memberName);
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", relativePath);
        fields.put("line", pos[0]);
        fields.put("column", pos[1]);
        fields.put("targetTypes", new ArrayList<>(targetTypes));
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
