package io.serena.javarefactor.v3.transformation;

import io.serena.javarefactor.protocol.Json;
import io.serena.javarefactor.protocol.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps the sidecar's {@code impact.facts} fact-sheet ({@link io.serena.javarefactor.compiler.ImpactFactsAnalyzer}) and
 * the composed workspace's javac before/after diagnostic delta into the authoritative five-section transformation impact
 * report (refactor-feature-plan-V3.md §17).
 *
 * <p>Every section is genuinely computed — there are no {@code computed:false} placeholders. A no-resource,
 * no-framework, no-test change yields zeroed counts (computed value {@code 0}), never an "uncomputed" marker. The five
 * sections are:
 * <ul>
 *   <li>{@code summary}: operation, risk, filesChanged, javaFilesMoved, resourceFilesChanged, newCompileErrors;</li>
 *   <li>{@code semanticImpact}: typesMoved, publicApisChanged, overridesAffected, callSitesChanged;</li>
 *   <li>{@code resourceImpact}: serviceLoaderFilesChanged, xmlFilesChanged, reflectionCandidatesNotChanged
 *       (review-only reflection candidates that are NEVER auto-edited);</li>
 *   <li>{@code tests}: suggestedTestCommands (per build model), likelyAffectedTests (test types that reference a
 *       touched type);</li>
 *   <li>{@code warnings}: the composed workspace's warnings.</li>
 * </ul>
 */
final class TransformationImpactReport {

    private TransformationImpactReport() {
    }

    /**
     * Builds the {@code report} object body (the value of the top-level {@code "report"} member) from the facts JSON the
     * analyzer produced, the workspace stats, the validated accepted JSON (source of the javac before/after delta), the
     * operation label and the workspace warnings.
     */
    @SuppressWarnings("unchecked")
    static String build(
            String factsJson,
            TransformationWorkspace.Stats stats,
            String validatedAcceptedJson,
            String operation,
            List<String> warnings) {
        return build(factsJson, stats, validatedAcceptedJson, operation, warnings, Set.of());
    }

    /**
     * As {@link #build(String, TransformationWorkspace.Stats, String, String, List)}, but with the unified
     * {@link io.serena.javarefactor.v3.graph.TestGraph}'s authoritative likely-affected test types merged into the
     * {@code tests.likelyAffectedTests} section. The report's caller resolves these from the cached transformation graph
     * (refactor-feature-plan-V3.md §1.2): a touched type's test referrers per the graph's test&rarr;production edges,
     * which are javac-resolved rather than approximated from the flat incoming-ref array alone.
     */
    static String build(
            String factsJson,
            TransformationWorkspace.Stats stats,
            String validatedAcceptedJson,
            String operation,
            List<String> warnings,
            Set<String> graphLikelyAffectedTests) {
        Map<String, Object> facts = Json.parseObject(factsJson);

        String risk = riskLevel(facts);
        int filesChanged = stats.javaFilesMoved() + stats.javaFilesEdited()
                + stats.resourceFilesEdited() + stats.buildFilesEdited();
        int newCompileErrors = newCompileErrorCount(validatedAcceptedJson);
        String summary = "{"
                + "\"operation\":" + JsonUtil.quote(operation) + ","
                + "\"risk\":" + JsonUtil.quote(risk) + ","
                + "\"filesChanged\":" + filesChanged + ","
                + "\"javaFilesMoved\":" + stats.javaFilesMoved() + ","
                + "\"resourceFilesChanged\":" + stats.resourceFilesEdited() + ","
                + "\"newCompileErrors\":" + newCompileErrors
                + "}";

        List<Object> touchedTypes = list(facts.get("touchedTypes"));
        int publicApisChanged = 0;
        for (Object entry : touchedTypes) {
            if (entry instanceof Map<?, ?> type && Boolean.TRUE.equals(type.get("publicApi"))) {
                publicApisChanged++;
            }
        }
        // typesMoved: a moved Java file relocates its declared top-level type(s); a rename file-op moves the type.
        int typesMoved = stats.javaFilesMoved();
        List<Object> incomingRefs = list(facts.get("incomingRefs"));
        int callSitesChanged = incomingRefs.size();
        // overridesAffected: incoming references whose referrer is itself a public-API member (an override/contract
        // participant the compiler resolved). The facts carry fromPublicApi per incoming ref.
        int overridesAffected = 0;
        for (Object entry : incomingRefs) {
            if (entry instanceof Map<?, ?> ref && Boolean.TRUE.equals(ref.get("fromPublicApi"))) {
                overridesAffected++;
            }
        }
        String semanticImpact = "{"
                + "\"typesMoved\":" + typesMoved + ","
                + "\"publicApisChanged\":" + publicApisChanged + ","
                + "\"overridesAffected\":" + overridesAffected + ","
                + "\"callSitesChanged\":" + callSitesChanged
                + "}";

        // serviceLoader / xml file counts are over the HIGH-confidence entries a rename/move would actually rewrite
        // (exactChangedEntries), counted by DISTINCT resource file; reflection candidates are review-only (never edited).
        List<Object> exactChanged = list(facts.get("exactChangedEntries"));
        Set<String> serviceLoaderFiles = new LinkedHashSet<>();
        Set<String> xmlFiles = new LinkedHashSet<>();
        for (Object entry : exactChanged) {
            if (!(entry instanceof Map<?, ?> ref)) {
                continue;
            }
            String kind = String.valueOf(ref.get("kind"));
            String provider = String.valueOf(ref.get("provider"));
            String resourcePath = String.valueOf(ref.get("resourcePath"));
            if ("SERVICE_LOADER_PROVIDER".equals(kind) || "service-loader".equals(provider)) {
                serviceLoaderFiles.add(resourcePath);
            } else if ("xml".equals(provider)
                    || "SPRING_BEAN_CLASS".equals(kind)
                    || resourcePath.endsWith(".xml")) {
                xmlFiles.add(resourcePath);
            }
        }
        int reflectionCandidatesNotChanged = list(facts.get("reflectionCandidates")).size();
        String resourceImpact = "{"
                + "\"serviceLoaderFilesChanged\":" + serviceLoaderFiles.size() + ","
                + "\"xmlFilesChanged\":" + xmlFiles.size() + ","
                + "\"reflectionCandidatesNotChanged\":" + reflectionCandidatesNotChanged
                + "}";

        List<String> suggestedTestCommands = stringList(facts.get("suggestedTestCommands"));
        // likelyAffectedTests: distinct owning test types that reference a touched type (test-source incoming refs).
        Set<String> likelyAffectedTests = new LinkedHashSet<>();
        for (Object entry : incomingRefs) {
            if (entry instanceof Map<?, ?> ref && Boolean.TRUE.equals(ref.get("fromTestSource"))) {
                Object fromFqn = ref.get("fromFqn");
                if (fromFqn != null && !String.valueOf(fromFqn).isBlank()) {
                    likelyAffectedTests.add(String.valueOf(fromFqn));
                }
            }
        }
        // Merge the unified transformation graph's javac-resolved test->production edges (when the caller supplied
        // them): the graph's TestGraph is the authoritative "which tests exercise this type" source, so the report's
        // likelyAffectedTests is graph-backed rather than relying on the flat incoming-ref array alone.
        for (String graphTest : graphLikelyAffectedTests) {
            if (graphTest != null && !graphTest.isBlank()) {
                likelyAffectedTests.add(graphTest);
            }
        }
        String tests = "{"
                + "\"suggestedTestCommands\":" + JsonUtil.array(suggestedTestCommands) + ","
                + "\"likelyAffectedTests\":" + JsonUtil.array(new ArrayList<>(likelyAffectedTests))
                + "}";

        return "{"
                + "\"summary\":" + summary + ","
                + "\"semanticImpact\":" + semanticImpact + ","
                + "\"resourceImpact\":" + resourceImpact + ","
                + "\"tests\":" + tests + ","
                + "\"warnings\":" + JsonUtil.array(warnings)
                + "}";
    }

    /** The risk level from the facts {@code risk.level} (HIGH/MEDIUM/LOW), defaulting to {@code LOW} if absent. */
    private static String riskLevel(Map<String, Object> facts) {
        Object risk = facts.get("risk");
        if (risk instanceof Map<?, ?> riskMap && riskMap.get("level") != null) {
            return String.valueOf(riskMap.get("level"));
        }
        return "LOW";
    }

    /**
     * The number of NEW javac errors the composed after-state introduced, read from the validated accepted JSON's
     * {@code diagnosticDelta.newErrors} array. This is the same authoritative before/after delta the sidecar already
     * compiled when the workspace was created (never a placeholder): an empty array yields {@code 0}.
     */
    @SuppressWarnings("unchecked")
    private static int newCompileErrorCount(String validatedAcceptedJson) {
        if (validatedAcceptedJson == null || validatedAcceptedJson.isBlank()) {
            return 0;
        }
        Map<String, Object> accepted = Json.parseObject(validatedAcceptedJson);
        if (accepted.get("diagnosticDelta") instanceof Map<?, ?> delta
                && delta.get("newErrors") instanceof List<?> newErrors) {
            return newErrors.size();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : list(value)) {
            result.add(String.valueOf(item));
        }
        return result;
    }
}
