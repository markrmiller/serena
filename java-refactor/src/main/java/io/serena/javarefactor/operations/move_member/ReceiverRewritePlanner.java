package io.serena.javarefactor.operations.move_member;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Receiver-strategy and receiver-rewriting unit for the V2 instance-method move (plan §3 {@code move_member}, hard
 * blocker 8 / G008).
 *
 * <p>Extracted from {@link MoveMemberPlanner} so the receiver decisions of {@link MoveInstanceMethodPlanner} live in one
 * named home. Two AST-proven concerns live here:
 * <ul>
 *   <li><b>Moved-body receiver rewrite</b> — when an instance method is relocated so that a former parameter, field, or
 *       explicit-receiver expression becomes the moved method's own {@code this}, the references to that receiver inside
 *       the moved body must be rewritten. A <em>simple-identifier</em> receiver (target parameter or single-name field)
 *       is rewritten via {@link #astRewriteMember}: every genuine read becomes {@code this} and every {@code name.member}
 *       qualifier drops its qualifier, proven against the parsed AST so comments, string/char literals, unrelated
 *       identifiers, and names rebound in nested scopes are never touched. A <em>compound</em> explicit-receiver
 *       expression (e.g. {@code (Target) raw}, {@code holder.targets[0]}) is rewritten via
 *       {@link #rewriteCompoundReceiverBody}, which removes only the AST-resolved {@code expr.member} qualifier spans; if
 *       the body's positions cannot be resolved it <b>refuses</b> rather than fall back to a text replace that could
 *       corrupt comments/strings.</li>
 *   <li><b>Call-site receiver expression</b> — {@link #asReceiver(String)} renders a call-site argument or explicit
 *       receiver as a safe receiver expression, parenthesizing compound forms so an appended {@code .method(...)} binds
 *       to the whole expression.</li>
 * </ul>
 */
final class ReceiverRewritePlanner {

    static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    static final Pattern SIMPLE_RECEIVER =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*\\.\\s*[A-Za-z_$][A-Za-z0-9_$]*)*");

    private static final String MEMBER_WRAPPER_PREFIX = "final class __SerenaMoveMember {\n";
    private static final String MEMBER_WRAPPER_SUFFIX = "\n}\n";

    private ReceiverRewritePlanner() {
    }

    /**
     * Renders a call-site argument as a safe receiver expression. A simple selector chain ({@code customer},
     * {@code this.customer}, {@code a.b.c}) is used verbatim; any compound expression (ternary, cast, binary
     * expression, array access) is parenthesized so that appending {@code .method(...)} binds to the whole expression
     * rather than to its trailing operand.
     */
    static String asReceiver(String argument) {
        return SIMPLE_RECEIVER.matcher(argument).matches() ? argument : "(" + argument + ")";
    }

    /**
     * Rewrites a moved method so that the receiver variable {@code receiverName} becomes the moved method's own
     * {@code this} (G015). Member-select qualifiers ({@code receiverName.member}) lose the qualifier; bare reads and
     * method-reference qualifiers ({@code receiverName}, {@code receiverName::m}) become {@code this}. The edit is
     * proven against the parsed AST and source positions, so it never touches comments, string/char literals, unrelated
     * identifiers (including {@code obj.receiverName} member names and {@code receiverName(...)} method names), or
     * references inside nested classes/lambdas/blocks that rebind the name. When {@code dropParameter} is set, the
     * parameter named {@code receiverName} is also removed from the header. Refuses when the member does not parse in
     * isolation.
     */
    static String astRewriteMember(String memberText, String receiverName, boolean dropParameter)
            throws MoveMemberPlanner.Refusal {
        ParsedMember parsed = parseWrappedMethod(memberText);
        if (parsed == null) {
            throw new MoveMemberPlanner.Refusal(
                    "body_rewrite_unparseable", "The moved method body could not be parsed for a semantic receiver rewrite.");
        }
        String wrapped = MEMBER_WRAPPER_PREFIX + memberText + MEMBER_WRAPPER_SUFFIX;
        List<Edit> edits = new ArrayList<>();

        if (dropParameter) {
            Edit paramEdit = parameterRemovalEdit(parsed, receiverName);
            if (paramEdit == null) {
                throw new MoveMemberPlanner.Refusal(
                        "target_parameter_not_found", "Target parameter '" + receiverName + "' was not found in the parsed header.");
            }
            edits.add(paramEdit);
        }

        collectReceiverRewrites(parsed, receiverName, wrapped, edits);

        String rewritten = applyEdits(wrapped, edits);
        return rewritten.substring(MEMBER_WRAPPER_PREFIX.length(), rewritten.length() - MEMBER_WRAPPER_SUFFIX.length());
    }

    /**
     * G008: AST/source-position-based rewrite of a moved body for a COMPOUND explicit-receiver move (e.g.
     * {@code (Target) raw}, {@code holder.targets[0]}). The compound receiver is not a simple identifier the AST can bind
     * by name, so genuine in-body occurrences are located by their exact source text against AST node spans: every
     * {@code <receiver>.member} member-select qualifier whose qualifier source equals {@code receiverExpression} drops
     * the qualifier (and its dot), and a bare {@code <receiver>} expression node becomes {@code this}. Because the match
     * is taken from resolved {@link MemberSelectTree}/expression spans — not a raw {@code String.replace} — comments,
     * string/char literals, and unrelated text are never touched. When the member does not parse in isolation (so no
     * source positions are available) this <b>refuses</b> rather than fall back to a corrupting text replace.
     */
    static String rewriteCompoundReceiverBody(String memberText, String receiverExpression)
            throws MoveMemberPlanner.Refusal {
        ParsedMember parsed = parseWrappedMethod(memberText);
        if (parsed == null) {
            throw new MoveMemberPlanner.Refusal(
                    "body_rewrite_unparseable",
                    "The moved method body could not be parsed for a source-position receiver rewrite; refusing rather "
                            + "than text-replacing a compound receiver, which could corrupt comments or string literals.");
        }
        String wrapped = MEMBER_WRAPPER_PREFIX + memberText + MEMBER_WRAPPER_SUFFIX;
        String normalizedReceiver = normalizeExpressionText(receiverExpression);
        List<Edit> edits = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                Tree qualifier = node.getExpression();
                int qStart = (int) parsed.positions().getStartPosition(parsed.unit(), qualifier);
                int qEnd = (int) parsed.positions().getEndPosition(parsed.unit(), qualifier);
                if (qStart < 0 || qEnd <= qStart || qEnd > wrapped.length()) {
                    return super.visitMemberSelect(node, unused);
                }
                String qualifierText = normalizeExpressionText(wrapped.substring(qStart, qEnd));
                // A compound receiver that needs parenthesization at the call site (e.g. a cast `(Target) raw`) appears in
                // the body already wrapped, as `((Target) raw).member`; javac's qualifier span for that member-select is
                // the parenthesized `((Target) raw)`. Match the requested receiver against the qualifier verbatim AND with
                // one fully-enclosing parenthesis layer stripped, so both `(Target) raw` and `((Target) raw)` bind.
                if (qualifierText.equals(normalizedReceiver) || stripOuterParens(qualifierText).equals(normalizedReceiver)) {
                    int dot = wrapped.indexOf('.', qEnd);
                    if (dot >= 0) {
                        edits.add(new Edit(qStart, dot + 1, "")); // remove "<receiver>."
                    }
                }
                return super.visitMemberSelect(node, unused);
            }
        }.scan(parsed.unit(), null);

        String rewritten = applyEdits(wrapped, edits);
        return rewritten.substring(MEMBER_WRAPPER_PREFIX.length(), rewritten.length() - MEMBER_WRAPPER_SUFFIX.length());
    }

    /** Collapses interior whitespace so {@code (Target) raw} and {@code (Target)  raw} compare equal. */
    private static String normalizeExpressionText(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    /**
     * Strips exactly one fully-enclosing parenthesis pair from {@code text} when the opening paren at index 0 matches the
     * closing paren at the end (balanced across the whole span), else returns {@code text} unchanged. Used to reconcile a
     * call-site-parenthesized receiver ({@code ((Target) raw)}) with the unparenthesized requested form
     * ({@code (Target) raw}); an inner pair like the cast's own parentheses is not enclosing and is preserved.
     */
    private static String stripOuterParens(String text) {
        if (text.length() < 2 || text.charAt(0) != '(' || text.charAt(text.length() - 1) != ')') {
            return text;
        }
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && i != text.length() - 1) {
                    return text; // the leading '(' closes before the end, so it is not a fully-enclosing pair
                }
            }
        }
        return depth == 0 ? normalizeExpressionText(text.substring(1, text.length() - 1)) : text;
    }

    // ── synthetic-wrapper parsing and edit application ─────────────────────────────────────────────────────────────

    /**
     * A single moved method declaration parsed in isolation. The declaration is wrapped in a synthetic class so javac
     * parses it as a normal method; only syntactic structure and source positions are consumed, so no symbol resolution
     * (and hence no project classpath) is required.
     */
    record ParsedMember(MethodTree method, CompilationUnitTree unit, SourcePositions positions) {}

    /** A single offset-bounded rewrite within the wrapped member text: replace {@code [start, end)} with {@code replacement}. */
    private record Edit(int start, int end, String replacement) {}

    /** Parses {@code memberText} as a method in a synthetic class, or {@code null} when it does not parse to one. */
    private static ParsedMember parseWrappedMethod(String memberText) {
        String wrapped = MEMBER_WRAPPER_PREFIX + memberText + MEMBER_WRAPPER_SUFFIX;
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return null;
        }
        JavaFileObject file = new SimpleJavaFileObject(
                URI.create("string:///__SerenaMoveMember.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return wrapped;
            }
        };
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> { }, List.of("-proc:none"), null, List.of(file));
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : task.parse()) {
                MethodTree method = firstMethod(unit);
                if (method != null) {
                    return new ParsedMember(method, unit, positions);
                }
            }
        } catch (IOException | RuntimeException | Error ignored) {
            // unparseable in isolation; callers degrade conservatively
        }
        return null;
    }

    /** locate the single declared method inside the synthetic wrapper class. */
    private static MethodTree firstMethod(CompilationUnitTree unit) {
        MethodTree[] found = new MethodTree[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                if (found[0] == null && node.getBody() != null) {
                    found[0] = node;
                }
                return null;
            }
        }.scan(unit, null);
        return found[0];
    }

    /** Computes the wrapped-text deletion edit for the receiver parameter, absorbing one adjacent comma. */
    private static Edit parameterRemovalEdit(ParsedMember parsed, String receiverName) {
        List<? extends VariableTree> parameters = parsed.method().getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (!parameters.get(i).getName().contentEquals(receiverName)) {
                continue;
            }
            int start = (int) parsed.positions().getStartPosition(parsed.unit(), parameters.get(i));
            int end = (int) parsed.positions().getEndPosition(parsed.unit(), parameters.get(i));
            if (start < 0 || end < start) {
                return null;
            }
            if (parameters.size() == 1) {
                return new Edit(start, end, "");
            }
            if (i < parameters.size() - 1) {
                int nextStart = (int) parsed.positions().getStartPosition(parsed.unit(), parameters.get(i + 1));
                return new Edit(start, nextStart >= 0 ? nextStart : end, "");
            }
            int prevEnd = (int) parsed.positions().getEndPosition(parsed.unit(), parameters.get(i - 1));
            return new Edit(prevEnd >= 0 ? prevEnd : start, end, "");
        }
        return null;
    }

    /** Collects body edits that turn genuine {@code receiverName} references into the moved method's {@code this}. */
    private static void collectReceiverRewrites(ParsedMember parsed, String receiverName, String wrapped, List<Edit> edits) {
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                if (node.getName().contentEquals(receiverName) && !shadowed(getCurrentPath(), receiverName)) {
                    Edit edit = receiverEdit(parsed, getCurrentPath(), node, wrapped);
                    if (edit != null) {
                        edits.add(edit);
                    }
                }
                return super.visitIdentifier(node, unused);
            }
        }.scan(parsed.unit(), null);
    }

    /**
     * Computes the edit for one resolved receiver identifier based on its syntactic role: a member-select qualifier
     * loses the qualifier and its dot; a method name ({@code receiverName(...)}) is left untouched ({@code null});
     * everything else (bare read, method-reference qualifier) becomes {@code this}.
     */
    private static Edit receiverEdit(ParsedMember parsed, TreePath path, IdentifierTree node, String wrapped) {
        int start = (int) parsed.positions().getStartPosition(parsed.unit(), node);
        int end = (int) parsed.positions().getEndPosition(parsed.unit(), node);
        if (start < 0 || end < start) {
            return null;
        }
        Tree parent = path.getParentPath() == null ? null : path.getParentPath().getLeaf();
        if (parent instanceof MethodInvocationTree invocation && invocation.getMethodSelect() == node) {
            return null; // a method named receiverName, not the receiver variable
        }
        if (parent instanceof MemberSelectTree select && select.getExpression() == node) {
            int dot = wrapped.indexOf('.', end);
            return dot >= 0 ? new Edit(start, dot + 1, "") : null; // remove "receiverName."
        }
        return new Edit(start, end, "this"); // bare read or receiverName::m
    }

    /** Whether {@code name} is rebound between {@code path} and the moved method (nested class/lambda/catch/block local). */
    private static boolean shadowed(TreePath path, String name) {
        for (TreePath current = path.getParentPath(); current != null; current = current.getParentPath()) {
            Tree leaf = current.getLeaf();
            if (leaf instanceof MethodTree) {
                return false; // reached the moved method without an intervening rebind
            }
            if (leaf instanceof ClassTree) {
                return true; // inside a nested/anonymous/local type: `this` and names rebind
            }
            if (leaf instanceof LambdaExpressionTree lambda) {
                for (VariableTree parameter : lambda.getParameters()) {
                    if (parameter.getName().contentEquals(name)) {
                        return true;
                    }
                }
            }
            if (leaf instanceof CatchTree catchTree && catchTree.getParameter().getName().contentEquals(name)) {
                return true;
            }
            if (leaf instanceof BlockTree block) {
                for (Tree statement : block.getStatements()) {
                    if (statement instanceof VariableTree local && local.getName().contentEquals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Applies edits right-to-left so earlier offsets stay valid; defensively skips any overlapping edit. */
    private static String applyEdits(String source, List<Edit> edits) {
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort((left, right) -> Integer.compare(right.start(), left.start()));
        StringBuilder builder = new StringBuilder(source);
        int previousStart = Integer.MAX_VALUE;
        for (Edit edit : ordered) {
            if (edit.end() > previousStart) {
                continue;
            }
            builder.replace(edit.start(), edit.end(), edit.replacement());
            previousStart = edit.start();
        }
        return builder.toString();
    }
}
