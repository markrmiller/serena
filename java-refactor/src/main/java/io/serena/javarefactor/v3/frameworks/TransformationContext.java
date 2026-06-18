package io.serena.javarefactor.v3.frameworks;

import io.serena.javarefactor.compiler.FrameworkAnnotationIndex.AnnotationOccurrence;

import java.nio.file.Path;
import java.util.List;

/**
 * The read-only project context handed to a {@link FrameworkPlugin#participate(SymbolChange, TransformationContext)}
 * call (refactor-feature-plan-V3.md §16). It carries exactly the compiler-resolved framework facts a plugin needs to
 * reason about a pending change — the full set of {@link AnnotationOccurrence}s found across the project (the same exact
 * annotation facts that back {@code frameworks.detect}/{@code frameworks.findReferences}) plus the project root for
 * rendering resource-edit/warning paths — and nothing else, so participation can never reach past these facts into
 * planner internals.
 *
 * @param projectRoot the absolute, normalized project root
 * @param annotations every framework annotation application across the project (compiler-resolved, exact FQNs)
 */
public record TransformationContext(Path projectRoot, List<AnnotationOccurrence> annotations) {

    public TransformationContext {
        annotations = List.copyOf(annotations);
    }
}
