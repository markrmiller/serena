package io.serena.javarefactor.operations.change_signature;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.shared.ImportConflictResolvers;
import io.serena.javarefactor.shared.ImportManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * G006 architecture unit: call-site and method-reference rewriting. Given the resolved override group and the desired
 * signature, it produces the edits that retarget every caller — reordering/dropping/defaulting positional arguments at
 * ordinary invocations and constructor calls, and rewriting (or refusing) method references using the javac-backed SAM
 * conformance verdict ({@link SemanticIndex#methodReferenceVerdict}) rather than a textual arity heuristic. All semantic
 * facts (argument reorder-safety, default-expression resolution, functional-interface conformance) come from the
 * {@link SemanticIndex}; the unit only assembles edits and structured refusals.
 */
public final class CallSiteRewriter {
    private static final Pattern QUALIFIED_DEFAULT_MEMBER = Pattern.compile(
            "^([a-z]\\w*(?:\\.[a-z]\\w*)+\\.[A-Z]\\w*(?:\\.[A-Z]\\w*)*)(\\..*)$");

    private final SemanticIndex index;
    private final boolean allowRemovedSideEffectingArguments;

    public CallSiteRewriter(SemanticIndex index, boolean allowRemovedSideEffectingArguments) {
        this.index = index;
        this.allowRemovedSideEffectingArguments = allowRemovedSideEffectingArguments;
    }

    public List<PlannerSupport.TextEdit> callSiteEdits(
            List<MethodMatch> declarations,
            String newName,
            String returnType,
            List<ParameterSpec> desired,
            String returnConversion) throws SignatureRefusal {
        Map<String, PlannerSupport.TextEdit> edits = new LinkedHashMap<>();
        List<String> desiredParameterTypes = MethodSignatureModel.desiredParameterTypes(desired);
        for (MethodMatch declaration : declarations) {
            for (SemanticIndex.SemanticCallSite site : index.methodCallSites(declaration.semantic())) {
                if (site.methodReference()) {
                    rewriteMethodReference(declaration, site, newName, returnType, desiredParameterTypes, edits);
                    continue;
                }
                rewriteInvocation(declaration, site, newName, returnType, desired, returnConversion, edits);
            }
        }
        return new ArrayList<>(edits.values());
    }

    /**
     * G006: rewrite a method reference to the canonical semantic standard. The javac-backed verdict resolves the target
     * functional interface's single abstract method and decides whether the new signature still conforms:
     * <ul>
     *   <li>SAFE with a name rewrite required (the executable was renamed but still conforms) → emit a name-token edit;</li>
     *   <li>SAFE with no rewrite required (conforms and the name is unchanged) → emit nothing;</li>
     *   <li>INCOMPATIBLE / UNRESOLVED → refuse with the verdict's structured reason (fail closed).</li>
     * </ul>
     * This replaces the old over-narrow rule that refused every arity/return/type change and only ever allowed a
     * name-only rewrite when the shape was byte-for-byte unchanged.
     */
    private void rewriteMethodReference(
            MethodMatch declaration,
            SemanticIndex.SemanticCallSite site,
            String newName,
            String returnType,
            List<String> desiredParameterTypes,
            Map<String, PlannerSupport.TextEdit> edits) throws SignatureRefusal {
        SemanticIndex.MethodReferenceVerdict verdict =
                index.methodReferenceVerdict(site, desiredParameterTypes, returnType, newName);
        if (verdict.incompatible() || verdict.unresolved()) {
            throw new SignatureRefusal(verdict.code(), verdict.message());
        }
        if (verdict.nameRewriteRequired()) {
            PlannerSupport.TextEdit edit = new PlannerSupport.TextEdit(
                    site.file(),
                    site.nameRange().start(),
                    site.nameRange().end(),
                    newName,
                    "CHANGE_SIGNATURE_METHOD_REFERENCE");
            edits.put(site.file().toAbsolutePath().normalize() + ":" + site.nameRange().start() + ":" + site.nameRange().end(), edit);
        }
    }

    private void rewriteInvocation(
            MethodMatch declaration,
            SemanticIndex.SemanticCallSite site,
            String newName,
            String returnType,
            List<ParameterSpec> desired,
            String returnConversion,
            Map<String, PlannerSupport.TextEdit> edits) throws SignatureRefusal {
        List<String> arguments = site.arguments().stream().map(SemanticIndex.SemanticArgument::text).toList();
        if (arguments.size() > declaration.parameters().size()) {
            throw new SignatureRefusal("call_site_arity_mismatch", "Call site has more arguments than the current signature.");
        }
        List<String> rewritten = new ArrayList<>();
        Set<Integer> consumedIndexes = new LinkedHashSet<>();
        for (int desiredIndex = 0; desiredIndex < desired.size(); desiredIndex++) {
            ParameterSpec parameter = desired.get(desiredIndex);
            int oldIndex = MethodSignatureModel.oldIndex(parameter, declaration, desiredIndex, desired);
            if (oldIndex >= 0) {
                if (oldIndex < arguments.size()) {
                    rewritten.add(arguments.get(oldIndex));
                    consumedIndexes.add(oldIndex);
                    continue;
                }
                throw new SignatureRefusal("call_site_arity_mismatch", "Call site has fewer arguments than the current signature.");
            }
            // G005: resolve the raw default expression in THIS call site's lexical scope before splicing it.
            String unresolvedLocation = index.defaultExpressionResolutionFailure(site, parameter.defaultValue());
            if (unresolvedLocation != null) {
                throw new SignatureRefusal(
                        "DEFAULT_ARGUMENT_UNRESOLVED",
                        "Added parameter '" + parameter.name() + "' default '" + parameter.defaultValue()
                                + "' references a type that is not resolvable or accessible at the call site "
                                + unresolvedLocation + ". Use a fully-qualified default or one whose types are "
                                + "visible at every call site.");
            }
            String defaultValue = defaultValueForCallSite(site.file(), parameter.defaultValue(), edits);
            if (defaultValue == null || defaultValue.isBlank()) {
                throw new SignatureRefusal("DEFAULT_ARGUMENT_UNRESOLVED", "Added parameters require defaultValue for every call site.");
            }
            rewritten.add(defaultValue);
        }
        for (int indexArg = 0; indexArg < arguments.size(); indexArg++) {
            // G004/G001: dropping an argument removes its evaluation; only safe when javac proves it reorder/removal safe,
            // or the caller opted in to removing a side-effecting argument.
            if (!consumedIndexes.contains(indexArg)
                    && !index.isCallArgumentReorderSafe(site, indexArg)
                    && !allowRemovedSideEffectingArguments) {
                throw new SignatureRefusal("CALL_SITE_ARGUMENT_HAS_SIDE_EFFECTS", "V2 change signature refuses to drop side-effecting call-site arguments. Set allow_removed_side_effecting_arguments to opt in once you have confirmed dropping the argument's evaluation is safe.");
            }
        }
        String methodCall = newName + "(" + String.join(", ", rewritten) + ")";
        String replacement = methodCall;
        int replaceStart = site.nameRange().start();
        if (declaration.constructor()) {
            CharSequence callSource = index.sourceText(site.file());
            if (callSource == null) {
                throw new SignatureRefusal("OVERRIDE_GROUP_INCOMPLETE", "Cannot resolve source text for constructor call site in " + site.file() + ".");
            }
            replaceStart = constructorArgumentListStart(callSource, site);
            replacement = "(" + String.join(", ", rewritten) + ")";
        } else if (returnConversionApplies(declaration, returnType, returnConversion, site)) {
            // HB-05: a return conversion must wrap the ENTIRE invocation expression, including the receiver/qualifier,
            // so `service.oldName(x)` becomes `adapt(service.newName(x))` rather than `service.adapt(newName(x))`. Start
            // the replacement at the invocation span (not the method-name token) and carry the original receiver prefix
            // (the source between the invocation start and the name token, e.g. "service.", "this.", or "") into the
            // wrapped expression.
            CharSequence callSource = index.sourceText(site.file());
            if (callSource == null) {
                throw new SignatureRefusal("OVERRIDE_GROUP_INCOMPLETE", "Cannot resolve source text for call site in " + site.file() + ".");
            }
            int invocationStart = site.invocationRange().start();
            String receiverPrefix = callSource.subSequence(invocationStart, site.nameRange().start()).toString();
            replacement = returnConversion.replace("$return", receiverPrefix + methodCall);
            replaceStart = invocationStart;
        }
        PlannerSupport.TextEdit edit = new PlannerSupport.TextEdit(site.file(), replaceStart, site.invocationRange().end(), replacement, "CHANGE_SIGNATURE_CALL");
        edits.put(site.file().toAbsolutePath().normalize() + ":" + replaceStart + ":" + site.invocationRange().end(), edit);
    }

    private int constructorArgumentListStart(CharSequence source, SemanticIndex.SemanticCallSite site) throws SignatureRefusal {
        int start = Math.max(0, site.nameRange().end());
        int end = Math.min(source.length(), site.invocationRange().end());
        for (int offset = start; offset < end; offset++) {
            if (source.charAt(offset) == '(') {
                return offset;
            }
        }
        throw new SignatureRefusal("call_site_arity_mismatch", "Cannot locate constructor argument list at call site.");
    }

    private boolean returnConversionApplies(MethodMatch declaration, String returnType, String returnConversion, SemanticIndex.SemanticCallSite site) {
        return returnConversion != null
                && !returnConversion.isBlank()
                && !site.statementExpression()
                && !declaration.constructor()
                && !MethodSignatureModel.typeEquivalent(MethodSignatureModel.resolvedReturnType(declaration), returnType);
    }

    /**
     * Rewrites a fully-qualified static-member default (e.g. {@code java.util.Collections.emptyList()}) into an
     * import-simplified spelling and records the needed import edits per call-site file. Non-qualified defaults pass
     * through unchanged (they were already proven resolvable in scope by the caller).
     */
    private String defaultValueForCallSite(Path file, String defaultValue, Map<String, PlannerSupport.TextEdit> edits) throws SignatureRefusal {
        if (defaultValue == null || defaultValue.isBlank() || !QUALIFIED_DEFAULT_MEMBER.matcher(defaultValue.trim()).matches()) {
            return defaultValue;
        }
        CharSequence source = index.sourceText(file);
        if (source == null) {
            throw new SignatureRefusal("DEFAULT_ARGUMENT_UNRESOLVED", "Cannot resolve source text for default argument imports in " + file + ".");
        }
        ImportManager planner = new ImportManager(source.toString())
                .withConflictResolver(ImportConflictResolvers.samePackageAndProject(index, file, index.packageNameOf(file)));
        Matcher matcher = QUALIFIED_DEFAULT_MEMBER.matcher(defaultValue.trim());
        if (!matcher.matches()) {
            return defaultValue;
        }
        String qualifiedType = matcher.group(1);
        ImportManager.TypeUse typeUse = planner.planTypeUsageDeep(file, qualifiedType, "CHANGE_SIGNATURE_DEFAULT_IMPORT");
        for (PlannerSupport.TextEdit importEdit : typeUse.importEdits()) {
            edits.put(importEdit.file() + ":" + importEdit.startOffset() + ":" + importEdit.endOffset() + ":" + importEdit.newText(), importEdit);
        }
        return typeUse.renderedType() + matcher.group(2);
    }
}
