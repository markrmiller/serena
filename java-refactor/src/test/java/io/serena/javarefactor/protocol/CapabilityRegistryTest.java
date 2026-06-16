package io.serena.javarefactor.protocol;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G018 capability-registry truthfulness guard. The registry advertises every V1 stable op and every V2 op as ready
 * because all V2 hard-requirement blockers (G001-G017) have landed; this is the LEGITIMATE state. These tests are the
 * regression guard that ties "supported" to real readiness so the registry cannot silently rot:
 *
 * <ul>
 *   <li>{@code READY_OPERATIONS} must equal an explicit expected set (V1 stable + refactorSessions + every V2 op), so
 *       dropping or adding a ready op is a deliberate, test-visible change rather than a silent edit.</li>
 *   <li>Every {@code V2_OPERATIONS} entry must appear in {@code capabilitiesJson()} output, so a V2 op cannot be wired
 *       into dispatch without also being advertised in the registry Python reads.</li>
 *   <li>{@code capabilityStatus} precedence (disabled > preview > supported) must hold: a config-disabled V2 op reports
 *       "disabled", a not-ready op reports "preview", and a ready+enabled op reports "supported".</li>
 * </ul>
 *
 * <p>The registry methods are private on the package-private {@code Main}; this test drives them via reflection, exactly
 * the layer the initialize/capabilities responses call.</p>
 */
class CapabilityRegistryTest {

    // The complete, deliberate set of operations advertised "ready". V1 stable ops (always supported, not V2-gated),
    // the refactorSessions capability, and every V2 op (all blockers G001-G017 resolved). Any divergence from this set
    // -- a dropped op or a newly-added op -- must be reflected here, making registry changes test-visible.
    private static final Set<String> EXPECTED_READY_OPERATIONS = Set.of(
            // V1 stable
            "semanticRename", "safeDelete", "moveTopLevelType", "inlineLocalVariable", "inlineConstant",
            // session capability
            "refactorSessions",
            // stable alias of semanticRename advertised for V2 plan parity (G003)
            "rename",
            // V2 operations (every entry of V2_OPERATIONS)
            "inlineMethod", "changeSignature", "introduceParameter", "moveStaticMember", "moveInstanceMethod",
            "pullUpMember", "pushDownMember", "extractMethod", "extractInterface", "introduceField", "encapsulateField");

    @SuppressWarnings("unchecked")
    private static Set<String> readyOperations() throws Exception {
        Field field = Main.class.getDeclaredField("READY_OPERATIONS");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> v2Operations() throws Exception {
        Field field = Main.class.getDeclaredField("V2_OPERATIONS");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    private static Main mainWithConfig(String configJson) throws Exception {
        Constructor<Main> ctor = Main.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Main main = ctor.newInstance();
        Field configField = Main.class.getDeclaredField("configuration");
        configField.setAccessible(true);
        configField.set(main, configJson);
        return main;
    }

    private static String capabilitiesJson(Main main) throws Exception {
        Method method = Main.class.getDeclaredMethod("capabilitiesJson");
        method.setAccessible(true);
        return (String) method.invoke(main);
    }

    private static String capabilityDetailsJson(Main main) throws Exception {
        Method method = Main.class.getDeclaredMethod("capabilityDetailsJson");
        method.setAccessible(true);
        return (String) method.invoke(main);
    }

    private static String capabilityStatus(Main main, String operation) throws Exception {
        Method method = Main.class.getDeclaredMethod("capabilityStatus", String.class);
        method.setAccessible(true);
        return (String) method.invoke(main, operation);
    }

    private static boolean operationReady(String operation) throws Exception {
        Method method = Main.class.getDeclaredMethod("operationReady", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, operation);
    }

    /**
     * (a) READY_OPERATIONS is exactly the expected complete set. Pinning the set makes any add/drop a deliberate,
     * reviewed change: a sibling task that resolves a blocker flips its op's readiness AND updates this expectation,
     * so an op can never silently appear or vanish from "ready" without a failing test.
     */
    @Test
    void readyOperationsMatchExpectedCompleteSet() throws Exception {
        assertEquals(EXPECTED_READY_OPERATIONS, readyOperations(),
                "READY_OPERATIONS drifted from the deliberate expected set; update EXPECTED_READY_OPERATIONS only as a "
                        + "reviewed, intentional change so readiness cannot silently rot.");
    }

    /** All eleven V2 operations are members of READY_OPERATIONS: the V2 surface is fully ready (G001-G017 landed). */
    @Test
    void everyV2OperationIsReady() throws Exception {
        Set<String> ready = readyOperations();
        for (String operation : v2Operations()) {
            assertTrue(ready.contains(operation), () -> operation + " is a V2 operation but is not in READY_OPERATIONS");
        }
    }

    /**
     * (b) Every V2 operation appears in the capabilities registry. A V2 op wired into dispatch but missing from the
     * advertised registry would be invisible to Python (which only registers advertised ops), so this guards against an
     * op that is dispatchable yet not truthfully reported. The public {@code capabilities} map renders each op as a
     * string level ("operation":"level"); the sibling {@code capabilityDetails} renders the {level,status,description}
     * object ("operation":{...}). Both must advertise every V2 op.
     */
    @Test
    void everyV2OperationIsAdvertisedInCapabilitiesJson() throws Exception {
        String levels = capabilitiesJson(mainWithConfig("default"));
        String details = capabilityDetailsJson(mainWithConfig("default"));
        for (String operation : v2Operations()) {
            assertTrue(levels.contains("\"" + operation + "\":\""),
                    () -> operation + " is a V2 operation but is not advertised in capabilitiesJson(): " + levels);
            assertTrue(details.contains("\"" + operation + "\":{"),
                    () -> operation + " is a V2 operation but is not in capabilityDetailsJson(): " + details);
        }
    }

    /**
     * G007 (no false safe-beta surface): the advertised capability descriptions must not carry narrowing caveat words
     * ("conservative"/"experimental") that let an agent read a "supported" op as an incomplete subset. A "supported" op
     * either meets its full V2 contract (described precisely) or is not advertised as supported at all. This guard fails
     * if the caveat wording regresses into the registry.
     */
    @Test
    void capabilityDescriptionsCarryNoNarrowingCaveat() throws Exception {
        String json = capabilityDetailsJson(mainWithConfig("default")).toLowerCase(java.util.Locale.ROOT);
        for (String caveat : new String[] {"conservative", "experimental", "partial", "best-effort"}) {
            assertFalse(json.contains(caveat),
                    () -> "capability descriptions must not narrow a supported op with the caveat word '" + caveat
                            + "': " + json);
        }
    }

    /**
     * G004: the advertised descriptions must not promise sub-features that V2 hard-refuses. encapsulateField must not
     * advertise compound-assignment support, and extractMethod must not advertise multi-output or control-flow-exit
     * extraction. A regression that re-adds those promises (e.g. "including compound assignment") fails here, keeping
     * the registry truthful about the real, restricted V2 surface (G002/G003).
     */
    @Test
    void capabilityDescriptionsDoNotAdvertiseBlockedSubFeatures() throws Exception {
        String json = capabilityDetailsJson(mainWithConfig("default"));
        String encapsulate = capabilityDescription(json, "encapsulateField").toLowerCase(java.util.Locale.ROOT);
        assertFalse(encapsulate.contains("including compound assignment"),
                () -> "encapsulateField must not advertise compound-assignment support: " + encapsulate);
        // The only mention of "compound" allowed is an explicit refusal statement, never a supported-feature claim.
        assertTrue(!encapsulate.contains("compound") || encapsulate.contains("refus"),
                () -> "encapsulateField may only mention compound assignment as a refusal: " + encapsulate);

        String extract = capabilityDescription(json, "extractMethod").toLowerCase(java.util.Locale.ROOT);
        assertTrue(extract.contains("refus"),
                () -> "extractMethod description must state what it refuses (multi-output / control-flow): " + extract);
        // Any mention of multi-output / control-flow must be in the refusal clause, not advertised as supported.
        assertFalse(extract.contains("multi-output") && !extract.contains("refuses multi-output"),
                () -> "extractMethod must not advertise multi-output extraction as supported: " + extract);
    }

    /** Extracts the {@code description} string for an advertised operation from the capabilities JSON. */
    private static String capabilityDescription(String json, String operation) {
        String key = "\"" + operation + "\":{";
        int objStart = json.indexOf(key);
        if (objStart < 0) {
            return "";
        }
        String marker = "\"description\":\"";
        int descStart = json.indexOf(marker, objStart);
        if (descStart < 0) {
            return "";
        }
        descStart += marker.length();
        int descEnd = descStart;
        while (descEnd < json.length() && !(json.charAt(descEnd) == '"' && json.charAt(descEnd - 1) != '\\')) {
            descEnd++;
        }
        return json.substring(descStart, descEnd);
    }

    /**
     * (c) Precedence: a config-disabled V2 op reports "disabled" (highest precedence). Setting the global
     * {@code enabled:false} flag disables every V2 op regardless of its readiness, so changeSignature -- a ready op --
     * must still report "disabled" when configuration turns V2 off.
     */
    @Test
    void configDisabledV2OperationReportsDisabled() throws Exception {
        Main main = mainWithConfig("{\"enabled\":false}");
        assertEquals("disabled", capabilityStatus(main, "changeSignature"),
                "a ready V2 op must report 'disabled' when configuration turns V2 off (disabled > preview > supported)");
    }

    /**
     * (c) Precedence: a not-ready op reports "preview". A synthetic operation name that is not in READY_OPERATIONS is
     * not ready by definition, so its status must be "preview" -- this exercises the not-ready branch and documents the
     * contract that "supported" is never claimed for an op absent from the readiness registry.
     */
    @Test
    void notReadyOperationReportsPreview() throws Exception {
        assertFalse(operationReady("definitelyNotAReadyOp"),
                "a synthetic op absent from READY_OPERATIONS must not be ready");
        Main main = mainWithConfig("default");
        // Not a V2 op, so the disabled branch does not apply; not ready, so it falls through to "preview".
        assertEquals("preview", capabilityStatus(main, "definitelyNotAReadyOp"),
                "an op that is not ready must report 'preview', never 'supported'");
    }

    /**
     * (c) Precedence: a ready + enabled V2 op reports "supported". With default configuration (V2 enabled) every V2 op
     * is ready (G001-G017 landed), so each must truthfully advertise "supported" -- proving the all-supported registry
     * is the legitimate state, not an over-claim.
     */
    @Test
    void readyEnabledV2OperationsReportSupported() throws Exception {
        Main main = mainWithConfig("default");
        for (String operation : v2Operations()) {
            assertEquals("supported", capabilityStatus(main, operation),
                    () -> operation + " is ready and enabled but does not report 'supported'");
        }
    }

    /** A V1 stable op is always "supported": it is not V2-gated and is in the readiness registry. */
    @Test
    void v1StableOperationReportsSupported() throws Exception {
        Main main = mainWithConfig("{\"enabled\":false}");
        // Even with V2 disabled, V1 ops are unaffected (the disabled branch only applies to V2 ops).
        assertEquals("supported", capabilityStatus(main, "semanticRename"),
                "V1 stable ops must remain 'supported' regardless of the V2 enable flag");
    }
}
