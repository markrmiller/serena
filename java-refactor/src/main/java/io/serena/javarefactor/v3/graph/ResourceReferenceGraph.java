package io.serena.javarefactor.v3.graph;

import java.util.List;

/**
 * The resource view (refactor-feature-plan-V3.md §1.2): every exact, provider-backed reference to a project type found
 * inside a non-Java resource file.
 *
 * <p>Each {@link Reference} is sourced from the resource SPI providers
 * (ServiceLoader/Xml/StructuredText/Reflection) via {@link io.serena.javarefactor.v3.resources.ResourceReferenceScanner}
 * — it carries the target FQN, the structural kind, the confidence, the providing provider id, and exact offsets. These
 * are never bare substring hits, so a consumer can rely on {@link #referencesTo(String)} to decide whether a type rename
 * or move would invalidate string-encoded wiring the compiler cannot see.
 *
 * <p><b>Scan completeness (story R06).</b> The graph also carries whether the resource scan that produced these
 * references was complete. When {@link #scanIncomplete()} is true an in-scope resource file could not be examined
 * (unreadable or over the configured size cap), so the resource view is partial — a consumer relying on
 * {@link #referencesTo(String)} to decide whether a rename/move is safe MUST treat the answer as incomplete and escalate
 * to review rather than auto-applying. {@link #incompleteResourceFiles()} lists the project-relative files that were not
 * fully examined, for surfacing.
 *
 * @param references             every provider-backed resource reference, in (file, document-order) order
 * @param incompleteResourceFiles project-relative resource files the scan could not fully examine (empty when complete)
 */
public record ResourceReferenceGraph(List<Reference> references, List<String> incompleteResourceFiles) {

    public ResourceReferenceGraph(List<Reference> references, List<String> incompleteResourceFiles) {
        this.references = List.copyOf(references);
        this.incompleteResourceFiles = List.copyOf(incompleteResourceFiles);
    }

    /** Backward-compatible constructor for a fully-examined (complete) resource scan. */
    public ResourceReferenceGraph(List<Reference> references) {
        this(references, List.of());
    }

    /**
     * Whether the resource scan that produced this graph was incomplete (an in-scope resource file was unreadable or over
     * the size cap and therefore not examined). A consumer MUST escalate risk and refuse SAFE auto-apply when true.
     */
    public boolean scanIncomplete() {
        return !incompleteResourceFiles.isEmpty();
    }

    /**
     * A fully-qualified type reference located inside a resource file.
     *
     * @param target       the referenced type FQN
     * @param relativePath project-relative path of the resource file
     * @param startOffset  start character offset of the reference text
     * @param endOffset    end character offset of the reference text
     * @param oldText      the exact matched text
     * @param kind         the structural reference kind (provider classification)
     * @param confidence   the provider's confidence in the match
     * @param providerId   the id of the provider that found it
     */
    public record Reference(
            String target,
            String relativePath,
            int startOffset,
            int endOffset,
            String oldText,
            String kind,
            String confidence,
            String providerId) {
    }

    /** All resource references naming {@code fqn} exactly. */
    public List<Reference> referencesTo(String fqn) {
        return references.stream().filter(ref -> ref.target().equals(fqn)).toList();
    }

    /** All references located in a given resource file. */
    public List<Reference> referencesIn(String relativePath) {
        return references.stream().filter(ref -> ref.relativePath().equals(relativePath)).toList();
    }
}
