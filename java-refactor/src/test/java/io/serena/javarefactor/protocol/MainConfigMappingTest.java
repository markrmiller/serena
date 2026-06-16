package io.serena.javarefactor.protocol;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G001: config-key parity between the Python {@code java_refactor.v2} schema and the sidecar's per-operation request
 * fields. These tests exercise {@code Main.applyConfiguredDefaults}/{@code operationConfig} directly (via reflection,
 * since the methods are private on the package-private {@code Main}) to prove each config block is mapped onto the
 * request fields the planners read. No project model or jar is required — the mapping is pure config plumbing.
 */
class MainConfigMappingTest {

    private Main mainWithConfig(String configJson) throws Exception {
        Constructor<Main> ctor = Main.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Main main = ctor.newInstance();
        Field configField = Main.class.getDeclaredField("configuration");
        configField.setAccessible(true);
        configField.set(main, configJson);
        return main;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyDefaults(Main main, String operation, Map<String, Object> fields) throws Exception {
        Method method = Main.class.getDeclaredMethod("applyConfiguredDefaults", String.class, Map.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(main, operation, fields);
    }

    private Map<?, ?> operationConfig(String configJson, String operation) throws Exception {
        Main main = mainWithConfig(configJson);
        Method effective = Main.class.getDeclaredMethod("effectiveConfigurationMap");
        effective.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) effective.invoke(main);
        Method opConfig = Main.class.getDeclaredMethod("operationConfig", Map.class, String.class);
        opConfig.setAccessible(true);
        return (Map<?, ?>) opConfig.invoke(null, config, operation);
    }

    @Test
    void changeSignatureAllowPublicApiChangeMapsToConfirmPublicApi() throws Exception {
        Main main = mainWithConfig("{\"change_signature\":{\"allow_public_api_change\":true}}");
        Map<String, Object> effective = applyDefaults(main, "changeSignature", new LinkedHashMap<>());
        assertEquals(Boolean.TRUE, effective.get("confirmPublicApi"));
        assertEquals(Boolean.TRUE, effective.get("confirmPublicApiChange"));
    }

    @Test
    void changeSignatureAllowRemovedSideEffectingArgumentsIsMapped() throws Exception {
        Main main = mainWithConfig("{\"change_signature\":{\"allow_removed_side_effecting_arguments\":true}}");
        Map<String, Object> effective = applyDefaults(main, "changeSignature", new LinkedHashMap<>());
        assertEquals(Boolean.TRUE, effective.get("allowRemovedSideEffectingArguments"));
    }

    @Test
    void perRequestConfirmPublicApiIsNotOverwrittenByConfig() throws Exception {
        // copyDefault is absent-only: an explicit request value wins over the config default.
        Main main = mainWithConfig("{\"change_signature\":{\"allow_public_api_change\":true}}");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("confirmPublicApi", Boolean.FALSE);
        Map<String, Object> effective = applyDefaults(main, "changeSignature", fields);
        assertEquals(Boolean.FALSE, effective.get("confirmPublicApi"));
    }

    @Test
    void hierarchyAllowPublicApiChangeMapsToConfirmPublicApiForPullAndPush() throws Exception {
        Main main = mainWithConfig("{\"hierarchy\":{\"allow_public_api_change\":true}}");
        Map<String, Object> pull = applyDefaults(main, "pullUpMember", new LinkedHashMap<>());
        assertEquals(Boolean.TRUE, pull.get("confirmPublicApi"));
        assertEquals(Boolean.TRUE, pull.get("confirmPublicApiChange"));
        Map<String, Object> push = applyDefaults(main, "pushDownMember", new LinkedHashMap<>());
        assertEquals(Boolean.TRUE, push.get("confirmPublicApi"));
        assertEquals(Boolean.TRUE, push.get("confirmPublicApiChange"));
    }

    @Test
    void hierarchyWithoutConfirmationLeavesConfirmPublicApiUnset() throws Exception {
        Main main = mainWithConfig("{\"hierarchy\":{}}");
        Map<String, Object> pull = applyDefaults(main, "pullUpMember", new LinkedHashMap<>());
        assertNull(pull.get("confirmPublicApi"));
        assertNull(pull.get("confirmPublicApiChange"));
    }

    @Test
    void extractInterfaceReplaceUsagesDefaultIsHonored() throws Exception {
        Main main = mainWithConfig("{\"extract_interface\":{\"replace_usages_default\":true}}");
        Map<String, Object> effective = applyDefaults(main, "extractInterface", new LinkedHashMap<>());
        assertEquals(Boolean.TRUE, effective.get("replaceUsages"));
    }

    @Test
    void encapsulateFieldRewriteInternalUsagesDefaultIsMapped() throws Exception {
        Main main = mainWithConfig("{\"encapsulate_field\":{\"rewrite_internal_usages_default\":true}}");
        Map<String, Object> effective = applyDefaults(main, "encapsulateField", new LinkedHashMap<>());
        assertEquals(Boolean.TRUE, effective.get("rewriteInternalUsages"));
    }

    @Test
    void encapsulateFieldRewriteInternalUsagesAbsentLeavesFieldUnsetSoPlannerDefaultsFalse() throws Exception {
        // When the config does not set rewrite_internal_usages_default the request field stays unset; the planner reads
        // it with a false default (G001), so internal usages are left as direct field access.
        Main main = mainWithConfig("{\"encapsulate_field\":{}}");
        Map<String, Object> effective = applyDefaults(main, "encapsulateField", new LinkedHashMap<>());
        assertNull(effective.get("rewriteInternalUsages"));
    }

    @Test
    void inlineMethodMaxCallSitesIsMapped() throws Exception {
        Main main = mainWithConfig("{\"inline_method\":{\"max_call_sites\":5}}");
        Map<String, Object> effective = applyDefaults(main, "inlineMethod", new LinkedHashMap<>());
        assertEquals(5, ((Number) effective.get("maxCallSites")).intValue());
    }

    @Test
    void introduceFieldOperationConfigResolves() throws Exception {
        Map<?, ?> resolved = operationConfig("{\"introduce_field\":{\"enabled\":false}}", "introduceField");
        assertFalse((Boolean) resolved.get("enabled"));
    }

    @Test
    void introduceFieldCamelCaseSpellingResolves() throws Exception {
        Map<?, ?> resolved = operationConfig("{\"introduceField\":{\"enabled\":false}}", "introduceField");
        assertFalse((Boolean) resolved.get("enabled"));
    }

    @Test
    void formattingKeyIsRecognizedNotWarnedAsUnknown() throws Exception {
        // A recognized top-level v2 key (introduce_field/formatting) must resolve through expandNestedV2Config without
        // tripping the unknown-key path. We assert the effective config retains the formatting block verbatim.
        Main main = mainWithConfig("{\"formatting\":{\"use_external_formatter\":true,\"command\":\"gjf\"}}");
        Method effective = Main.class.getDeclaredMethod("effectiveConfigurationMap");
        effective.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) effective.invoke(main);
        Map<?, ?> formatting = (Map<?, ?>) config.get("formatting");
        assertTrue((Boolean) formatting.get("use_external_formatter"));
        assertEquals("gjf", formatting.get("command"));
    }

    @Test
    void inlineMethodDeleteInlinedMethodDefaultMapsToDeleteMethod() throws Exception {
        // G001 miswire fix: the planner reads `deleteMethod`, so delete_inlined_method_default must land there (not on
        // the never-read deleteInlinedMethod).
        Main main = mainWithConfig("{\"inline_method\":{\"delete_inlined_method_default\":true}}");
        Map<String, Object> effective = applyDefaults(main, "inlineMethod", new LinkedHashMap<>());
        assertEquals(Boolean.TRUE, effective.get("deleteMethod"));
        assertNull(effective.get("deleteInlinedMethod"));
    }

    @Test
    void moveInstanceMethodAllowAccessWideningIsMapped() throws Exception {
        // G001: instance moves can require widening too; move_member.allow_access_widening must reach allowAccessWidening.
        Main main = mainWithConfig("{\"move_member\":{\"allow_access_widening\":true}}");
        Map<String, Object> effective = applyDefaults(main, "moveInstanceMethod", new LinkedHashMap<>());
        assertEquals(Boolean.TRUE, effective.get("allowAccessWidening"));
    }

    @Test
    void moveInstanceMethodRewriteCallSitesDefaultIsMapped() throws Exception {
        Main main = mainWithConfig("{\"move_member\":{\"rewrite_call_sites_default\":false}}");
        Map<String, Object> effective = applyDefaults(main, "moveInstanceMethod", new LinkedHashMap<>());
        assertEquals(Boolean.FALSE, effective.get("rewriteCallSites"));
    }

    @Test
    void moveInstanceMethodLeaveDelegateDefaultMapsToKeepDelegate() throws Exception {
        // The planner reads keepDelegate; move_member.leave_delegate_default is the documented default that feeds it.
        Main main = mainWithConfig("{\"move_member\":{\"leave_delegate_default\":false}}");
        Map<String, Object> effective = applyDefaults(main, "moveInstanceMethod", new LinkedHashMap<>());
        assertEquals(Boolean.FALSE, effective.get("keepDelegate"));
        assertEquals(Boolean.FALSE, effective.get("leaveDelegate"));
    }

    @Test
    void moveStaticMemberAllowAccessWideningIsMapped() throws Exception {
        Main main = mainWithConfig("{\"move_member\":{\"allow_access_widening\":true}}");
        Map<String, Object> effective = applyDefaults(main, "moveStaticMember", new LinkedHashMap<>());
        assertEquals(Boolean.TRUE, effective.get("allowAccessWidening"));
    }

    @Test
    void perRequestDeleteMethodWinsOverConfigDefault() throws Exception {
        // copyDefault is absent-only: an explicit per-request deleteMethod is not overwritten by the config default.
        Main main = mainWithConfig("{\"inline_method\":{\"delete_inlined_method_default\":true}}");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("deleteMethod", Boolean.FALSE);
        Map<String, Object> effective = applyDefaults(main, "inlineMethod", fields);
        assertEquals(Boolean.FALSE, effective.get("deleteMethod"));
    }

    @Test
    void nestedIntroduceFieldEnabledFlagIsSurfacedAndGatesOperation() throws Exception {
        // G002: a nested java_refactor.v2.introduce_field block must be copied up so operationConfig/operationEnabled
        // can see its enabled flag (previously it was a known-but-dropped key).
        Main main = mainWithConfig("{\"java_refactor\":{\"v2\":{\"introduce_field\":{\"enabled\":false}}}}");
        Map<?, ?> resolved = operationConfig(
                "{\"java_refactor\":{\"v2\":{\"introduce_field\":{\"enabled\":false}}}}", "introduceField");
        assertFalse((Boolean) resolved.get("enabled"));
        Method opEnabled = Main.class.getDeclaredMethod("operationEnabled", String.class);
        opEnabled.setAccessible(true);
        assertFalse((Boolean) opEnabled.invoke(main, "introduceField"),
                "nested introduce_field.enabled:false must disable the introduceField operation");
    }

    // ── G002/G003: config cannot re-enable the V2 hard gates ─────────────────────────────────────────

    @Test
    void extractMethodConfigCannotEnableMultipleOutputsOrControlFlow() throws Exception {
        // G003: even with an explicit config trying to enable them, allow_multiple_outputs / allow_control_flow_exits
        // are NOT mapped onto the planner's request fields (the OPERATION_DEFAULT_RULES entry for extractMethod is
        // empty), so config can never re-enable the blocked extraction paths.
        Main main = mainWithConfig(
                "{\"extract_method\":{\"allow_multiple_outputs\":true,\"allow_control_flow_exits\":true}}");
        Map<String, Object> effective = applyDefaults(main, "extractMethod", new LinkedHashMap<>());
        assertFalse(effective.containsKey("allowMultipleOutputs"),
                "config must not inject allowMultipleOutputs into extractMethod fields: " + effective);
        assertFalse(effective.containsKey("allow_multiple_outputs"), effective.toString());
        assertFalse(effective.containsKey("allowControlFlowExits"),
                "config must not inject allowControlFlowExits into extractMethod fields: " + effective);
        assertFalse(effective.containsKey("allow_control_flow_exits"), effective.toString());
    }

    @Test
    void encapsulateFieldConfigCannotDisableCompoundRefusal() throws Exception {
        // G002: a config trying to set refuse_compound_assignments=false is NOT mapped onto the planner's request
        // fields (the encapsulateField rule no longer includes refuseCompoundAssignments), so config can never opt out
        // of the compound/increment refusal.
        Main main = mainWithConfig("{\"encapsulate_field\":{\"refuse_compound_assignments\":false}}");
        Map<String, Object> effective = applyDefaults(main, "encapsulateField", new LinkedHashMap<>());
        assertFalse(effective.containsKey("refuseCompoundAssignments"),
                "config must not inject refuseCompoundAssignments into encapsulateField fields: " + effective);
        assertFalse(effective.containsKey("refuse_compound_assignments"), effective.toString());
    }
}
