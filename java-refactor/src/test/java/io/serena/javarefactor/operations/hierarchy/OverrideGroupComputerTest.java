package io.serena.javarefactor.operations.hierarchy;

import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link OverrideGroupComputer} forms override groups from the canonical {@code Elements.overrides}
 * relation rather than name-only matching, using a real in-process javac compilation of inline sources.
 */
class OverrideGroupComputerTest {

    @Test
    void overrideAnnotatedMethodGroupsWithSupertypeDeclaration() {
        Compiled compiled = compile(
                source("demo.Base", "package demo; public class Base { public void foo() {} }"),
                source("demo.Child", "package demo; public class Child extends Base { @Override public void foo() {} }"));

        Set<String> group = groupContaining(compiled, "demo.Base", "foo");
        assertTrue(group.contains(key("demo.Base", "foo")), "supertype declaration is in the group");
        assertTrue(group.contains(key("demo.Child", "foo")), "subtype override is in the group");
        assertEquals(2, group.size());
    }

    @Test
    void overloadDoesNotGroup() {
        Compiled compiled = compile(
                source("demo.Base", "package demo; public class Base { public void bar(int x) {} }"),
                source("demo.Child", "package demo; public class Child extends Base { public void bar(String x) {} }"));

        // Same name and arity, different erased parameter types: an overload, not an override.
        assertTrue(groupsFor(compiled).isEmpty(), "overloads must not form an override group");
    }

    @Test
    void covariantReturnOverrideGroups() {
        Compiled compiled = compile(
                source("demo.Base", "package demo; public class Base { public Object make() { return null; } }"),
                source("demo.Child", "package demo; public class Child extends Base { @Override public String make() { return null; } }"));

        Set<String> group = groupContaining(compiled, "demo.Base", "make");
        assertTrue(group.contains(key("demo.Base", "make")));
        assertTrue(group.contains(key("demo.Child", "make")));
        assertEquals(2, group.size());
    }

    @Test
    void interfaceDefaultAndClassMethodGroup() {
        Compiled compiled = compile(
                source("demo.Runner", "package demo; public interface Runner { default void run() {} }"),
                source("demo.Job", "package demo; public class Job implements Runner { @Override public void run() {} }"));

        Set<String> group = groupContaining(compiled, "demo.Runner", "run");
        assertTrue(group.contains(key("demo.Runner", "run")));
        assertTrue(group.contains(key("demo.Job", "run")));
        assertEquals(2, group.size());
    }

    @Test
    void genericSubstitutionGroups() {
        Compiled compiled = compile(
                source("demo.Box", "package demo; public interface Box<T> { void put(T value); }"),
                source("demo.StringBox", "package demo; public class StringBox implements Box<String> { public void put(String value) {} }"));

        Set<String> group = groupContaining(compiled, "demo.Box", "put");
        assertTrue(group.contains(key("demo.Box", "put")), "generic declaration is in the group");
        assertTrue(group.contains(key("demo.StringBox", "put")), "concrete substitution override is in the group");
        assertEquals(2, group.size());
    }

    // --- harness -------------------------------------------------------------------------------------------------

    private record Compiled(List<TypeElement> types, Elements elements) {}

    private List<Set<String>> groupsFor(Compiled compiled) {
        return OverrideGroupComputer.compute(compiled.types(), compiled.elements(), OverrideGroupComputerTest::key);
    }

    private Set<String> groupContaining(Compiled compiled, String owner, String method) {
        String target = key(owner, method);
        return groupsFor(compiled).stream()
                .filter(group -> group.contains(target))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no override group contains " + target));
    }

    private static String key(ExecutableElement method) {
        TypeElement owner = (TypeElement) method.getEnclosingElement();
        return key(owner.getQualifiedName().toString(), method.getSimpleName().toString());
    }

    private static String key(String owner, String method) {
        return owner + "#" + method;
    }

    private Compiled compile(JavaFileObject... units) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavacTask task = (JavacTask) compiler.getTask(
                null, null, diagnostic -> {}, List.of("-proc:none"), null, List.of(units));
        try {
            task.analyze();
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
        Elements elements = task.getElements();
        List<TypeElement> types = new ArrayList<>();
        for (JavaFileObject unit : units) {
            TypeElement type = elements.getTypeElement(((InlineSource) unit).typeName);
            if (type != null) {
                collectTypes(type, types);
            }
        }
        return new Compiled(types, elements);
    }

    private void collectTypes(TypeElement type, List<TypeElement> out) {
        out.add(type);
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed instanceof TypeElement nested) {
                collectTypes(nested, out);
            }
        }
    }

    private static JavaFileObject source(String typeName, String code) {
        return new InlineSource(typeName, code);
    }

    private static final class InlineSource extends SimpleJavaFileObject {
        private final String code;
        private final String typeName;

        InlineSource(String typeName, String code) {
            super(URI.create("string:///" + typeName.replace('.', '/') + ".java"), Kind.SOURCE);
            this.code = code;
            this.typeName = typeName;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
