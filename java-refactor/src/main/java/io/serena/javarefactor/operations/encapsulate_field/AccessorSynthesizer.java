package io.serena.javarefactor.operations.encapsulate_field;

import io.serena.javarefactor.shared.JavaStyleProfile;

/**
 * Synthesizes JavaBean getter/setter names and accessor method bodies for {@code encapsulateField} (plan §3
 * {@code encapsulate_field/AccessorSynthesizer}).
 *
 * <p>This is a pure, side-effect-free unit: it neither resolves javac symbols nor produces {@code TextEdit}s. The
 * planner owns symbol resolution and collision detection (a name produced here may still collide with an existing
 * method, which the planner refuses); this class only decides the canonical names and renders the accessor source so
 * the rendering rules live in one tested place.
 */
public final class AccessorSynthesizer {

    private AccessorSynthesizer() {
    }

    /**
     * The default getter name for a field, following JavaBean conventions:
     *
     * <ul>
     *   <li>{@code boolean} primitive: {@code isX} — but if the field name already reads as {@code is<Upper>}
     *       (e.g. {@code isEnabled}) the name is reused verbatim to avoid the double-prefix {@code isIsEnabled()}.</li>
     *   <li>wrapper {@code Boolean} and all other reference types: {@code getX} — the {@code is} prefix is reserved for
     *       the {@code boolean} primitive because a {@code Boolean} can be {@code null}.</li>
     * </ul>
     */
    public static String defaultGetterName(String type, String name) {
        if ("boolean".equals(type)) {
            if (name.length() >= 3 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
                return name;
            }
            return "is" + capitalize(name);
        }
        return "get" + capitalize(name);
    }

    /** The default setter name {@code setX} for a field. */
    public static String defaultSetterName(String name) {
        return "set" + capitalize(name);
    }

    /**
     * Renders the getter (and, when {@code generateSetter} is true, the setter) source to be inserted at the end of the
     * declaring type body. A {@code static} field gets static accessors whose setter assigns through {@code setterTarget}
     * (the declaring type name, since a static context cannot use {@code this.}); an instance field's setter assigns
     * through {@code this.<name>}. Line endings are normalized to the surrounding source style.
     *
     * @param setterTarget the left-hand side of the setter assignment ({@code this.count} or {@code Sample.count})
     */
    public static String renderAccessors(
            JavaStyleProfile style,
            String indent,
            String bodyIndent,
            boolean staticField,
            String type,
            String getterName,
            String name,
            boolean generateSetter,
            String setterName,
            String setterTarget) {
        String staticModifier = staticField ? "static " : "";
        String openBrace = style.openBrace(indent);
        String accessors = style.normalizeLineEndings("\n" + indent + "public " + staticModifier + type + " " + getterName + "()" + openBrace + "\n"
                + bodyIndent + "return " + name + ";\n" + indent + "}\n");
        if (generateSetter) {
            String valueParameter = style.parameter(type, "value");
            accessors = style.normalizeLineEndings(accessors + "\n"
                    + indent + "public " + staticModifier + "void " + setterName + "(" + valueParameter + ")" + openBrace + "\n"
                    + bodyIndent + setterTarget + " = value;\n" + indent + "}\n");
        }
        return accessors;
    }

    private static String capitalize(String name) {
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }
}
