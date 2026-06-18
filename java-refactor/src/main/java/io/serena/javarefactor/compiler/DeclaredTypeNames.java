package io.serena.javarefactor.compiler;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts the set of fully-qualified type names DECLARED in a batch of Java source texts, using a real javac parse (no
 * attribution) — never a regex or filename heuristic. A compilation unit's declared types are {@code package + simpleName}
 * for each top-level type and, recursively, {@code outer.Nested} for nested types (canonical dotted form). This is the
 * exact, overlay-friendly primitive behind {@code v3} edit-validation: the difference between the types declared in the
 * files an edit deletes/renames-away and the types it (re)declares elsewhere is precisely the set of FQNs the edit removes.
 *
 * <p>Parse-only and content-addressed (the FQN derives from the package declaration and class names, not the file path),
 * so it works on in-memory post-edit content without touching disk and without needing the full project classpath.</p>
 */
public final class DeclaredTypeNames {

    private DeclaredTypeNames() {
    }

    /** The fully-qualified names of every type declared across the supplied Java source texts. */
    public static Set<String> from(Collection<String> javaSources) {
        Set<String> result = new LinkedHashSet<>();
        if (javaSources.isEmpty()) {
            return result;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return result;
        }
        List<JavaFileObject> files = new ArrayList<>();
        int index = 0;
        for (String source : javaSources) {
            if (source != null) {
                files.add(new StringSource("Source" + index++ + ".java", source));
            }
        }
        if (files.isEmpty()) {
            return result;
        }
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostic -> {
            }, List.of("-proc:none"), null, files);
            for (CompilationUnitTree unit : task.parse()) {
                String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                for (Tree typeDecl : unit.getTypeDecls()) {
                    if (typeDecl instanceof ClassTree classTree) {
                        collect(packageName, classTree, result);
                    }
                }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // A malformed source that does not parse contributes no declared names; the edit's javac validation reports it.
        }
        return result;
    }

    private static void collect(String enclosing, ClassTree type, Set<String> out) {
        String simpleName = type.getSimpleName().toString();
        if (simpleName.isEmpty()) {
            return; // anonymous / local classes have no stable FQN that a resource could reference
        }
        String fqn = enclosing.isEmpty() ? simpleName : enclosing + "." + simpleName;
        out.add(fqn);
        for (Tree member : type.getMembers()) {
            if (member instanceof ClassTree nested) {
                collect(fqn, nested, out);
            }
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        private StringSource(String name, String code) {
            super(URI.create("string:///" + name), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
