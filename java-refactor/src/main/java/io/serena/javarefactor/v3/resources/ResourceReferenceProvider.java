package io.serena.javarefactor.v3.resources;

import java.nio.file.Path;
import java.util.List;

/**
 * SPI for locating and rewriting references to Java types/packages inside one family of non-Java resource files
 * (refactor-feature-plan-V3.md §15). Implementations are stateless and registered in {@link ResourceProviderRegistry}.
 *
 * <p>The SPI has two halves: {@link #findReferences} (read-only — surfacing every reference, including
 * low-confidence reflective candidates, for impact/safety analysis) and {@link #planEdits} (rewriting resource files
 * during a move/rename — planning only the edits that are SAFE to apply automatically). Both are real: package
 * rename/move drive their resource rewriting through {@code planEdits}, and safe-delete/impact consult
 * {@code findReferences}.
 */
public interface ResourceReferenceProvider {

    /** Stable identifier surfaced on every {@link ResourceReference} this provider emits. */
    String id();

    /** Whether this provider knows how to read {@code file}. */
    boolean supports(Path file);

    /**
     * Find references to {@code query.target} within {@code content} (the full text of {@code file}).
     *
     * @param file    the resource file (already confirmed via {@link #supports(Path)})
     * @param content the file's text
     * @param query   the resolved target
     * @return references in document order (possibly empty, never {@code null})
     */
    List<ResourceReference> findReferences(Path file, String content, ResourceQuery query);

    /**
     * Plan the SAFE in-place rewrites (and any file renames) this provider would apply to {@code file} for
     * {@code request} — the moved-type/package maps for one rename or move. A provider rewrites only what it can
     * rewrite without ambiguity: exact fully-qualified class tokens (HIGH) and, when enabled, bare package-prefix
     * tokens (MEDIUM). Reflective/free-text candidates are never auto-edited and so contribute no edits here; they
     * remain discoverable via {@link #findReferences}.
     *
     * @param file    the resource file (already confirmed via {@link #supports(Path)})
     * @param content the file's text
     * @param request the moved types/packages and which rewrite classes are enabled
     * @return the planned edits and file renames for this file (possibly {@link ResourceEditPlan#EMPTY}, never
     *     {@code null})
     */
    ResourceEditPlan planEdits(Path file, String content, ResourceRenameRequest request);
}
