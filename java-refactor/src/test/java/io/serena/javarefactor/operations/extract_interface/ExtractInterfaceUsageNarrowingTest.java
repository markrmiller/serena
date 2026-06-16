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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the V2 extract-interface usage-narrowing contract:
 * <ul>
 *   <li>G024 — an API-visible usage-narrowing change is REFUSED without an explicit confirmation flag, rather than
 *       emitted with only a warning;</li>
 *   <li>G025 — with confirmation, the top-level reported touched-file set (derived via {@code ResponseBuilder}) includes
 *       the usage-replacement candidate declaration files edited by narrowing, not just the source + interface files;</li>
 *   <li>G026 — blocking unsafe usages are reported as a structured list of EVERY site at once, instead of throwing on
 *       the first one.</li>
 * </ul>
 */
class ExtractInterfaceUsageNarrowingTest {

    @Test
    void refusesApiVisibleFieldNarrowingWithoutConfirmation(@TempDir Path tmp) throws IOException {
        JavaProjectModel model = apiVisibleNarrowingProject(tmp);
        Map<String, Object> fields = baseFields();
        fields.put("replaceUsages", true);
        // No confirmPublicApiChange flag.

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"public_api_confirmation_required\""), json);
        // The refusal lists the affected API-visible reference site so the caller can review it before confirming.
        assertTrue(json.contains("\"sites\":["), json);
        assertTrue(json.contains("UseInterfaceSource.java"), json);
    }

    @Test
    void directApplyOfApiVisibleNarrowingRefusalReportsApplyModeAndNotApplied(@TempDir Path tmp) throws IOException {
        // G002: a direct apply=true that is refused for an unconfirmed API-visible narrowing must route through the
        // canonical refusal envelope — reporting the ACTUAL requested mode "apply" (not a hard-coded "preview") and
        // applied:false — while still surfacing the structured refusal.sites. The previous hand-rolled shape hard-coded
        // mode:"preview".
        JavaProjectModel model = apiVisibleNarrowingProject(tmp);
        Map<String, Object> fields = baseFields();
        fields.put("replaceUsages", true);

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, true);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"mode\":\"apply\""), json);
        assertTrue(json.contains("\"applied\":false"), json);
        assertFalse(json.contains("\"applied\":true"), json);
        assertTrue(json.contains("\"code\":\"public_api_confirmation_required\""), json);
        assertTrue(json.contains("\"sites\":["), json);
    }

    @Test
    void localVariableNarrowingDoesNotRequireConfirmation(@TempDir Path tmp) throws IOException {
        // A local variable's declared type is internal to one method body — not part of any API surface — so narrowing
        // it is accepted without a confirmation flag.
        JavaProjectModel model = localNarrowingProject(tmp);
        Map<String, Object> fields = baseFields();
        fields.put("replaceUsages", true);

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("EXTRACT_INTERFACE_USAGE"), json);
    }

    @Test
    void doesNotNarrowUsagesWhenReplaceUsagesDefaultIsOff(@TempDir Path tmp) throws IOException {
        // replace_usages_default is wired into the `replaceUsages` field by Main; when that default is off (the field is
        // absent), the planner extracts the interface but emits no usage-narrowing edit even though a safe API-visible
        // candidate exists. This confirms the planner honors the default rather than always narrowing.
        JavaProjectModel model = apiVisibleNarrowingProject(tmp);
        Map<String, Object> fields = baseFields();
        // No replaceUsages flag.

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertFalse(json.contains("EXTRACT_INTERFACE_USAGE"), json);
        // Only the source + created interface are touched (the usage declaration file is NOT narrowed/touched).
        assertTrue(json.contains("\"touchedFileCount\":2"), json);
        assertFalse(json.contains("UseInterfaceSource.java"), json);
    }

    @Test
    void parameterOfPublicMethodIsApiVisibleAndRequiresConfirmation(@TempDir Path tmp) throws IOException {
        // A parameter of a non-private (public) method is part of that method's API surface, so narrowing it requires
        // explicit confirmation — proving the gate fires on a PARAMETER kind via semantic classification, not nesting.
        JavaProjectModel model = parameterNarrowingProject(tmp, "public");
        Map<String, Object> fields = baseFields();
        fields.put("replaceUsages", true);

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"public_api_confirmation_required\""), json);
        assertTrue(json.contains("API-visible parameter declaration"), json);
    }

    @Test
    void parameterOfPrivateMethodIsInternalAndNarrowsWithoutConfirmation(@TempDir Path tmp) throws IOException {
        // A parameter of a private method cannot be observed by any caller, so its declared type is internal: narrowing
        // it proceeds without confirmation. The lexical heuristic could not see the enclosing method's visibility.
        JavaProjectModel model = parameterNarrowingProject(tmp, "private");
        Map<String, Object> fields = baseFields();
        fields.put("replaceUsages", true);

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("EXTRACT_INTERFACE_USAGE"), json);
    }

    @Test
    void confirmedApiVisibleNarrowingReportsUsageFileInTouchedFiles(@TempDir Path tmp) throws IOException {
        JavaProjectModel model = apiVisibleNarrowingProject(tmp);
        Map<String, Object> fields = baseFields();
        fields.put("replaceUsages", true);
        fields.put("confirmPublicApiChange", true);

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("EXTRACT_INTERFACE_USAGE"), json);
        // G025: source + created interface + usage declaration file are all reported as touched (derived from the edit).
        assertTrue(json.contains("\"touchedFileCount\":3"), json);
        assertTrue(json.contains("\"touchedFiles\":["), json);
        assertTrue(json.contains("src/UseInterfaceSource.java"), json);
    }

    @Test
    void reportsEveryBlockingUnsafeUsageSiteAtOnce(@TempDir Path tmp) throws IOException {
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
                + "        InterfaceSource a = new InterfaceSource();\n"
                + "        InterfaceSource b = new InterfaceSource();\n"
                + "        Object x = (Object) a;\n" // unsafe (cast) use of a
                + "        return (Object) b;\n"     // unsafe (cast) use of b
                + "    }\n"
                + "}\n", StandardCharsets.UTF_8);

        JavaProjectModel model = model(tmp, sourceRoot, List.of(source, usage));
        Map<String, Object> fields = baseFields();
        fields.put("replaceUsages", true);
        fields.put("confirmPublicApiChange", true);

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"unsafe_usage_replacement\""), json);
        // Every blocking site is collected before refusing (two cast uses), not just the first.
        assertTrue(json.contains("hide 2 non-interface use(s)"), json);
        assertTrue(json.contains("\"sites\":["), json);
    }

    // ── G016 narrowing safety matrix ────────────────────────────────────────────

    @Test
    void narrowsWhenAssignableAndEveryLaterCallIsAnInterfaceMember(@TempDir Path tmp) throws IOException {
        // Matrix case 1: the initializer is assignable to the extracted interface (the variable is the concrete type,
        // which will implement the interface) AND every later member call on the variable is an extracted interface
        // member, so the local declaration is narrowed.
        Path sourceRoot = tmp.resolve("src");
        Files.createDirectories(sourceRoot);
        Path source = sourceRoot.resolve("InterfaceSource.java");
        Files.writeString(source, ""
                + "public class InterfaceSource {\n"
                + "    public String value() { return \"v\"; }\n"
                + "    public int size() { return 0; }\n"
                + "}\n", StandardCharsets.UTF_8);
        Path usage = sourceRoot.resolve("UseInterfaceSource.java");
        Files.writeString(usage, ""
                + "public class UseInterfaceSource {\n"
                + "    public String call() {\n"
                + "        InterfaceSource source = new InterfaceSource();\n"
                + "        return source.value();\n" // only the extracted method is called => safe
                + "    }\n"
                + "}\n", StandardCharsets.UTF_8);

        JavaProjectModel model = model(tmp, sourceRoot, List.of(source, usage));
        Map<String, Object> fields = baseFields();
        // Extract BOTH methods so 'value' is an interface member; 'size' is extracted too but not called here.
        fields.put("members", List.of("value", "size"));
        fields.put("replaceUsages", true);

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertTrue(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("EXTRACT_INTERFACE_USAGE"), json);
    }

    @Test
    void refusesNarrowingWhenALaterCallTargetsANonInterfaceMember(@TempDir Path tmp) throws IOException {
        // Matrix case 2: the variable also calls a method that is NOT one of the extracted interface members, so
        // narrowing would hide that call. The planner must refuse and name the hidden non-extracted method.
        Path sourceRoot = tmp.resolve("src");
        Files.createDirectories(sourceRoot);
        Path source = sourceRoot.resolve("InterfaceSource.java");
        Files.writeString(source, ""
                + "public class InterfaceSource {\n"
                + "    public String value() { return \"v\"; }\n"
                + "    public int onlyOnConcrete() { return 7; }\n" // not extracted
                + "}\n", StandardCharsets.UTF_8);
        Path usage = sourceRoot.resolve("UseInterfaceSource.java");
        Files.writeString(usage, ""
                + "public class UseInterfaceSource {\n"
                + "    public String call() {\n"
                + "        InterfaceSource source = new InterfaceSource();\n"
                + "        source.onlyOnConcrete();\n" // call to a method not on the interface => blocks narrowing
                + "        return source.value();\n"
                + "    }\n"
                + "}\n", StandardCharsets.UTF_8);

        JavaProjectModel model = model(tmp, sourceRoot, List.of(source, usage));
        Map<String, Object> fields = baseFields();
        fields.put("members", List.of("value")); // only 'value' is extracted; onlyOnConcrete stays concrete-only
        fields.put("replaceUsages", true);

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"unsafe_usage_replacement\""), json);
        assertTrue(json.contains("would hide call to non-extracted method"), json);
        assertTrue(json.contains("onlyOnConcrete"), json);
    }

    @Test
    void refusesNarrowingWhenVariableFlowsIntoReflectionOrSerializationContext(@TempDir Path tmp) throws IOException {
        // Matrix case 3 (reflection/serialization-shaped flow): the variable is passed as a bare argument (here to a
        // reflection/identity sink) rather than used only as a method receiver. That bare reference is a non-method use,
        // so narrowing to the interface would change the static type seen by the sink and must be refused.
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
                + "    public Class<?> call() {\n"
                + "        InterfaceSource source = new InterfaceSource();\n"
                + "        source.value();\n"
                + "        return source.getClass();\n" // getClass() is java.lang.Object reflection, on the variable
                + "    }\n"
                + "}\n", StandardCharsets.UTF_8);

        JavaProjectModel model = model(tmp, sourceRoot, List.of(source, usage));
        Map<String, Object> fields = baseFields();
        fields.put("members", List.of("value"));
        fields.put("replaceUsages", true);

        String json = new ExtractInterfacePlanner(tmp.toAbsolutePath().normalize(), model).extractInterface(fields, false);

        // getClass() resolves to Object's method, which is not an extracted interface member, so narrowing is refused.
        assertFalse(json.contains("\"accepted\":true"), json);
        assertTrue(json.contains("\"code\":\"unsafe_usage_replacement\""), json);
    }

    private static Map<String, Object> baseFields() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("relativePath", "src/InterfaceSource.java");
        fields.put("interfaceName", "ExtractedValue");
        fields.put("members", List.of("value"));
        return fields;
    }

    /** A project whose usage declares an API-visible FIELD of the concrete type, used only via the extracted method. */
    private static JavaProjectModel apiVisibleNarrowingProject(Path tmp) throws IOException {
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
                + "    public InterfaceSource handle = new InterfaceSource();\n" // API-visible field
                + "    public String call() {\n"
                + "        return handle.value();\n" // only calls the extracted method => safe to narrow
                + "    }\n"
                + "}\n", StandardCharsets.UTF_8);
        return model(tmp, sourceRoot, List.of(source, usage));
    }

    /**
     * A project whose usage declares the concrete type as a PARAMETER of a method with the given visibility, using it
     * only via the extracted method so narrowing is safe. The enclosing method's visibility decides whether the
     * parameter is part of the API surface.
     */
    private static JavaProjectModel parameterNarrowingProject(Path tmp, String visibility) throws IOException {
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
                + "    " + visibility + " String call(InterfaceSource source) {\n" // parameter of given visibility
                + "        return source.value();\n" // only the extracted method is called => safe to narrow
                + "    }\n"
                + "}\n", StandardCharsets.UTF_8);
        return model(tmp, sourceRoot, List.of(source, usage));
    }

    /** A project whose usage declares a LOCAL variable of the concrete type, used only via the extracted method. */
    private static JavaProjectModel localNarrowingProject(Path tmp) throws IOException {
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
                + "    public String call() {\n"
                + "        InterfaceSource source = new InterfaceSource();\n" // internal local
                + "        return source.value();\n"
                + "    }\n"
                + "}\n", StandardCharsets.UTF_8);
        return model(tmp, sourceRoot, List.of(source, usage));
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
