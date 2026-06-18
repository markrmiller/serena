package io.serena.javarefactor.v3.resources;

/**
 * Classification of a reference to a Java type/package found in a non-Java resource file
 * (refactor-feature-plan-V3.md §15). The kind records <em>why</em> the scanner believes the text is a type reference,
 * which in turn drives its {@link ResourceConfidence}.
 */
public enum ResourceReferenceKind {
    /** A maximal dotted token equal to the fully-qualified class name. */
    EXACT_CLASS_NAME,
    /** A maximal dotted token under the target package prefix. */
    PACKAGE_PREFIX,
    /** A provider class listed in {@code META-INF/services/*}. */
    SERVICE_LOADER_PROVIDER,
    /** A bean/component class referenced from a Spring context resource. */
    SPRING_BEAN_CLASS,
    /** An entity/converter class referenced from a JPA/persistence resource. */
    JPA_ENTITY_CLASS,
    /** A type name referenced from a Jackson/databind resource. */
    JACKSON_TYPE_NAME,
    /** A test class referenced from a JUnit resource (suite/config). */
    JUNIT_CLASS_NAME,
    /** A string literal that could be a reflectively-loaded class name (scan-only, never auto-edited). */
    REFLECTIVE_STRING_CANDIDATE
}
