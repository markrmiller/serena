package io.serena.javarefactor.v3.frameworks;

import io.serena.javarefactor.compiler.FrameworkAnnotationIndex;
import io.serena.javarefactor.compiler.FrameworkAnnotationIndex.AnnotationOccurrence;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.v3.resources.ResourceEdit;
import io.serena.javarefactor.v3.resources.ResourcePlanner;
import io.serena.javarefactor.v3.resources.ResourceRenameRequest;
import io.serena.javarefactor.v3.resources.ResourceScanScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Drives the transformation-participant half of the framework SPI (refactor-feature-plan-V3.md §16) on behalf of the V3
 * operation planners. It builds the project's compiler-resolved framework annotation facts once, asks every registered
 * {@link FrameworkPlugin} to {@link FrameworkPlugin#participate(SymbolChange, TransformationContext) participate} in a
 * pending {@link SymbolChange}, and merges their {@link FrameworkParticipation} contributions into a single result the
 * delete / rename / dead-code seams fold into their plans.
 *
 * <p>This is the single integration point the planners consult, so plugin output actually affects the plan (vetoes,
 * warnings, resource-edit descriptions, reachability roots) without any planner depending on a concrete framework.
 * Participation only ever makes an operation more conservative.
 */
public final class FrameworkParticipationCoordinator {

    private final FrameworkRegistry registry = new FrameworkRegistry();

    /** The merged result of asking every plugin to participate in a change. */
    public record Result(
            List<FrameworkParticipation.Block> blocks,
            List<String> warnings,
            List<FrameworkResourceEdit> resourceEdits,
            Set<String> roots) {

        public Result {
            blocks = List.copyOf(blocks);
            warnings = List.copyOf(warnings);
            resourceEdits = List.copyOf(resourceEdits);
            roots = Set.copyOf(roots);
        }

        public static Result empty() {
            return new Result(List.of(), List.of(), List.of(), Set.of());
        }

        /** The block reason for {@code symbol}, or {@code null} if no plugin vetoed deleting it. */
        public String blockReasonFor(String symbol) {
            for (FrameworkParticipation.Block block : blocks) {
                if (block.symbol().equals(symbol)) {
                    return block.reason();
                }
            }
            return null;
        }

        /**
         * The typed {@link #resourceEdits()} rendered as human-readable {@code frameworkBoundaryChange} reasons (shared
         * contract 1, {@code riskFacts.frameworkBoundaryChanges}). Each reason names the target resource, whether a
         * concrete edit was produced or manual review is required, and the framework's description — so a planner can
         * carry framework-owned edits into a structured risk fact rather than folding opaque strings into warnings.
         */
        public List<String> frameworkBoundaryChanges() {
            List<String> reasons = new ArrayList<>();
            for (FrameworkResourceEdit edit : resourceEdits) {
                String disposition = edit.manualReviewRequired()
                        ? "manual review required (no compiler/parse-verified edit could be produced)"
                        : "framework-proven edit";
                reasons.add("Framework-owned resource change in '" + edit.targetResource() + "' [" + edit.kind() + ", "
                        + disposition + "]: " + edit.description());
            }
            return reasons;
        }
    }

    /**
     * Builds the framework facts for {@code model} and asks every plugin to participate in {@code change}, returning the
     * merged contribution. Returns an {@link Result#empty() empty} result (never throws) when the project has no Java
     * sources to scan, so a framework-free or source-free project simply gets no framework participation.
     */
    public Result participate(JavaProjectModel model, SymbolChange change) throws IOException {
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();
        String seed = seedRelativePath(model);
        if (seed == null) {
            return Result.empty();
        }
        List<AnnotationOccurrence> annotations;
        try (SemanticIndex index = SemanticIndex.open(model, seed)) {
            annotations = new FrameworkAnnotationIndex(index).annotations();
        }
        TransformationContext context = new TransformationContext(projectRoot, annotations);

        List<FrameworkParticipation.Block> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<FrameworkResourceEdit> resourceEdits = new ArrayList<>();
        Set<String> roots = new LinkedHashSet<>();
        for (FrameworkPlugin plugin : registry.plugins()) {
            FrameworkParticipation participation = plugin.participate(change, context);
            if (participation == null || participation.isEmpty()) {
                continue;
            }
            blocks.addAll(participation.blocks());
            warnings.addAll(participation.warnings());
            resourceEdits.addAll(participation.resourceEdits());
            roots.addAll(participation.roots());
        }
        // B07: drive the §15 base resource SPI to turn the plugins' descriptor-level manual-review markers into CONCRETE,
        // parse-verified resource edits wherever the scanner can actually prove the span — Spring <bean class="…">, exact
        // dotted FQN tokens in XML/properties/YAML/JSON, and JPA persistence.xml/orm.xml <class>…</class> element text.
        // The plugins themselves cannot do this: TransformationContext intentionally carries no project model, so a plugin
        // has nothing to scan. The coordinator does hold the model, so it runs ResourcePlanner once for a type rename,
        // folds in every concrete edit, and drops the now-redundant manual-review marker for any descriptor kind it
        // proved. Genuinely unparseable/ambiguous constructs (string bean names, @ComponentScan base packages,
        // JPQL/@NamedQuery strings, Jackson wire names) are emitted by the plugins as WARNINGS — never as resource edits —
        // so they stay review-required and are untouched by the fold.
        resourceEdits = foldConcreteResourceEdits(model, projectRoot, change, resourceEdits);
        return new Result(blocks, warnings, resourceEdits, roots);
    }

    /**
     * For a {@link SymbolChange.Kind#RENAME_TYPE} change, scans the project's resource files via the §15
     * {@link ResourcePlanner} and converts every concrete {@link ResourceEdit} into a concrete
     * {@link FrameworkResourceEdit} (one carrying a real {@link PlannerSupport.TextEdit}). Any plugin-emitted
     * {@link FrameworkResourceEdit#manualReviewRequired() manual-review} marker whose {@link FrameworkResourceEdit.Kind}
     * is now covered by a concrete edit is dropped, so a descriptor the scanner could prove is no longer reported as
     * manual-review-required. A non-rename change, an absent/identical old-or-new name, or a scan failure leaves
     * {@code pluginEdits} unchanged — participation only ever makes an operation more conservative; it never silently
     * drops a marker it could not prove.
     */
    private static List<FrameworkResourceEdit> foldConcreteResourceEdits(JavaProjectModel model, Path projectRoot,
            SymbolChange change, List<FrameworkResourceEdit> pluginEdits) {
        if (change.kind() != SymbolChange.Kind.RENAME_TYPE
                || change.targetFqn() == null || change.targetFqn().isBlank()
                || change.newName() == null || change.newName().isBlank()
                || change.targetFqn().equals(change.newName())) {
            return pluginEdits;
        }
        List<ResourceEdit> concrete;
        try {
            ResourceRenameRequest request = new ResourceRenameRequest(
                    Map.of(change.targetFqn(), change.newName()), Map.of(), true, false);
            concrete = new ResourcePlanner(projectRoot, model)
                    .plan(request, ResourceScanScope.all()).edits();
        } catch (IOException | RuntimeException scanFailure) {
            // A scan failure must not lose the conservative manual-review markers the plugins already produced.
            return pluginEdits;
        }
        if (concrete.isEmpty()) {
            return pluginEdits;
        }
        List<FrameworkResourceEdit> folded = new ArrayList<>();
        Set<FrameworkResourceEdit.Kind> provenKinds = new LinkedHashSet<>();
        for (ResourceEdit edit : concrete) {
            FrameworkResourceEdit.Kind kind = frameworkKindFor(edit);
            provenKinds.add(kind);
            String relative = PlannerSupport.relative(projectRoot, edit.file());
            PlannerSupport.TextEdit textEdit = new PlannerSupport.TextEdit(
                    edit.file(), edit.startOffset(), edit.endOffset(), edit.newText(),
                    "FRAMEWORK_RESOURCE:" + edit.confidence().name());
            folded.add(new FrameworkResourceEdit(relative, kind,
                    "Rewrite exact class name '" + change.targetFqn() + "' → '" + change.newName()
                            + "' in resource '" + relative + "' (parse-verified, " + edit.confidence() + ").",
                    textEdit));
        }
        // Keep every plugin marker EXCEPT a manual-review marker whose kind the scanner just proved with a concrete edit.
        for (FrameworkResourceEdit pluginEdit : pluginEdits) {
            if (pluginEdit.manualReviewRequired() && provenKinds.contains(pluginEdit.kind())) {
                continue;
            }
            folded.add(pluginEdit);
        }
        return folded;
    }

    /**
     * Maps a concrete §15 {@link ResourceEdit} to the framework-level {@link FrameworkResourceEdit.Kind} for the fold. A
     * type-rename's token rewrites are all {@link io.serena.javarefactor.v3.resources.ResourceReferenceKind#EXACT_CLASS_NAME}
     * at the §15 layer, so the framework kind is decided by the descriptor the edit lands in: an edit in a JPA persistence/
     * ORM mapping descriptor is a {@link FrameworkResourceEdit.Kind#METADATA_MAPPING} (matching the JPA plugin's marker),
     * any other proven dotted-token rewrite (Spring {@code <bean class>}, generic config tokens) is an
     * {@link FrameworkResourceEdit.Kind#EXACT_CLASS_NAME} (matching the Spring plugin's marker).
     */
    private static FrameworkResourceEdit.Kind frameworkKindFor(ResourceEdit edit) {
        return isJpaMappingDescriptor(edit.file()) ? FrameworkResourceEdit.Kind.METADATA_MAPPING
                : FrameworkResourceEdit.Kind.EXACT_CLASS_NAME;
    }

    /** Whether {@code file} is a JPA persistence/ORM mapping descriptor whose {@code <class>} edits are METADATA_MAPPING. */
    private static boolean isJpaMappingDescriptor(Path file) {
        if (file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("persistence.xml") || name.equals("orm.xml") || name.endsWith("-orm.xml");
    }

    private static String seedRelativePath(JavaProjectModel model) {
        Path projectRoot = model.projectRoot().toAbsolutePath().normalize();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path file : sourceSet.javaFiles()) {
                Path absolute = file.toAbsolutePath().normalize();
                if (absolute.startsWith(projectRoot)) {
                    return projectRoot.relativize(absolute).toString();
                }
            }
        }
        return null;
    }
}
