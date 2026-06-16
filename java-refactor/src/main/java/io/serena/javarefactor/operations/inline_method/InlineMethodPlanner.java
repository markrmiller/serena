package io.serena.javarefactor.operations.inline_method;

import io.serena.javarefactor.ast.ResolvedTarget;
import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.shared.SemanticTargetGate;
import io.serena.javarefactor.project.JavaProjectModel;
import java.io.IOException;
import io.serena.javarefactor.shared.ExpressionPurity;
import io.serena.javarefactor.shared.ExpressionPurityAnalyzer;
import io.serena.javarefactor.shared.ImportManager;
import io.serena.javarefactor.shared.MethodBodyModel;
import io.serena.javarefactor.shared.ProjectPathResolver;
import io.serena.javarefactor.shared.SourceText;

import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.StatementTree;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;

public final class InlineMethodPlanner {

    private final Path projectRoot;
    private final JavaProjectModel model;
    private final ExpressionPurityAnalyzer purityAnalyzer = new ExpressionPurityAnalyzer();
    private final EvaluationOrderGuard evaluationOrderGuard;
    private final SubstitutionEngine substitutionEngine;

    public InlineMethodPlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
        this.evaluationOrderGuard = new EvaluationOrderGuard(model, purityAnalyzer);
        this.substitutionEngine = new SubstitutionEngine(evaluationOrderGuard);
    }

    /** Returns a preview for source-editable single-expression methods. */
    public String inlineMethod(Map<String, Object> fields, boolean apply) {
        try {
            Path file = sourceFile(fields);
            String source = SourceText.read(model, file);
            String requestedName = stringField(fields, "methodName", "");
            String relativePath = PlannerSupport.relative(projectRoot, file);
            try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
                ResolvedTarget verified = SemanticTargetGate.require(index, relativePath, fields);
                MethodPlan method = findMethod(index, file, source, requestedName, fields, verified);
                SemanticTargetGate.confirmSelection(verified, method.semantic().element());
                validateMethod(index, method);

                List<SemanticIndex.SemanticCallSite> allCallSites = index.methodCallSites(method.semantic());
                // G009: when a callSiteSelection is present, narrow to the one requested invocation; otherwise all sites
                // are inlined (existing behavior). The selection is matched by file + character offset (preferred) or
                // file + one-based line/column, with an optional hint for tie-breaking.
                List<SemanticIndex.SemanticCallSite> targetCallSites = selectCallSites(allCallSites, fields);
                // G001: refuse rather than silently proceed when the number of call sites to rewrite exceeds the
                // configured cap (inline_method.max_call_sites, wired in by Main.applyConfiguredDefaults as maxCallSites;
                // default 100). A blast radius beyond the cap is a hard refusal so large inlines must be opted into.
                int maxCallSites = intField(fields, "maxCallSites", 100);
                if (targetCallSites.size() > maxCallSites) {
                    throw new Refusal(
                            "max_call_sites_exceeded",
                            "V2 inlineMethod refuses to rewrite " + targetCallSites.size() + " call sites: this exceeds the "
                                    + "configured max_call_sites limit of " + maxCallSites + ". Raise inline_method.max_call_sites "
                                    + "(or maxCallSites on the request) to opt in to a larger inline.");
                }
                // G032: when a call site lives outside the declaring file, the inlined body is transplanted across a type
                // boundary, so every reference it makes (this/super, helper methods, fields, types) must be proven
                // resolvable at the destination or the inline is refused. Same-file inline keeps the conservative path.
                List<PlannerSupport.TextEdit> importEdits = planCrossFileResolvability(file, source, method, targetCallSites);

                boolean deleteMethod = boolField(fields, "deleteMethod", false);
                List<PlannerSupport.TextEdit> edits = new ArrayList<>();
                int replacements = 0;
                for (SemanticIndex.SemanticCallSite site : targetCallSites) {
                    validateCallSite(index, method, site);
                    String replacement = substitutionEngine.substitute(method.body, method.parameterNames, method.semantic().isStatic(), site);
                    if (method.body.kind == MethodBodyKind.VOID_EXPRESSION_STATEMENT) {
                        edits.add(new PlannerSupport.TextEdit(site.file(), site.invocationRange().start(), site.invocationRange().end(), replacement, "INLINE_METHOD_CALL"));
                    } else if (method.body.kind == MethodBodyKind.THROW_STATEMENT) {
                        // A single-throw body inlines as a throw statement; the call site is a standalone statement (proven
                        // by validateCallSite), so replacing the invocation with `throw <expr>` and keeping the trailing `;`
                        // yields a valid `throw <expr>;` statement.
                        edits.add(new PlannerSupport.TextEdit(site.file(), site.invocationRange().start(), site.invocationRange().end(), "throw " + replacement, "INLINE_METHOD_CALL"));
                    } else {
                        edits.add(new PlannerSupport.TextEdit(site.file(), site.invocationRange().start(), site.invocationRange().end(), "(" + replacement + ")", "INLINE_METHOD_CALL"));
                    }
                    replacements++;
                }
                if (replacements == 0) {
                    throw new Refusal("call_site_not_found", "No javac-resolved call sites were found for the method.");
                }
                edits.addAll(importEdits);
                if (deleteMethod) {
                    validateDelete(method);
                    // G009: proveNoReferencesRemain compares replacements against the FULL resolved count from the index,
                    // so it naturally refuses deletion when only a subset of call sites was targeted by a selection.
                    proveNoReferencesRemain(index, method, replacements);
                    edits.add(new PlannerSupport.TextEdit(file, method.start, method.end, "", "INLINE_METHOD_DELETE"));
                }
                String keyJson = SemanticKey.from(method.semantic().element()).toJson();
                String semanticTargetJson = "{\"identity\":" + keyJson + ",\"semanticKey\":" + keyJson + "}";
                return ResponseBuilder.acceptedResult(projectRoot, "inlineMethod", apply, semanticTargetJson, edits, List.of(),
                        List.of(
                                "V2 inlineMethod resolves the method and call sites with javac Elements/source positions before editing.",
                                "Inline substitutions are limited to semantic call sites whose arguments and receivers preserve evaluation order.",
                                "Optional deletion is accepted only when every javac-resolved call site is handled and the target is safe-delete eligible."),
                        List.of("inline method semantic target resolved by javac"),
                        ResponseBuilder.DiagnosticDelta.unvalidated(),
                        false);
            }
        } catch (Refusal refusal) {
            return PlannerSupport.refusalJson("inlineMethod", apply, refusal.code, refusal.getMessage());
        } catch (SemanticTargetGate.Refused refused) {
            return PlannerSupport.refusalJson("inlineMethod", apply, refused.code(), refused.getMessage());
        } catch (Exception error) {
            return PlannerSupport.refusalJson("inlineMethod", apply, "inline_method_failed", error.getMessage());
        }
    }

    private MethodPlan findMethod(SemanticIndex index, Path file, String source, String requestedName, Map<String, Object> fields, ResolvedTarget verified) {
        int requestedLine = fields.containsKey("line") ? intField(fields, "line") : -1;
        // Prefer the gate-verified simple name so an overloaded method resolves to the proven semantic target.
        String selectionName = verified != null ? verified.element().getSimpleName().toString() : requestedName;
        SemanticIndex.SemanticMethod method = index.selectedMethod(file, requestedLine, selectionName);
        if (method == null) {
            throw new Refusal("method_not_supported", "No javac-resolved method matched the request.");
        }
        if (!file.equals(method.file().toAbsolutePath().normalize())) {
            throw new Refusal("method_not_supported", "Inline method requires a source-editable target in the requested file.");
        }
        if (!method.isPrivate() && !method.isStatic()) {
            throw new Refusal("method_not_supported", "Inline method supports private methods or static methods only.");
        }
        if (method.declarationRange() == null || method.bodyRange() == null) {
            throw new Refusal("method_not_supported", "Selected method does not expose editable declaration/body ranges.");
        }
        MethodBody body = parseBody(method, source);
        List<String> parameterNames = new ArrayList<>();
        for (SemanticIndex.SemanticParameter parameter : method.parameters()) {
            parameterNames.add(parameter.name());
        }
        return new MethodPlan(method.declarationRange().start(), method.declarationRange().end(), method.name(), parameterNames, body, method);
    }

    private void validateMethod(SemanticIndex index, MethodPlan method) {
        if (index.methodParticipatesInOverrideHierarchy(method.semantic())) {
            throw new Refusal("override_method_unsupported", "Inline method refuses methods that participate in an override hierarchy.");
        }
        if (method.semantic().element() instanceof ExecutableElement executable) {
            List<? extends TypeParameterElement> typeParameters = executable.getTypeParameters();
            if (!typeParameters.isEmpty()) {
                throw new Refusal("type_parameter_unsupported", "Inline method refuses generic methods because call-site type substitution is not yet safe.");
            }
        }
    }

    /**
     * Classifies the inline target's body from the javac-parsed statement AST (G014/G019). The {@link MethodBodyModel} is
     * bound to the <em>specific selected executable</em> by the source position of its body's opening brace
     * ({@code method.bodyRange().start()}), which is unique within the file. This disambiguates overloaded methods: where
     * the legacy {@code MethodBodyModel.fromSource(source, name)} returned a model only when exactly one method shared the
     * simple name — and otherwise fell through to an unsafe text match against the wrong overload's body — this binds the
     * model to the very method the gate selected. When the selected executable cannot be uniquely bound by position
     * (overloaded source that fails standalone attribution, or a method whose body offset the standalone compile does not
     * reproduce) the planner refuses for overloaded names rather than guessing; a uniquely-named method that fails
     * standalone attribution falls back to the conservative single-statement classifier so single-file inlines do not
     * regress.
     */
    private MethodBody parseBody(SemanticIndex.SemanticMethod method, String source) {
        MethodBodyModel bound = MethodBodyModel.fromSourceAtBody(source, method.name(), method.bodyRange().start());
        if (!bound.statements().isEmpty()) {
            return modelBody(bound, method.name(), method.returnType(), purityAnalyzer);
        }
        // The selected executable could not be modelled from its own body position. A name-only fallback is unsafe for an
        // overloaded method (it could classify the wrong overload's body), so refuse rather than guess. A uniquely-named
        // method is safe to model by name, so fall back to that path for it.
        if (isOverloaded(method)) {
            throw new Refusal("overloaded_method_body_unbound",
                    "Inline method cannot uniquely bind the body of overloaded method '" + method.name()
                            + "' to the selected executable; refusing rather than risk inlining the wrong overload.");
        }
        MethodBodyModel byName = MethodBodyModel.fromSource(source, method.name());
        if (!byName.statements().isEmpty()) {
            return modelBody(byName, method.name(), method.returnType(), purityAnalyzer);
        }
        throw new Refusal("method_body_unsupported",
                "Inline method could not model the body of '" + method.name() + "' from its declaring source.");
    }

    /** True when the declaring type declares more than one method (or constructor) sharing this method's simple name. */
    private static boolean isOverloaded(SemanticIndex.SemanticMethod method) {
        Element element = method.element();
        if (element == null || !(element.getEnclosingElement() instanceof TypeElement owner)) {
            return false;
        }
        String simpleName = element.getSimpleName().toString();
        int count = 0;
        for (Element enclosed : owner.getEnclosedElements()) {
            if ((enclosed.getKind() == ElementKind.METHOD || enclosed.getKind() == ElementKind.CONSTRUCTOR)
                    && enclosed.getSimpleName().contentEquals(simpleName)) {
                count++;
                if (count > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** AST-backed body classification used whenever javac can model the method body. */
    static MethodBody modelBody(MethodBodyModel model, String name, String returnType, ExpressionPurityAnalyzer purityAnalyzer) {
        boolean isVoid = "void".equals(returnType == null ? "" : returnType.trim());
        List<? extends StatementTree> statements = model.statements();
        if (statements.size() != 1) {
            // The contract is single-return-or-void; multiple statements (e.g. a guard plus a second return) cannot be
            // inlined into an expression position, so they are refused rather than partially substituted.
            throw new Refusal("statement_body_unsupported",
                    "Inline method supports a single return expression or a void expression statement only.");
        }
        if (model.usesSuper()) {
            throw new Refusal("super_body_unsupported",
                    "Inline method refuses bodies that reference super; the super binding cannot be reproduced at a call site.");
        }
        if (model.calls().contains(name)) {
            throw new Refusal("recursive_body_unsupported", "Inline method refuses recursive method bodies.");
        }
        StatementTree statement = statements.get(0);
        if (statement instanceof ReturnTree returnTree) {
            ExpressionTree expression = returnTree.getExpression();
            if (expression == null || isVoid) {
                throw new Refusal("statement_body_unsupported", "Void inline methods must contain one expression statement.");
            }
            return new MethodBody(MethodBodyKind.RETURN_EXPRESSION, expression.toString().trim());
        }
        if (statement instanceof ExpressionStatementTree expressionStatement) {
            if (!isVoid) {
                throw new Refusal("statement_body_unsupported", "A non-void inline method must return a single expression.");
            }
            ExpressionTree expressionTree = expressionStatement.getExpression();
            // Classify the real parsed statement-expression node (AST-backed) rather than re-lexing its text: a void
            // inline body must carry an observable effect, otherwise inlining it deletes behavior.
            if (purityAnalyzer.classify(expressionTree) == ExpressionPurity.PURE) {
                throw new Refusal("statement_body_unsupported", "Void inline methods must contain an effectful expression statement.");
            }
            return new MethodBody(MethodBodyKind.VOID_EXPRESSION_STATEMENT, expressionTree.toString().trim());
        }
        if (statement instanceof ThrowTree throwTree) {
            // HB-9: a single-`throw` body always escapes, so it inlines as a `throw <expr>;` statement at a standalone
            // statement call site. The thrown expression's checked exceptions are exactly the method's declared `throws`
            // set, so the existing checkedExceptionsCompatible gate at the call site proves handling compatibility.
            ExpressionTree thrown = throwTree.getExpression();
            if (thrown == null) {
                throw new Refusal("statement_body_unsupported", "Inline method refuses a throw statement without an operand.");
            }
            return new MethodBody(MethodBodyKind.THROW_STATEMENT, thrown.toString().trim());
        }
        throw new Refusal("statement_body_unsupported",
                "Inline method supports a single return expression, a void expression statement, or a single throw only.");
    }

    /**
     * Reference-completeness proof gating optional safe-delete (G019): the declaration is removed only once every
     * javac-resolved reference to the method has been rewritten. Method-reference call sites cannot be rewritten as
     * expression substitutions, and any count mismatch means a resolved reference would dangle after deletion.
     */
    private void proveNoReferencesRemain(SemanticIndex index, MethodPlan method, int rewrittenCallSites) {
        if (index.methodReferencePresent(method.semantic())) {
            throw new Refusal("method_reference_unsupported",
                    "Inline method cannot delete the declaration while a method-reference usage remains.");
        }
        int resolved = index.methodCallSites(method.semantic()).size();
        if (resolved != rewrittenCallSites) {
            throw new Refusal("incomplete_inline",
                    "Refusing to delete the method: " + (resolved - rewrittenCallSites)
                            + " javac-resolved reference(s) were not rewritten.");
        }
    }

    private void validateCallSite(SemanticIndex index, MethodPlan method, SemanticIndex.SemanticCallSite site) {
        if (site.methodReference()) {
            throw new Refusal("method_reference_unsupported", "Inline method refuses method-reference call sites.");
        }
        if ((method.body.kind == MethodBodyKind.VOID_EXPRESSION_STATEMENT || method.body.kind == MethodBodyKind.THROW_STATEMENT)
                && !site.statementExpression()) {
            throw new Refusal("void_call_context_unsupported",
                    "Void and single-throw inline methods can replace only standalone statement call sites (a throw cannot occupy an expression position).");
        }
        if (site.arguments().size() != method.parameterNames.size()) {
            throw new Refusal("argument_mismatch", "Call-site argument count does not match the inlined method.");
        }
        if (!index.checkedExceptionsCompatible(method.semantic(), site)) {
            throw new Refusal("checked_exception_unsupported", "Inline method refuses call sites that do not preserve checked-exception handling.");
        }
        for (SemanticIndex.SemanticArgument argument : site.arguments()) {
            if (!evaluationOrderGuard.reorderSafe(site.file(), argument.range(), argument.text())) {
                throw new Refusal("unsafe_argument", "Inline method accepts only arguments that preserve evaluation order when substituted.");
            }
        }
        String receiver = site.receiverText().trim();
        if (!receiver.isEmpty() && !evaluationOrderGuard.reorderSafe(site.file(), site.receiverRange(), receiver)) {
            throw new Refusal("unsafe_receiver", "Inline method accepts only receivers that preserve evaluation order when substituted.");
        }
        if (method.body.expression.contains("super") && !receiver.isEmpty()) {
            throw new Refusal("super_receiver_unsupported", "Inline method refuses super expressions through explicit receivers.");
        }
        if (!method.semantic().isStatic() && !receiver.isEmpty() && !"this".equals(receiver)) {
            if (!method.body.expression.contains("this.") || !receiver.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
                throw new Refusal("receiver_substitution_unsupported", "Inline method accepts explicit instance receivers only for simple this-member substitutions.");
            }
        }
    }

    /**
     * Proves that every reference made by the inlined body remains resolvable at each call site that lives outside the
     * declaring file (G032), and plans the type imports those cross-file call sites need. Same-file inline keeps the
     * existing conservative path and produces no extra edits.
     *
     * <p>A body transplanted into another type can only depend on its own parameters, literals, and types that are
     * resolvable (imported or same-package) at the destination. Any {@code this}/{@code super} use, helper-method call, or
     * field/static read of the declaring type would dangle once moved, so the inline is refused with a structured code
     * rather than emitting a reference that fails to compile. When the body cannot be modelled from the declaring source
     * (a multi-file target the standalone compile cannot attribute) cross-file resolvability cannot be proven and the
     * inline is likewise refused.
     */
    private List<PlannerSupport.TextEdit> planCrossFileResolvability(
            Path declaringFile, String declaringSource, MethodPlan method, List<SemanticIndex.SemanticCallSite> callSites) {
        Path declaring = declaringFile.toAbsolutePath().normalize();
        LinkedHashSet<Path> foreignFiles = new LinkedHashSet<>();
        for (SemanticIndex.SemanticCallSite site : callSites) {
            Path siteFile = site.file().toAbsolutePath().normalize();
            if (!siteFile.equals(declaring)) {
                foreignFiles.add(siteFile);
            }
        }
        if (foreignFiles.isEmpty()) {
            return List.of();
        }
        // G014: bind the cross-file resolvability model to the SELECTED executable by its body position, not by simple
        // name, so an overloaded declaring method proves resolvability against the right overload's body.
        MethodBodyModel bodyModel = MethodBodyModel.fromSourceAtBody(
                declaringSource, method.name, method.semantic().bodyRange().start());
        if (bodyModel.methodKey() == null && bodyModel.statements().isEmpty()) {
            throw new Refusal("cross_site_resolvability_unproven",
                    "Inline method cannot prove the body is resolvable at call sites in other files without uniquely binding the selected declaring method's body.");
        }
        if (bodyModel.usesThis() || bodyModel.usesSuper()) {
            throw new Refusal("cross_site_reference_unsupported",
                    "Inline method refuses cross-file inlining of a body that reads instance state via this/super.");
        }
        if (!bodyModel.calls().isEmpty()) {
            throw new Refusal("cross_site_reference_unsupported",
                    "Inline method refuses cross-file inlining of a body that calls another member of the declaring type.");
        }
        Set<String> parameters = new HashSet<>(method.parameterNames);
        // Pure external reads only: a name assigned/declared inside the body (a local) is produced locally and need not
        // resolve at the call site. The canonical reads() set now retains read-modify-write locals (Blocker 6), so use
        // the derived view to keep proving resolvability against genuinely-external references.
        for (String read : bodyModel.pureExternalReads()) {
            if (!parameters.contains(read)) {
                throw new Refusal("cross_site_reference_unsupported",
                        "Inline method refuses cross-file inlining of a body that reads '" + read
                                + "', which is not a parameter and may not be accessible at the call site.");
            }
        }
        List<String> referencedTypeNames = new ArrayList<>(bodyModel.referencedTypeKeys());
        return planForeignImportEdits(new ArrayList<>(foreignFiles), referencedTypeNames);
    }

    /**
     * Reads every foreign call-site file and plans the type imports its transplanted body needs (G014/G032). A foreign
     * file that cannot be read offers no proof that the inlined body resolves at that site — its imports and package
     * cannot be inspected and the import plan cannot be computed — so the read failure is a hard {@link Refusal} rather
     * than a silently-ignored exception. Package-private so the cross-file resolvability proof can be exercised directly.
     */
    List<PlannerSupport.TextEdit> planForeignImportEdits(List<Path> foreignFiles, List<String> referencedTypeNames) {
        List<PlannerSupport.TextEdit> importEdits = new ArrayList<>();
        for (Path foreignFile : foreignFiles) {
            String foreignSource;
            try {
                foreignSource = SourceText.read(model, foreignFile);
            } catch (IOException unreadable) {
                throw new Refusal("cross_site_resolvability_unproven",
                        "Inline method cannot prove the body resolves at the call site in '" + foreignFile
                                + "': the file could not be read (" + unreadable.getMessage() + ").");
            }
            if (!referencedTypeNames.isEmpty()) {
                importEdits.addAll(new ImportManager(foreignSource)
                        .planTypesForBody(foreignFile, referencedTypeNames, "INLINE_METHOD_IMPORT"));
            }
        }
        return importEdits;
    }

    /**
     * Filters the full javac-resolved call-site list to the one selected call site when the request carries a
     * {@code callSiteSelection} field (G009). The selection may identify a site by:
     * <ul>
     *   <li>{@code file} — project-relative path of the file containing the call site (omit for same-file inline).</li>
     *   <li>{@code startOffset} / {@code endOffset} — zero-based character offsets into the call-site file (preferred).</li>
     *   <li>{@code startLine} / {@code startColumn} — one-based line/column when offsets are unavailable.</li>
     *   <li>{@code hint} — a short distinguishing text (e.g. a receiver or argument snippet) used to break ties.</li>
     * </ul>
     * When no selection is present, the full list is returned unchanged (all-sites mode, current behavior).
     */
    @SuppressWarnings("unchecked")
    private List<SemanticIndex.SemanticCallSite> selectCallSites(
            List<SemanticIndex.SemanticCallSite> allCallSites, Map<String, Object> fields) {
        Object selectionObj = fields.get("callSiteSelection");
        if (!(selectionObj instanceof Map<?, ?> rawMap)) {
            return allCallSites;
        }
        Map<String, Object> sel = (Map<String, Object>) rawMap;

        String selFile = stringField(sel, "file", null);
        int startOffset = intField(sel, "startOffset", -1);
        int endOffset = intField(sel, "endOffset", -1);
        int startLine = intField(sel, "startLine", -1);
        int startColumn = intField(sel, "startColumn", -1);
        String hint = stringField(sel, "hint", null);

        List<SemanticIndex.SemanticCallSite> candidates = allCallSites;

        // Filter by file when provided.
        if (selFile != null && !selFile.isBlank()) {
            Path targetFile = projectRoot.resolve(selFile).toAbsolutePath().normalize();
            List<SemanticIndex.SemanticCallSite> byFile = new ArrayList<>();
            for (SemanticIndex.SemanticCallSite site : candidates) {
                if (site.file().toAbsolutePath().normalize().equals(targetFile)) {
                    byFile.add(site);
                }
            }
            candidates = byFile;
        }

        // Filter by character offset range (preferred over line/col). When endOffset is also provided the invocation
        // range must fully contain the selected span [startOffset, endOffset); when only startOffset is given any
        // invocation range that contains it qualifies.
        if (startOffset >= 0) {
            int spanEnd = endOffset >= 0 ? endOffset : startOffset + 1;
            List<SemanticIndex.SemanticCallSite> byOffset = new ArrayList<>();
            for (SemanticIndex.SemanticCallSite site : candidates) {
                SemanticIndex.SourceRange r = site.invocationRange();
                if (r != null && r.start() <= startOffset && spanEnd <= r.end()) {
                    byOffset.add(site);
                }
            }
            candidates = byOffset;
        } else if (startLine >= 0 && startColumn >= 0) {
            candidates = filterByLineCol(candidates, startLine, startColumn);
        }

        // Use hint as a tie-breaker when multiple candidates remain.
        if (hint != null && !hint.isBlank() && candidates.size() > 1) {
            List<SemanticIndex.SemanticCallSite> hinted = new ArrayList<>();
            for (SemanticIndex.SemanticCallSite site : candidates) {
                try {
                    String siteSource = SourceText.read(model, site.file());
                    SemanticIndex.SourceRange r = site.invocationRange();
                    if (r != null && r.end() <= siteSource.length()
                            && siteSource.substring(r.start(), r.end()).contains(hint)) {
                        hinted.add(site);
                    }
                } catch (IOException ignored) {
                    // Cannot read site source; skip this candidate for hint matching.
                }
            }
            if (!hinted.isEmpty()) {
                candidates = hinted;
            }
        }

        if (candidates.isEmpty()) {
            throw new Refusal("call_site_not_found",
                    "The callSiteSelection did not match any javac-resolved call site for the method.");
        }
        if (candidates.size() > 1) {
            throw new Refusal("call_site_ambiguous",
                    "The callSiteSelection matched " + candidates.size()
                            + " call sites; provide a file path, character offset, or line/column to narrow the selection.");
        }
        return candidates;
    }

    /**
     * Filters candidates to those whose {@link SemanticIndex.SemanticCallSite#invocationRange()} contains the character
     * offset that corresponds to {@code startLine}/{@code startColumn} (one-based) in the candidate's source file.
     */
    private List<SemanticIndex.SemanticCallSite> filterByLineCol(
            List<SemanticIndex.SemanticCallSite> candidates, int startLine, int startColumn) {
        Map<Path, String> sourceCache = new HashMap<>();
        List<SemanticIndex.SemanticCallSite> result = new ArrayList<>();
        for (SemanticIndex.SemanticCallSite site : candidates) {
            Path siteFile = site.file().toAbsolutePath().normalize();
            if (!sourceCache.containsKey(siteFile)) {
                String src = null;
                try { src = SourceText.read(model, siteFile); } catch (IOException ignored) { }
                sourceCache.put(siteFile, src);
            }
            String src = sourceCache.get(siteFile);
            if (src == null) {
                continue;
            }
            int offset = lineColToOffset(src, startLine, startColumn);
            SemanticIndex.SourceRange r = site.invocationRange();
            if (offset >= 0 && r != null && r.start() <= offset && offset < r.end()) {
                result.add(site);
            }
        }
        return result;
    }

    /** Converts one-based {@code line} and {@code column} to a zero-based character offset, or {@code -1} when out of range. */
    private static int lineColToOffset(String source, int line, int column) {
        int currentLine = 1, currentCol = 1;
        for (int i = 0; i < source.length(); i++) {
            if (currentLine == line && currentCol == column) {
                return i;
            }
            if (source.charAt(i) == '\n') {
                currentLine++;
                currentCol = 1;
            } else {
                currentCol++;
            }
        }
        return -1;
    }

    private void validateDelete(MethodPlan method) {
        if (!method.semantic().isPrivate()) {
            throw new Refusal("delete_public_api_unsupported", "Inline method deletion requires a private target, matching safe-delete public API protection.");
        }
    }

    private Path sourceFile(Map<String, Object> fields) {
        String relative = stringField(fields, "relativePath", "");
        if (relative.isBlank()) {
            throw new Refusal("missing_relative_path", "relativePath is required.");
        }
        try {
            return ProjectPathResolver.resolveProjectRelative(projectRoot, relative, "relativePath");
        } catch (ProjectPathResolver.Violation refusal) {
            throw new Refusal(refusal.code(), refusal.getMessage());
        }
    }

    private int intField(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new Refusal("missing_" + name, name + " is required.");
    }

    private int intField(Map<String, Object> fields, String name, int fallback) {
        Object value = fields.get(name);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private boolean boolField(Map<String, Object> fields, String name, boolean fallback) {
        Object value = fields.get(name);
        return value instanceof Boolean flag ? flag : fallback;
    }

    private String stringField(Map<String, Object> fields, String name, String fallback) {
        Object value = fields.get(name);
        return value instanceof String text ? text : fallback;
    }


    enum MethodBodyKind {
        RETURN_EXPRESSION,
        VOID_EXPRESSION_STATEMENT,
        THROW_STATEMENT
    }

    record MethodBody(MethodBodyKind kind, String expression) {}

    private static final class MethodPlan {
        final int start;
        final int end;
        final String name;
        final List<String> parameterNames;
        final MethodBody body;
        private final SemanticIndex.SemanticMethod semantic;

        private MethodPlan(int start, int end, String name, List<String> parameterNames, MethodBody body, SemanticIndex.SemanticMethod semantic) {
            this.start = start;
            this.end = end;
            this.name = name;
            this.parameterNames = parameterNames;
            this.body = body;
            this.semantic = semantic;
        }

        private SemanticIndex.SemanticMethod semantic() {
            return semantic;
        }
    }

    static final class Refusal extends RuntimeException {
        private final String code;

        Refusal(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
