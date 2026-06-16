package io.serena.javarefactor.operations.inline_method;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.serena.javarefactor.edits.PlannerSupport.refusalJson;
import static io.serena.javarefactor.edits.PlannerSupport.relative;
import static io.serena.javarefactor.edits.PlannerSupport.sha256;

public final class InlineVariablePlanner {
    public String plan(JavaProjectModel model, String relativePath, long line, long column, boolean constantMode, boolean apply, boolean allowPublicApi) throws IOException {
        return plan(model, relativePath, line, column, constantMode, apply, allowPublicApi, TargetHints.NONE);
    }

    public String plan(JavaProjectModel model, String relativePath, long line, long column, boolean constantMode, boolean apply,
                boolean allowPublicApi, TargetHints hints) throws IOException {
        try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
            RefactorAnalysisResult analysis = index.resolveTarget(relativePath, line, column, hints.nameHint());
            if (analysis.target() == null) {
                return refusalJson("target_not_found", "No local variable or constant target found.");
            }
            // Target-identity gate (before every other check): the caller named a symbol; prove the position-resolved
            // element IS that symbol before planning any edit for it.
            String hintMismatch = hints.mismatch(analysis.target());
            if (hintMismatch != null) {
                return refusalJson("target_mismatch", "Refused to plan an inline against an unverified target: " + hintMismatch);
            }
            // Centralized target-origin editability gate (before op-specific logic): refuse generated roots, external/
            // out-of-tree source attachments, and classpath-only binary targets.
            String originRefusal = index.targetOriginRefusal(analysis.target());
            if (originRefusal != null) {
                return refusalJson("non_editable_target", originRefusal);
            }
            Element element = analysis.target().element();
            String kind = analysis.target().key().kind();
            // Private constants inline usages and remove their declaration. Non-private constants are API-shaped: preview
            // may show usage replacements, but the declaration is retained and apply requires an explicit public-API opt-in.
            boolean deleteDeclaration = true;
            boolean nonPrivateConstant = false;
            if (constantMode) {
                if (!kind.equals("FIELD")) {
                    return refusalJson("not_constant", "Inline constant requires a field constant target.");
                }
                Set<Modifier> modifiers = element.getModifiers();
                if (!modifiers.contains(Modifier.STATIC) || !modifiers.contains(Modifier.FINAL)) {
                    return refusalJson("not_constant", "Inline constant requires a static final field.");
                }
                if (!index.isCompileTimeConstant(element)) {
                    return refusalJson(
                            "not_compile_time_constant",
                            "Inline constant requires a Java compile-time constant (a primitive- or String-typed static "
                                    + "final field with a constant initializer).");
                }
                nonPrivateConstant = !modifiers.contains(Modifier.PRIVATE);
                if (nonPrivateConstant) {
                    deleteDeclaration = false;
                    if (apply && !allowPublicApi) {
                        return refusalJson(
                                "public_api_apply_requires_opt_in",
                                "Inline constant preview is available for a non-private (public, protected, or "
                                        + "package-private) compile-time constant, but applying the usage replacements "
                                        + "requires allow_public_api=true because the constant may be API- or "
                                        + "reflection-visible.");
                    }
                }
            } else {
                if (kind.equals("RESOURCE_VARIABLE")) {
                    return refusalJson(
                            "unsupported_resource_variable",
                            "Inline local refuses a try-with-resources resource variable; it owns a resource that must be "
                                    + "closed and cannot be inlined.");
                }
                if (!kind.equals("LOCAL_VARIABLE")) {
                    return refusalJson("not_local_variable", "Inline local requires a local variable target.");
                }
                if (!index.isStandaloneBlockStatementLocal(element)) {
                    return refusalJson(
                            "non_standalone_local",
                            "Only a local variable declared as its own statement in a block can be inlined; this refuses "
                                    + "resource, for-init, enhanced-for, lambda, and catch declarations.");
                }
                if (index.isReassigned(element)) {
                    return refusalJson("not_effectively_final", "Inline local requires an effectively-final variable (it is reassigned).");
                }
                if (index.initializerHasSideEffects(element)) {
                    return refusalJson(
                            "unsafe_initializer",
                            "Inline local refuses an initializer with side effects (method/constructor calls, array "
                                    + "creation, or assignments) because each usage would re-run the effect.");
                }
                // Purity alone is not soundness: a pure initializer whose dependencies change between the declaration
                // and a use (e.g. `int a = 1; int x = a; a = 2; return x;`) would compile after inlining but change
                // behavior. Every value the initializer reads must be proven stable.
                String unstableDependency = index.initializerUnstableDependencyReason(element);
                if (unstableDependency != null) {
                    return refusalJson(
                            "unstable_initializer_dependency",
                            "Inline local refuses this initializer: " + unstableDependency + ". Re-evaluating it at a "
                                    + "usage site could observe a different value than the original declaration did.");
                }
                if (index.isUsedInNestedScope(element)) {
                    return refusalJson(
                            "captured_in_nested_scope",
                            "Inline local refuses a variable captured in a lambda or anonymous/inner class body; "
                                    + "inlining across the capture boundary can change evaluation timing/semantics and "
                                    + "effectively-final guarantees.");
                }
            }

            SemanticIndex.DeclarationRange declaration = index.declarationRange(element);
            if (declaration == null) {
                return refusalJson("unsupported_declaration_range", "Inline could not determine a precise declaration range.");
            }
            String source = Files.readString(declaration.file(), SemanticIndex.charsetOf(model));
            if (SemanticIndex.isMultiDeclarator(source, declaration.start(), declaration.end())) {
                return refusalJson("ambiguous_multi_declarator", "Inline refuses multi-declarator variable declarations.");
            }
            SemanticIndex.InitializerInfo initializer = index.initializerInfo(element);
            if (initializer == null) {
                return refusalJson("unsupported_initializer", "Inline requires a declaration with an initializer.");
            }
            String initializerText = initializer.text().trim();

            Path file = declaration.file();
            List<SemanticIndex.UsageReplacement> usages;
            if (constantMode) {
                // Constants may be referenced project-wide; apply one context-insensitive parenthesization (G012) to
                // every semantic reference, excluding the declaration's own name span.
                String replacement = parenthesize(initializerText, index.initializerKind(element));
                usages = analysis.references().stream()
                        .filter(span -> span.startOffset() != analysis.target().span().startOffset() || !span.file().equals(file))
                        .sorted(Comparator.comparing((IdentifierSpan span) -> span.file().toString()).thenComparingLong(IdentifierSpan::startOffset))
                        .map(span -> new SemanticIndex.UsageReplacement(span, replacement))
                        .toList();
            } else {
                // A usage in a syntactic context the precedence model does not cover would get a replacement whose
                // parenthesization we cannot prove safe. Refuse rather than emit a possibly-incorrect edit (e.g. for
                // newer/unknown syntax not modelled here).
                String unsupportedContext = index.firstUnsupportedInlineUsageContext(element);
                if (unsupportedContext != null) {
                    return refusalJson(
                            "unsupported_inline_context",
                            "Inline refuses a usage in an unsupported syntactic context ('" + unsupportedContext
                                    + "'): the parenthesization needed to preserve semantics there is not modelled. "
                                    + "Inline this variable manually.");
                }
                // Locals are method-scoped: compute a per-usage replacement so each site is parenthesized for its own
                // surrounding expression context, not from the initializer kind alone.
                usages = index.usageReplacements(element, initializerText).stream()
                        .sorted(Comparator.comparing((SemanticIndex.UsageReplacement usage) -> usage.span().file().toString())
                                .thenComparingLong(usage -> usage.span().startOffset()))
                        .toList();
            }
            // A private compile-time constant with no remaining references is still a valid inline-constant request: the
            // design's "optionally delete the private constant once no references remain" branch is already satisfied
            // (the no-reference condition holds), so emit a delete-only plan rather than refusing. The no-usage case is
            // refused for inline-local (an unused local has nothing to inline; safe delete handles that instead).
            if (usages.isEmpty() && !(constantMode && deleteDeclaration)) {
                return refusalJson("no_usages", "Inline target has no usages to replace.");
            }
            // Generated/dependency-source safety gate (shared with rename/safe-delete/move): every file the inline would
            // edit (the declaration plus all usage sites) must be an editable, non-generated source file.
            java.util.LinkedHashSet<Path> affected = new java.util.LinkedHashSet<>();
            affected.add(file);
            for (SemanticIndex.UsageReplacement usage : usages) {
                affected.add(usage.span().file());
            }
            String nonEditable = index.detectNonEditableFiles(affected);
            if (nonEditable != null) {
                return refusalJson("non_editable_target", nonEditable);
            }
            // Fields (constants) use whole-line removal (Javadoc/annotations/own-line); a local may share its line with
            // sibling statements, so it uses the exact-span removal that leaves neighbours intact.
            int[] range = constantMode
                    ? SemanticIndex.expandDeclarationRangeForDelete(source, declaration.start(), declaration.end())
                    : SemanticIndex.localDeclarationDeleteRange(source, declaration.start(), declaration.end());
            List<String> warnings = new ArrayList<>(PlannerSupport.modelSafetyWarnings(model));
            if (nonPrivateConstant) {
                warnings.add("Inlining a non-private constant rewrites project usages while keeping the declaration; "
                        + "review API compatibility, binary compatibility, reflection, string/resource references, and "
                        + "constant inlining by downstream consumers before applying.");
            }
            return workspaceEditJson(model.projectRoot(), analysis, file, range, usages, deleteDeclaration, warnings);
        }
    }

    private static String workspaceEditJson(Path projectRoot, RefactorAnalysisResult analysis, Path declarationFile, int[] declarationRange, List<SemanticIndex.UsageReplacement> usages, boolean deleteDeclaration, List<String> warnings) throws IOException {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        if (deleteDeclaration) {
            edits.add(new PlannerSupport.TextEdit(declarationFile, declarationRange[0], declarationRange[1], "", "DECLARATION"));
        }
        for (SemanticIndex.UsageReplacement usage : usages) {
            IdentifierSpan span = usage.span();
            edits.add(new PlannerSupport.TextEdit(span.file(), span.startOffset(), span.endOffset(), usage.replacement(), "REFERENCE"));
        }
        int editCount = edits.size();
        return "{\"accepted\":true,\"target\":" + analysis.targetJson(projectRoot) + ",\"workspaceEdit\":{"
                + "\"changes\":" + PlannerSupport.changesJson(projectRoot, edits) + ",\"fileOperations\":[],"
                + "\"warnings\":" + PlannerSupport.warningsJson(warnings) + ",\"preconditions\":[\"pure effectively-final initializer\"],"
                + "\"stats\":{\"editCount\":" + editCount + ",\"fileOperationCount\":0}},"
                + "\"stats\":{\"editCount\":" + editCount + ",\"fileOperationCount\":0}}";
    }

    /**
     * Wraps the initializer in parentheses unless its expression kind is atomic enough to splice into any surrounding
     * expression without changing precedence. Atomic kinds (identifiers, member selects, calls, literals, array access,
     * {@code new}) bind tighter than any operator; binary/ternary/cast/instanceof/unary/lambda expressions do not and
     * are parenthesized to preserve meaning at every usage site.
     */
    private static String parenthesize(String initializer, com.sun.source.tree.Tree.Kind kind) {
        if (kind == null) {
            return "(" + initializer + ")";
        }
        return switch (kind) {
            case IDENTIFIER, MEMBER_SELECT, METHOD_INVOCATION, ARRAY_ACCESS, PARENTHESIZED, NEW_CLASS, NEW_ARRAY,
                    INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL, BOOLEAN_LITERAL, CHAR_LITERAL,
                    STRING_LITERAL, NULL_LITERAL -> initializer;
            default -> "(" + initializer + ")";
        };
    }

}
