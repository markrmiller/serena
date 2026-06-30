package io.serena.javarefactor.compiler;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiler-backed analysis for V3 generalized (multi-statement) inline method (refactor-feature-plan-V3.md §11). Like
 * {@link SemanticConversionIndex} this is a thin façade over a {@link SemanticIndex}'s javac model; it resolves the
 * target method, classifies its body against the spec's supported scope, finds every call site, and produces the
 * block-inline edits (or a refusal) as a {@link DeepInlineResult}.
 *
 * <p>The target is restricted to {@code private} methods. A private method is only callable from within its own
 * top-level class, so every call site lives in the declaring compilation unit — no cross-file transplant is possible and
 * the analysis stays within one file. The supported body shape is "straight-line": local declarations and
 * expression statements, with at most one trailing {@code return} (for a value-returning method). Anything else (loops,
 * branches, early returns, {@code super}, recursion, a call site that is not a standalone statement, or a body that can
 * throw a checked exception unhandled/undeclared at a call site — §11.1 "no checked exception mismatch") is refused; the
 * sidecar's before/after javac validation is the final backstop.
 */
public final class DeepInlineIndex {

    private final SemanticIndex index;
    private final Trees trees;
    private final SourcePositions positions;
    private final List<CompilationUnitTree> units;
    private final Elements elements;
    private final Types types;

    public DeepInlineIndex(SemanticIndex index) {
        this.index = index;
        this.trees = index.trees;
        this.positions = index.positions;
        this.units = index.units;
        this.elements = index.elements;
        this.types = index.types;
    }

    /** How a call site consumes the inlined method's return value, deciding how the final {@code return} is rendered. */
    private enum CallContext { STATEMENT, VAR_INIT, ASSIGN, RETURN }

    /**
     * Inlines every call site of the private method selected at {@code (line, column)} (1-based; {@code column <= 0}
     * selects by line only). When {@code deleteMethod} is set and all call sites are rewritten, the declaration is also
     * removed. When the number of call sites found exceeds {@code maxCallSites} the operation is refused with
     * {@code deep_inline_too_many_call_sites} rather than proceeding silently with a large blast radius.
     */
    public DeepInlineResult inlineMethod(Path file, int line, int column, String methodName, boolean deleteMethod,
            int maxCallSites) {
        CompilationUnitTree unit = unitFor(file);
        if (unit == null) {
            return DeepInlineResult.refuse("method_not_found", "File is not in the Java project model: " + file);
        }
        TreePath methodPath;
        try {
            methodPath = line > 0 ? locateMethod(unit, line, column) : locateUniqueMethodByName(unit, methodName);
        } catch (AmbiguousInlineMethod ambiguous) {
            return DeepInlineResult.refuse("inline_overloaded", ambiguous.getMessage());
        }
        if (methodPath == null) {
            if (line <= 0 && methodName != null && !methodName.isBlank()) {
                return DeepInlineResult.refuse("method_not_found",
                        "No method declaration named '" + methodName.trim() + "' was found.");
            }
            return DeepInlineResult.refuse("method_not_found",
                    "No method declaration was found at " + line + ":" + column + ".");
        }
        MethodTree method = (MethodTree) methodPath.getLeaf();
        if (methodName != null && !methodName.isBlank() && !method.getName().contentEquals(methodName.trim())) {
            return DeepInlineResult.refuse("method_not_found",
                    "Method at " + line + ":" + column + " is '" + method.getName() + "', not '" + methodName + "'.");
        }

        Element resolved = trees.getElement(methodPath);
        if (!(resolved instanceof ExecutableElement target) || target.getKind() != ElementKind.METHOD) {
            return DeepInlineResult.refuse("method_not_found", "The selected declaration is not a method.");
        }
        if (!target.getModifiers().contains(Modifier.PRIVATE)) {
            return DeepInlineResult.refuse("not_private",
                    "V3 inline method supports private methods only (a private method has no overriding/cross-file "
                            + "call sites); '" + method.getName() + "' is not private.");
        }
        if (target.getModifiers().contains(Modifier.SYNCHRONIZED)) {
            return DeepInlineResult.refuse("synchronized_method_unsupported",
                    "V3 inline method refuses synchronized methods because monitor ownership cannot be preserved by textual inlining.");
        }
        if (!target.getTypeParameters().isEmpty()) {
            return DeepInlineResult.refuse("generic_method_unsupported",
                    "V3 inline method refuses generic methods: call-site type-argument substitution cannot be proven "
                            + "safe in general, so generic-method inlining is outside V3 scope (refused).");
        }
        BlockTree body = method.getBody();
        if (body == null) {
            return DeepInlineResult.refuse("abstract_method", "The selected method has no body to inline.");
        }

        String unsupportedInlineConstruct = firstUnsupportedInlineConstruct(body);
        if (unsupportedInlineConstruct != null) {
            return DeepInlineResult.refuse(unsupportedInlineConstruct, escapeMessage(unsupportedInlineConstruct));
        }

        boolean returnsValue = target.getReturnType() != null && target.getReturnType().getKind() != TypeKind.VOID;
        List<? extends StatementTree> statements = body.getStatements();

        ReturnTree finalReturn = null;
        for (int i = 0; i < statements.size(); i++) {
            StatementTree statement = statements.get(i);
            boolean last = i == statements.size() - 1;
            if (statement instanceof VariableTree || statement instanceof ExpressionStatementTree) {
                continue;
            }
            if (statement instanceof ReturnTree returnTree) {
                if (!last) {
                    return DeepInlineResult.refuse("early_return_unsupported",
                            "V3 inline method refuses early returns; only a single trailing return is supported.");
                }
                finalReturn = returnTree;
                continue;
            }
            return DeepInlineResult.refuse("unsupported_statement",
                    "V3 inline method supports straight-line bodies (local declarations, expression statements, one "
                            + "trailing return); found a " + statement.getKind() + " statement.");
        }
        if (returnsValue && (finalReturn == null || finalReturn.getExpression() == null)) {
            return DeepInlineResult.refuse("unsupported_statement",
                    "A value-returning method must end with a single 'return <expr>;' to be inlined.");
        }

        // Reject nested returns / super usage that escape the straight-line model (skipping nested lambdas/classes,
        // which carry their own scope), and self-recursion.
        String escape = firstEscape(methodPath, target, finalReturn);
        if (escape != null) {
            return DeepInlineResult.refuse(escape, escapeMessage(escape));
        }

        List<VariableElement> parameters = collectParameters(method, methodPath);
        List<String> paramTypeText = new ArrayList<>();
        for (VariableTree parameter : method.getParameters()) {
            paramTypeText.add(text(file, parameter.getType()));
        }

        // Find call sites (same file: private method).
        List<TreePath> callSites = findCallSites(unit, target);
        if (callSites.isEmpty()) {
            return DeepInlineResult.refuse("no_call_sites", "No call sites were found for '" + method.getName() + "'.");
        }
        // Enforce the call-site cap before attempting to build edits; a large blast radius is a hard refusal so the
        // caller must opt in explicitly (raise inline.max_call_sites or pass maxCallSites on the request).
        if (callSites.size() > maxCallSites) {
            return DeepInlineResult.refuse("deep_inline_too_many_call_sites",
                    "V3 deep inline method refuses to rewrite " + callSites.size() + " call sites: this exceeds the "
                            + "configured limit of " + maxCallSites + ". Raise java_refactor.v3.inline.max_call_sites "
                            + "(or pass maxCallSites on the request) to opt in to a larger inline.");
        }

        // §11.1 "no checked exception mismatch": inlining moves the body's throwing statements into each call site. The
        // invocation itself forces handle-or-declare, but the inlined body can throw checked exceptions that the
        // invocation does not surface — a checked exception thrown from inside a try inside the method body would not
        // appear in the method's throws clause, yet lands in the caller's context once inlined. We refuse pre-flight
        // (rather than relying solely on the post-transform javac error) when any checked exception the body may raise
        // is not already handled (by an enclosing try-catch) or declared (by the enclosing method's throws) at a call
        // site.
        DeepInlineResult mismatch = checkCheckedExceptionMismatch(methodPath, callSites, method);
        if (mismatch != null) {
            return mismatch;
        }

        CharSequence source = index.sourceText(file.toAbsolutePath().normalize());
        if (source == null) {
            return DeepInlineResult.refuse("method_not_found", "Could not read the declaring file's source.");
        }
        // The body indentation (of the first inlined statement) is stripped and replaced with each call site's indent.
        List<? extends StatementTree> emitted = returnsValue
                ? statements.subList(0, statements.size() - 1)
                : (finalReturn != null ? statements.subList(0, statements.size() - 1) : statements);
        String bodyIndent = emitted.isEmpty() ? "" : lineIndent(source, startOf(unit, emitted.get(0)));

        List<DeepInlineResult.Edit> edits = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (TreePath callPath : callSites) {
            DeepInlineResult site = planCallSite(file, unit, source, callPath, methodPath, method, parameters,
                    paramTypeText, emitted, finalReturn, returnsValue, bodyIndent);
            if (!site.accepted()) {
                return site;
            }
            edits.addAll(site.edits());
            warnings.addAll(site.warnings());
        }

        if (deleteMethod) {
            int start = startOf(unit, method);
            int end = endOf(unit, method);
            if (start < 0 || end < start) {
                return DeepInlineResult.refuse("method_not_found", "Could not locate the method declaration range.");
            }
            // Extend deletion to consume the trailing newline left behind, when present.
            int extendedEnd = end;
            while (extendedEnd < source.length() && (source.charAt(extendedEnd) == ' ' || source.charAt(extendedEnd) == '\t')) {
                extendedEnd++;
            }
            if (extendedEnd < source.length() && source.charAt(extendedEnd) == '\n') {
                extendedEnd++;
            }
            int deleteStart = start;
            int lineStart = start;
            while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }
            if (source.subSequence(lineStart, start).toString().isBlank()) {
                deleteStart = lineStart;
            }
            edits.add(new DeepInlineResult.Edit(deleteStart, extendedEnd, ""));
        }

        edits.sort(Comparator.comparingInt(DeepInlineResult.Edit::start));
        return DeepInlineResult.accept(edits, warnings);
    }

    private DeepInlineResult planCallSite(Path file, CompilationUnitTree unit, CharSequence source, TreePath callPath,
            TreePath methodPath, MethodTree method, List<VariableElement> parameters, List<String> paramTypeText,
            List<? extends StatementTree> emitted, ReturnTree finalReturn, boolean returnsValue, String bodyIndent) {
        MethodInvocationTree invocation = (MethodInvocationTree) callPath.getLeaf();

        // Locate the enclosing statement and how the result is consumed.
        TreePath parentPath = callPath.getParentPath();
        Tree parent = parentPath == null ? null : parentPath.getLeaf();
        while (parent instanceof ParenthesizedTree) {
            parentPath = parentPath.getParentPath();
            parent = parentPath == null ? null : parentPath.getLeaf();
        }
        CallContext context;
        StatementTree enclosing;
        TreePath enclosingPath;
        String prefix;
        if (parent instanceof ExpressionStatementTree expressionStatement) {
            context = CallContext.STATEMENT;
            enclosing = expressionStatement;
            enclosingPath = parentPath;
            prefix = "";
        } else if (parent instanceof VariableTree variable && variable.getInitializer() == invocation) {
            context = CallContext.VAR_INIT;
            enclosing = variable;
            enclosingPath = parentPath;
            prefix = source.subSequence(startOf(unit, variable), startOf(unit, invocation)).toString();
        } else if (parent instanceof ReturnTree returnTree && returnTree.getExpression() == invocation) {
            context = CallContext.RETURN;
            enclosing = returnTree;
            enclosingPath = parentPath;
            prefix = "return ";
        } else if (parent instanceof AssignmentTree assignment && assignment.getExpression() == invocation
                && parentPath.getParentPath() != null
                && parentPath.getParentPath().getLeaf() instanceof ExpressionStatementTree assignStatement) {
            context = CallContext.ASSIGN;
            enclosing = assignStatement;
            enclosingPath = parentPath.getParentPath();
            prefix = source.subSequence(startOf(unit, assignment), startOf(unit, invocation)).toString();
        } else {
            return DeepInlineResult.refuse("no_block_insertion_point",
                    "Call site is not a standalone statement (it is nested in a larger expression), so the "
                            + "multi-statement body cannot be block-inlined here.");
        }

        // The enclosing statement must sit directly in a block so sibling statements can be inserted around it.
        if (enclosingPath.getParentPath() == null
                || !(enclosingPath.getParentPath().getLeaf() instanceof BlockTree)) {
            return DeepInlineResult.refuse("no_block_insertion_point",
                    "Call site's enclosing statement is not directly inside a block, so statements cannot be inserted.");
        }
        if (!returnsValue && context != CallContext.STATEMENT) {
            return DeepInlineResult.refuse("no_block_insertion_point",
                    "A void method's call site must be a standalone statement.");
        }

        String callIndent = lineIndent(source, startOf(unit, enclosing));

        // Names in scope at the call site, used to avoid collisions with introduced temps and renamed locals.
        Set<String> used = new LinkedHashSet<>(scopeNames(callPath));

        // Build per-parameter substitutions, hoisting side-effecting arguments into temps (evaluated in order).
        List<? extends ExpressionTree> arguments = invocation.getArguments();
        if (arguments.size() != parameters.size()) {
            return DeepInlineResult.refuse("no_block_insertion_point",
                    "Call site argument count does not match the method's parameters.");
        }
        Map<Element, String> paramSubstitution = new java.util.HashMap<>();
        List<String> prelude = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            VariableElement parameter = parameters.get(i);
            ExpressionTree argument = arguments.get(i);
            String argText = text(file, argument);
            if (argText == null) {
                return DeepInlineResult.refuse("no_block_insertion_point", "Could not read a call argument's source.");
            }
            if (SemanticInlineIndex.expressionHasObservableSideEffects(argument)) {
                String type = paramTypeText.get(i) != null ? paramTypeText.get(i) : "var";
                String temp = freshName(parameter.getSimpleName().toString(), used);
                used.add(temp);
                prelude.add(type + " " + temp + " = " + argText + ";");
                paramSubstitution.put(parameter, temp);
            } else {
                paramSubstitution.put(parameter, isPrimaryLike(argument) ? argText : "(" + argText + ")");
            }
        }

        // Rename inlined locals whose names collide with the call-site scope (or with introduced temps).
        Map<Element, String> localRenames = new java.util.HashMap<>();
        for (StatementTree statement : emitted) {
            if (statement instanceof VariableTree local) {
                TreePath localPath = pathTo(methodPath, local);
                Element localElement = localPath == null ? null : trees.getElement(localPath);
                String name = local.getName().toString();
                if (localElement != null && used.contains(name)) {
                    String fresh = freshName(name, used);
                    used.add(fresh);
                    localRenames.put(localElement, fresh);
                } else if (localElement != null) {
                    used.add(name);
                }
            }
        }

        // Collect identifier-span rewrites (parameter substitution + local renames) across the inlined region.
        List<Rewrite> rewrites = collectRewrites(unit, methodPath, method, paramSubstitution, localRenames);

        List<String> lines = new ArrayList<>(prelude);
        for (StatementTree statement : emitted) {
            String rendered = render(source, unit, statement, rewrites, bodyIndent, callIndent, localRenames, file);
            if (rendered == null) {
                return DeepInlineResult.refuse("no_block_insertion_point", "Could not render an inlined statement.");
            }
            lines.add(rendered);
        }
        String finalLine = buildFinalLine(file, unit, source, context, prefix, finalReturn, returnsValue, rewrites);
        if (finalLine != null) {
            lines.add(finalLine);
        }
        if (lines.isEmpty()) {
            // Nothing to emit (empty void method): drop the call statement entirely.
            int dropStart = startOf(unit, enclosing);
            int lineStart = dropStart;
            while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }
            return DeepInlineResult.accept(
                    List.of(new DeepInlineResult.Edit(lineStart, endOf(unit, enclosing), "")), List.of());
        }

        String replacement = String.join("\n" + callIndent, lines);
        return DeepInlineResult.accept(
                List.of(new DeepInlineResult.Edit(startOf(unit, enclosing), endOf(unit, enclosing), replacement)),
                List.of());
    }

    private String buildFinalLine(Path file, CompilationUnitTree unit, CharSequence source, CallContext context,
            String prefix, ReturnTree finalReturn, boolean returnsValue, List<Rewrite> rewrites) {
        if (!returnsValue || finalReturn == null || finalReturn.getExpression() == null) {
            return null;
        }
        String returnExpr = applyRewrites(source, startOf(unit, finalReturn.getExpression()),
                endOf(unit, finalReturn.getExpression()), rewrites);
        return switch (context) {
            case VAR_INIT, ASSIGN -> prefix + returnExpr + ";";
            case RETURN -> "return " + returnExpr + ";";
            // Return value discarded: keep it only if it is a valid statement expression (it has observable effects).
            case STATEMENT -> SemanticInlineIndex.expressionHasObservableSideEffects(finalReturn.getExpression())
                    ? returnExpr + ";"
                    : null;
        };
    }

    private String render(CharSequence source, CompilationUnitTree unit, StatementTree statement, List<Rewrite> rewrites,
            String bodyIndent, String callIndent, Map<Element, String> localRenames, Path file) {
        int start = startOf(unit, statement);
        int end = endOf(unit, statement);
        if (start < 0 || end < start) {
            return null;
        }
        String rewritten = applyRewrites(source, start, end, rewrites);
        // Re-indent: the statement's first line carries no indent (the surrounding replacement supplies it); later
        // lines have their body indentation swapped for the call-site indentation.
        String[] segments = rewritten.split("\n", -1);
        StringBuilder out = new StringBuilder(segments[0]);
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (!bodyIndent.isEmpty() && segment.startsWith(bodyIndent)) {
                segment = segment.substring(bodyIndent.length());
            } else {
                segment = segment.stripLeading();
            }
            out.append('\n').append(callIndent).append(segment);
        }
        return out.toString();
    }

    /** Identifier rewrites within the method body: parameter occurrences and renamed-local occurrences (refs + decls). */
    private List<Rewrite> collectRewrites(CompilationUnitTree unit, TreePath methodPath, MethodTree method,
            Map<Element, String> paramSubstitution, Map<Element, String> localRenames) {
        List<Rewrite> rewrites = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                Element resolved = trees.getElement(getCurrentPath());
                if (resolved != null) {
                    String substitution = paramSubstitution.get(resolved);
                    if (substitution == null) {
                        substitution = localRenames.get(resolved);
                    }
                    if (substitution != null) {
                        int start = (int) positions.getStartPosition(unit, node);
                        int end = (int) positions.getEndPosition(unit, node);
                        if (start >= 0 && end > start) {
                            rewrites.add(new Rewrite(start, end, substitution));
                        }
                    }
                }
                return super.visitIdentifier(node, unused);
            }
        }.scan(methodPath, null);

        // Declaration-name tokens of renamed locals (the name in `Type name = ...` is not an IdentifierTree node).
        CharSequence source = index.sourceText(SemanticIndex.pathOf(unit));
        for (StatementTree statement : method.getBody().getStatements()) {
            if (statement instanceof VariableTree local) {
                TreePath localPath = pathTo(methodPath, local);
                Element localElement = localPath == null ? null : trees.getElement(localPath);
                String fresh = localElement == null ? null : localRenames.get(localElement);
                if (fresh != null && source != null) {
                    int declStart = (int) positions.getStartPosition(unit, local);
                    int initStart = local.getInitializer() != null
                            ? (int) positions.getStartPosition(unit, local.getInitializer())
                            : (int) positions.getEndPosition(unit, local);
                    int nameOffset = lastWordOffset(source, local.getName().toString(), declStart, initStart);
                    if (nameOffset >= 0) {
                        rewrites.add(new Rewrite(nameOffset, nameOffset + local.getName().length(), fresh));
                    }
                }
            }
        }
        rewrites.sort(Comparator.comparingInt(Rewrite::start));
        return rewrites;
    }

    private static String applyRewrites(CharSequence source, int start, int end, List<Rewrite> rewrites) {
        StringBuilder out = new StringBuilder();
        int cursor = start;
        for (Rewrite rewrite : rewrites) {
            if (rewrite.start() < start || rewrite.end() > end || rewrite.start() < cursor) {
                continue;
            }
            out.append(source.subSequence(cursor, rewrite.start()));
            out.append(rewrite.replacement());
            cursor = rewrite.end();
        }
        out.append(source.subSequence(cursor, end));
        return out.toString();
    }

    private record Rewrite(int start, int end, String replacement) {
    }

    // ── classification helpers ───────────────────────────────────────────────────────────────────────────────────


    private String firstUnsupportedInlineConstruct(Tree tree) {
        final String[] code = new String[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitSynchronized(SynchronizedTree node, Void unused) {
                if (code[0] == null) {
                    code[0] = "synchronized_block_unsupported";
                }
                return null;
            }

            @Override
            public Void visitYield(YieldTree node, Void unused) {
                if (code[0] == null) {
                    code[0] = "yield_unsupported";
                }
                return null;
            }

            @Override
            public Void scan(Tree node, Void unused) {
                return code[0] == null ? super.scan(node, unused) : null;
            }
        }.scan(tree, null);
        return code[0];
    }

    private String firstEscape(TreePath methodPath, ExecutableElement target, ReturnTree finalReturn) {
        String[] found = {null};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree node, Void unused) {
                return null; // nested type carries its own scope
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                return null; // a lambda's own return/this is independent of the inlined method
            }

            @Override
            public Void visitReturn(ReturnTree node, Void unused) {
                // The single trailing return is legal; any other return is a nested/early return.
                if (found[0] == null && node != finalReturn) {
                    found[0] = "early_return_unsupported";
                }
                return super.visitReturn(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                if (found[0] == null && node.getExpression() instanceof IdentifierTree id
                        && id.getName().contentEquals("super")) {
                    found[0] = "super_unsupported";
                }
                return super.visitMemberSelect(node, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                Element resolved = trees.getElement(getCurrentPath());
                if (found[0] == null && resolved != null && resolved.equals(target)) {
                    found[0] = "recursive_method";
                }
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(methodPath, null);
        return found[0];
    }

    // ── §11.1 checked-exception mismatch ─────────────────────────────────────────────────────────────────────────

    /**
     * Refuses pre-flight when inlining would inject a checked exception into a call site whose enclosing method/try does
     * not already handle or declare it (refactor-feature-plan-V3.md §11.1, "no checked exception mismatch"). The checked
     * exceptions the body propagates are exactly the target method's declared {@code throws} types (filtered to checked
     * exceptions); inlining moves the throwing statements out of the method and into each call site, so each call site's
     * enclosing method/try must already permit every such exception. When one does not, we refuse with a documented
     * reason rather than relying solely on the post-transform javac error.
     */
    private DeepInlineResult checkCheckedExceptionMismatch(TreePath methodPath, List<TreePath> callSites,
            MethodTree method) {
        Element resolved = trees.getElement(methodPath);
        if (!(resolved instanceof ExecutableElement target) || target.getThrownTypes().isEmpty()) {
            return null;
        }
        for (TypeMirror thrown : target.getThrownTypes()) {
            if (!isCheckedException(thrown)) {
                continue;
            }
            for (TreePath callPath : callSites) {
                if (!isHandledAtCallSite(callPath, thrown)) {
                    return DeepInlineResult.refuse("checked_exception_mismatch",
                            "V3 inline method refuses '" + method.getName() + "' because its body can throw the checked "
                                    + "exception '" + types.erasure(thrown) + "', which a call site's enclosing method or "
                                    + "try/catch does not handle or declare; inlining would not compile.");
                }
            }
        }
        return null;
    }

    /** Whether {@code thrown} is handled (enclosing try-catch) or declared (enclosing method throws) at the call site. */
    private boolean isHandledAtCallSite(TreePath invocationPath, TypeMirror thrown) {
        TypeMirror erased = types.erasure(thrown);
        TreePath child = invocationPath;
        for (TreePath current = invocationPath.getParentPath(); current != null; current = current.getParentPath()) {
            Tree leaf = current.getLeaf();
            if (leaf instanceof TryTree tryTree && tryBodyContains(tryTree, child.getLeaf())) {
                for (CatchTree catchTree : tryTree.getCatches()) {
                    TreePath catchTypePath =
                            new TreePath(new TreePath(current, catchTree), catchTree.getParameter().getType());
                    if (catchesThrownType(erased, trees.getTypeMirror(catchTypePath))) {
                        return true;
                    }
                }
            }
            if (leaf instanceof MethodTree) {
                Element element = trees.getElement(current);
                if (element instanceof ExecutableElement executable) {
                    for (TypeMirror declared : executable.getThrownTypes()) {
                        if (types.isAssignable(erased, types.erasure(declared))) {
                            return true;
                        }
                    }
                }
                return false;
            }
            if (leaf instanceof LambdaExpressionTree) {
                return false;
            }
            child = current;
        }
        return false;
    }

    private boolean catchesThrownType(TypeMirror thrown, TypeMirror catchType) {
        if (catchType == null) {
            return false;
        }
        if (catchType instanceof javax.lang.model.type.UnionType unionType) {
            for (TypeMirror alternative : unionType.getAlternatives()) {
                if (types.isAssignable(thrown, types.erasure(alternative))) {
                    return true;
                }
            }
            return false;
        }
        return types.isAssignable(thrown, types.erasure(catchType));
    }

    private boolean tryBodyContains(TryTree tryTree, Tree child) {
        if (child == tryTree.getBlock()) {
            return true;
        }
        for (Tree resource : tryTree.getResources()) {
            if (resource == child) {
                return true;
            }
        }
        return false;
    }

    private boolean isCheckedException(TypeMirror thrown) {
        TypeMirror erased = types.erasure(thrown);
        TypeElement runtimeException = elements.getTypeElement("java.lang.RuntimeException");
        TypeElement error = elements.getTypeElement("java.lang.Error");
        boolean unchecked =
                (runtimeException != null && types.isAssignable(erased, types.erasure(runtimeException.asType())))
                        || (error != null && types.isAssignable(erased, types.erasure(error.asType())));
        return !unchecked;
    }

    private List<VariableElement> collectParameters(MethodTree method, TreePath methodPath) {
        List<VariableElement> parameters = new ArrayList<>();
        for (VariableTree parameter : method.getParameters()) {
            Element element = trees.getElement(new TreePath(methodPath, parameter));
            if (element instanceof VariableElement variable) {
                parameters.add(variable);
            }
        }
        return parameters;
    }

    private List<TreePath> findCallSites(CompilationUnitTree unit, ExecutableElement target) {
        List<TreePath> sites = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                Element resolved = trees.getElement(getCurrentPath());
                if (resolved != null && resolved.equals(target)) {
                    // getCurrentPath() already has this invocation as its leaf; wrapping it again would double-nest
                    // the path so its parent leaf is the invocation rather than the enclosing statement.
                    sites.add(getCurrentPath());
                }
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(unit, null);
        return sites;
    }

    private Set<String> scopeNames(TreePath callPath) {
        Set<String> names = new LinkedHashSet<>();
        try {
            com.sun.source.tree.Scope scope = trees.getScope(callPath);
            while (scope != null) {
                for (Element element : scope.getLocalElements()) {
                    if (element instanceof VariableElement) {
                        names.add(element.getSimpleName().toString());
                    }
                }
                scope = scope.getEnclosingScope();
            }
        } catch (RuntimeException ignored) {
            // Scope resolution can fail on partial models; collision avoidance then falls back to the temps/locals set.
        }
        return names;
    }

    // ── small text/position helpers ──────────────────────────────────────────────────────────────────────────────

    private static String freshName(String base, Set<String> used) {
        if (!used.contains(base)) {
            return base;
        }
        for (int i = 1; ; i++) {
            String candidate = base + i;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static boolean isPrimaryLike(ExpressionTree expression) {
        return switch (expression.getKind()) {
            case IDENTIFIER, MEMBER_SELECT, METHOD_INVOCATION, ARRAY_ACCESS, PARENTHESIZED, NEW_CLASS, NEW_ARRAY,
                    INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL, BOOLEAN_LITERAL, CHAR_LITERAL,
                    STRING_LITERAL, NULL_LITERAL -> true;
            default -> false;
        };
    }

    private static String lineIndent(CharSequence source, int offset) {
        int lineStart = offset;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        int i = lineStart;
        while (i < offset && (source.charAt(i) == ' ' || source.charAt(i) == '\t')) {
            i++;
        }
        return source.subSequence(lineStart, i).toString();
    }

    private static int lastWordOffset(CharSequence source, String word, int from, int to) {
        int found = -1;
        int limit = Math.min(to, source.length());
        for (int i = Math.max(0, from); i + word.length() <= limit; i++) {
            if (matchesWord(source, word, i)) {
                found = i;
            }
        }
        return found;
    }

    private static boolean matchesWord(CharSequence source, String word, int at) {
        for (int j = 0; j < word.length(); j++) {
            if (source.charAt(at + j) != word.charAt(j)) {
                return false;
            }
        }
        char before = at > 0 ? source.charAt(at - 1) : ' ';
        char after = at + word.length() < source.length() ? source.charAt(at + word.length()) : ' ';
        return !Character.isJavaIdentifierPart(before) && !Character.isJavaIdentifierPart(after);
    }

    private TreePath locateUniqueMethodByName(CompilationUnitTree unit, String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return null;
        }
        String targetName = methodName.trim();
        List<TreePath> matches = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                if (node.getName().contentEquals(targetName)) {
                    matches.add(getCurrentPath());
                }
                return super.visitMethod(node, unused);
            }
        }.scan(unit, null);
        if (matches.size() > 1) {
            throw new AmbiguousInlineMethod(
                    "Method name '" + targetName + "' is ambiguous; pass line/column to select one overload.");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static final class AmbiguousInlineMethod extends RuntimeException {
        AmbiguousInlineMethod(String message) {
            super(message);
        }
    }

    private TreePath locateMethod(CompilationUnitTree unit, int line, int column) {
        long targetOffset = column > 0 ? unit.getLineMap().getPosition(line, column) : -1;
        TreePath[] best = {null};
        int[] bestSpan = {Integer.MAX_VALUE};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                int start = startOf(unit, node);
                int end = endOf(unit, node);
                if (start >= 0 && end >= start) {
                    if (targetOffset >= 0) {
                        if (targetOffset >= start && targetOffset < end && (end - start) < bestSpan[0]) {
                            bestSpan[0] = end - start;
                            best[0] = new TreePath(getCurrentPath(), node);
                        }
                    } else {
                        long startLine = unit.getLineMap().getLineNumber(start);
                        long endLine = unit.getLineMap().getLineNumber(end);
                        if (startLine <= line && line <= endLine && (end - start) < bestSpan[0]) {
                            bestSpan[0] = end - start;
                            best[0] = new TreePath(getCurrentPath(), node);
                        }
                    }
                }
                return super.visitMethod(node, unused);
            }
        }.scan(unit, null);
        return best[0];
    }

    private TreePath pathTo(TreePath root, Tree target) {
        TreePath[] result = {null};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (result[0] != null) {
                    return null;
                }
                if (tree == target) {
                    result[0] = new TreePath(getCurrentPath(), tree);
                    return null;
                }
                return super.scan(tree, unused);
            }
        }.scan(root, null);
        return result[0];
    }

    private CompilationUnitTree unitFor(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilationUnitTree unit : units) {
            if (SemanticIndex.pathOf(unit).equals(normalized)) {
                return unit;
            }
        }
        return null;
    }

    private int startOf(CompilationUnitTree unit, Tree tree) {
        return (int) positions.getStartPosition(unit, tree);
    }

    private int endOf(CompilationUnitTree unit, Tree tree) {
        return (int) positions.getEndPosition(unit, tree);
    }

    private String text(Path file, Tree tree) {
        CompilationUnitTree unit = unitFor(file);
        if (unit == null) {
            return null;
        }
        int start = startOf(unit, tree);
        int end = endOf(unit, tree);
        CharSequence source = index.sourceText(file.toAbsolutePath().normalize());
        if (source == null || start < 0 || end < start || end > source.length()) {
            return null;
        }
        return source.subSequence(start, end).toString();
    }

    private static String escapeMessage(String code) {
        return switch (code) {
            case "early_return_unsupported" -> "V3 inline method refuses bodies with non-trailing (early) returns.";
            case "super_unsupported" -> "V3 inline method refuses bodies that use 'super'.";
            case "recursive_method" -> "V3 inline method refuses recursive methods.";
            default -> "V3 inline method refuses this body shape.";
        };
    }
}
