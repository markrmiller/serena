package io.serena.javarefactor.operations.move_member;

import io.serena.javarefactor.ast.IdentifierSpan;
import io.serena.javarefactor.ast.ResolvedTarget;
import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.shared.AccessAdjustmentPlanner;
import io.serena.javarefactor.shared.AccessPlan;
import io.serena.javarefactor.shared.ImportManager;
import io.serena.javarefactor.shared.SemanticTargetGate;
import io.serena.javarefactor.shared.SourceText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * V2 planner for the <em>static</em> member move (plan §3 {@code move_member}, hard blocker 7 / G007).
 *
 * <p>This is the named home of the static-move path, separated from {@link MoveMemberPlanner} (which still owns the
 * instance-method path for G008) so the static path can evolve independently. It reuses {@link MoveMemberPlanner}'s
 * shared member-selection, insertion-offset, collision, and path-resolution services through package-private accessors,
 * and reuses the shared {@link AccessAdjustmentPlanner} for the visibility decision.
 *
 * <p>The distinguishing work of G007 is <b>compiler-backed moved-body import resolution</b>: instead of a regex
 * single-type import transplant, the moved static member's body dependencies are resolved with javac through
 * {@link SemanticIndex#movedStaticBodyDependencies} and reconciled against the target file with the shared
 * {@link ImportManager}. That covers single-type imports, wildcard imports, static imports used inside the body,
 * nested/generic type surfaces, same-package source types, and project simple-name conflicts (left fully qualified).
 * Stale source static wildcard imports are removed only on a real per-file usage proof
 * ({@link SemanticIndex#fileStillUsesOtherStaticMembers}), never a member-count heuristic.
 */
public final class MoveStaticMemberPlanner {
    private final Path projectRoot;
    private final JavaProjectModel model;
    private final MoveMemberPlanner shared;
    private final AccessAdjustmentPlanner accessPlanner = new AccessAdjustmentPlanner();

    public MoveStaticMemberPlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot;
        this.model = model;
        this.shared = new MoveMemberPlanner(projectRoot, model);
    }

    public String move(Map<String, Object> fields, boolean apply) {
        try {
            Path sourceFile = shared.sourceFileShared(fields);
            Path targetFile = shared.targetFileShared(fields, sourceFile, "targetRelativePath", "targetType");
            String source = SourceText.read(model, sourceFile);
            String target = SourceText.read(model, targetFile);
            String relativePath = PlannerSupport.relative(projectRoot, sourceFile);
            try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
                ResolvedTarget verified = SemanticTargetGate.require(index, relativePath, fields);
                MoveMemberPlanner.Member member =
                        shared.selectedSemanticMemberShared(index, sourceFile, source, shared.intFieldShared(fields, "line"), verified);
                SemanticTargetGate.confirmSelection(verified, member.semantic().element());
                if (!member.isStatic()) {
                    throw new MoveMemberPlanner.Refusal("not_static_member", "moveStaticMember requires a static method or field target.");
                }
                // Blocker 1: a static FIELD move is no longer categorically refused for non-constants. A compile-time
                // constant (getConstantValue() != null) moves unconditionally. A NON-constant static field is planned
                // when javac proves its initialization is not entangled with static-init ordering (analyzeStaticFieldMoveSafety);
                // only a proven initializer-order coupling is refused. Static methods are unaffected by this gate.
                boolean movedNonConstantField = false;
                if (member.kind() == MoveMemberPlanner.MemberKind.FIELD) {
                    Element fieldElement = member.semantic().element();
                    boolean isConstant = fieldElement instanceof VariableElement variable && variable.getConstantValue() != null;
                    if (!isConstant) {
                        movedNonConstantField = true;
                        SemanticIndex.StaticFieldMoveSafety safety = index.analyzeStaticFieldMoveSafety(
                                fieldElement, sourceTypeElement(index, sourceFile), targetTypeElement(index, targetFile));
                        if (!safety.safe()) {
                            throw new MoveMemberPlanner.Refusal(safety.code(), safety.message());
                        }
                    }
                }
                String replacementName = MoveMemberPlanner.stringFieldShared(fields, "newName", null);
                MoveMemberPlanner.Member namedMember =
                        replacementName == null || replacementName.isBlank() ? member : member.withName(replacementName);
                shared.refuseTargetMemberCollisionShared(index, targetFile, namedMember);
                List<PlannerSupport.TextEdit> dependencyAccessEdits = shared.planPrivateDependencyAccessShared(
                        index,
                        sourceFile,
                        targetFile,
                        member,
                        Boolean.TRUE.equals(fields.get("allowAccessWidening")),
                        Boolean.TRUE.equals(fields.get("allowSecuritySensitivePrivateWidening")),
                        "MOVE_STATIC_MEMBER_ACCESS");

                String targetType = shared.targetTypeNameShared(fields, targetFile);
                AccessPlan accessPlan = accessPlanner.plan(
                        member.modifiers(),
                        shared.packageNameShared(index, sourceFile),
                        shared.packageNameShared(index, targetFile),
                        false,
                        member.name(),
                        Boolean.TRUE.equals(fields.get("allowSecuritySensitivePrivateWidening")));
                if (!accessPlan.allowed()) {
                    throw new MoveMemberPlanner.Refusal(accessPlan.refusal().code(), accessPlan.refusal().message());
                }
                if (!Boolean.TRUE.equals(fields.get("allowAccessWidening")) && !"unchanged".equals(accessPlan.requiredVisibility())) {
                    throw new MoveMemberPlanner.Refusal(
                            "access_widening_not_allowed",
                            "moveStaticMember would require access widening; pass allow_access_widening=true to opt in.");
                }
                MoveMemberPlanner.Member moved = namedMember.withModifiers(accessPlanner.rewriteModifiers(member.modifiers(), accessPlan));
                List<PlannerSupport.TextEdit> edits = new ArrayList<>();
                edits.add(new PlannerSupport.TextEdit(sourceFile, member.removeStart(), member.removeEnd(), "", "MOVE_STATIC_MEMBER_REMOVE"));
                int insertionOffset = shared.classInsertionOffsetShared(index, targetFile, moved.kind(), target);
                edits.add(new PlannerSupport.TextEdit(targetFile, insertionOffset, insertionOffset, "\n" + moved.text().stripTrailing() + "\n", "MOVE_STATIC_MEMBER_INSERT"));
                edits.addAll(staticReferenceEdits(index, sourceFile, targetFile, member, targetType, namedMember.name()));
                edits.addAll(bodyDependencyImportEdits(index, sourceFile, targetFile, target, member));
                edits.addAll(dependencyAccessEdits);

                List<String> warnings = new ArrayList<>(accessPlanner.warnings(List.of(accessPlan)));
                warnings.add("V2 moveStaticMember resolves the moved member and rewritten references with javac Trees/Elements/source positions; text formatting is applied only to AST-proven ranges.");
                // G015: relocating a PUBLIC static member changes the public API surface of both types.
                if (member.modifiers().contains("public")) {
                    warnings.add("V2 moveStaticMember relocates the public static member '" + member.name()
                            + "' to '" + targetType + "', changing the public API surface; update external callers, "
                            + "binary dependents, and any reflection/serialization references to the new home.");
                }
                if (movedNonConstantField) {
                    warnings.add("V2 moveStaticMember relocates the non-constant static field '" + member.name()
                            + "': its initializer now runs during the destination type's class initialization rather than the "
                            + "source type's. javac proved no static-initialization-order coupling, but if the initializer has "
                            + "observable side effects, confirm the new initialization timing is acceptable.");
                }
                warnings.add(PlannerSupport.reflectionResourceCaveat("static member '" + member.name() + "'"));
                String structuredWarningsJson = null;
                if (accessPlan.requiredVisibility() != null && !"unchanged".equals(accessPlan.requiredVisibility())) {
                    String message = "Moved static member '" + member.name() + "' would have its visibility widened to "
                            + accessPlan.requiredVisibility() + " in '" + targetType + "'.";
                    structuredWarningsJson = "[{\"code\":" + JsonUtil.quote("ACCESS_WIDENING_REQUIRED")
                            + ",\"message\":" + JsonUtil.quote(message) + "}]";
                }
                return ResponseBuilder.acceptedResult(
                        projectRoot,
                        "moveStaticMember",
                        apply,
                        "{\"semanticKey\":" + SemanticKey.from(member.semantic().element()).toJson() + "}",
                        edits,
                        List.of(),
                        warnings,
                        List.of(),
                        ResponseBuilder.DiagnosticDelta.unvalidated(),
                        false,
                        accessPlanner.plansJson(List.of(accessPlan)),
                        structuredWarningsJson);
            }
        } catch (SemanticTargetGate.Refused refused) {
            return refusedJson("moveStaticMember", apply, refused.code(), refused.getMessage());
        } catch (MoveMemberPlanner.Refusal refusal) {
            return refusedJson("moveStaticMember", apply, refusal.code(), refusal.getMessage());
        } catch (Exception error) {
            return refusedJson("moveStaticMember", apply, "move_static_member_failed", error.getMessage());
        }
    }

    /**
     * Cross-file reference edits for the moved static member: a caller's {@code import static Source.member;} is
     * retargeted to the new home, a {@code Source.member} qualified use is requalified, and a stale source
     * {@code import static Source.*;} is removed only on a real per-file usage proof
     * ({@link SemanticIndex#fileStillUsesOtherStaticMembers}). Unchanged from the prior static-path behaviour except that
     * the wildcard staleness test is now proved per reference file rather than from a source member count.
     */
    private List<PlannerSupport.TextEdit> staticReferenceEdits(
            SemanticIndex index, Path sourceFile, Path targetFile, MoveMemberPlanner.Member member, String targetType, String replacementName)
            throws IOException {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        String targetPackage = index.packageNameOfAnyTask(targetFile);
        String targetSimple = simpleType(targetType);
        String targetFqn = targetType.contains(".")
                ? targetType
                : ((targetPackage == null || targetPackage.isBlank()) ? targetSimple : targetPackage + "." + targetSimple);
        String sourceSimple = sourceFile.getFileName().toString().replaceFirst("\\.java$", "");
        String sourcePackage = index.packageNameOfAnyTask(sourceFile);
        String sourceFqn = sourcePackage == null || sourcePackage.isBlank() ? sourceSimple : sourcePackage + "." + sourceSimple;
        TypeElement sourceTypeElement = sourceTypeElement(index, sourceFile);
        Map<Path, ImportManager> importPlanners = new LinkedHashMap<>();
        Set<String> removedWildcardImports = new LinkedHashSet<>();
        for (IdentifierSpan span : index.referencesTo(member.semantic())) {
            Path referenceFile = span.file().toAbsolutePath().normalize();
            if (referenceFile.equals(sourceFile.toAbsolutePath().normalize())
                    && span.startOffset() >= member.removeStart() && span.endOffset() <= member.removeEnd()) {
                continue;
            }
            CharSequence contentSeq = index.sourceText(span.file());
            if (contentSeq == null) {
                continue;
            }
            String content = contentSeq.toString();
            String wildcardImport = "import static " + sourceFqn + ".*;";
            int wildcardImportOffset = content.indexOf(wildcardImport);
            // G007: remove the source static wildcard only when this file PROVABLY uses no other static member of the
            // source type (real per-file usage proof), not when the source type happens to have no other static members.
            if (wildcardImportOffset >= 0
                    && !index.fileStillUsesOtherStaticMembers(span.file(), sourceTypeElement, member.name())
                    && removedWildcardImports.add(span.file() + ":" + wildcardImportOffset)) {
                edits.add(new PlannerSupport.TextEdit(span.file(), wildcardImportOffset, wildcardImportOffset + wildcardImport.length(), "", "MOVE_STATIC_MEMBER_STALE_WILDCARD_IMPORT"));
            }
            int lineStart = MoveMemberPlanner.lineStartShared(content, (int) span.startOffset());
            int lineEnd = MoveMemberPlanner.lineEndShared(content, (int) span.endOffset());
            String line = content.substring(lineStart, lineEnd);
            if (line.contains("import static")) {
                edits.add(new PlannerSupport.TextEdit(span.file(), lineStart, lineEnd,
                        "import static " + targetFqn + "." + replacementName + ";", "MOVE_STATIC_MEMBER_STATIC_IMPORT"));
                continue;
            }
            ImportManager.TypeUse targetUse = importPlanners
                    .computeIfAbsent(referenceFile, ignored -> new ImportManager(content)
                            .withConflictResolver(io.serena.javarefactor.shared.ImportConflictResolvers.samePackageAndProject(
                                    index, referenceFile, index.packageNameOfAnyTask(referenceFile))))
                    .planTypeUsageDeep(span.file(), targetFqn, "MOVE_STATIC_MEMBER_IMPORT");
            for (PlannerSupport.TextEdit importEdit : targetUse.importEdits()) {
                if (!edits.contains(importEdit)) {
                    edits.add(importEdit);
                }
            }

            int start = (int) span.startOffset();
            int end = (int) span.endOffset();
            String replacement = targetUse.renderedType() + "." + replacementName;
            int previous = MoveMemberPlanner.previousNonWhitespaceShared(content, start - 1);
            if (previous >= 0 && content.charAt(previous) == '.') {
                int qualifierEnd = MoveMemberPlanner.previousNonWhitespaceShared(content, previous - 1) + 1;
                int qualifierStart = MoveMemberPlanner.qualifiedReferenceStartShared(content, qualifierEnd);
                if (qualifierStart >= 0) {
                    start = qualifierStart;
                }
            } else if (previous >= 0 && content.charAt(previous) == ':') {
                int firstColon = MoveMemberPlanner.previousNonWhitespaceShared(content, previous - 1);
                if (firstColon >= 0 && content.charAt(firstColon) == ':') {
                    int qualifierEnd = MoveMemberPlanner.previousNonWhitespaceShared(content, firstColon - 1) + 1;
                    int qualifierStart = MoveMemberPlanner.qualifiedReferenceStartShared(content, qualifierEnd);
                    if (qualifierStart >= 0) {
                        start = qualifierStart;
                        replacement = targetUse.renderedType() + "::" + replacementName;
                    }
                }
            }
            edits.add(new PlannerSupport.TextEdit(span.file(), start, end, replacement, "MOVE_STATIC_MEMBER_REFERENCE"));
        }
        return edits;
    }

    /**
     * The compiler-backed body-dependency import edits for the moved static member (G007). The moved body's referenced
     * types and unqualified static members are resolved with javac via {@link SemanticIndex#movedStaticBodyDependencies},
     * then reconciled against the TARGET file with the shared {@link ImportManager}:
     * <ul>
     *   <li>each referenced type is added with {@link ImportManager#addImport} — {@code java.lang} and same-package
     *       types are skipped, a wildcard-covered or already-imported type yields no edit, and a project simple-name
     *       conflict is refused (the moved body must keep the FQN, which it already carries);</li>
     *   <li>each static member used in the body is added with {@link ImportManager#addStaticImport}, refusing on a
     *       colliding member simple name.</li>
     * </ul>
     * Only the genuinely new imports (those not already present in the target) are emitted as deterministic insertions.
     * The target's pre-existing wildcard imports and static imports are preserved verbatim by the manager.
     */
    private List<PlannerSupport.TextEdit> bodyDependencyImportEdits(
            SemanticIndex index, Path sourceFile, Path targetFile, String targetText, MoveMemberPlanner.Member member) {
        TypeElement sourceTypeElement = sourceTypeElement(index, sourceFile);
        SemanticIndex.MovedBodyDependencies dependencies =
                index.movedStaticBodyDependencies(member.semantic().element(), sourceTypeElement);

        ImportManager targetImports = new ImportManager(targetText);
        Set<String> existingImports = new LinkedHashSet<>(targetImports.imports());
        Set<String> existingStaticImports = new LinkedHashSet<>(targetImports.staticImports());
        String targetPackage = index.packageNameOfAnyTask(targetFile);

        List<PlannerSupport.TextEdit> edits = new ArrayList<>();

        // Single-type / wildcard / same-package / nested-generic surfaces: ImportManager decides what the target needs.
        for (String typeFqn : dependencies.referencedTypeFqns()) {
            // Project simple-name conflict the target's own import list cannot see: a DIFFERENT type with this simple
            // name is declared in the target package. ImportManager would happily import it (no in-file clash), but it
            // would collide with the same-package type, so leave the moved body's FQN and emit no import.
            if (conflictsWithTargetPackageType(index, targetFile, targetPackage, typeFqn)) {
                continue;
            }
            // addImport is a no-op for java.lang / same-package / already-covered types, and refuses (returns a refusal)
            // on a simple-name conflict — in which case the moved body keeps the FQN and no import edit is emitted.
            if (targetImports.addImport(typeFqn).isPresent()) {
                continue;
            }
            if (!existingImports.contains(typeFqn) && targetImports.imports().contains(typeFqn)) {
                ImportManager.computeImportInsertion(targetText, typeFqn)
                        .ifPresent(insertion -> edits.add(new PlannerSupport.TextEdit(
                                targetFile, insertion.offset(), insertion.offset(), insertion.text(), "MOVE_STATIC_MEMBER_IMPORT")));
            }
        }

        // Static imports used inside the moved body: transplanted only when the target does not already bind them.
        for (SemanticIndex.StaticMemberRef staticRef : dependencies.staticMemberRefs()) {
            String qualifiedMember = staticRef.qualifiedMember();
            if (targetImports.addStaticImport(qualifiedMember).isPresent()) {
                continue; // member simple-name collision in the target: the body must qualify it instead
            }
            if (!existingStaticImports.contains(qualifiedMember) && targetImports.staticImports().contains(qualifiedMember)) {
                ImportManager.computeStaticImportInsertion(targetText, qualifiedMember)
                        .ifPresent(insertion -> edits.add(new PlannerSupport.TextEdit(
                                targetFile, insertion.offset(), insertion.offset(), insertion.text(), "MOVE_STATIC_MEMBER_IMPORT")));
            }
        }
        return edits;
    }

    /**
     * Whether importing {@code typeFqn} into {@code targetFile} would collide with a DIFFERENT type of the same simple
     * name already declared in the target's package (G007 project conflict). The target package's own siblings are
     * visible without an import and the {@link ImportManager} cannot see them from the target file's import list, so this
     * compiler-backed check leaves such a reference fully qualified in the moved body. A type that already lives in the
     * target package is not a conflict with itself.
     */
    private static boolean conflictsWithTargetPackageType(SemanticIndex index, Path targetFile, String targetPackage, String typeFqn) {
        if (typeFqn == null || !typeFqn.contains(".")) {
            return false;
        }
        String pkg = targetPackage == null ? "" : targetPackage;
        String typePackage = typeFqn.substring(0, typeFqn.lastIndexOf('.'));
        if (typePackage.equals(pkg)) {
            return false; // same-package: visible without an import, not a collision against one
        }
        String simpleName = typeFqn.substring(typeFqn.lastIndexOf('.') + 1);
        return index.targetPackageHasType(pkg, simpleName, targetFile);
    }

    /** The source file's primary {@link TypeElement}, or {@code null} when it does not resolve. */
    private static TypeElement sourceTypeElement(SemanticIndex index, Path sourceFile) {
        SemanticIndex.SemanticType type = index.primaryType(sourceFile);
        if (type != null && type.element() instanceof TypeElement typeElement) {
            return typeElement;
        }
        return null;
    }

    /** The target file's primary {@link TypeElement}, or {@code null} when it does not resolve (used by the init-order gate). */
    private static TypeElement targetTypeElement(SemanticIndex index, Path targetFile) {
        SemanticIndex.SemanticType type = index.primaryType(targetFile);
        if (type != null && type.element() instanceof TypeElement typeElement) {
            return typeElement;
        }
        return null;
    }

    private static String simpleType(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }

    private String refusedJson(String operation, boolean apply, String code, String message) {
        // Blocker 3: one canonical refusal envelope — applied:false on every refusal, actual requested mode.
        return ResponseBuilder.refusedResult(operation, apply, code, message);
    }
}
