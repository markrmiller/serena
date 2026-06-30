package io.serena.javarefactor.v3.packages;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder.FileOperation;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.ResourceRootModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.v3.resources.ResourceApplyPolicy;
import io.serena.javarefactor.v3.resources.ResourceConfidence;
import io.serena.javarefactor.v3.resources.ResourceEdit;
import io.serena.javarefactor.v3.resources.ResourceFileRename;
import io.serena.javarefactor.v3.resources.ResourcePlanner;
import io.serena.javarefactor.v3.resources.ResourceRenameRequest;
import io.serena.javarefactor.v3.resources.ResourceScanScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Adapts a package rename/move to the unified resource-reference engine (refactor-feature-plan-V3.md §5.5 / §15). The
 * compiler model tracks only Java references, so a package move leaves string-encoded type names in resources dangling;
 * the engine closes that gap for the SAFE, unambiguous cases and surfaces the ambiguous ones for review.
 *
 * <p>This class is a thin policy-to-SPI adapter: it translates the {@link PackageRewritePolicy} into a
 * {@link ResourceRenameRequest} + {@link ResourceScanScope}, runs the single {@link ResourcePlanner} (the same engine
 * behind the {@code resources.findReferences}/{@code resources.planEdits} protocol ops and safe-delete participation),
 * and maps the engine's results back into the package-layer edit/file-operation primitives. There is no second,
 * bespoke resource scanner — {@code planEdits} on each provider is the one source of resource rewrites.</p>
 *
 * <p><b>Exact class names (rewritten, {@link ResourceConfidence#HIGH}):</b> a maximal dotted token that equals a moved
 * type's old fully-qualified name ({@code com.old.Foo} → {@code com.new.Foo}). This covers CDI/Spring {@code class="…"}
 * attributes and {@code META-INF/services/*} provider implementation lines. Gated by
 * {@link PackageRewritePolicy#rewriteExactClassNames()} (on by default).
 *
 * <p><b>Standalone package prefixes (NOT rewritten by default, {@link ResourceConfidence#MEDIUM} when enabled):</b> a
 * bare moved-package token ({@code base-package="com.old"}). A bare package name in a resource is ambiguous — it may be
 * a scanning root that should follow the move, or an unrelated string — so it is left untouched unless
 * {@link PackageRewritePolicy#rewritePackagePrefixes()} is explicitly enabled, per §5.5.
 *
 * <p><b>ServiceLoader interface file rename:</b> a {@code META-INF/services/<interface-fqn>} registration encodes the
 * service interface FQN in its FILENAME. When that interface type is itself moved, the file is renamed
 * {@code META-INF/services/com.old.Spi} → {@code META-INF/services/com.new.Spi} (a {@link FileOperation}) in addition to
 * any provider-line content rewrites — §15.2.
 *
 * <p><b>Reflective constructions (NOT rewritten, surfaced as a warning):</b> a literal package-prefix fragment
 * {@code "com.old."} (the tell-tale of {@code Class.forName("com.old." + name)}) is reported as a reflection candidate
 * (when {@code report_reflection_candidates} is on) rather than half-rewritten. This is a package-move-specific caveat,
 * not a generic resource reference, so it stays in this adapter rather than the SPI.
 */
final class ResourceRewriter {

    private ResourceRewriter() {
    }

    /**
     * The resource text edits, file renames, and reflection caveats produced for a package rename/move, plus the story
     * R06 {@code scanIncomplete} gate: when true an in-scope resource file could not be examined (unreadable or over the
     * size cap), so the resource participation is incomplete — {@code edits} is then EMPTY (nothing is auto-applied) and
     * the incomplete files are surfaced as warnings, and the caller must classify the op needs_review (never safe).
     */
    record Result(List<PlannerSupport.TextEdit> edits, List<FileOperation> fileRenames, List<String> warnings,
            boolean scanIncomplete) {
    }

    /**
     * Scans the project's resource directories via the {@link ResourcePlanner} and, per {@link PackageRewritePolicy},
     * rewrites exact moved-FQN class tokens (HIGH confidence) and optionally standalone moved-package tokens (MEDIUM
     * confidence) in the enabled resource kinds, renames {@code META-INF/services/*} files whose service-interface FQN
     * moved, and reports reflective-string candidates. {@code typeFqcnMap} maps a moved type's old FQN → new FQN;
     * {@code packageMap} maps a moved package's old name → new name; {@code movedPackages} drives reflection-candidate
     * detection. Best-effort: an unreadable resource is skipped (and reported as a warning by the planner).
     */
    static Result rewrite(Path projectRoot, JavaProjectModel model, PackageRewritePolicy policy,
            Map<String, String> typeFqcnMap, Map<String, String> packageMap, Set<String> movedPackages)
            throws IOException {
        ResourceRenameRequest request = new ResourceRenameRequest(
                typeFqcnMap, packageMap, policy.rewriteExactClassNames(), policy.rewritePackagePrefixes());
        ResourceScanScope scope = new ResourceScanScope(
                policy.scanXml(), policy.scanProperties(), policy.scanYaml(), policy.scanJson(),
                policy.scanServiceLoader());
        ResourcePlanner.ResourcePlan plan = new ResourcePlanner(projectRoot, model, policy.maxResourceFileBytes()).plan(request, scope);

        // Story R06 gate: when the resource scan was incomplete (an in-scope file was unreadable, so we could not
        // determine whether it references a moved type) NO resource edit may be auto-applied — the package move's
        // resource participation is incomplete, so the safe HIGH/MEDIUM rewrites are withheld and surfaced for review
        // rather than written behind an incomplete scan. The caller propagates scanIncomplete so the op classifies
        // needs_review (never safe), and the specific incomplete files are surfaced as warnings below.
        boolean scanIncomplete = !plan.completeness().isComplete();

        // §18.4 apply policy, applied uniformly: only AUTO_APPLY-disposition edits are written. HIGH always auto-applies;
        // MEDIUM (package-prefix) auto-applies only when the caller enabled prefix rewriting (the same gate that lets a
        // MEDIUM edit be planned at all), so a MEDIUM edit is never written behind the user's back; LOW is never planned.
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        if (!scanIncomplete) {
            for (ResourceEdit edit : plan.edits()) {
                if (!ResourceApplyPolicy.autoApplies(edit.confidence(), policy.applyMediumConfidence())) {
                    continue;
                }
                edits.add(new PlannerSupport.TextEdit(edit.file(), edit.startOffset(), edit.endOffset(), edit.newText(),
                        "RESOURCE_REFERENCE:" + edit.confidence().name()));
            }
        }

        // Story R06 / shared contract 2: on an incomplete scan NOTHING resource-side may auto-apply — not the text edits
        // (suppressed above) and not the resource FILE renames (META-INF/services/* etc.). A file rename derived from a
        // scan that could not examine every in-scope resource is exactly as unsafe to auto-apply as a text edit from it,
        // so the renames are withheld and surfaced for review; scanIncomplete still propagates so the caller classifies
        // the op needs_review and the apply gate blocks SAFE auto-apply.
        List<FileOperation> fileRenames = new ArrayList<>();
        List<String> warnings = new ArrayList<>(plan.warnings());
        if (scanIncomplete) {
            warnings.add("Resource scan was incomplete (" + String.join(", ", plan.completeness().incompleteFiles())
                    + "); safe resource rewrites AND resource file renames for this package move were NOT auto-applied"
                    + " and must be reviewed.");
            for (ResourceFileRename rename : plan.fileRenames()) {
                warnings.add("ServiceLoader registration '" + PlannerSupport.relative(projectRoot, rename.from())
                        + "' names a moved service interface and WOULD be renamed to '"
                        + PlannerSupport.relative(projectRoot, rename.to())
                        + "' (§15.2), but the rename was withheld because the resource scan was incomplete; review it"
                        + " manually.");
            }
        } else {
            for (ResourceFileRename rename : plan.fileRenames()) {
                fileRenames.add(FileOperation.rename(
                        PlannerSupport.relative(projectRoot, rename.from()),
                        PlannerSupport.relative(projectRoot, rename.to()),
                        PlannerSupport.sha256(rename.from())));
                warnings.add("ServiceLoader registration '" + PlannerSupport.relative(projectRoot, rename.from())
                        + "' names a moved service interface and was renamed to '"
                        + PlannerSupport.relative(projectRoot, rename.to()) + "' (§15.2).");
            }
        }

        if (policy.reportReflectionCandidates()) {
            addReflectionWarnings(projectRoot, model, policy, movedPackages, warnings);
        }
        return new Result(edits, fileRenames, warnings, scanIncomplete);
    }

    /**
     * Reflection candidates: a literal package-prefix fragment {@code "<pkg>."} (a quoted package name ending in a dot,
     * the tell-tale of {@code Class.forName("<pkg>." + simpleName)}) is reported but never rewritten — the simple-name
     * suffix is computed at runtime, so a blind rewrite could be wrong. One warning per (file, package).
     */
    static List<String> reflectionCandidateWarnings(String fileLabel, String content, Set<String> movedPackages) {
        List<String> warnings = new ArrayList<>();
        for (String pkg : movedPackages) {
            if (content.contains("\"" + pkg + ".\"")) {
                warnings.add("Reflection candidate in '" + fileLabel + "': a dynamic class name is built from the prefix \""
                        + pkg + ".\"; this string was NOT rewritten and may need a manual update after the package move.");
            }
        }
        return warnings;
    }

    /** Walks the enabled resource kinds once to collect reflection-candidate warnings (best-effort). */
    private static void addReflectionWarnings(Path projectRoot, JavaProjectModel model, PackageRewritePolicy policy,
            Set<String> movedPackages, List<String> warnings) throws IOException {
        for (Path dir : resourceDirectories(model)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    if (!isScannable(file, policy)) {
                        continue;
                    }
                    String content;
                    try {
                        content = Files.readString(file);
                    } catch (IOException unreadable) {
                        continue;
                    }
                    warnings.addAll(reflectionCandidateWarnings(
                            PlannerSupport.relative(projectRoot, file), content, movedPackages));
                }
            }
        }
    }

    private static boolean isScannable(Path file, PackageRewritePolicy policy) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (policy.scanServiceLoader() && isUnderMetaInfServices(file)) {
            return true;
        }
        if (policy.scanXml() && name.endsWith(".xml")) {
            return true;
        }
        if (policy.scanProperties() && name.endsWith(".properties")) {
            return true;
        }
        if (policy.scanYaml() && (name.endsWith(".yml") || name.endsWith(".yaml"))) {
            return true;
        }
        return policy.scanJson() && name.endsWith(".json");
    }

    private static boolean isUnderMetaInfServices(Path file) {
        Path parent = file.getParent();
        return parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("services")
                && parent.getParent() != null && parent.getParent().getFileName() != null
                && parent.getParent().getFileName().toString().equals("META-INF");
    }

    /**
     * Resource roots, MODEL-FIRST (blocker B11). The authoritative {@link JavaProjectModel} is consulted first via
     * {@link ResourceRootModel#resourceRoots(JavaProjectModel)}; only when the model yields no resource roots does this
     * fall back to the filename convention (a source root named {@code resources}, or the {@code resources} sibling of a
     * {@code java} source root). NOTE: the current project model carries NO dedicated resource-root data — {@link SourceSet}
     * exposes only {@code sourceRoots()} (plus generated/output/classpath roots), so {@link ResourceRootModel} derives
     * resource roots from the model's configured source roots rather than ad-hoc filesystem probing, and the convention
     * remains a guarded fallback. Behavior is identical for the common {@code src/main/resources} layout.
     */
    private static Set<Path> resourceDirectories(JavaProjectModel model) {
        Set<Path> modelRoots = ResourceRootModel.resourceRoots(model);
        if (!modelRoots.isEmpty()) {
            return modelRoots;
        }
        return conventionResourceDirectories(model);
    }

    /** Fallback resource roots discovered purely by filename convention, used only when the model yields none. */
    private static Set<Path> conventionResourceDirectories(JavaProjectModel model) {
        Set<Path> dirs = new LinkedHashSet<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                Path normalized = root.toAbsolutePath().normalize();
                if (normalized.getFileName() != null && normalized.getFileName().toString().equals("resources")) {
                    addIfDirectory(dirs, normalized);
                }
                Path parent = normalized.getParent();
                if (parent != null) {
                    addIfDirectory(dirs, parent.resolve("resources"));
                }
            }
        }
        return dirs;
    }

    private static void addIfDirectory(Set<Path> dirs, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            dirs.add(normalized);
        }
    }
}
