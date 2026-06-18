package io.serena.javarefactor.v3.classops;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticMethod;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Static helpers shared by the V3 class-shape planners (extract class / extract superclass / replace inheritance with
 * delegation). Keeps selector parsing, member resolution, and small text utilities in one place so each planner reads as
 * a straight transcription of its spec section.
 */
final class ClassOpsSupport {

    private ClassOpsSupport() {
    }

    /** A parsed member selector such as {@code field:taxPolicy} or {@code method:calculateTax(Order)}. */
    record Selector(String kind, String name, List<String> paramTypes) {
        boolean isField() {
            return "field".equals(kind);
        }

        boolean isMethod() {
            return "method".equals(kind);
        }
    }

    static Selector parseSelector(String raw) {
        if (raw == null) {
            throw new ClassOpsRefusal("invalid_member", "A member selector must not be null.");
        }
        String text = raw.trim();
        int colon = text.indexOf(':');
        if (colon <= 0) {
            throw new ClassOpsRefusal("invalid_member",
                    "Member selector '" + raw + "' must be 'field:<name>' or 'method:<name>(<types>)'.");
        }
        String kind = text.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String spec = text.substring(colon + 1).trim();
        if (!kind.equals("field") && !kind.equals("method")) {
            throw new ClassOpsRefusal("invalid_member", "Member selector kind must be 'field' or 'method': " + raw);
        }
        if (kind.equals("field")) {
            return new Selector("field", spec, null);
        }
        int paren = spec.indexOf('(');
        if (paren < 0) {
            return new Selector("method", spec.trim(), null);
        }
        String name = spec.substring(0, paren).trim();
        String inside = spec.substring(paren + 1, spec.endsWith(")") ? spec.length() - 1 : spec.length()).trim();
        List<String> params = new ArrayList<>();
        if (!inside.isEmpty()) {
            for (String part : inside.split(",")) {
                params.add(simpleType(part.trim()));
            }
        }
        return new Selector("method", name, params);
    }

    /** Resolves a {@code method:} selector to the single matching declared method, refusing on miss/ambiguity. */
    static SemanticMethod resolveMethod(SemanticIndex index, SemanticType type, Selector selector) {
        if (!(type.element() instanceof TypeElement owner)) {
            throw new ClassOpsRefusal("source_type_not_found", "Source type could not be resolved by javac.");
        }
        List<ExecutableElement> matches = new ArrayList<>();
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement method
                    && method.getKind() == ElementKind.METHOD
                    && method.getSimpleName().contentEquals(selector.name())) {
                if (selector.paramTypes() == null || parameterTypesMatch(method, selector.paramTypes())) {
                    matches.add(method);
                }
            }
        }
        if (matches.isEmpty()) {
            throw new ClassOpsRefusal("member_not_found",
                    "No method '" + selector.name() + "' was found on " + type.qualifiedName() + ".");
        }
        if (matches.size() > 1) {
            throw new ClassOpsRefusal("ambiguous_member",
                    "Method selector '" + selector.name() + "' is ambiguous; qualify it with parameter types.");
        }
        SemanticMethod method = index.semanticMethod(matches.get(0));
        if (method == null) {
            throw new ClassOpsRefusal("member_not_found",
                    "Method '" + selector.name() + "' could not be resolved to a source declaration.");
        }
        return method;
    }

    private static boolean parameterTypesMatch(ExecutableElement method, List<String> wanted) {
        List<? extends VariableElement> params = method.getParameters();
        if (params.size() != wanted.size()) {
            return false;
        }
        for (int i = 0; i < params.size(); i++) {
            if (!simpleType(params.get(i).asType().toString()).equals(simpleType(wanted.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /** Reduces a type rendering to its bare simple name (drop package qualifier and generic arguments). */
    static String simpleType(String type) {
        if (type == null) {
            return "";
        }
        String text = type.trim();
        int angle = text.indexOf('<');
        if (angle >= 0) {
            text = text.substring(0, angle);
        }
        int dot = text.lastIndexOf('.');
        if (dot >= 0) {
            text = text.substring(dot + 1);
        }
        return text.trim();
    }

    static String decapitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** Copies every {@code import ...;} line verbatim from a source file (slight over-import is harmless). */
    static List<String> importLines(String source) {
        List<String> imports = new ArrayList<>();
        for (String line : source.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                imports.add(trimmed);
            }
        }
        return imports;
    }
}
