package io.serena.javarefactor.v3.packages;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.edits.ResponseBuilder.FileOperation;
import io.serena.javarefactor.project.GeneratedSourcePolicy;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.shared.SourceText;
import io.serena.javarefactor.v3.frameworks.FrameworkParticipationCoordinator;
import io.serena.javarefactor.v3.frameworks.SymbolChange;

import java.io.IOException;
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
 * V3 planner that owns the <em>renamePackage</em> operation (headline plan §5/§26).
 *
 * <p>Renames a Java package across the whole project as a javac-validated, preview-first workspace edit. By default the
 * rename includes every SUBPACKAGE of {@code oldPackage} (e.g. {@code com.acme.app.util} migrates to
 * {@code com.acme.core.util} when {@code com.acme.app} → {@code com.acme.core}); pass {@code includeSubpackages=false}
 * to rename only the types declared directly in {@code oldPackage}. For every source file whose package declaration is
 * in the moved set, the planner:
 * <ol>
 *   <li>rewrites the {@code package} declaration in place, swapping the {@code oldPackage} prefix for {@code newPackage}
 *       (subpackages preserve their tail), and</li>
 *   <li>moves the file from the old package directory to the new one UNDER THE SAME SOURCE ROOT (a rename
 *       {@link FileOperation}).</li>
 * </ol>
 * Across ALL project sources it then rewrites references to the moved package tree: single-type imports
 * ({@code import oldPackage.Type;}), on-demand imports ({@code import oldPackage.*;}), static imports
 * ({@code import static oldPackage.Type.member;}), and fully-qualified references ({@code oldPackage.Type}). Reference
 * rewriting is owner-aware: only an occurrence whose true owning package (the longest known project package prefixing it)
 * is actually moving is migrated, so a sibling such as {@code oldPackage}{@code application} (sharing a prefix) and a
 * reference into a NON-moved subpackage (when {@code includeSubpackages=false}) are never corrupted.
 *
 * <p>The planner emits the one canonical accepted/refused JSON through {@link ResponseBuilder}. It carries the
 * not-yet-validated placeholder diagnostic delta; the authoritative before/after javac delta is produced by the
 * sidecar's {@code PreviewDiagnosticValidator} after the plan is built (the same contract every V2/V3 operation uses).
 */
public final class RenamePackagePlanner {
    /** A dotted Java package/type identifier, used to validate the {@code oldPackage}/{@code newPackage} inputs. */
    private static final Pattern DOTTED_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private final Path projectRoot;
    private final JavaProjectModel model;
    private final PackageRewritePolicy policy;
    /** Framework SPI participant (refactor-feature-plan-V3.md §16): contributes resource-edit descriptions + warnings. */
    private final FrameworkParticipationCoordinator frameworkParticipation = new FrameworkParticipationCoordinator();

    public RenamePackagePlanner(Path projectRoot, JavaProjectModel model) {
        this(projectRoot, model, PackageRewritePolicy.defaults());
    }

    public RenamePackagePlanner(Path projectRoot, JavaProjectModel model, PackageRewritePolicy policy) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
        this.policy = policy;
    }

    /** A planned single moved file: its declared package, current/new paths after the rename, and its package-decl edit. */
    private record MovedFile(Path file, String declaredPackage, String relativeOld, String relativeNew,
                             PlannerSupport.TextEdit packageEdit, Set<String> simpleTypeNames) {
    }

    /**
     * Plans the package rename. {@code fields} must carry {@code oldPackage} and {@code newPackage}; {@code apply} is the
     * standard requested-mode flag threaded by the dispatcher (it only affects the reported {@code mode}, never whether
     * files are mutated — the sidecar never writes files for V2/V3 ops).
     */
    public String plan(Map<String, Object> fields, boolean apply) {
        try {
            Planned planned = planInternal(fields);
            io.serena.javarefactor.v3.transformation.TransformationStep step = planned.step();
            String json = ResponseBuilder.acceptedResult(
                    projectRoot,
                    "renamePackage",
                    apply,
                    step.semanticTargetJson(),
                    step.edits(),
                    step.fileOperations(),
                    step.warnings(),
                    List.of("renamePackage moves every type declared directly in the old package and rewrites references "
                            + "across the project; the after-state is javac-validated before the preview is accepted."),
                    ResponseBuilder.DiagnosticDelta.unvalidated(),
                    false);
            // B2 + B5 (shared contracts 1 & 2): surface the structured risk facts as additive top-level result fields so
            // CanonicalEnvelope.classifyRisk escalates the op to needs_review (and the Python apply gate blocks SAFE
            // auto-apply). resourceScanIncomplete is the existing boolean; framework-owned resource edits are carried as
            // riskFacts.frameworkBoundaryChanges — NOT folded into warnings — per B5. Additive splice, mirroring
            // CanonicalEnvelope.augment, so a result with no incomplete scan and no framework edits is byte-identical to
            // the prior shape.
            return spliceRiskFields(json, planned.resourceScanIncomplete(), planned.frameworkBoundaryChanges());
        } catch (Refusal refusal) {
            return PlannerSupport.refusalJson("renamePackage", apply, refusal.code, refusal.getMessage());
        } catch (IOException error) {
            return PlannerSupport.refusalJson("renamePackage", apply, "rename_package_failed", error.getMessage());
        }
    }

    /**
     * The standalone {@code plan(...)} result plus the structured risk facts (shared contracts 1 & 2) the canonical
     * envelope escalates on: whether the resource scan was incomplete, and the framework-owned resource edits rendered as
     * {@code frameworkBoundaryChange} reasons. The composition path ({@link #planStep}) has no structured riskFacts
     * channel, so it folds these same reasons into step warnings instead (equivalent needs_review escalation).
     */
    private record Planned(io.serena.javarefactor.v3.transformation.TransformationStep step,
            boolean resourceScanIncomplete, List<String> frameworkBoundaryChanges) {
    }

    /**
     * Splices the additive shared-contract risk fields into an accepted-result JSON object immediately before its closing
     * brace (the same additive technique {@code CanonicalEnvelope.augment} uses). Emits {@code resourceScanIncomplete:true}
     * when the resource scan was incomplete (B2) and a {@code riskFacts.frameworkBoundaryChanges} array when the framework
     * SPI contributed framework-owned resource edits (B5). When neither applies the JSON is returned unchanged.
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
        // Composition path (refactor-feature-plan-V3.md §3): the EditComposer only propagates a step's `warnings` (and
        // semanticTarget) to the composed top-level workspaceEdit, which CanonicalEnvelope.classifyRisk escalates on.
        // There is no structured riskFacts/resourceScanIncomplete channel through composition, so the same B2/B5
        // escalation reasons the standalone path emits as top-level riskFacts.frameworkBoundaryChanges /
        // resourceScanIncomplete are surfaced HERE as step warnings — an equivalent needs_review escalation (the exact
        // pattern ExtractClassPlanner.planStep uses for its publicApiChanges riskFact).
        Planned planned = planInternal(fields);
        io.serena.javarefactor.v3.transformation.TransformationStep step = planned.step();
        if (!planned.frameworkBoundaryChanges().isEmpty() || planned.resourceScanIncomplete()) {
            List<String> warnings = new ArrayList<>(step.warnings());
            warnings.addAll(planned.frameworkBoundaryChanges());
            if (planned.resourceScanIncomplete()) {
                warnings.add("renamePackage resource scan was incomplete; resource-side changes were withheld and this "
                        + "step requires review (resourceScanIncomplete).");
            }
            return new io.serena.javarefactor.v3.transformation.TransformationStep(
                    step.operation(), step.edits(), step.fileOperations(), warnings, step.semanticTargetJson());
        }
        return step;
    }

    /**
     * Builds the structured edit/op contribution plus the shared-contract risk facts (B2 resourceScanIncomplete, B5
     * framework-owned resource edits). Throws {@link Refusal} on a precondition violation and {@link IOException} on a
     * read failure — the caller maps those to the canonical refusal JSON.
     */
    private Planned planInternal(Map<String, Object> fields) throws IOException {
        {
            String oldPackage = required(fields, "oldPackage");
            String newPackage = required(fields, "newPackage");
            if (!DOTTED_NAME.matcher(oldPackage).matches() || !DOTTED_NAME.matcher(newPackage).matches()) {
                throw new Refusal("malformed_rename_package",
                        "renamePackage requires dotted Java package names for oldPackage and newPackage.");
            }
            if (oldPackage.equals(newPackage)) {
                throw new Refusal("malformed_rename_package", "oldPackage and newPackage must differ.");
            }

            List<Path> allFiles = allJavaFiles();
            // Pre-read every file's package declaration so we can both find the moved files and (for collision checks)
            // enumerate the simple type names already present in newPackage.
            Map<Path, String> packageByFile = new LinkedHashMap<>();
            Map<Path, String> sourceByFile = new LinkedHashMap<>();
            for (Path file : allFiles) {
                String source = SourceText.read(model, file);
                sourceByFile.put(file, source);
                packageByFile.put(file, declaredPackage(source));
            }

            boolean includeSubpackages = boolValue(fields, "includeSubpackages", true);
            List<MovedFile> moved = new ArrayList<>();
            for (Path file : allFiles) {
                String declared = packageByFile.get(file);
                if (movesWithPackage(declared, oldPackage, includeSubpackages)) {
                    String newDeclared = newPackage + declared.substring(oldPackage.length());
                    moved.add(planMovedFile(file, sourceByFile.get(file), declared, newDeclared));
                }
            }
            if (moved.isEmpty()) {
                throw new Refusal("package_not_found",
                        "No source file declares package '" + oldPackage + "'"
                                + (includeSubpackages ? " or a subpackage of it." : "."));
            }

            // package_collision: a destination package already contains a type whose simple name matches a moved type.
            Set<String> movedNewPackages = new LinkedHashSet<>();
            for (MovedFile movedFile : moved) {
                movedNewPackages.add(newPackage + movedFile.declaredPackage().substring(oldPackage.length()));
            }
            Map<String, Set<String>> existingByPackage = new LinkedHashMap<>();
            for (Path file : allFiles) {
                String declared = packageByFile.get(file);
                if (movedNewPackages.contains(declared) && !movesWithPackage(declared, oldPackage, includeSubpackages)) {
                    existingByPackage.computeIfAbsent(declared, k -> new LinkedHashSet<>())
                            .addAll(simpleTypeNames(sourceByFile.get(file)));
                }
            }
            for (MovedFile movedFile : moved) {
                String destPackage = newPackage + movedFile.declaredPackage().substring(oldPackage.length());
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
            // reference into a NON-moved subpackage (when includeSubpackages=false) untouched, instead of mis-rewriting
            // its prefix. The moved files themselves are included (a moved file may reference a sibling by FQN).
            // module-info.java carries only directives (no type bodies) — EXCLUDED here and handled by
            // ModuleInfoRewriter (§5.4) to avoid an over-broad prefix rewrite of a NON-moved subpackage export.
            Set<String> allPackages = new LinkedHashSet<>(packageByFile.values());
            allPackages.remove("");
            Set<String> movedPackages = new LinkedHashSet<>();
            for (MovedFile movedFile : moved) {
                movedPackages.add(movedFile.declaredPackage());
            }
            // §5.3/§5.4: a package PHYSICALLY split across more than one source root/module — proven from build-graph
            // package-to-source-root facts for ALL packages, not from module-info exports — cannot be renamed safely
            // without an explicit decision about which root/module keeps it, so refuse unless a moduleStrategy is given.
            // This catches a split of a NON-exported package that the module-info-only guard below cannot see.
            requireNoSplitAcrossSourceRoots(fields, packageByFile, movedPackages);
            // §5.4: a package owned (exported/opened) by more than one module descriptor is split across modules; renaming
            // it cannot be done safely without an explicit decision about which module keeps the export, so refuse unless
            // the request supplies a moduleStrategy.
            requireSingleOwningModule(fields, allFiles, sourceByFile, movedPackages, oldPackage);
            for (Path file : allFiles) {
                if (ModuleInfoRewriter.isModuleInfo(file)) {
                    continue;
                }
                edits.addAll(rewriteReferences(
                        file, sourceByFile.get(file), oldPackage, newPackage, allPackages, movedPackages));
            }

            List<String> warnings = new ArrayList<>();
            // §5.4/§5.5: a package moves when its declaration is in the moved set; its destination swaps the oldPackage
            // prefix for newPackage (subpackages preserve their tail).
            Function<String, String> packageMapper = pkg -> movedPackages.contains(pkg)
                    ? newPackage + pkg.substring(oldPackage.length()) : null;
            Map<String, String> typeFqcnMap = new LinkedHashMap<>();
            for (MovedFile movedFile : moved) {
                String newDeclared = newPackage + movedFile.declaredPackage().substring(oldPackage.length());
                for (String simple : movedFile.simpleTypeNames()) {
                    typeFqcnMap.put(movedFile.declaredPackage() + "." + simple, newDeclared + "." + simple);
                }
            }
            Map<String, String> packageMap = new LinkedHashMap<>();
            for (String movedPackage : movedPackages) {
                packageMap.put(movedPackage, newPackage + movedPackage.substring(oldPackage.length()));
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

            String semanticTarget = "{\"oldPackage\":" + io.serena.javarefactor.protocol.JsonUtil.quote(oldPackage)
                    + ",\"newPackage\":" + io.serena.javarefactor.protocol.JsonUtil.quote(newPackage)
                    + ",\"includeSubpackages\":" + includeSubpackages + "}";
            warnings.add("renamePackage rewrites package declarations, moves files under the same source root, and "
                    + "updates imports and fully-qualified references to the moved package"
                    + (includeSubpackages ? " and its subpackages." : "; subpackages of '" + oldPackage
                            + "' were left in place (includeSubpackages=false)."));
            warnings.add(PlannerSupport.reflectionResourceCaveat("package '" + oldPackage + "'"));
            // Framework participation (refactor-feature-plan-V3.md §16): let the framework plugins contribute resource-
            // edit descriptions and review-required warnings for framework-managed types under the renamed package
            // (e.g. Spring bean class references in XML / @ComponentScan base packages). Participation never blocks a
            // rename; it only surfaces the framework's resource impact and review items.
            FrameworkParticipationCoordinator.Result participation = frameworkParticipation
                    .participate(model, SymbolChange.renamePackage(oldPackage, newPackage));
            // B5: framework-owned resource edits are TYPED facts carried as riskFacts.frameworkBoundaryChanges by the
            // caller — NEVER folded into warnings (the old behavior that made them indistinguishable advisory text).
            // The participation's review-required `warnings` (e.g. string bean names / JPQL) remain genuine warnings.
            List<String> frameworkBoundaryChanges = participation.frameworkBoundaryChanges();
            warnings.addAll(participation.warnings());
            io.serena.javarefactor.v3.transformation.TransformationStep step =
                    new io.serena.javarefactor.v3.transformation.TransformationStep(
                            "renamePackage", edits, fileOperations, warnings, semanticTarget);
            return new Planned(step, resourceScanIncomplete, frameworkBoundaryChanges);
        }
    }

    /** Whether a file declaring {@code declared} renames with {@code oldPackage} (exact, or a subpackage when allowed). */
    private static boolean movesWithPackage(String declared, String oldPackage, boolean includeSubpackages) {
        if (declared.equals(oldPackage)) {
            return true;
        }
        return includeSubpackages && declared.startsWith(oldPackage + ".");
    }

    /** Builds the per-file rename op + package-declaration edit for a file moving from {@code declared} to {@code newDeclared}. */
    private MovedFile planMovedFile(Path file, String source, String declared, String newDeclared) throws IOException {
        String relativeOld = PlannerSupport.relative(projectRoot, file);
        // non_editable_target: a moved file under a generated source root (authoritative build-model signal) or matching
        // the generated-path heuristic cannot be relocated.
        if (GeneratedSourcePolicy.isUnderGeneratedRoot(model.generatedSourceRoots(), file)
                || GeneratedSourcePolicy.matchesGeneratedPathHeuristic(relativeOld)) {
            throw new Refusal("non_editable_target",
                    "Refusing renamePackage: '" + relativeOld + "' is generated/non-editable and cannot be moved.");
        }

        // Replace the package name in the declaration in place, preserving leading whitespace/comments.
        int[] declRange = packageDeclarationNameRange(source, declared);
        if (declRange == null) {
            // Should not happen — declaredPackage already matched — but guard rather than emit a bad edit.
            throw new Refusal("rename_package_failed",
                    "Could not locate the package declaration for '" + declared + "' in '" + relativeOld + "'.");
        }
        PlannerSupport.TextEdit packageEdit = new PlannerSupport.TextEdit(
                file, declRange[0], declRange[1], newDeclared, "PACKAGE_DECLARATION");

        // Relocate the file under the new package directory, under its OWN effective root (rename = same-root move). The
        // effective root is the file's on-disk directory with its CURRENT package path stripped off: a bare source root
        // in a flat layout, or the MODULE root in a module-source-path layout (where sources live under
        // <sourceRoot>/<moduleDir>/<packagePath>). Rooting at the module dir keeps the moved file on the module source
        // path javac configures, so a package that spans modules has each file re-rooted under its own module.
        Path effectiveRoot = effectiveSourceRoot(file, declared);
        Path fileName = file.getFileName();
        Path newFile = effectiveRoot.resolve(packageToPath(newDeclared)).resolve(fileName);
        String relativeNew = PlannerSupport.relative(projectRoot, newFile);

        return new MovedFile(file, declared, relativeOld, relativeNew, packageEdit, simpleTypeNames(source));
    }

    /**
     * Rewrites every reference whose owning package is in the moved set ({@code movedPackages}) from the old tree to the
     * new tree: single-type/static/on-demand imports and fully-qualified type references. For each candidate occurrence
     * anchored on {@code oldPackage}, the reference's true owning package is the LONGEST known project package
     * ({@code allPackages}) that prefixes it at this position; the occurrence is rewritten only when that owner is
     * actually moving. This precisely migrates {@code owner.Type} (e.g. {@code oldPackage.sub.Type} when {@code sub}
     * moves) while leaving a reference into a NON-moved subpackage untouched. Token-boundary correct: an
     * {@code oldPackage}-prefixed sibling and the package-declaration occurrence are excluded (declarations are
     * rewritten by the dedicated package-decl edit).
     */
    private List<PlannerSupport.TextEdit> rewriteReferences(Path file, String source, String oldPackage,
            String newPackage, Set<String> allPackages, Set<String> movedPackages) {
        // Detection is driven by the javac parse tree, not by raw text: the scan marks offsets covered by a real
        // identifier/member-select node (code) and the offsets of Javadoc reference targets, so an occurrence of the
        // package name inside a string/char literal or a plain comment has no covering node and is never rewritten.
        // Fail closed (refuse) if the file cannot be parsed rather than fall back to scanning raw text.
        PackageReferenceScanner.Scan scan;
        try {
            scan = PackageReferenceScanner.scan(source);
        } catch (PackageReferenceScanner.ParseFailure failure) {
            throw new Refusal("unparseable_source",
                    "renamePackage refuses to rewrite references in '" + PlannerSupport.relative(projectRoot, file)
                            + "': it could not be parsed (" + failure.getMessage() + "), so reference rewriting cannot "
                            + "be limited to real code and could corrupt strings or comments.");
        }
        int[] packageDeclRange = packageDeclarationNameRange(source, declaredPackage(source));
        int packageDeclStart = packageDeclRange == null ? -1 : packageDeclRange[0];
        return PackageReferenceRewriter.rewrite(
                file, source, oldPackage, newPackage, allPackages, movedPackages, scan, packageDeclStart);
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
                            + " source roots/modules (build-graph package-to-source-root facts), so renaming it would "
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
    private static void requireSingleOwningModule(Map<String, Object> fields, List<Path> allFiles,
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

    // ── Parsing helpers ────────────────────────────────────────────────────────────────────────────────────────────

    private static final Pattern PACKAGE_DECL = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*;");

    /** The dotted name declared by the file's {@code package} statement, or {@code ""} for the default package. */
    private static String declaredPackage(String source) {
        Matcher matcher = PACKAGE_DECL.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * The half-open {@code [start, end)} offset range of the package NAME (not the whole statement) in the file's
     * {@code package} declaration, when it is exactly {@code expectedPackage}; otherwise {@code null}.
     */
    private static int[] packageDeclarationNameRange(String source, String expectedPackage) {
        Matcher matcher = PACKAGE_DECL.matcher(source);
        if (matcher.find() && matcher.group(1).equals(expectedPackage)) {
            return new int[] {matcher.start(1), matcher.end(1)};
        }
        return null;
    }

    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?m)(?:^|\\s)(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+|strictfp\\s+)*"
                    + "(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    /** The simple names of the top-level/nested type declarations in a source file (used for collision detection). */
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
        // model.allJavaFiles() is package-private to the project package, so union the public per-source-set lists here.
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path file : sourceSet.javaFiles()) {
                files.add(file.toAbsolutePath().normalize());
            }
        }
        return new ArrayList<>(files);
    }

    /** The configured source root that contains {@code file} (the longest matching root); the file's parent otherwise. */
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

    private static Path packageToPath(String dottedPackage) {
        if (dottedPackage.isEmpty()) {
            return Path.of("");
        }
        return Path.of("", dottedPackage.split("\\."));
    }

    private static String required(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new Refusal("malformed_rename_package", name + " is required.");
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
