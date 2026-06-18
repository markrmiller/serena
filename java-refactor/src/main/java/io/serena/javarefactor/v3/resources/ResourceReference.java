package io.serena.javarefactor.v3.resources;

import java.nio.file.Path;

/**
 * A single occurrence in a non-Java resource file that references a Java type or package
 * (refactor-feature-plan-V3.md §15). Offsets are byte/char offsets into the resource file's text; {@code oldText} is the
 * exact matched token so callers can verify before any future edit.
 *
 * @param file        the resource file containing the reference
 * @param startOffset inclusive start offset of the matched token
 * @param endOffset   exclusive end offset of the matched token
 * @param oldText     the exact matched text
 * @param kind        why the scanner treats this as a type reference
 * @param confidence  how certain the scanner is
 * @param target      the query target this reference matched
 * @param providerId  the provider that produced this reference
 */
public record ResourceReference(
        Path file,
        int startOffset,
        int endOffset,
        String oldText,
        ResourceReferenceKind kind,
        ResourceConfidence confidence,
        String target,
        String providerId) {
}
