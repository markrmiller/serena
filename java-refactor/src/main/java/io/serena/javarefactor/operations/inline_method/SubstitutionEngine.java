package io.serena.javarefactor.operations.inline_method;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.operations.inline_method.InlineMethodPlanner.MethodBody;
import io.serena.javarefactor.operations.inline_method.InlineMethodPlanner.Refusal;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Parameter and receiver substitution unit for inline method (G014).
 *
 * <p>Extracted from the inline-method monolith, this is the single authority for turning an inlined method body template
 * into the concrete expression spliced at one call site. It is responsible for:
 * <ul>
 *   <li><b>Parameter substitution</b> — replacing each parameter identifier in the body with its call-site argument,
 *       precedence-correctly parenthesized. The accepted path is AST-backed ({@link #substituteByAst}): it rewrites only
 *       genuine identifier references, leaving member-select member names and lambda-shadowed captures untouched. When the
 *       body cannot be modelled into an AST the engine REFUSES ({@code inline_body_unmodellable}) rather than degrading to
 *       a token-level approximation (HB-9). The identifier scanner ({@link #replaceIdentifiers}) survives only for the
 *       narrow {@code this}-receiver rewrite below, which runs after successful AST modelling.</li>
 *   <li><b>Receiver substitution</b> — rewriting {@code this} in the body to an explicit call-site receiver.</li>
 *   <li><b>Duplicate-evaluation gating</b> — a parameter used more than once duplicates its argument, which is only
 *       admitted when the {@link EvaluationOrderGuard} proves the argument reorder-safe.</li>
 * </ul>
 */
final class SubstitutionEngine {

    private final EvaluationOrderGuard guard;

    SubstitutionEngine(EvaluationOrderGuard guard) {
        this.guard = guard;
    }

    /** Produces the concrete inlined expression for {@code body} substituted at {@code site}. */
    String substitute(MethodBody body, List<String> parameterNames, boolean isStatic, SemanticIndex.SemanticCallSite site) {
        Map<String, String> replacements = new HashMap<>();
        Map<String, Integer> useCounts = guard.parameterUseCounts(body.expression(), parameterNames);
        for (int i = 0; i < parameterNames.size(); i++) {
            String parameter = parameterNames.get(i);
            SemanticIndex.SemanticArgument argument = site.arguments().get(i);
            String argumentText = argument.text().trim();
            int useCount = useCounts.getOrDefault(parameter, 0);
            // A parameter used more than once duplicates its argument; only a proven reorder-safe expression may be
            // duplicated. The verdict comes from the genuine javac green-light, never from detached text.
            if (useCount > 1 && !guard.reorderSafe(site.file(), argument.range(), argumentText)) {
                throw new Refusal("unsafe_argument_reuse", "Inline refuses arguments that would be evaluated more than once.");
            }
            replacements.put(parameter, renderArgumentSubstitution(argumentText));
        }
        // HB-9: parameter substitution is AST-backed only. It maps identifiers from the parsed body AST (skipping
        // member-select member names and identifiers shadowed by lambda parameters/locals). When javac cannot model the
        // body into an AST we REFUSE with a structured code rather than degrading to a token-level textual approximation
        // that could rewrite the wrong occurrence (e.g. a name inside a string literal or a nested shadowing scope).
        String bodyExpression = body.expression();
        String substituted = substituteByAst(bodyExpression, replacements).orElseThrow(() -> new Refusal(
                "inline_body_unmodellable",
                "Inline method refuses a body the parser cannot model into an AST; it does not fall back to a token-level "
                        + "textual substitution."));
        // The explicit-receiver `this`->receiver rewrite keeps its proven token-level path and runs after parameter
        // substitution, exactly as before, so receiver semantics are unchanged.
        String receiver = site.receiverText().trim();
        if (!receiver.isEmpty() && !"this".equals(receiver)) {
            substituted = replaceIdentifiers(substituted, Map.of("this", renderReceiverSubstitution(receiver)));
        }
        return substituted;
    }

    /**
     * AST-backed parameter substitution (G033). Parses {@code bodyExpression} and rewrites only the identifier
     * occurrences that are genuine references to a substituted name: the member name of a {@code a.b} select is never
     * rewritten, and an identifier shadowed by an enclosing lambda parameter (or local declaration of the same name) is
     * left untouched so captures keep their own binding. Returns {@link Optional#empty()} when the body cannot be parsed
     * or contains a nested class declaration whose member shadowing cannot be resolved here, so the caller falls back to
     * the conservative token scanner instead of emitting a possibly-wrong edit.
     */
    static Optional<String> substituteByAst(String bodyExpression, Map<String, String> replacements) {
        if (replacements.isEmpty()) {
            return Optional.of(bodyExpression);
        }
        String prefix = "class __SerenaInlineSubst { Object __serena() { return (";
        String suffix = "); } }";
        String unitSource = prefix + bodyExpression + suffix;
        int base = prefix.length();
        try {
            JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                    .getTask(null, null, diagnostic -> { }, List.of("-proc:none"), null,
                            List.of(new StringJavaFileObject("__SerenaInlineSubst", unitSource)));
            Iterable<? extends CompilationUnitTree> units = task.parse();
            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            CompilationUnitTree unit = units.iterator().next();
            List<int[]> spans = new ArrayList<>();
            List<String> renderings = new ArrayList<>();
            boolean[] nestedClass = {false};
            new TreePathScanner<Void, Void>() {
                private final Deque<Set<String>> shadowed = new ArrayDeque<>();

                @Override
                public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                    Set<String> names = new HashSet<>();
                    node.getParameters().forEach(parameter -> names.add(parameter.getName().toString()));
                    shadowed.push(names);
                    super.visitLambdaExpression(node, unused);
                    shadowed.pop();
                    return null;
                }

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    // The synthetic wrapper class is the root; any class nested inside the body expression introduces
                    // member shadowing we cannot resolve without attribution, so defer the whole body to the fallback.
                    if (node.getSimpleName().contentEquals("__SerenaInlineSubst")) {
                        return super.visitClass(node, unused);
                    }
                    nestedClass[0] = true;
                    return null;
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                    // Only the receiver expression participates in substitution; the selected member name (`b` in `a.b`)
                    // is a field/method of the receiver, never a parameter reference.
                    return scan(node.getExpression(), unused);
                }

                @Override
                public Void visitIdentifier(IdentifierTree node, Void unused) {
                    String name = node.getName().toString();
                    String rendering = replacements.get(name);
                    if (rendering != null && !isShadowed(name)) {
                        long start = positions.getStartPosition(unit, node);
                        long end = positions.getEndPosition(unit, node);
                        if (start >= base && end >= start) {
                            spans.add(new int[] {(int) (start - base), (int) (end - base), renderings.size()});
                            renderings.add(rendering);
                        }
                    }
                    return null;
                }

                private boolean isShadowed(String name) {
                    for (Set<String> scope : shadowed) {
                        if (scope.contains(name)) {
                            return true;
                        }
                    }
                    return false;
                }
            }.scan(units, null);
            if (nestedClass[0]) {
                return Optional.empty();
            }
            spans.sort((left, right) -> Integer.compare(right[0], left[0]));
            StringBuilder rewritten = new StringBuilder(bodyExpression);
            for (int[] span : spans) {
                if (span[0] < 0 || span[1] > bodyExpression.length() || span[0] > span[1]) {
                    return Optional.empty();
                }
                rewritten.replace(span[0], span[1], renderings.get(span[2]));
            }
            return Optional.of(rewritten.toString());
        } catch (RuntimeException | java.io.IOException error) {
            return Optional.empty();
        }
    }

    /**
     * Precedence-conservative argument rendering for AST substitution: an expression that already binds tighter than any
     * operator (a name/member-select chain, a literal, or an already-parenthesized expression) is spliced as-is; anything
     * that could re-associate against an operator in the body template (a binary/ternary/cast expression) is wrapped so it
     * preserves its meaning at every substitution point, regardless of the surrounding operator.
     */
    static String renderArgumentSubstitution(String argument) {
        String trimmed = argument.trim();
        if (trimmed.isEmpty() || isAtomicExpression(trimmed) || isParenthesized(trimmed)) {
            return trimmed;
        }
        return "(" + trimmed + ")";
    }

    static String renderReceiverSubstitution(String receiver) {
        String trimmed = receiver.trim();
        if (trimmed.isEmpty() || isAtomicExpression(trimmed) || isParenthesized(trimmed)) {
            return trimmed;
        }
        return "(" + trimmed + ")";
    }

    private static boolean isAtomicExpression(String expression) {
        return expression.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                || expression.matches("(?:true|false|null)")
                || expression.matches("(?:0[xX][0-9a-fA-F_]+|[0-9][0-9_]*(?:\\.[0-9_]+)?)(?:[eE][+-]?[0-9_]+)?[fFdDlL]?")
                || expression.matches("\\\"(?:\\\\.|[^\\\\\\\"])*\\\"")
                || expression.matches("'(?:\\\\.|[^\\\\'])+'");
    }

    private static boolean isParenthesized(String expression) {
        if (!expression.startsWith("(") || !expression.endsWith(")")) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char current = expression.charAt(i);
            if (current == '\"' || current == '\'') {
                i = skipQuoted(expression, i, current) - 1;
                continue;
            }
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0 && i < expression.length() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    static String replaceIdentifiers(String expression, Map<String, String> replacements) {
        if (replacements.isEmpty()) {
            return expression;
        }
        StringBuilder result = new StringBuilder(expression.length());
        int index = 0;
        while (index < expression.length()) {
            if (expression.startsWith("//", index)) {
                index = copyLineComment(expression, index, result);
                continue;
            }
            if (expression.startsWith("/*", index)) {
                index = copyBlockComment(expression, index, result);
                continue;
            }
            char current = expression.charAt(index);
            if (current == '\"' || current == '\'') {
                index = copyQuoted(expression, index, result, current);
                continue;
            }
            if (Character.isJavaIdentifierStart(current)) {
                int start = index++;
                while (index < expression.length() && Character.isJavaIdentifierPart(expression.charAt(index))) {
                    index++;
                }
                String identifier = expression.substring(start, index);
                if (replacements.containsKey(identifier) && !EvaluationOrderGuard.isQualifiedIdentifier(expression, start, identifier)) {
                    result.append(replacements.get(identifier));
                } else {
                    result.append(identifier);
                }
                continue;
            }
            result.append(current);
            index++;
        }
        return result.toString();
    }

    private static int copyQuoted(String expression, int start, StringBuilder result, char quote) {
        int index = start;
        result.append(expression.charAt(index++));
        while (index < expression.length()) {
            char current = expression.charAt(index++);
            result.append(current);
            if (current == '\\' && index < expression.length()) {
                result.append(expression.charAt(index++));
            } else if (current == quote) {
                break;
            }
        }
        return index;
    }

    private static int skipQuoted(String expression, int start, char quote) {
        int index = start + 1;
        while (index < expression.length()) {
            char current = expression.charAt(index++);
            if (current == '\\' && index < expression.length()) {
                index++;
            } else if (current == quote) {
                break;
            }
        }
        return index;
    }

    private static int copyLineComment(String expression, int start, StringBuilder result) {
        int index = start;
        while (index < expression.length()) {
            char current = expression.charAt(index++);
            result.append(current);
            if (current == '\n') {
                break;
            }
        }
        return index;
    }

    private static int copyBlockComment(String expression, int start, StringBuilder result) {
        int index = start;
        while (index < expression.length()) {
            char current = expression.charAt(index++);
            result.append(current);
            if (current == '*' && index < expression.length() && expression.charAt(index) == '/') {
                result.append(expression.charAt(index++));
                break;
            }
        }
        return index;
    }

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String source;

        private StringJavaFileObject(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
