package io.serena.javarefactor.v3.resources;

/**
 * A resolved request to find references to a Java type or package in resource files
 * (refactor-feature-plan-V3.md §15).
 *
 * @param target    the fully-qualified class name (when {@code !isPackage}) or package name (when {@code isPackage})
 * @param isPackage whether {@code target} denotes a package prefix rather than a single class
 */
public record ResourceQuery(String target, boolean isPackage) {

    /** The simple name of a class target (segment after the last dot), or the whole target if unqualified. */
    public String simpleName() {
        int dot = target.lastIndexOf('.');
        return dot >= 0 ? target.substring(dot + 1) : target;
    }
}
