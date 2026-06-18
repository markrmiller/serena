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

final class CompilerTask {
        final Trees trees;
        final com.sun.source.util.DocTrees docTrees;
        final Elements elements;
        final Types types;
        final SourcePositions positions;
        final List<CompilationUnitTree> units;
        final Map<Path, CharSequence> sourceByPath;
        private List<TypeElement> projectTypesCache;

        private CompilerTask(Trees trees, com.sun.source.util.DocTrees docTrees, Elements elements, Types types, SourcePositions positions, List<CompilationUnitTree> units, Map<Path, CharSequence> sourceByPath) {
            this.trees = trees;
            this.docTrees = docTrees;
            this.elements = elements;
            this.types = types;
            this.positions = positions;
            this.units = units;
            this.sourceByPath = sourceByPath;
        }

        static CompilerTask open(JavaCompiler compiler, FileManagerPool fileManagerPool, SourceSet sourceSet, List<SourceSet> allSourceSets) throws IOException {
            List<Path> javaFiles = sourceSet.javaFiles().stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .distinct()
                    .toList();
            Charset charset = Charset.forName(sourceSet.encoding() == null ? "UTF-8" : sourceSet.encoding());
            DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
            List<String> options = crossSourceSetOptions(sourceSet, allSourceSets);
            // Reuse the pooled standard file manager for this (charset, options) configuration so the classpath/jar scan
            // is amortized across source sets and passes. The manager is owned by the pool and must NOT be closed here;
            // SemanticIndex.close() leaves it alone and the pool drops it on model change / shutdown.
            StandardJavaFileManager fileManager = fileManagerPool.acquire(compiler, charset, options);
            Iterable<? extends JavaFileObject> files = fileManager.getJavaFileObjectsFromPaths(javaFiles);
            JavacTask task = (JavacTask) compiler.getTask(
                    null, fileManager, collector, FileManagerPool.taskOptions(options), null, files);
            List<CompilationUnitTree> units = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                units.add(unit);
            }
            task.analyze();
            Map<Path, CharSequence> sourceByPath = javaFiles.stream()
                    .collect(Collectors.toMap(path -> path, path -> SemanticIndex.readSource(path, charset), (left, ignored) -> left));
            Trees trees = Trees.instance(task);
            com.sun.source.util.DocTrees docTrees = com.sun.source.util.DocTrees.instance(task);
            return new CompilerTask(trees, docTrees, task.getElements(), task.getTypes(), trees.getSourcePositions(), units, sourceByPath);
        }
        /**
         * The source set's own javac options, augmented with depended-on source sets' roots on {@code -sourcepath}
         * and {@code -implicit:none} (so cross-source-set references resolve against source without requiring the
         * referenced set to be pre-compiled). Modular source sets resolve cross-module references via their own
         * {@code --module-source-path}, so a flat {@code -sourcepath} would conflict; their options are left untouched.
         * Mirrors {@code JavacSession.crossSourceSetOptions}.
         */
        private static List<String> crossSourceSetOptions(SourceSet sourceSet, List<SourceSet> allSourceSets) {
            // Mirrors JavacSession.crossSourceSetOptions: only the depended-on source sets' roots (e.g. test -> main)
            // are added to -sourcepath, so main is indexed without visibility into test.
            List<Path> otherRoots = SourceSet.crossSourceRoots(sourceSet, allSourceSets);
            if (otherRoots.isEmpty() || sourceSet.modular()) {
                return sourceSet.javacOptions();
            }
            List<String> options = new ArrayList<>(sourceSet.javacOptions());
            options.add("-sourcepath");
            options.add(otherRoots.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator)));
            options.add("-implicit:none");
            return options;
        }

        /** Top-level and nested type declarations of this task's own compilation units. */
        List<TypeElement> projectTypes() {
            if (projectTypesCache == null) {
                LinkedHashSet<TypeElement> result = new LinkedHashSet<>();
                for (CompilationUnitTree unit : units) {
                    TreePath unitPath = new TreePath(unit);
                    for (Tree decl : unit.getTypeDecls()) {
                        Element element = trees.getElement(new TreePath(unitPath, decl));
                        if (element instanceof TypeElement type) {
                            addTypeRecursively(type, result);
                        }
                    }
                }
                projectTypesCache = new ArrayList<>(result);
            }
            return projectTypesCache;
        }

        private static void addTypeRecursively(TypeElement type, Set<TypeElement> out) {
            if (!out.add(type)) {
                return;
            }
            for (Element enclosed : type.getEnclosedElements()) {
                if (enclosed instanceof TypeElement nested) {
                    addTypeRecursively(nested, out);
                }
            }
        }

        /** Canonical key of an element resolved in this task (using this task's trees/types/unit/file). */
        String canonicalKey(Element element) {
            TreePath path = trees.getPath(element);
            if (path == null) {
                return SemanticKey.from(element).canonical();
            }
            CompilationUnitTree unit = path.getCompilationUnit();
            return SemanticKey.from(element, trees, types, unit, SemanticIndex.pathOf(unit)).canonical();
        }
    }
