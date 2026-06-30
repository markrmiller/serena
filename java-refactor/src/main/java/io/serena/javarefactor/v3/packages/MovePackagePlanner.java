package io.serena.javarefactor.v3.packages;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.v3.frameworks.FrameworkParticipationCoordinator;
import io.serena.javarefactor.v3.frameworks.SymbolChange;
import io.serena.javarefactor.edits.ResponseBuilder.FileOperation;
import io.serena.javarefactor.project.GeneratedSourcePolicy;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.shared.SourceText;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V3 planner that owns the <em>movePackage</em> operation (headline plan §1.1/§4.1, the {@code movePackageDirectory}
 * workspace step / {@code JavaMovePackageTool}).
 *
 * <p>Moves a Java package — and, by default, ALL of its subpackages — from {@code sourcePackage} to
 * {@code targetPackage} as a javac-validated, preview-first workspace edit. Unlike {@link RenamePackagePlanner} (which
 * renames a single package directly under the same source root), this planner:
 * <ul>
 *   <li>relocates the source package AND every {@code sourcePackage.*} subpackage (a true directory move), unless
 *       {@code includeSubpackages=false} is requested, and</li>
 *   <li>optionally lands the moved files under a DIFFERENT configured source root ({@code targetSourceRoot}), so a
 *       package can migrate between source sets (e.g. {@code src/main/java} → {@code src/main/java9}).</li>
 * </ul>
 * For each moved file it rewrites the {@code package} declaration (swapping the {@code sourcePackage} prefix for
 * {@code targetPackage}) and emits a rename {@link FileOperation}; across ALL project sources it rewrites references to
 * the moved package tree (single-type/on-demand/static imports and fully-qualified references). The rewriting is
 * token-boundary correct, so a sibling such as {@code sourcePackage}{@code application} is never corrupted, and a
 * subpackage reference {@code sourcePackage.sub.Type} is migrated to {@code targetPackage.sub.Type} by replacing only
 * the matched prefix.
 *
 * <p>The planner carries the not-yet-validated placeholder diagnostic delta; the authoritative before/after javac delta
 * is produced by the sidecar's {@code PreviewDiagnosticValidator} after the plan is built (the same contract every
 * V2/V3 operation uses), so an over-broad prefix rewrite or a cross-root collision is caught and refused rather than
 * silently applied.
 */
public final class MovePackagePlanner {
    /** A dotted Java package identifier, used to validate the {@code sourcePackage}/{@code targetPackage} inputs. */
    private static final Pattern DOTTED_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private final Path projectRoot;
    private final JavaProjectModel model;
    private final PackageRewritePolicy policy;
    /** Framework SPI participant (refactor-feature-plan-V3.md §16): contributes resource-edit descriptions + warnings. */
    private final FrameworkParticipationCoordinator frameworkParticipation = new FrameworkParticipationCoordinator();

    public MovePackagePlanner(Path projectRoot, JavaProjectModel model) {
        this(projectRoot, model, PackageRewritePolicy.defaults());
    }

    public MovePackagePlanner(Path projectRoot, JavaProjectModel model, PackageRewritePolicy policy) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
        this.policy = policy;
    }

    /** A planned moved file: its current/new project-relative paths and the package-declaration edit. */
    private record MovedFile(Path file, String declaredPackage, String relativeOld, String relativeNew,
                             PlannerSupport.TextEdit packageEdit, Set<String> simpleTypeNames) {
    }

    /**
     * Plans the package move. {@code fields} must carry {@code sourcePackage} and {@code targetPackage}; optional
     * {@code includeSubpackages} (default {@code true}) and {@code targetSourceRoot} (a project-relative configured
     * source root). {@code apply} only affects the reported {@code mode}; the sidecar never writes files for V2/V3 ops.
     */
    public String plan(Map<String, Object> fields, boolean apply) {
        try {
            Planned planned = planInternal(fields);
            io.serena.javarefactor.v3.transformation.TransformationStep step = planned.step();
            String json = ResponseBuilder.acceptedResult(
                    projectRoot,
                    "movePackage",
                    apply,
                    step.semanticTargetJson(),
                    step.edits(),
                    step.fileOperations(),
                    step.warnings(),
                    List.of("movePackage moves every type declared in the package tree and rewrites references across "
                            + "the project; the after-state is javac-validated before the preview is accepted."),
                    ResponseBuilder.DiagnosticDelta.unvalidated(),
                    false);
            // B2 (shared contract 2): surface an incomplete resource scan as the additive top-level
            // resourceScanIncomplete boolean so CanonicalEnvelope.classifyRisk escalates the op to needs_review and the
            // Python apply gate blocks SAFE auto-apply. Additive splice (mirroring CanonicalEnvelope.augment): a complete
            // scan leaves the result byte-identical to the prior shape.
            return spliceRiskFields(json, planned.resourceScanIncomplete(), planned.frameworkBoundaryChanges());
        } catch (Refusal refusal) {
            return PlannerSupport.refusalJson("movePackage", apply, refusal.code, refusal.getMessage());
        } catch (IOException error) {
            return PlannerSupport.refusalJson("movePackage", apply, "move_package_failed", error.getMessage());
        }
    }

    /**
     * The standalone {@code plan(...)} result plus the shared-contract-2 resource-scan completeness fact the canonical
     * envelope escalates on. The composition path ({@link #planStep}) has no structured resourceScanIncomplete channel,
     * so it folds an equivalent review escalation into step warnings instead.
     */
    private record Planned(io.serena.javarefactor.v3.transformation.TransformationStep step,
            boolean resourceScanIncomplete, List<String> frameworkBoundaryChanges) {
    }

    /**
     * Splices the additive {@code resourceScanIncomplete:true} field into an accepted-result JSON object immediately
     * before its closing brace (B2; the same additive technique {@code CanonicalEnvelope.augment} uses). Returns the JSON
     * unchanged when the scan was complete.
     */
    private static String spliceRiskFields(String json, boolean resourceScanIncomplete,
            List<String> frameworkBoundaryChanges) {
        if (!resourceScanIncomplete && frameworkBoundaryChanges.isEmpty()) {
            return json;
        }
        String trimmed = json.strip();
        if (!trimmed.endsWith("}")) {
            return json;
        }
        StringBuilder out = new StringBuilder(trimmed.substring(0, trimmed.length() - 1));
        if (resourceScanIncomplete) {
            out.append(",\"resourceScanIncomplete\":true");
        }
        if (!frameworkBoundaryChanges.isEmpty()) {
            out.append(",\"riskFacts\":{\"frameworkBoundaryChanges\":")
                    .append(PlannerSupport.warningsJson(frameworkBoundaryChanges)).append('}');
        }
        return out.append('}').toString();
    }

    /**
     * Builds the structured edit/op contribution for this operation (refactor-feature-plan-V3.md §3) so a transformation
     * workspace can compose it with other operations. Throws {@link Refusal} on a precondition violation and
     * {@link IOException} on a read failure — the caller maps those to the canonical refusal JSON.
     */
    public io.serena.javarefactor.v3.transformation.TransformationStep planStep(Map<String, Object> fields) throws IOException {
        // Composition path (refactor-feature-plan-V3.md §3): the EditComposer propagates only a step's `warnings` to the
        // composed top-level workspaceEdit, which CanonicalEnvelope.classifyRisk escalates on. There is no structured
        // resourceScanIncomplete channel through composition, so the same B2 escalation the standalone path emits as the
        // top-level resourceScanIncomplete boolean is surfaced HERE as a step warning — an equivalent needs_review
        // escalation (the pattern ExtractClassPlanner.planStep uses for its riskFact).
        Planned planned = planInternal(fields);
        io.serena.javarefactor.v3.transformation.TransformationStep step = planned.step();
        if (!planned.frameworkBoundaryChanges().isEmpty() || planned.resourceScanIncomplete()) {
            List<String> warnings = new ArrayList<>(step.warnings());
            warnings.addAll(planned.frameworkBoundaryChanges());
            if (planned.resourceScanIncomplete()) {
                warnings.add("movePackage resource scan was incomplete; resource-side changes were withheld and this step "
                    + "requires review (resourceScanIncomplete).");
            }
            return new io.serena.javarefactor.v3.transformation.TransformationStep(
                    step.operation(), step.edits(), step.fileOperations(), warnings, step.semanticTargetJson());
        }
        return step;
    }

    /**
     * Builds the structured edit/op contribution plus the shared-contract-2 resource-scan completeness fact. Throws
     * {@link Refusal} on a precondition violation and {@link IOException} on a read failure — the caller maps those to
     * the canonical refusal JSON.
     */
    private Planned planInternal(Map<String, Object> fields) throws IOException {
        {
            String sourcePackage = required(fields, "sourcePackage");
            String targetPackage = required(fields, "targetPackage");
            if (!DOTTED_NAME.matcher(sourcePackage).matches() || !DOTTED_NAME.matcher(targetPackage).matches()) {
                throw new Refusal("malformed_move_package",
                        "movePackage requires dotted Java package names for sourcePackage and targetPackage.");
            }
            boolean includeSubpackages = boolValue(fields, "includeSubpackages", true);
            Path targetSourceRoot = resolveTargetSourceRoot(fields);
            if (sourcePackage.equals(targetPackage) && targetSourceRoot == null) {
                throw new Refusal("malformed_move_package",
                        "sourcePackage and targetPackage must differ unless a different targetSourceRoot is given.");
            }

            List<Path> allFiles = allJavaFiles();
            Map<Path, String> packageByFile = new LinkedHashMap<>();
            Map<Path, String> sourceByFile = new LinkedHashMap<>();
            for (Path file : allFiles) {
                String source = SourceText.read(model, file);
                sourceByFile.put(file, source);
                packageByFile.put(file, declaredPackage(source));
            }

            List<MovedFile> moved = new ArrayList<>();
            for (Path file : allFiles) {
                String declared = packageByFile.get(file);
                if (movesWithPackage(declared, sourcePackage, includeSubpackages)) {
                    String newDeclared = targetPackage + declared.substring(sourcePackage.length());
                    moved.add(planMovedFile(file, sourceByFile.get(file), declared, newDeclared, targetSourceRoot));
                }
            }
            if (moved.isEmpty()) {
                throw new Refusal("package_not_found",
                        "No source file declares package '" + sourcePackage + "'"
                                + (includeSubpackages ? " or a subpackage of it." : "."));
            }

            requireNoDestinationCollision(moved);

            // package_collision: a destination package already contains a type whose simple name matches a moved type.
            Set<String> movedNewPackages = new LinkedHashSet<>();
            for (MovedFile movedFile : moved) {
                movedNewPackages.add(targetPackage + movedFile.declaredPackage().substring(sourcePackage.length()));
            }
            Map<String, Set<String>> existingByPackage = new LinkedHashMap<>();
            for (Path file : allFiles) {
                String declared = packageByFile.get(file);
                if (movedNewPackages.contains(declared) && !movesWithPackage(declared, sourcePackage, includeSubpackages)) {
                    existingByPackage.computeIfAbsent(declared, k -> new LinkedHashSet<>())
                            .addAll(simpleTypeNames(sourceByFile.get(file)));
                }
            }
            for (MovedFile movedFile : moved) {
                String destPackage = targetPackage + movedFile.declaredPackage().substring(sourcePackage.length());
                Set<String> existing = existingByPackage.getOrDefault(destPackage, Set.of());
                for (String simple : movedFile.simpleTypeNames()) {
                    if (existing.contains(simple)) {
                        throw new Refusal("package_collision",
                                "Target package '" + destPackage + "' already contains a type named '" + simple
                                        + "' that collides with a type being moved from '"
                                        + movedFile.declaredPackage() + "'.");
                    }
                }
            }

            List<PlannerSupport.TextEdit> edits = new ArrayList<>();
            List<FileOperation> fileOperations = new ArrayList<>();
            for (MovedFile movedFile : moved) {
                edits.add(movedFile.packageEdit());
                fileOperations.add(FileOperation.rename(
                        movedFile.relativeOld(), movedFile.relativeNew(), PlannerSupport.sha256(movedFile.file())));
            }

            // Reference rewriting is scoped to the packages that actually move: only a reference whose true owning
            // package (the longest known project package prefixing it) is in the moved set is migrated. This keeps a
            // reference into a NON-moved subpackage (e.g. when includeSubpackages=false) untouched, instead of
            // mis-rewriting its prefix into a destination that holds no such type.
            Set<String> allPackages = new LinkedHashSet<>(packageByFile.values());
            allPackages.remove("");
            Set<String> movedPackages = new LinkedHashSet<>();
            for (MovedFile movedFile : moved) {
                movedPackages.add(movedFile.declaredPackage());
            }
            // §5.3/§5.4: a package PHYSICALLY split across more than one source root/module — proven from build-graph
            // package-to-source-root facts for ALL packages, not from module-info exports — cannot be moved safely
            // without an explicit decision about which root/module keeps it, so refuse unless a moduleStrategy is given.
            // This catches a split of a NON-exported package that the module-info-only guard below cannot see.
            requireNoSplitAcrossSourceRoots(fields, packageByFile, movedPackages);
            // §5.4: a package owned (exported/opened) by more than one module descriptor is split across modules; moving it
            // cannot be done safely without an explicit decision about which module keeps the export, so refuse unless the
            // request supplies a moduleStrategy.
            requireSingleOwningModule(fields, allFiles, sourceByFile, movedPackages, sourcePackage);
            // module-info.java carries only directives (no type bodies), so it is EXCLUDED from the generic reference
            // rewrite and handled by ModuleInfoRewriter (§5.4) — preventing a double edit and an over-broad prefix
            // rewrite of a NON-moved subpackage export.
            for (Path file : allFiles) {
                if (ModuleInfoRewriter.isModuleInfo(file)) {
                    continue;
                }
                edits.addAll(rewriteReferences(
                        file, sourceByFile.get(file), sourcePackage, targetPackage, allPackages, movedPackages));
            }

            List<String> warnings = new ArrayList<>();
            // §5.4/§5.5: a package moves when its declaration is in the moved set; its destination swaps the
            // sourcePackage prefix for targetPackage (subpackages preserve their tail).
            Function<String, String> packageMapper = pkg -> movedPackages.contains(pkg)
                    ? targetPackage + pkg.substring(sourcePackage.length()) : null;
            Map<String, String> typeFqcnMap = new LinkedHashMap<>();
            for (MovedFile movedFile : moved) {
                String newDeclared = targetPackage + movedFile.declaredPackage().substring(sourcePackage.length());
                for (String simple : movedFile.simpleTypeNames()) {
                    typeFqcnMap.put(movedFile.declaredPackage() + "." + simple, newDeclared + "." + simple);
                }
            }
            Map<String, String> packageMap = new LinkedHashMap<>();
            for (String movedPackage : movedPackages) {
                packageMap.put(movedPackage, targetPackage + movedPackage.substring(sourcePackage.length()));
            }

            if (policy.rewriteModuleInfo()) {
                for (Path file : allFiles) {
                    if (ModuleInfoRewriter.isModuleInfo(file)) {
                        ModuleInfoRewriter.Result moduleResult =
                                ModuleInfoRewriter.rewrite(file, sourceByFile.get(file), packageMapper, typeFqcnMap);
                        edits.addAll(moduleResult.edits());
                        warnings.addAll(moduleResult.warnings());
                    }
                }
            }
            boolean resourceScanIncomplete = false;
            if (policy.rewriteResources()) {
                ResourceRewriter.Result resourceResult =
                        ResourceRewriter.rewrite(projectRoot, model, policy, typeFqcnMap, packageMap, movedPackages);
                edits.addAll(resourceResult.edits());
                fileOperations.addAll(resourceResult.fileRenames());
                warnings.addAll(resourceResult.warnings());
                // B2 (shared contract 2): ResourceRewriter withholds BOTH text edits and file renames on an incomplete
                // scan and reports it here; the planner surfaces that as a top-level risk fact so the op escalates to
                // needs_review and SAFE auto-apply is blocked.
                resourceScanIncomplete = resourceResult.scanIncomplete();
            }
            if (policy.reportReflectionCandidates()) {
                for (Path file : allFiles) {
                    warnings.addAll(ResourceRewriter.reflectionCandidateWarnings(
                            PlannerSupport.relative(projectRoot, file), sourceByFile.get(file), movedPackages));
                }
            }

            String semanticTarget = "{\"sourcePackage\":" + io.serena.javarefactor.protocol.JsonUtil.quote(sourcePackage)
                    + ",\"targetPackage\":" + io.serena.javarefactor.protocol.JsonUtil.quote(targetPackage)
                    + ",\"includeSubpackages\":" + includeSubpackages + "}";
            warnings.add("movePackage relocates package '" + sourcePackage + "'"
                    + (includeSubpackages ? " and its subpackages" : "") + " to '" + targetPackage
                    + "', rewriting package declarations, moving files"
                    + (targetSourceRoot != null ? " under the requested source root" : " under the same source root")
                    + ", and updating imports and fully-qualified references.");
            warnings.add(PlannerSupport.reflectionResourceCaveat("package '" + sourcePackage + "'"));
            // Framework participation mirrors renamePackage: framework-owned resource edits become typed risk facts;
            // plugin review warnings remain warnings that classify the operation as needs_review.
            FrameworkParticipationCoordinator.Result participation = frameworkParticipation
                    .participate(model, SymbolChange.renamePackage(sourcePackage, targetPackage));
            List<String> frameworkBoundaryChanges = participation.frameworkBoundaryChanges();
            warnings.addAll(participation.warnings());
            io.serena.javarefactor.v3.transformation.TransformationStep step =
                    new io.serena.javarefactor.v3.transformation.TransformationStep(
                            "movePackage", edits, fileOperations, warnings, semanticTarget);
            return new Planned(step, resourceScanIncomplete, frameworkBoundaryChanges);
        }
    }

    /** Whether a file declaring {@code declared} moves with {@code sourcePackage} (exact, or a subpackage when allowed). */
    private static boolean movesWithPackage(String declared, String sourcePackage, boolean includeSubpackages) {
        if (declared.equals(sourcePackage)) {
            return true;
        }
        return includeSubpackages && declared.startsWith(sourcePackage + ".");
    }

    /** Refuses before accepting a plan when a package move would collide with an existing or duplicate target file. */
    private void requireNoDestinationCollision(List<MovedFile> moved) {
        Set<String> destinations = new LinkedHashSet<>();
        for (MovedFile movedFile : moved) {
            Path destination = projectRoot.resolve(movedFile.relativeNew()).normalize();
            Path source = movedFile.file().toAbsolutePath().normalize();
            if (Files.exists(destination) && !destination.equals(source)) {
                throw new Refusal(
                        "package_collision",
                        "Target file already exists for moved package source: " + movedFile.relativeNew());
            }
            if (!destinations.add(movedFile.relativeNew())) {
                throw new Refusal(
                        "package_collision",
                        "Multiple moved package sources resolve to the same target file: " + movedFile.relativeNew());
            }
        }
    }

    private MovedFile planMovedFile(Path file, String source, String declared, String newDeclared, Path targetSourceRoot)
            throws IOException {
        String relativeOld = PlannerSupport.relative(projectRoot, file);
        if (GeneratedSourcePolicy.isUnderGeneratedRoot(model.generatedSourceRoots(), file)
                || GeneratedSourcePolicy.matchesGeneratedPathHeuristic(relativeOld)) {
            throw new Refusal("non_editable_target",
                    "Refusing movePackage: '" + relativeOld + "' is generated/non-editable and cannot be moved.");
        }

        int[] declRange = packageDeclarationNameRange(source, declared);
        if (declRange == null) {
            throw new Refusal("move_package_failed",
                    "Could not locate the package declaration for '" + declared + "' in '" + relativeOld + "'.");
        }
        PlannerSupport.TextEdit packageEdit = new PlannerSupport.TextEdit(
                file, declRange[0], declRange[1], newDeclared, "PACKAGE_DECLARATION");

        // Land the file under the requested target source root (cross-source-set move) or its own effective root
        // (in-place rename), at <root>/<newPackageDir>/<fileName>. The in-place effective root is the file's directory
        // with its CURRENT package path stripped: a bare source root in a flat layout, or the MODULE root in a
        // module-source-path layout, so a moved file stays on the module source path javac configures rather than being
        // re-rooted at the bare source root (which would drop it off the module source path).
        Path destRoot = targetSourceRoot != null ? targetSourceRoot : effectiveSourceRoot(file, declared);
        Path fileName = file.getFileName();
        Path newFile = destRoot.resolve(packageToPath(newDeclared)).resolve(fileName);
        String relativeNew = PlannerSupport.relative(projectRoot, newFile);

        return new MovedFile(file, declared, relativeOld, relativeNew, packageEdit, simpleTypeNames(source));
    }

    /**
     * Rewrites every reference whose owning package is in the moved set ({@code movedPackages}) from the source tree to
     * the target tree. For each candidate occurrence anchored on {@code sourcePackage}, the reference's true owning
     * package is the LONGEST known project package ({@code allPackages}) that prefixes it at this position; the
     * occurrence is rewritten only when that owner is actually moving. This precisely migrates {@code owner.Type} (e.g.
     * {@code sourcePackage.sub.Type} when the {@code sub} package moves) while leaving a reference into a NON-moved
     * subpackage untouched. Token-boundary correct: a {@code sourcePackage}-prefixed sibling and the package-declaration
     * occurrence are excluded (declarations are rewritten by the dedicated package-decl edit).
     */
    private List<PlannerSupport.TextEdit> rewriteReferences(Path file, String source, String sourcePackage,
            String targetPackage, Set<String> allPackages, Set<String> movedPackages) {
        // Detection is driven by the javac parse tree, not by raw text: the scan marks offsets covered by a real
        // identifier/member-select node (code) and the offsets of Javadoc reference targets, so an occurrence of the
        // package name inside a string/char literal or a plain comment has no covering node and is never rewritten.
        // Fail closed (refuse) if the file cannot be parsed rather than fall back to scanning raw text — the same
        // contract renamePackage uses, so movePackage can no longer corrupt comments/strings.
        PackageReferenceScanner.Scan scan;
        try {
            scan = PackageReferenceScanner.scan(source);
        } catch (PackageReferenceScanner.ParseFailure failure) {
            throw new Refusal("unparseable_source",
                    "movePackage refuses to rewrite references in '" + PlannerSupport.relative(projectRoot, file)
                            + "': it could not be parsed (" + failure.getMessage() + "), so reference rewriting cannot "
                            + "be limited to real code and could corrupt strings or comments.");
        }
        int[] packageDeclRange = packageDeclarationNameRange(source, declaredPackage(source));
        int packageDeclStart = packageDeclRange == null ? -1 : packageDeclRange[0];
        return PackageReferenceRewriter.rewrite(
                file, source, sourcePackage, targetPackage, allPackages, movedPackages, scan, packageDeclStart);
    }

    // ── Parsing helpers ────────────────────────────────────────────────────────────────────────────────────────────

    private static final Pattern PACKAGE_DECL = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*;");

    private static String declaredPackage(String source) {
        Matcher matcher = PACKAGE_DECL.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int[] packageDeclarationNameRange(String source, String expectedPackage) {
        if (expectedPackage.isEmpty()) {
            return null;
        }
        Matcher matcher = PACKAGE_DECL.matcher(source);
        if (matcher.find() && matcher.group(1).equals(expectedPackage)) {
            return new int[] {matcher.start(1), matcher.end(1)};
        }
        return null;
    }

    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?m)(?:^|\\s)(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+|strictfp\\s+)*"
                    + "(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    private static Set<String> simpleTypeNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = TYPE_DECL.matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    // ── Path helpers ───────────────────────────────────────────────────────────────────────────────────────────────

    private List<Path> allJavaFiles() {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path file : sourceSet.javaFiles()) {
                files.add(file.toAbsolutePath().normalize());
            }
        }
        return new ArrayList<>(files);
    }

    private Path sourceRootFor(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        Path best = null;
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                Path normalizedRoot = root.toAbsolutePath().normalize();
                if (normalized.startsWith(normalizedRoot)
                        && (best == null || normalizedRoot.getNameCount() > best.getNameCount())) {
                    best = normalizedRoot;
                }
            }
        }
        return best != null ? best : normalized.getParent();
    }

    /**
     * The directory under which {@code file}'s package path is rooted: the file's on-disk parent directory with the
     * segments of its CURRENT declared package stripped off. In a flat source layout this equals the configured source
     * root; in a module-source-path layout — where a module's sources live under
     * {@code <sourceRoot>/<moduleDir>/<packagePath>} — this is the MODULE root ({@code <sourceRoot>/<moduleDir>}), so a
     * relocated file is re-rooted under its OWN module instead of the bare source root, keeping it on the module source
     * path javac configures. Falls back to {@link #sourceRootFor} when the file's directory does not actually end with
     * its declared package path (an unconventional layout we must not silently mis-root).
     */
    private Path effectiveSourceRoot(Path file, String declaredPackage) {
        Path dir = file.toAbsolutePath().normalize().getParent();
        if (dir == null) {
            return sourceRootFor(file);
        }
        if (declaredPackage.isEmpty()) {
            return dir;
        }
        Path packagePath = packageToPath(declaredPackage);
        if (!dir.endsWith(packagePath)) {
            return sourceRootFor(file);
        }
        Path root = dir;
        for (int i = 0; i < packagePath.getNameCount() && root != null; i++) {
            root = root.getParent();
        }
        return root != null ? root : sourceRootFor(file);
    }

    /** Resolves the optional {@code targetSourceRoot} field to a configured source root; refuses an unknown one. */
    private Path resolveTargetSourceRoot(Map<String, Object> fields) {
        Object value = fields.get("targetSourceRoot");
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        Path requested = projectRoot.resolve(text).toAbsolutePath().normalize();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                if (root.toAbsolutePath().normalize().equals(requested)) {
                    return requested;
                }
            }
        }
        throw new Refusal("non_editable_target",
                "targetSourceRoot '" + text + "' is not a configured source root of this project.");
    }

    private static Path packageToPath(String dottedPackage) {
        if (dottedPackage.isEmpty()) {
            return Path.of("");
        }
        return Path.of("", dottedPackage.split("\\."));
    }

    private static boolean boolValue(Map<String, Object> fields, String name, boolean fallback) {
        Object value = fields.get(name);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    /**
     * §5.3/§5.4 build-graph split-package guard: refuses when any moved package is PHYSICALLY split across more than one
     * source root/module, derived from the build-model package-to-source-root facts ({@link PackageSourceRootFacts}) for
     * ALL source packages — exported or not. This is stronger than {@link #requireSingleOwningModule}: a package that is
     * never exported/opened (and so is invisible to a module-info-only check) is still detected as split when its files
     * land under two distinct roots/modules. Refused unless a non-blank {@code moduleStrategy} signals the caller has
     * decided how the split is resolved (the same explicit-strategy escape hatch the module-info guard honors).
     */
    private void requireNoSplitAcrossSourceRoots(Map<String, Object> fields, Map<Path, String> packageByFile,
            Set<String> movedPackages) {
        if (hasModuleStrategy(fields)) {
            return;
        }
        PackageSourceRootFacts facts = PackageSourceRootFacts.compute(model, packageByFile);
        String split = facts.firstSplitPackage(movedPackages);
        if (split != null) {
            throw new Refusal("package_split_across_modules",
                    "Package '" + split + "' is physically split across " + facts.rootsFor(split).size()
                            + " source roots/modules (build-graph package-to-source-root facts), so moving it would "
                            + "carry one package name across roots; supply a 'moduleStrategy' to resolve which root/module "
                            + "keeps it before moving it (§5.3/§5.4).");
        }
    }

    /** Whether the request supplied a non-blank {@code moduleStrategy} (the explicit split-resolution escape hatch). */
    private static boolean hasModuleStrategy(Map<String, Object> fields) {
        Object strategy = fields.get("moduleStrategy");
        return strategy instanceof String text && !text.isBlank();
    }

    /**
     * §5.4 split-package guard: a package may legally be exported/opened by exactly one module, so if more than one
     * {@code module-info.java} owns any moved package the move is split across modules and is refused — unless the request
     * supplies a non-blank {@code moduleStrategy}, signalling the caller has decided how to resolve the split.
     */
    private void requireSingleOwningModule(Map<String, Object> fields, List<Path> allFiles,
            Map<Path, String> sourceByFile, Set<String> movedPackages, String movedLabel) {
        if (hasModuleStrategy(fields)) {
            return;
        }
        int owningModules = 0;
        for (Path file : allFiles) {
            if (ModuleInfoRewriter.isModuleInfo(file)
                    && !Collections.disjoint(ModuleInfoRewriter.ownedPackages(sourceByFile.get(file)), movedPackages)) {
                owningModules++;
            }
        }
        if (owningModules > 1) {
            throw new Refusal("package_split_across_modules",
                    "Package '" + movedLabel + "' is exported/opened by " + owningModules + " module descriptors (a "
                            + "package split across modules); supply a 'moduleStrategy' to resolve which module keeps the "
                            + "directive before moving it (§5.4).");
        }
    }

    private static String required(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new Refusal("malformed_move_package", name + " is required.");
    }

    /** A structured-refusal signal carrying the registry code; converted to canonical refusal JSON by {@link #plan}. */
    private static final class Refusal extends RuntimeException implements CodedRefusal {
        private final String code;

        private Refusal(String code, String message) {
            super(message);
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }
    }
}
