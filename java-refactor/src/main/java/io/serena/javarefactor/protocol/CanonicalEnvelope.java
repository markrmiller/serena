package io.serena.javarefactor.protocol;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B13: augments an accepted V3 result envelope with a canonical, identically-shaped {@code impact} summary
 * (refactor-feature-plan-V3.md §1.1) and a {@code risk} classification (§14.3) — both computed IN THE SIDECAR from the
 * result's REAL workspace edit, never hand-rolled per planner.
 *
 * <p>Why this exists (hard blocker B13): the dedicated V3 ops disagreed on their success-envelope shape. Single-op
 * planners (extract class/superclass, delegation, conversions, deep inline, propagating safe-delete, recipe apply)
 * emitted {@code stats} as {@code {editCount, fileOperationCount, touchedFileCount, touchedFiles}}, while
 * {@code transformation.*} emitted {@code stats} as the §1.1 {@code {javaFilesMoved, javaFilesEdited, ...}} shape — the
 * SAME key carrying two different schemas — and NONE carried the §14.3 risk classification the plan's acceptance
 * (§"acceptance") requires on accepted results. Rather than touch all eleven planners (and the two that bypass
 * {@code ResponseBuilder} entirely), every dispatched V3 result is routed through {@link #augment(String)} once. It
 * derives the impact summary from the actual {@code workspaceEdit} (or a §1.1-shaped {@code stats} for the workspace
 * summary responses that carry no edit) and rolls the real plan facts up into {@code safe}/{@code needs_review}, so the
 * canonical fields cannot drift from the edit that was actually planned.
 */
public final class CanonicalEnvelope {
    private CanonicalEnvelope() {
    }

    /** The §1.1 impact summary, identical in shape for every accepted V3 op. */
    record Impact(
            int javaFilesMoved,
            int javaFilesEdited,
            int resourceFilesEdited,
            int buildFilesEdited,
            int textEdits,
            int fileOperations) {

        String json() {
            return "{\"javaFilesMoved\":" + javaFilesMoved
                    + ",\"javaFilesEdited\":" + javaFilesEdited
                    + ",\"resourceFilesEdited\":" + resourceFilesEdited
                    + ",\"buildFilesEdited\":" + buildFilesEdited
                    + ",\"textEdits\":" + textEdits
                    + ",\"fileOperations\":" + fileOperations + "}";
        }
    }

    /**
     * Augments an accepted read-only scan result (no {@code workspaceEdit}) with a canonical {@code risk} classification
     * of {@code informational} and an {@code impact} summary derived from the scan's own count fields.
     *
     * <p>Handles three scan operations:
     * <ul>
     *   <li>{@code findDeadCode}: derives impact from {@code stats.candidates/high/low}.</li>
     *   <li>{@code transformation.report}: derives impact from the {@code report.summary} §1.1 stats block.</li>
     *   <li>{@code scanMigrationOpportunities}: derives impact from {@code stats.matches/files}.</li>
     * </ul>
     *
     * <p>Refusals, non-objects, results already carrying both canonical fields, and results not matching any known
     * read-only operation are returned byte-for-byte unchanged.
     */
    public static String augmentReadOnly(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        Map<String, Object> root;
        try {
            root = Json.parseObject(json);
        } catch (RuntimeException e) {
            return json;
        }
        if (!(root.get("accepted") instanceof Boolean accepted) || !accepted) {
            return json;
        }
        boolean hasImpact = root.containsKey("impact");
        boolean hasRisk = root.containsKey("risk");
        if (hasImpact && hasRisk) {
            return json;
        }
        Impact impact = computeReadOnlyImpact(root);
        if (impact == null) {
            return json;
        }
        String trimmed = json.strip();
        if (!trimmed.endsWith("}")) {
            return json;
        }
        StringBuilder out = new StringBuilder(trimmed.substring(0, trimmed.length() - 1));
        if (!hasImpact) {
            out.append(",\"impact\":").append(impact.json());
        }
        if (!hasRisk) {
            String risk = classifyReadOnlyRisk(root);
            out.append(",\"risk\":").append(JsonUtil.quote(risk));
            out.append(",\"riskClassification\":").append(JsonUtil.quote(risk));
        }
        return out.append('}').toString();
    }

    /**
     * Derives a read-only §1.1-shaped impact from the scan's own count fields. Returns {@code null} when none of the
     * known scan shapes match (so the caller passes the JSON through unchanged).
     */
    private static Impact computeReadOnlyImpact(Map<String, Object> root) {
        String operation = stringValue(root.get("operation"));
        // findDeadCode: stats.{candidates, high, low}
        if ("findDeadCode".equals(operation)) {
            if (root.get("stats") instanceof Map<?, ?> stats) {
                int candidates = intValue(stats.get("candidates"));
                int high = intValue(stats.get("high"));
                int low = intValue(stats.get("low"));
                // Represent dead-code findings as: javaFilesEdited=high-confidence candidates, textEdits=total candidates,
                // resourceFilesEdited=low-confidence candidates; no moves, no fileOperations, no build files.
                return new Impact(0, high, low, 0, candidates, 0);
            }
        }
        // scanMigrationOpportunities: stats.{matches, files}
        if ("scanMigrationOpportunities".equals(operation)) {
            if (root.get("stats") instanceof Map<?, ?> stats) {
                int matches = intValue(stats.get("matches"));
                int files = intValue(stats.get("files"));
                // Represent recipe findings as: javaFilesEdited=files affected, textEdits=total matches; no moves/ops.
                return new Impact(0, files, 0, 0, matches, 0);
            }
        }
        // resources.findReferences / frameworks.findReferences: stats.{count,files}.
        if (("findResourceReferences".equals(operation) || "findFrameworkReferences".equals(operation))
                && root.get("stats") instanceof Map<?, ?> stats) {
            int count = intValue(stats.get("count"));
            int files = intValue(stats.get("files"));
            int resourceFiles = "findResourceReferences".equals(operation) ? files : 0;
            int javaFiles = "findFrameworkReferences".equals(operation) ? files : 0;
            return new Impact(0, javaFiles, resourceFiles, 0, count, 0);
        }
        // frameworks.detect / frameworks.participate / graph.build are read-only provenance reports; their impact is zero.
        if ("detectFrameworks".equals(operation) || "frameworks.participate".equals(operation)
                || "participateFrameworks".equals(operation) || "graph.build".equals(operation)) {
            return new Impact(0, 0, 0, 0, 0, 0);
        }
        // transformation.report: report.summary is a §1.1 stats block
        if (root.get("report") instanceof Map<?, ?> report
                && report.get("summary") instanceof Map<?, ?> summary
                && summary.containsKey("javaFilesMoved")) {
            return new Impact(
                    intValue(summary.get("javaFilesMoved")),
                    intValue(summary.get("javaFilesEdited")),
                    intValue(summary.get("resourceFilesEdited")),
                    intValue(summary.get("buildFilesEdited")),
                    intValue(summary.get("textEdits")),
                    intValue(summary.get("fileOperations")));
        }
        return null;
    }

    private static String classifyReadOnlyRisk(Map<String, Object> root) {
        boolean needsReview = !asList(root.get("warnings")).isEmpty()
                || !asList(root.get("structuredWarnings")).isEmpty()
                || isTrue(root.get("resourceScanIncomplete"));
        if (root.get("riskFacts") instanceof Map<?, ?> riskFacts
                && !asList(riskFacts.get("analysisIncomplete")).isEmpty()) {
            needsReview = true;
        }
        return needsReview ? "needs_review" : "informational";
    }

    /**
     * Returns {@code json} with {@code impact} and {@code risk} spliced in when it is an accepted result that carries a
     * summarizable edit. Refusals, non-objects, read-only results (no {@code workspaceEdit} and no §1.1 {@code stats}),
     * and results already carrying both canonical fields are returned byte-for-byte unchanged.
     */
    public static String augment(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        Map<String, Object> root;
        try {
            root = Json.parseObject(json);
        } catch (RuntimeException e) {
            return json;
        }
        if (!(root.get("accepted") instanceof Boolean accepted) || !accepted) {
            return json;
        }
        boolean hasImpact = root.containsKey("impact");
        boolean hasRisk = root.containsKey("risk");
        if (hasImpact && hasRisk) {
            return json;
        }
        Impact impact = computeImpact(root);
        if (impact == null) {
            return json;
        }
        String risk = classifyRisk(root, impact);
        String trimmed = json.strip();
        if (!trimmed.endsWith("}")) {
            return json;
        }
        StringBuilder out = new StringBuilder(trimmed.substring(0, trimmed.length() - 1));
        if (!hasImpact) {
            out.append(",\"impact\":").append(impact.json());
        }
        if (!hasRisk) {
            out.append(",\"risk\":").append(JsonUtil.quote(risk));
            out.append(",\"riskClassification\":").append(JsonUtil.quote(risk));
        }
        return out.append('}').toString();
    }

    /**
     * Derives the §1.1 impact from the actual edit. Prefers the {@code workspaceEdit} (changes + fileOperations); falls
     * back to a top-level {@code stats} that is already §1.1-shaped (the {@code transformation.createWorkspace} summary,
     * which carries no {@code workspaceEdit}). Returns null when neither is present (a read-only result).
     */
    private static Impact computeImpact(Map<String, Object> root) {
        if (root.get("workspaceEdit") instanceof Map<?, ?> workspaceEdit) {
            return impactFromWorkspaceEdit(workspaceEdit);
        }
        if (root.get("stats") instanceof Map<?, ?> stats && stats.containsKey("javaFilesMoved")) {
            return new Impact(
                    intValue(stats.get("javaFilesMoved")),
                    intValue(stats.get("javaFilesEdited")),
                    intValue(stats.get("resourceFilesEdited")),
                    intValue(stats.get("buildFilesEdited")),
                    intValue(stats.get("textEdits")),
                    intValue(stats.get("fileOperations")));
        }
        return null;
    }

    private static Impact impactFromWorkspaceEdit(Map<?, ?> workspaceEdit) {
        Set<String> javaEdited = new LinkedHashSet<>();
        Set<String> resourceEdited = new LinkedHashSet<>();
        Set<String> buildEdited = new LinkedHashSet<>();
        int textEdits = 0;
        for (Object change : asList(workspaceEdit.get("changes"))) {
            if (!(change instanceof Map<?, ?> changeMap)) {
                continue;
            }
            String path = stringValue(changeMap.get("path"));
            if (path == null) {
                continue;
            }
            textEdits += asList(changeMap.get("edits")).size();
            if (isBuildFile(path)) {
                buildEdited.add(path);
            } else if (isJava(path)) {
                javaEdited.add(path);
            } else {
                resourceEdited.add(path);
            }
        }
        List<Object> fileOperations = asList(workspaceEdit.get("fileOperations"));
        int javaFilesMoved = 0;
        for (Object op : fileOperations) {
            if (op instanceof Map<?, ?> opMap
                    && "rename".equals(stringValue(opMap.get("kind")))
                    && isJava(stringValue(opMap.get("oldPath")))) {
                javaFilesMoved++;
            }
        }
        return new Impact(javaFilesMoved, javaEdited.size(), resourceEdited.size(), buildEdited.size(),
                textEdits, fileOperations.size());
    }

    /**
     * Rolls the real plan facts up into the §14.3 / §24 classification ({@code refused} is the separate refusal envelope,
     * so an accepted result is only ever {@code safe} or {@code needs_review}). A result is {@code needs_review} when it
     * carries warnings, touches resource/build files, or its diagnostic delta was not javac-validated; otherwise it is
     * {@code safe}. A plain Java safe-delete is NOT inherently needs_review: the §24 safety table classifies neither type
     * nor file removal as review-required — a reachability-proven, javac-validated removal is {@code safe} (exactly like
     * the table's service-loader provider removal), and public-API removal is handled separately as a REFUSAL. Only a
     * resource/build touch (or an unvalidated/warned delta) on top of the delete escalates it to needs_review.
     *
     * <p>Blocker B6 (shared contract 1): an accepted planner result MAY additionally carry a top-level {@code riskFacts}
     * object with four reason arrays — {@code publicApiChanges} (public/protected API removed or changed without
     * compatibility), {@code frameworkBoundaryChanges} (framework-owned edits / boundary crossings), {@code heuristicEdits}
     * (non-compiler-proven resource/text edits), and {@code analysisIncomplete} (any incomplete analysis such as a
     * resource scan, graph enrichment, or service-loader probe). If ANY of those arrays is non-empty, the result is
     * escalated to {@code needs_review}. This is purely additive on top of every existing condition (warnings,
     * structuredWarnings, resource/build edits, {@code resourceScanIncomplete}, {@code workspaceEdit.warnings},
     * {@code diagnosticDeltaValidated==false}); a non-empty {@code analysisIncomplete} is equivalent to the existing
     * {@code resourceScanIncomplete:true} boolean. Planners that emit no {@code riskFacts} keep current behavior.
     */
    private static String classifyRisk(Map<String, Object> root, Impact impact) {
        boolean needsReview = !asList(root.get("warnings")).isEmpty()
                || !asList(root.get("structuredWarnings")).isEmpty()
                || impact.resourceFilesEdited() > 0
                || impact.buildFilesEdited() > 0
                // Story R06: an incomplete resource scan (an in-scope resource file was unreadable or over the size cap,
                // so its participation in this op could not be determined) is a hard at-least-needs_review escalation —
                // independent of whether a resource edit survived — so a resource-participating op can never classify
                // "safe" (and therefore can never be auto-applied as SAFE) on an incomplete scan.
                || isTrue(root.get("resourceScanIncomplete"));
        if (root.get("workspaceEdit") instanceof Map<?, ?> workspaceEdit
                && !asList(workspaceEdit.get("warnings")).isEmpty()) {
            needsReview = true;
        }
        if (root.get("diagnosticDeltaValidated") instanceof Boolean validated && !validated) {
            needsReview = true;
        }
        // B6 (shared contract 1): structured risk facts escalate to needs_review when any reason array is non-empty.
        // This catches API-changing or heuristic edits that carry no warning string and would otherwise classify "safe".
        if (root.get("riskFacts") instanceof Map<?, ?> riskFacts) {
            if (!asList(riskFacts.get("publicApiChanges")).isEmpty()
                    || !asList(riskFacts.get("frameworkBoundaryChanges")).isEmpty()
                    || !asList(riskFacts.get("heuristicEdits")).isEmpty()
                    || !asList(riskFacts.get("analysisIncomplete")).isEmpty()) {
                needsReview = true;
            }
        }
        return needsReview ? "needs_review" : "safe";
    }

    private static boolean isJava(String relative) {
        return relative != null && relative.endsWith(".java");
    }

    private static boolean isBuildFile(String relative) {
        if (relative == null) {
            return false;
        }
        String normalized = relative.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return name.equals("pom.xml") || name.startsWith("build.gradle");
    }

    private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static boolean isTrue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof String s && Boolean.parseBoolean(s);
    }
}
