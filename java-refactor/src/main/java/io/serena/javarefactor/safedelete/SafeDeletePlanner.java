package io.serena.javarefactor.safedelete;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.serena.javarefactor.edits.PlannerSupport.refusalJson;
import static io.serena.javarefactor.edits.PlannerSupport.relative;
import static io.serena.javarefactor.edits.PlannerSupport.sha256;
import static io.serena.javarefactor.edits.PlannerSupport.simpleName;

public final class SafeDeletePlanner {
    public String plan(JavaProjectModel model, String relativePath, long line, long column, boolean allowPublicApi) throws IOException {
        return plan(model, relativePath, line, column, allowPublicApi, TargetHints.NONE);
    }

    public String plan(JavaProjectModel model, String relativePath, long line, long column, boolean allowPublicApi, TargetHints hints)
            throws IOException {
        try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
            RefactorAnalysisResult analysis = index.resolveTarget(relativePath, line, column, hints.nameHint());
            if (analysis.target() == null) {
                return refusalJson("target_not_found", "No refactorable Java symbol was found at the requested position.", "[]");
            }
            // Target-identity gate (before every other check): the caller named a symbol; prove the position-resolved
            // element IS that symbol before planning any edit for it.
            String hintMismatch = hints.mismatch(analysis.target());
            if (hintMismatch != null) {
                return refusalJson("target_mismatch", "Refused to plan a safe delete against an unverified target: " + hintMismatch, "[]");
            }
            // Centralized target-origin editability gate (shared across all planners): the declaration being deleted
            // must itself originate from an editable, non-generated, in-tree project source file (not a generated root,
            // an external/dependency source attachment, or a classpath-only binary element).
            String originRefusal = index.targetOriginRefusal(analysis.target());
            if (originRefusal != null) {
                return refusalJson("non_editable_target", originRefusal, "[]");
            }
            if ("PARAMETER".equals(analysis.target().key().kind())) {
                return planParameterDelete(index, model, analysis.target());
            }
            if (!allowPublicApi && isPublicApi(analysis.target())) {
                return refusalJson("public_api", "Safe delete refuses public/protected Java API unless explicitly allowed.",
                        index.referencesJsonRich(model.projectRoot(), analysis.references()));
            }
            List<IdentifierSpan> externalReferences = analysis.references().stream()
                    .filter(span -> span.startOffset() != analysis.target().span().startOffset() || !span.file().equals(analysis.target().span().file()))
                    .toList();
            if (!externalReferences.isEmpty()) {
                return refusalJson("semantic_references_exist", "Safe delete target still has semantic references.",
                        index.referencesJsonRich(model.projectRoot(), externalReferences));
            }
            String targetKind = analysis.target().key().kind();
            // Per-construct gate (replaces a broad "standalone block locals only" refusal): a block-statement local —
            // whether on its own line or sharing a line with siblings, single- or multi-declarator — is deletable with
            // an exact span (handled in declarationDeleteJson); genuinely-undeletable constructs (resource variable,
            // catch parameter, enhanced-for/for-init variable) are refused with their own semantic reason.
            if ("LOCAL_VARIABLE".equals(targetKind) || "RESOURCE_VARIABLE".equals(targetKind) || "EXCEPTION_PARAMETER".equals(targetKind)) {
                String constructRefusal = index.localDeleteConstructRefusal(analysis.target().element());
                if (constructRefusal != null) {
                    return refusalJson("unsupported_local_variable_delete", constructRefusal, "[]");
                }
            }
            // A variable/field whose initializer is absent-but-required or performs observable side effects cannot be
            // safely deleted: removing the declaration would silently drop that effect (an unused local/field whose
            // initializer is `compute()`, `new T()`, `arr[i] = x`, `i++`, etc. still runs the effect today). Reuse the
            // inline side-effect detector, whose two true-branches are exactly "no analyzable initializer" (absent-but-
            // required) and "impure initializer" (static/instance field init included).
            if (("FIELD".equals(targetKind) || "LOCAL_VARIABLE".equals(targetKind) || "RESOURCE_VARIABLE".equals(targetKind))
                    && index.safeDeleteInitializerHasSideEffects(analysis.target().element())) {
                return refusalJson("side_effecting_initializer",
                        "Safe delete refuses a declaration whose initializer is absent-but-required or has observable "
                                + "side effects (method/constructor calls, array creation, assignments, or "
                                + "in/decrements); deleting it would drop that effect. Extract the effect to its own "
                                + "statement, then delete the declaration.",
                        "[]");
            }
            String hierarchyRefusal = methodHierarchyRefusal(index, analysis.target());
            if (hierarchyRefusal != null) {
                return refusalJson("method_hierarchy_relationship", hierarchyRefusal, "[]");
            }
            // Whole-file delete is only safe when the file parsed cleanly: under an incomplete/failed parse javac's
            // top-level type count can be wrong (e.g. a second type with a syntax error is dropped), which would delete a
            // file that still holds another type. When the model reports any diagnostic for the file, fall back to the
            // declaration-only delete instead (M7).
            // Safety surface (warning-only): incomplete-classpath/annotation-processing caveats, plus a reflection/
            // resource caveat when deleting an API surface (reachable here only with allow_public_api).
            List<String> warnings = PlannerSupport.modelSafetyWarnings(model);
            if (isPublicApi(analysis.target())) {
                warnings.add(PlannerSupport.reflectionResourceCaveat("the deleted declaration"));
            }
            if (isTopLevelFileDelete(model.projectRoot(), analysis.target())
                    && index.topLevelTypeDeclarationCount(analysis.target().span().file()) == 1
                    && !modelReportsDiagnosticsForFile(model, analysis.target().span().file())) {
                return fileDeleteJson(model.projectRoot(), analysis.target(), warnings);
            }
            return declarationDeleteJson(index, model.projectRoot(), analysis.target(), SemanticIndex.charsetOf(model), warnings);
        }
    }

    /**
     * Conservative parameter deletion (plan section 9). Supported only when the enclosing method is {@code private} and
     * does not participate in any override/interface/abstract hierarchy, and the parameter has no semantic uses in the
     * method body. The parameter is removed from the declaration's parameter list (comma surgery, varargs and parameter
     * annotations handled) and the corresponding positional argument is removed at every call site. If any call site's
     * argument cannot be precisely located, or the method is used as a method reference, the whole delete is refused
     * rather than producing a partial edit. Parameters of non-private or hierarchy-participating methods are refused
     * with the existing-style message.
     */
    private static String planParameterDelete(SemanticIndex index, JavaProjectModel model, ResolvedTarget target) throws IOException {
        Element parameter = target.element();
        if (!(parameter.getEnclosingElement() instanceof ExecutableElement method)
                || !method.getModifiers().contains(Modifier.PRIVATE)
                || index.parameterParticipatesInHierarchy(parameter)) {
            return refusalJson("unsupported_parameter_delete",
                    "Safe delete supports removing a parameter only from a private method that does not participate in "
                            + "an override/interface/abstract hierarchy. Remove this parameter manually.",
                    "[]");
        }
        if (index.parameterHasUses(parameter)) {
            return refusalJson("parameter_in_use",
                    "Safe delete refuses removing a parameter that is still used in the method body.", "[]");
        }
        SemanticIndex.ParameterDeletionPlan plan = index.planParameterDeletion(parameter);
        if (!plan.accepted()) {
            return refusalJson("unsupported_parameter_delete", plan.refusalReason(), "[]");
        }
        // Generated/dependency-source safety gate parity: every call-site file the parameter delete would edit (a private
        // method's sites are in its own file, but gate explicitly for defense) must be editable, non-generated source.
        java.util.LinkedHashSet<java.nio.file.Path> affected = new java.util.LinkedHashSet<>();
        affected.add(target.span().file());
        for (SemanticIndex.ParameterEdit edit : plan.edits()) {
            affected.add(edit.file());
        }
        String nonEditable = index.detectNonEditableFiles(affected);
        if (nonEditable != null) {
            return refusalJson("non_editable_target", nonEditable, "[]");
        }
        return parameterDeleteJson(model.projectRoot(), target, plan.edits(), PlannerSupport.modelSafetyWarnings(model));
    }

    /**
     * Builds the accepted V1 workspace-edit JSON for a parameter deletion: every {@link SemanticIndex.ParameterEdit} is
     * an empty-text replacement. {@link PlannerSupport#changesJson} groups them per file and computes each file's
     * {@code oldSha256} once.
     */
    private static String parameterDeleteJson(Path projectRoot, ResolvedTarget target, List<SemanticIndex.ParameterEdit> edits, List<String> warnings) throws IOException {
        List<PlannerSupport.TextEdit> textEdits = new ArrayList<>();
        for (SemanticIndex.ParameterEdit edit : edits) {
            textEdits.add(new PlannerSupport.TextEdit(edit.file(), edit.start(), edit.end(), "", "PARAMETER"));
        }
        return acceptedJson(target, projectRoot, PlannerSupport.changesJson(projectRoot, textEdits), "[]", textEdits.size(), 0, warnings);
    }

    /**
     * Refuses deleting a method that participates in an override/implementation relationship. Removing such a method
     * orphans subtype {@code @Override} declarations, breaks an interface/abstract contract, or silently changes
     * dispatch — none of which the no-references check can detect, because overrides are not references to the target.
     */
    private static String methodHierarchyRefusal(SemanticIndex index, ResolvedTarget target) {
        if (!"METHOD".equals(target.key().kind())) {
            return null;
        }
        Element method = target.element();
        // Abstract and interface methods define a contract every implementor must satisfy. Deleting only the declaration
        // would either orphan implementations or break the contract; V1 does not implement whole-override-group delete,
        // so these are refused by default (independent of allow_public_api_delete).
        boolean enclosingIsInterface = method.getEnclosingElement() instanceof TypeElement owner
                && owner.getKind() == ElementKind.INTERFACE;
        if (method.getModifiers().contains(Modifier.ABSTRACT) || enclosingIsInterface) {
            return "Safe delete refuses an abstract or interface method: deleting only its declaration would break the "
                    + "contract its implementors satisfy. Deleting the whole override group is not supported in v1.";
        }
        List<Element> group = index.overrideGroup(method);
        if (group.size() > 1) {
            return "Safe delete refuses a method that overrides or is overridden by other methods in the project; "
                    + "deleting it would orphan an @Override or break a supertype contract.";
        }
        if (index.overridesLibraryMethod(group)) {
            return "Safe delete refuses a method that overrides a JDK or dependency method.";
        }
        return null;
    }

    private static String declarationDeleteJson(SemanticIndex index, Path projectRoot, ResolvedTarget target, java.nio.charset.Charset charset, List<String> warnings) throws IOException {
        SemanticIndex.DeclarationRange declaration = index.declarationRange(target.element());
        if (declaration == null) {
            return refusalJson("unsupported_declaration_range", "Safe delete could not determine a precise declaration range for the target.", "[]");
        }
        String source = Files.readString(declaration.file(), charset);
        String kind = target.key().kind();
        boolean local = kind.equals("LOCAL_VARIABLE");
        boolean variableKind = kind.equals("FIELD") || local || kind.equals("RESOURCE_VARIABLE");
        int[] range;
        if (variableKind && SemanticIndex.isMultiDeclarator(source, declaration.start(), declaration.end())) {
            // Multi-declarator: a local gets exact comma surgery that keeps the shared type and the sibling declarators;
            // a field stays refused (a field multi-declarator carries member visibility/annotation subtleties beyond v1
            // scope).
            if (!local) {
                return refusalJson("ambiguous_multi_declarator", "Safe delete refuses ambiguous multi-declarator declarations.", "[]");
            }
            range = SemanticIndex.multiDeclaratorLocalDeleteRange(source, declaration.start(), declaration.end(),
                    (int) target.span().startOffset(), (int) target.span().endOffset());
            if (range == null) {
                return refusalJson("ambiguous_multi_declarator",
                        "Safe delete could not isolate the selected declarator within a multi-declarator declaration.", "[]");
            }
        } else {
            // Single declarator. A field sharing its line with other code is still refused (whole-line removal would
            // delete the sibling code, and a field rarely shares a line). A local uses the exact-span removal, which
            // leaves same-line sibling statements intact rather than refusing them.
            if (variableKind && !local && SemanticIndex.declarationSharesLineWithOtherCode(source, declaration.start(), declaration.end())) {
                return refusalJson("shared_line_declaration",
                        "Safe delete refuses a declaration that shares a line with other code; deleting the line would "
                                + "remove that code too. Move the declaration to its own line first.", "[]");
            }
            range = local
                    ? SemanticIndex.localDeclarationDeleteRange(source, declaration.start(), declaration.end())
                    : SemanticIndex.expandDeclarationRangeForDelete(source, declaration.start(), declaration.end());
        }
        List<PlannerSupport.TextEdit> textEdits = List.of(
                new PlannerSupport.TextEdit(declaration.file(), range[0], range[1], "", "DECLARATION"));
        return acceptedJson(target, projectRoot, PlannerSupport.changesJson(projectRoot, textEdits), "[]", 1, 0, warnings);
    }

    private static String fileDeleteJson(Path projectRoot, ResolvedTarget target, List<String> warnings) throws IOException {
        String op = PlannerSupport.deleteFileOp(projectRoot, relative(projectRoot, target.span().file()));
        return acceptedJson(target, projectRoot, "[]", "[" + op + "]", 0, 1, warnings);
    }

    private static String acceptedJson(ResolvedTarget target, Path projectRoot, String changesJson, String fileOps, int edits, int ops, List<String> warnings) {
        return "{\"accepted\":true,\"target\":" + target.toJson(projectRoot) + ",\"workspaceEdit\":{"
                + "\"changes\":" + changesJson + ",\"fileOperations\":" + fileOps + ","
                + "\"warnings\":" + PlannerSupport.warningsJson(warnings) + ",\"preconditions\":[\"no semantic references outside declaration\"],"
                + "\"stats\":{\"editCount\":" + edits + ",\"fileOperationCount\":" + ops + "}},"
                + "\"stats\":{\"editCount\":" + edits + ",\"fileOperationCount\":" + ops + "}}";
    }

    /**
     * Whether the validated model reports any compiler diagnostic for {@code file}. Diagnostics are formatted as
     * {@code <absolute-source-path>:line:col: message} (see {@link JavacSession}); allow_incomplete_analysis routes them
     * into warnings instead of errors, so both lists are checked. A match means the file did not parse/analyze cleanly,
     * making javac's top-level-type count unreliable for the whole-file delete decision (M7).
     */
    private static boolean modelReportsDiagnosticsForFile(JavaProjectModel model, Path file) {
        String prefix = file.toAbsolutePath().normalize() + ":";
        return Stream.concat(model.errors().stream(), model.warnings().stream()).anyMatch(diagnostic -> diagnostic.startsWith(prefix));
    }

    private static boolean isPublicApi(ResolvedTarget target) {
        Set<Modifier> modifiers = target.element().getModifiers();
        return modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED);
    }

    private static boolean isTopLevelFileDelete(Path projectRoot, ResolvedTarget target) {
        if (!Set.of("CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION_TYPE").contains(target.key().kind())) {
            return false;
        }
        String simpleName = simpleName(target.key().name());
        Path fileName = target.span().file().getFileName();
        return fileName != null && fileName.toString().equals(simpleName + ".java") && target.span().file().startsWith(projectRoot.toAbsolutePath().normalize());
    }

}
