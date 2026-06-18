package io.serena.javarefactor.v3.resources;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.protocol.JsonUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the resource-rewrite SPI op {@code resources.planEdits} (refactor-feature-plan-V3.md §15, "planEdits"
 * half). Given the moved-type/package maps for a rename or move, it runs the single {@link ResourcePlanner} and returns
 * the SAFE in-place resource edits and file renames as a JSON envelope — the same engine the package rename/move
 * planners drive internally, exposed directly so a caller can preview or apply resource rewrites without re-running a
 * full package operation.
 *
 * <p>Unlike {@code resources.findReferences} (which surfaces every reference, including low-confidence reflective
 * candidates, for review) this op plans only what is safe to rewrite automatically: exact fully-qualified class tokens
 * (HIGH) and, when enabled, bare package-prefix tokens (MEDIUM). It never edits reflective/free-text matches.</p>
 */
public final class ResourceEditPlanner {

    private final Path projectRoot;
    private final JavaProjectModel model;
    private final long maxFileBytes;

    public ResourceEditPlanner(Path projectRoot, JavaProjectModel model) {
        this(projectRoot, model, ResourcePlanner.NO_FILE_SIZE_CAP);
    }

    /**
     * @param maxFileBytes the resource max-file-size cap forwarded to the {@link ResourcePlanner}; a file over the cap is
     *     surfaced as an over-cap incompleteness signal (story R06) rather than silently skipped, so the plan it informs
     *     cannot auto-apply.
     */
    public ResourceEditPlanner(Path projectRoot, JavaProjectModel model, long maxFileBytes) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
        this.maxFileBytes = maxFileBytes;
    }

    public String planEdits(Map<String, Object> fields) {
        try {
            return planEditsChecked(fields);
        } catch (ResourceRefusal refusal) {
            return PlannerSupport.refusalJson("planResourceEdits", false, refusal.code(), refusal.getMessage());
        } catch (IOException e) {
            return PlannerSupport.refusalJson("planResourceEdits", false, "io_error", String.valueOf(e.getMessage()));
        }
    }

    private String planEditsChecked(Map<String, Object> fields) throws IOException {
        Map<String, String> typeFqnMap = stringMap(fields.get("typeFqnMap"));
        Map<String, String> packageMap = stringMap(fields.get("packageMap"));
        if (typeFqnMap.isEmpty() && packageMap.isEmpty()) {
            throw new ResourceRefusal("resource_rename_empty",
                    "At least one of 'typeFqnMap' or 'packageMap' must be a non-empty {oldName:newName} object.");
        }
        boolean rewriteExactClassNames = bool(fields, "rewriteExactClassNames", true);
        boolean rewritePackagePrefixes = bool(fields, "rewritePackagePrefixes", false);
        // §18.4 apply policy: HIGH always auto-applies; MEDIUM previews unless the caller opts in here; LOW never
        // auto-applies (and is surfaced review-only below). Default false keeps MEDIUM preview-only.
        boolean applyMediumConfidence = bool(fields, "applyMediumConfidence", false);
        ResourceRenameRequest request = new ResourceRenameRequest(
                typeFqnMap, packageMap, rewriteExactClassNames, rewritePackagePrefixes);

        ResourceScanScope scope = new ResourceScanScope(
                bool(fields, "scanXml", true),
                bool(fields, "scanProperties", true),
                bool(fields, "scanYaml", true),
                bool(fields, "scanJson", true),
                bool(fields, "scanServiceLoader", true));

        ResourcePlanner planner = new ResourcePlanner(projectRoot, model, maxFileBytes);
        ResourcePlanner.ResourcePlan plan = planner.plan(request, scope);
        List<ResourceReference> reviewOnly = lowConfidenceReferences(planner, typeFqnMap, packageMap);
        return envelope(plan, reviewOnly, applyMediumConfidence);
    }

    /**
     * The LOW-confidence (reflective/free-text) references to any moved type/package — §18.4's "never auto-apply LOW".
     * These are never planned as edits by any provider, so the apply path cannot touch them; they are returned in a
     * dedicated {@code reviewOnly} array so a caller knows a possible reference remains for human review. This deliberately
     * goes through {@link ResourcePlanner#reviewOnlyReferences} (a scope-independent walk) rather than the scope-gated
     * {@link ResourcePlanner#referencesTo}: LOW candidates come from the reflection fallback, which only claims files
     * outside the structured-scan extensions, so a scope filter would always exclude them.
     */
    private List<ResourceReference> lowConfidenceReferences(ResourcePlanner planner, Map<String, String> typeFqnMap,
            Map<String, String> packageMap) throws IOException {
        List<ResourceQuery> queries = new ArrayList<>();
        for (String oldFqn : typeFqnMap.keySet()) {
            queries.add(new ResourceQuery(oldFqn, false));
        }
        for (String oldPackage : packageMap.keySet()) {
            queries.add(new ResourceQuery(oldPackage, true));
        }
        return planner.reviewOnlyReferences(queries);
    }

    private String envelope(ResourcePlanner.ResourcePlan plan, List<ResourceReference> reviewOnly,
            boolean applyMediumConfidence) {
        // Story R06 gate: when the resource scan that informed this plan was incomplete (an in-scope file was unreadable
        // or exceeded the size cap, so we could not determine whether it references a moved symbol) NO edit may be
        // auto-applied — every edit drops to PREVIEW and the envelope is marked resourceScanIncomplete so the canonical
        // risk roll-up classifies the op needs_review rather than safe. The incomplete files are still surfaced.
        boolean scanIncomplete = !plan.completeness().isComplete();
        List<ResourceEdit> autoApply = new ArrayList<>();
        List<ResourceEdit> preview = new ArrayList<>();
        for (ResourceEdit edit : plan.edits()) {
            if (!scanIncomplete && ResourceApplyPolicy.autoApplies(edit.confidence(), applyMediumConfidence)) {
                autoApply.add(edit);
            } else {
                preview.add(edit);
            }
        }

        String editsJson = editsArray(plan.edits(), applyMediumConfidence, scanIncomplete);
        String autoApplyJson = editsArray(autoApply, applyMediumConfidence, scanIncomplete);
        String previewJson = editsArray(preview, applyMediumConfidence, scanIncomplete);

        StringBuilder renames = new StringBuilder("[");
        for (int i = 0; i < plan.fileRenames().size(); i++) {
            if (i > 0) {
                renames.append(",");
            }
            renames.append(renameJson(plan.fileRenames().get(i)));
        }
        renames.append("]");

        StringBuilder review = new StringBuilder("[");
        for (int i = 0; i < reviewOnly.size(); i++) {
            if (i > 0) {
                review.append(",");
            }
            review.append(referenceJson(reviewOnly.get(i)));
        }
        review.append("]");

        return "{"
                + "\"accepted\":true,"
                + "\"operation\":\"planResourceEdits\","
                + "\"applyMediumConfidence\":" + applyMediumConfidence + ","
                + "\"resourceScanIncomplete\":" + scanIncomplete + ","
                + "\"incompleteResources\":" + JsonUtil.array(plan.completeness().incompleteFiles()) + ","
                + "\"edits\":" + editsJson + ","
                + "\"autoApply\":" + autoApplyJson + ","
                + "\"preview\":" + previewJson + ","
                + "\"reviewOnly\":" + review + ","
                + "\"fileRenames\":" + renames + ","
                + "\"stats\":{\"edits\":" + plan.edits().size()
                + ",\"autoApply\":" + autoApply.size()
                + ",\"preview\":" + preview.size()
                + ",\"reviewOnly\":" + reviewOnly.size()
                + ",\"fileRenames\":" + plan.fileRenames().size() + "},"
                + "\"warnings\":" + PlannerSupport.warningsJson(plan.warnings())
                + "}";
    }

    private String editsArray(List<ResourceEdit> edits, boolean applyMediumConfidence, boolean scanIncomplete) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < edits.size(); i++) {
            if (i > 0) {
                out.append(",");
            }
            out.append(editJson(edits.get(i), applyMediumConfidence, scanIncomplete));
        }
        return out.append("]").toString();
    }

    private String editJson(ResourceEdit edit, boolean applyMediumConfidence, boolean scanIncomplete) {
        // An incomplete scan can never report AUTO_APPLY: a HIGH edit that would normally auto-apply is downgraded to
        // PREVIEW so the reported per-edit disposition matches the partition (no edit lands in autoApply) and the §18.4
        // "apply HIGH" contract is not used as an auto-apply green-light behind an incomplete scan.
        ResourceApplyPolicy.Disposition disposition = scanIncomplete
                ? ResourceApplyPolicy.Disposition.PREVIEW
                : ResourceApplyPolicy.dispositionFor(edit.confidence(), applyMediumConfidence);
        return "{"
                + "\"path\":" + JsonUtil.quote(PlannerSupport.relative(projectRoot, edit.file())) + ","
                + "\"startOffset\":" + edit.startOffset() + ","
                + "\"endOffset\":" + edit.endOffset() + ","
                + "\"newText\":" + JsonUtil.quote(edit.newText()) + ","
                + "\"kind\":" + JsonUtil.quote(edit.kind().name()) + ","
                + "\"confidence\":" + JsonUtil.quote(edit.confidence().name()) + ","
                + "\"disposition\":" + JsonUtil.quote(disposition.name()) + ","
                + "\"provider\":" + JsonUtil.quote(edit.providerId())
                + "}";
    }

    private String referenceJson(ResourceReference ref) {
        return "{"
                + "\"path\":" + JsonUtil.quote(PlannerSupport.relative(projectRoot, ref.file())) + ","
                + "\"startOffset\":" + ref.startOffset() + ","
                + "\"endOffset\":" + ref.endOffset() + ","
                + "\"oldText\":" + JsonUtil.quote(ref.oldText()) + ","
                + "\"kind\":" + JsonUtil.quote(ref.kind().name()) + ","
                + "\"confidence\":" + JsonUtil.quote(ref.confidence().name()) + ","
                + "\"disposition\":" + JsonUtil.quote(ResourceApplyPolicy.Disposition.REVIEW_ONLY.name()) + ","
                + "\"provider\":" + JsonUtil.quote(ref.providerId()) + ","
                + "\"target\":" + JsonUtil.quote(ref.target())
                + "}";
    }

    private String renameJson(ResourceFileRename rename) {
        return "{"
                + "\"from\":" + JsonUtil.quote(PlannerSupport.relative(projectRoot, rename.from())) + ","
                + "\"to\":" + JsonUtil.quote(PlannerSupport.relative(projectRoot, rename.to())) + ","
                + "\"provider\":" + JsonUtil.quote(rename.providerId()) + ","
                + "\"reason\":" + JsonUtil.quote(rename.reason())
                + "}";
    }

    /** Coerce a JSON object field into a {@code <String,String>} map, ignoring non-string entries. */
    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> object) {
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                    map.put(key, value);
                }
            }
        }
        return map;
    }

    private static boolean bool(Map<String, Object> fields, String key, boolean fallback) {
        Object value = fields.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }
}
