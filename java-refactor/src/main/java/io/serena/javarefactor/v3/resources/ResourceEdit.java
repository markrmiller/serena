package io.serena.javarefactor.v3.resources;

import java.nio.file.Path;

/**
 * A single in-place text rewrite a {@link ResourceReferenceProvider} plans for a non-Java resource file when a Java
 * type/package is renamed or moved (refactor-feature-plan-V3.md §15, "planEdits" half of the SPI). Offsets are char
 * offsets into the resource file's text and {@code newText} replaces {@code [startOffset, endOffset)}.
 *
 * <p>Unlike {@link ResourceReference} (whose {@link ResourceConfidence} reports how sure the scanner is that the text is
 * a reference), the confidence here reports how SAFE the rewrite is: a structurally-exact fully-qualified class token is
 * {@link ResourceConfidence#HIGH}, a bare package-prefix token is {@link ResourceConfidence#MEDIUM}. Providers never
 * plan {@link ResourceConfidence#LOW} edits — low-confidence (reflective/free-text) matches are surfaced for review via
 * {@code findReferences}, never auto-rewritten.
 *
 * @param file        the resource file to edit
 * @param startOffset inclusive start offset of the replaced span
 * @param endOffset   exclusive end offset of the replaced span
 * @param newText     the replacement text
 * @param kind        why the provider treats the span as a type/package reference
 * @param confidence  how safe the rewrite is (HIGH exact class, MEDIUM package prefix)
 * @param providerId  the provider that planned this edit
 */
public record ResourceEdit(
        Path file,
        int startOffset,
        int endOffset,
        String newText,
        ResourceReferenceKind kind,
        ResourceConfidence confidence,
        String providerId) {
}
