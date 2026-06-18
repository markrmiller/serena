package io.serena.javarefactor.v3.resources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scan-only fallback for any other text resource (refactor-feature-plan-V3.md §15). It flags maximal dotted tokens that
 * match the target as {@link ResourceReferenceKind#REFLECTIVE_STRING_CANDIDATE} with {@link ResourceConfidence#LOW}:
 * these may be reflectively-loaded class names, but the format is unknown, so they are surfaced for human review and
 * never auto-edited.
 */
final class ReflectionResourceProvider implements ResourceReferenceProvider {

    @Override
    public String id() {
        return "reflection-candidate";
    }

    @Override
    public boolean supports(Path file) {
        // Fallback provider: the registry only consults it when no more-specific provider claims the file.
        return true;
    }

    @Override
    public List<ResourceReference> findReferences(Path file, String content, ResourceQuery query) {
        if (looksBinary(content)) {
            return List.of();
        }
        List<ResourceReference> refs = new ArrayList<>();
        for (ResourceSupport.Token token : ResourceSupport.dottedTokens(content)) {
            if (ResourceSupport.matches(token.text(), query)) {
                refs.add(new ResourceReference(file, token.start(), token.end(), token.text(),
                        ResourceReferenceKind.REFLECTIVE_STRING_CANDIDATE, ResourceConfidence.LOW,
                        query.target(), id()));
            }
        }
        return refs;
    }

    @Override
    public ResourceEditPlan planEdits(Path file, String content, ResourceRenameRequest request) {
        // Scan-only fallback: reflective/free-text candidates are surfaced via findReferences for human review and
        // never auto-rewritten, so this provider plans no edits.
        return ResourceEditPlan.EMPTY;
    }

    private static boolean looksBinary(String content) {
        int limit = Math.min(content.length(), 4096);
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\0') {
                return true;
            }
        }
        return false;
    }
}
