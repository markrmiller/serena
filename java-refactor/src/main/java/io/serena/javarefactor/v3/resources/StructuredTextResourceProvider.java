package io.serena.javarefactor.v3.resources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds exact dotted class/package tokens in line-structured config resources: {@code .properties}, {@code .yml},
 * {@code .yaml}, {@code .json} (refactor-feature-plan-V3.md §15). These formats mix class names with free-form values,
 * so matches are {@link ResourceConfidence#MEDIUM} (exact-class-only — no fuzzy/substring matching).
 */
final class StructuredTextResourceProvider implements ResourceReferenceProvider {

    @Override
    public String id() {
        return "structured-text";
    }

    @Override
    public boolean supports(Path file) {
        if (file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".properties")
                || name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.endsWith(".json");
    }

    @Override
    public List<ResourceReference> findReferences(Path file, String content, ResourceQuery query) {
        List<ResourceReference> refs = new ArrayList<>();
        for (ResourceSupport.Token token : ResourceSupport.dottedTokens(content)) {
            if (ResourceSupport.matches(token.text(), query)) {
                ResourceReferenceKind kind = query.isPackage() && !token.text().equals(query.target())
                        ? ResourceReferenceKind.PACKAGE_PREFIX
                        : ResourceReferenceKind.EXACT_CLASS_NAME;
                refs.add(new ResourceReference(file, token.start(), token.end(), token.text(),
                        kind, ResourceConfidence.MEDIUM, query.target(), id()));
            }
        }
        return refs;
    }

    @Override
    public ResourceEditPlan planEdits(Path file, String content, ResourceRenameRequest request) {
        return ResourceEditPlan.ofEdits(ResourceSupport.planTokenEdits(file, content, request, id()));
    }
}
