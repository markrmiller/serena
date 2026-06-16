package io.serena.javarefactor.compiler;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
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
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.serena.javarefactor.compiler.SemanticIndex.SemanticExtractVariable;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticStatementSelection;
import io.serena.javarefactor.compiler.SemanticIndex.SourceRange;
final class SemanticSelectionIndex {
    private final SemanticIndex index;
    private final Trees trees;
    private final Elements elements;
    private final Types types;

    SemanticSelectionIndex(SemanticIndex index) {
        this.index = index;
        this.trees = index.trees;
        this.elements = index.elements;
        this.types = index.types;
    }

    private CompilationUnitTree compilationUnit(Path file) {
        CompilerTask task = index.taskFor(file);
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilationUnitTree unit : task.units) {
            if (normalized.equals(SemanticIndex.pathOf(unit))) {
                return unit;
            }
        }
        return null;
    }

    private CharSequence sourceText(Path file) {
        return index.sourceText(file);
    }

    private Element enclosingExecutable(CompilerTask task, TreePath path) {
        return index.enclosingExecutable(task, path);
    }

    /** The {@link javax.lang.model.element.TypeElement} of the nearest enclosing {@link ClassTree}, or {@code null}. */
    private Element enclosingTypeElement(CompilerTask task, TreePath path) {
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof ClassTree) {
                return task.trees.getElement(current);
            }
        }
        return null;
    }

    private static int trimSelectionStart(CharSequence source, int start, int end) {
        return SemanticIndex.trimSelectionStart(source, start, end);
    }

    private static int trimSelectionEnd(CharSequence source, int start, int end) {
        return SemanticIndex.trimSelectionEnd(source, start, end);
    }

    private static String expressionTypeName(TypeMirror typeMirror) {
        return SemanticIndex.expressionTypeName(typeMirror);
    }

private record ExtractionFlow(
            List<SemanticExtractVariable> inputs,
            List<SemanticExtractVariable> outputs,
            Set<String> checkedExceptions,
            boolean usesThis,
            boolean usesSuper,
            boolean enclosingMethodStatic,
            boolean hasControlFlowExit) {}
private static final class ExtractVariableState {
        final String type;
        final String name;
        final boolean declaredInSelection;
        boolean readInSelection;
        boolean writtenInSelection;
        boolean usedAfterSelection;

        ExtractVariableState(String type, String name, boolean declaredInSelection) {
            this.type = type;
            this.name = name;
            this.declaredInSelection = declaredInSelection;
        }
    }
ExpressionExtractionFlow expressionExtractionFlow(CompilerTask task, CompilationUnitTree unit, CharSequence source, TreePath methodPath, SourceRange selectionRange) {
    ExtractionFlow flow = extractionFlow(task, unit, source, methodPath, selectionRange);
    return new ExpressionExtractionFlow(flow.inputs(), flow.checkedExceptions(), flow.usesThis(), flow.usesSuper(), flow.enclosingMethodStatic());
}

private ExtractionFlow extractionFlow(CompilerTask task, CompilationUnitTree unit, CharSequence source, TreePath methodPath, SourceRange selectionRange) {
        // The extraction scope is one of: a method/constructor body (MethodTree); a class/instance initializer block
        // (BlockTree whose direct parent is a ClassTree); or a field initializer (VariableTree whose direct parent is a
        // ClassTree). Block and field-initializer scopes have no parameters; their "static" fact is the block's/field's
        // own static modifier (a static scope extracts to a static helper, an instance scope to an instance helper).
        MethodTree method = methodPath != null && methodPath.getLeaf() instanceof MethodTree m ? m : null;
        BlockTree initializerBlock = methodPath != null && methodPath.getLeaf() instanceof BlockTree b ? b : null;
        VariableTree fieldInitializer = methodPath != null && methodPath.getLeaf() instanceof VariableTree v ? v : null;
        if (method == null && initializerBlock == null && fieldInitializer == null) {
            return new ExtractionFlow(List.of(), List.of(), Set.of(), false, false, false, false);
        }
        int selectedStart = selectionRange.start();
        int selectedEnd = selectionRange.end();
        LinkedHashMap<Element, ExtractVariableState> variables = new LinkedHashMap<>();
        LinkedHashSet<String> checkedExceptions = new LinkedHashSet<>();
        boolean[] usesThis = {false};
        boolean[] usesSuper = {false};
        boolean[] control = {false};
        boolean methodStatic = method != null
                ? method.getModifiers().getFlags().contains(Modifier.STATIC)
                : initializerBlock != null
                        ? initializerBlock.isStatic()
                        : fieldInitializer.getModifiers().getFlags().contains(Modifier.STATIC);

        if (method != null) {
            for (VariableTree parameter : method.getParameters()) {
                Element element = task.trees.getElement(new TreePath(methodPath, parameter));
                if (element instanceof VariableElement variable) {
                    variables.put(variable, new ExtractVariableState(expressionTypeName(variable.asType()), variable.getSimpleName().toString(), false));
                }
            }
        }

        class FlowScanner extends TreePathScanner<Void, Void> {
            private boolean writeTarget;
            private boolean compoundWriteTarget;

            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                if (getCurrentPath() != null && !getCurrentPath().equals(methodPath)) {
                    return null;
                }
                return super.visitMethod(node, unused);
            }

            @Override
            public Void visitClass(ClassTree node, Void unused) {
                if (getCurrentPath() != null && !getCurrentPath().equals(methodPath.getParentPath())) {
                    return null;
                }
                return super.visitClass(node, unused);
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                if (intersects(node, selectedStart, selectedEnd)) {
                    return null;
                }
                return super.visitLambdaExpression(node, unused);
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                Element element = task.trees.getElement(getCurrentPath());
                boolean inSelection = intersects(node, selectedStart, selectedEnd);
                if (element instanceof VariableElement variable && isExtractableLocal(variable)) {
                    variables.computeIfAbsent(variable, ignored -> new ExtractVariableState(
                            expressionTypeName(variable.asType()),
                            variable.getSimpleName().toString(),
                            inSelection));
                    if (inSelection) {
                        variables.get(variable).writtenInSelection = true;
                    }
                }
                scan(node.getModifiers(), unused);
                scan(node.getType(), unused);
                scan(node.getInitializer(), unused);
                return null;
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                recordVariableUse(node);
                String name = node.getName().toString();
                if ("this".equals(name)) {
                    usesThis[0] = true;
                } else if ("super".equals(name)) {
                    usesSuper[0] = true;
                }
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                Element element = task.trees.getElement(getCurrentPath());
                if (intersects(node, selectedStart, selectedEnd) && element instanceof VariableElement variable && variable.getKind() == ElementKind.FIELD) {
                    if (!variable.getModifiers().contains(Modifier.STATIC)) {
                        usesThis[0] = true;
                    }
                }
                return super.visitMemberSelect(node, unused);
            }

            @Override
            public Void visitAssignment(AssignmentTree node, Void unused) {
                boolean previousWrite = writeTarget;
                writeTarget = true;
                scan(node.getVariable(), unused);
                writeTarget = previousWrite;
                scan(node.getExpression(), unused);
                return null;
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                boolean previousWrite = writeTarget;
                boolean previousCompound = compoundWriteTarget;
                writeTarget = true;
                compoundWriteTarget = true;
                scan(node.getVariable(), unused);
                writeTarget = previousWrite;
                compoundWriteTarget = previousCompound;
                scan(node.getExpression(), unused);
                return null;
            }

            @Override
            public Void visitUnary(UnaryTree node, Void unused) {
                switch (node.getKind()) {
                    case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> {
                        boolean previousWrite = writeTarget;
                        boolean previousCompound = compoundWriteTarget;
                        writeTarget = true;
                        compoundWriteTarget = true;
                        scan(node.getExpression(), unused);
                        writeTarget = previousWrite;
                        compoundWriteTarget = previousCompound;
                        return null;
                    }
                    default -> {
                        return super.visitUnary(node, unused);
                    }
                }
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if (intersects(node, selectedStart, selectedEnd)) {
                    Element element = task.trees.getElement(getCurrentPath());
                    if (element instanceof ExecutableElement executable) {
                        addCheckedExceptions(executable, checkedExceptions);
                    }
                }
                return super.visitMethodInvocation(node, unused);
            }

            @Override
            public Void visitNewClass(NewClassTree node, Void unused) {
                if (intersects(node, selectedStart, selectedEnd)) {
                    Element element = task.trees.getElement(getCurrentPath());
                    if (element instanceof ExecutableElement executable) {
                        addCheckedExceptions(executable, checkedExceptions);
                    }
                    if (node.getClassBody() != null) {
                        return null;
                    }
                }
                return super.visitNewClass(node, unused);
            }

            @Override
            public Void visitThrow(ThrowTree node, Void unused) {
                if (intersects(node, selectedStart, selectedEnd)) {
                    TypeMirror thrown = task.trees.getTypeMirror(new TreePath(getCurrentPath(), node.getExpression()));
                    addCheckedException(thrown, checkedExceptions);
                }
                return super.visitThrow(node, unused);
            }

            @Override
            public Void visitReturn(ReturnTree node, Void unused) {
                if (intersects(node, selectedStart, selectedEnd)) {
                    control[0] = true;
                }
                return super.visitReturn(node, unused);
            }

            @Override
            public Void visitBreak(BreakTree node, Void unused) {
                if (intersects(node, selectedStart, selectedEnd)) {
                    control[0] = true;
                }
                return super.visitBreak(node, unused);
            }

            @Override
            public Void visitContinue(ContinueTree node, Void unused) {
                if (intersects(node, selectedStart, selectedEnd)) {
                    control[0] = true;
                }
                return super.visitContinue(node, unused);
            }

            private boolean intersects(Tree tree, int start, int end) {
                long treeStart = task.positions.getStartPosition(unit, tree);
                long treeEnd = task.positions.getEndPosition(unit, tree);
                return treeStart >= 0 && treeEnd >= treeStart && treeStart < end && treeEnd > start;
            }

            private boolean startsAtOrAfter(Tree tree, int offset) {
                long treeStart = task.positions.getStartPosition(unit, tree);
                return treeStart >= offset;
            }

            private void recordVariableUse(Tree node) {
                Element element = task.trees.getElement(getCurrentPath());
                if (!(element instanceof VariableElement variable)) {
                    return;
                }
                if (variable.getKind() == ElementKind.FIELD) {
                    if (intersects(node, selectedStart, selectedEnd) && !variable.getModifiers().contains(Modifier.STATIC)) {
                        usesThis[0] = true;
                    }
                    return;
                }
                if (!isExtractableLocal(variable)) {
                    return;
                }
                ExtractVariableState state = variables.computeIfAbsent(variable, ignored -> new ExtractVariableState(
                        expressionTypeName(variable.asType()),
                        variable.getSimpleName().toString(),
                        false));
                boolean inSelection = intersects(node, selectedStart, selectedEnd);
                if (inSelection) {
                    if (writeTarget) {
                        state.writtenInSelection = true;
                        if (compoundWriteTarget) {
                            state.readInSelection = true;
                        }
                    } else if (!state.declaredInSelection) {
                        state.readInSelection = true;
                    }
                } else if (startsAtOrAfter(node, selectedEnd) && (state.declaredInSelection || state.writtenInSelection)) {
                    state.usedAfterSelection = true;
                }
            }
        }

        new FlowScanner().scan(methodPath, null);

        List<SemanticExtractVariable> inputs = new ArrayList<>();
        List<SemanticExtractVariable> outputs = new ArrayList<>();
        for (ExtractVariableState state : variables.values()) {
            if (state.readInSelection && !state.declaredInSelection) {
                inputs.add(new SemanticExtractVariable(state.type, state.name, false));
            }
            if (state.usedAfterSelection && (state.declaredInSelection || state.writtenInSelection)) {
                outputs.add(new SemanticExtractVariable(state.type, state.name, state.declaredInSelection));
            }
        }
        return new ExtractionFlow(
                List.copyOf(inputs),
                List.copyOf(outputs),
                Set.copyOf(checkedExceptions),
                usesThis[0],
                usesSuper[0],
                methodStatic,
                control[0]);
    }
private boolean isExtractableLocal(VariableElement variable) {
        ElementKind kind = variable.getKind();
        return kind == ElementKind.LOCAL_VARIABLE || kind == ElementKind.PARAMETER || kind == ElementKind.EXCEPTION_PARAMETER;
    }
private void addCheckedExceptions(ExecutableElement executable, Set<String> checkedExceptions) {
        for (TypeMirror thrown : executable.getThrownTypes()) {
            addCheckedException(thrown, checkedExceptions);
        }
    }
private void addCheckedException(TypeMirror thrown, Set<String> checkedExceptions) {
        if (thrown == null || !isCheckedException(thrown)) {
            return;
        }
        String type = expressionTypeName(thrown);
        if (type != null && !type.isBlank()) {
            checkedExceptions.add(type);
        }
    }
private boolean isCheckedException(TypeMirror thrown) {
        TypeElement runtimeException = elements.getTypeElement("java.lang.RuntimeException");
        TypeElement error = elements.getTypeElement("java.lang.Error");
        if (runtimeException == null || error == null) {
            return true;
        }
        TypeMirror erased = types.erasure(thrown);
        return !types.isSubtype(erased, types.erasure(runtimeException.asType()))
                && !types.isSubtype(erased, types.erasure(error.asType()));
    }
    SemanticStatementSelection statementSelection(Path file, int start, int end) {
        Path normalized = file.toAbsolutePath().normalize();
        for (CompilerTask task : index.allTasks()) {
            CharSequence source = task.sourceByPath.get(normalized);
            if (source == null) {
                continue;
            }
            CompilationUnitTree unit = task.units.stream()
                    .filter(candidate -> normalized.equals(SemanticIndex.pathOf(candidate)))
                    .findFirst()
                    .orElse(null);
            if (unit == null) {
                continue;
            }
            if (end < start) {
                throw new IllegalArgumentException("Extract method selection end precedes selection start.");
            }
            int trimmedStart = trimSelectionStart(source, start, end);
            int trimmedEnd = trimSelectionEnd(source, trimmedStart, end);
            if (trimmedStart >= trimmedEnd) {
                return null;
            }

            List<StatementTree> containedStatements = new ArrayList<>();
            List<StatementTree> intersectingStatements = new ArrayList<>();
            TreePath[] methodPath = new TreePath[1];
            SourceRange[] methodRange = new SourceRange[1];
            SourceRange[] typeBodyRange = new SourceRange[1];
            // Fallback scope for selections that are not inside any method/constructor body: a class/static initializer
            // block (a BlockTree whose direct parent is a ClassTree). G022 currently supports only the STATIC case.
            TreePath[] initBlockPath = new TreePath[1];
            SourceRange[] initBlockRange = new SourceRange[1];
            boolean[] crosses = {false};
            boolean[] control = {false};

            class SelectionScanner extends TreePathScanner<Void, Void> {
                @Override
                public Void scan(Tree tree, Void unused) {
                    if (tree == null) {
                        return null;
                    }
                    if (tree instanceof StatementTree statement) {
                        long rawStart = task.positions.getStartPosition(unit, tree);
                        long rawEnd = task.positions.getEndPosition(unit, tree);
                        if (rawStart >= 0 && rawEnd >= rawStart && rawEnd <= source.length()) {
                            int statementStart = trimSelectionStart(source, (int) rawStart, (int) rawEnd);
                            int statementEnd = trimSelectionEnd(source, statementStart, (int) rawEnd);
                            if (statementStart < trimmedEnd && statementEnd > trimmedStart) {
                                if (statementStart >= trimmedStart && statementEnd <= trimmedEnd) {
                                    containedStatements.add(statement);
                                } else {
                                    intersectingStatements.add(statement);
                                }
                            }
                        }
                    }
                    return super.scan(tree, unused);
                }

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    long methodStart = task.positions.getStartPosition(unit, node);
                    long methodEnd = task.positions.getEndPosition(unit, node);
                    if (methodStart >= 0 && methodEnd >= trimmedEnd && methodStart <= trimmedStart) {
                        if (methodRange[0] == null || methodEnd - methodStart < methodRange[0].end() - methodRange[0].start()) {
                            methodPath[0] = getCurrentPath();
                            methodRange[0] = new SourceRange(normalized, (int) methodStart, (int) methodEnd);
                        }
                    }
                    return super.visitMethod(node, unused);
                }

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    long classStart = task.positions.getStartPosition(unit, node);
                    long classEnd = task.positions.getEndPosition(unit, node);
                    if (classStart >= 0 && classEnd >= trimmedEnd && classStart <= trimmedStart) {
                        int open = SemanticIndex.firstChar(source, (int) classStart, (int) classEnd, '{');
                        int close = SemanticIndex.lastChar(source, (int) classStart, (int) classEnd, '}');
                        if (open >= 0 && close >= open) {
                            if (typeBodyRange[0] == null || classEnd - classStart < typeBodyRange[0].end() - typeBodyRange[0].start()) {
                                typeBodyRange[0] = new SourceRange(normalized, open, close + 1);
                            }
                        }
                    }
                    TreePath parent = getCurrentPath() == null ? null : getCurrentPath().getParentPath();
                    if (parent != null && !(parent.getLeaf() instanceof CompilationUnitTree) && intersects(node)) {
                        crosses[0] = true;
                        return null;
                    }
                    return super.visitClass(node, unused);
                }

                @Override
                public Void visitBlock(BlockTree node, Void unused) {
                    // A class/instance initializer block is a BlockTree whose direct parent is a ClassTree. Record the
                    // tightest initializer block (static OR instance) enclosing the selection as a fallback extraction
                    // scope (used only when no method/constructor body encloses the selection). A static block extracts
                    // to a static helper, an instance block to an instance helper; the static fact is carried by the
                    // block's own modifier and resolved in extractionFlow. Both are inserted into the enclosing type body
                    // and the complete selected statements are replaced with a call, preserving evaluation order.
                    TreePath parent = getCurrentPath() == null ? null : getCurrentPath().getParentPath();
                    if (parent != null && parent.getLeaf() instanceof ClassTree) {
                        long blockStart = task.positions.getStartPosition(unit, node);
                        long blockEnd = task.positions.getEndPosition(unit, node);
                        if (blockStart >= 0 && blockEnd >= trimmedEnd && blockStart <= trimmedStart) {
                            if (initBlockRange[0] == null || blockEnd - blockStart < initBlockRange[0].end() - initBlockRange[0].start()) {
                                initBlockPath[0] = getCurrentPath();
                                initBlockRange[0] = new SourceRange(normalized, (int) blockStart, (int) blockEnd);
                            }
                        }
                    }
                    return super.visitBlock(node, unused);
                }

                @Override
                public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                    if (intersects(node)) {
                        crosses[0] = true;
                        return null;
                    }
                    return super.visitLambdaExpression(node, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    if (node.getClassBody() != null && intersects(node)) {
                        crosses[0] = true;
                        return null;
                    }
                    return super.visitNewClass(node, unused);
                }

                @Override
                public Void visitReturn(ReturnTree node, Void unused) {
                    if (intersects(node)) {
                        control[0] = true;
                    }
                    return super.visitReturn(node, unused);
                }

                @Override
                public Void visitBreak(BreakTree node, Void unused) {
                    if (intersects(node)) {
                        control[0] = true;
                    }
                    return super.visitBreak(node, unused);
                }

                @Override
                public Void visitContinue(ContinueTree node, Void unused) {
                    if (intersects(node)) {
                        control[0] = true;
                    }
                    return super.visitContinue(node, unused);
                }

                private boolean intersects(Tree tree) {
                    long treeStart = task.positions.getStartPosition(unit, tree);
                    long treeEnd = task.positions.getEndPosition(unit, tree);
                    return treeStart >= 0 && treeEnd >= treeStart && treeStart < trimmedEnd && treeEnd > trimmedStart;
                }
            }

            SelectionScanner scanner = new SelectionScanner();
            scanner.scan(unit, null);
            // Prefer a method/constructor scope; fall back to a static initializer block scope when no method encloses
            // the selection. The chosen scope drives the data-flow root, the insertion anchor, and the static decision.
            TreePath scopePath = methodPath[0] != null ? methodPath[0] : initBlockPath[0];
            SourceRange scopeRange = methodRange[0] != null ? methodRange[0] : initBlockRange[0];
            List<StatementTree> rangeStatements = containedStatements.isEmpty() ? intersectingStatements : containedStatements;
            if (rangeStatements.isEmpty() || scopePath == null || scopeRange == null || typeBodyRange[0] == null) {
                return null;
            }

            int semanticStart = rangeStatements.stream()
                    .mapToInt(statement -> trimSelectionStart(source,
                            (int) task.positions.getStartPosition(unit, statement),
                            (int) task.positions.getEndPosition(unit, statement)))
                    .min()
                    .orElse(trimmedStart);
            int semanticEnd = rangeStatements.stream()
                    .mapToInt(statement -> trimSelectionEnd(source,
                            trimSelectionStart(source, (int) task.positions.getStartPosition(unit, statement), (int) task.positions.getEndPosition(unit, statement)),
                            (int) task.positions.getEndPosition(unit, statement)))
                    .max()
                    .orElse(trimmedEnd);
            SourceRange normalizedRange = new SourceRange(normalized, semanticStart, semanticEnd);
            boolean complete = !containedStatements.isEmpty() && semanticStart >= trimmedStart && semanticEnd <= trimmedEnd;
            ExtractionFlow flow = extractionFlow(task, unit, source, scopePath, normalizedRange);
            Element enclosing = methodPath[0] != null
                    ? enclosingExecutable(task, scopePath)
                    : enclosingTypeElement(task, scopePath);
            return new SemanticStatementSelection(
                    normalized,
                    normalizedRange,
                    scopeRange,
                    typeBodyRange[0],
                    complete ? null : normalizedRange,
                    complete,
                    crosses[0],
                    control[0] || flow.hasControlFlowExit(),
                    flow.inputs(),
                    flow.outputs(),
                    flow.checkedExceptions(),
                    enclosing,
                    flow.usesThis(),
                    flow.usesSuper(),
                    flow.enclosingMethodStatic());
        }
        return null;
    }
}

record ExpressionExtractionFlow(
        List<SemanticExtractVariable> inputs,
        Set<String> checkedExceptions,
        boolean usesThis,
        boolean usesSuper,
        boolean enclosingMethodStatic) {}
