package io.serena.javarefactor.v3.resources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds provider-class references in {@code META-INF/services/*} files (refactor-feature-plan-V3.md §15). Each
 * non-comment, non-blank line is a fully-qualified provider class name, so matches are {@link ResourceConfidence#HIGH}.
 */
final class ServiceLoaderResourceProvider implements ResourceReferenceProvider {

    @Override
    public String id() {
        return "service-loader";
    }

    @Override
    public boolean supports(Path file) {
        Path parent = file.getParent();
        if (parent == null) {
            return false;
        }
        Path servicesDir = parent.getFileName();
        Path metaInf = parent.getParent() == null ? null : parent.getParent().getFileName();
        return servicesDir != null && servicesDir.toString().equals("services")
                && metaInf != null && metaInf.toString().equals("META-INF");
    }

    @Override
    public List<ResourceReference> findReferences(Path file, String content, ResourceQuery query) {
        List<ResourceReference> refs = new ArrayList<>();
        int offset = 0;
        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine;
            int hash = line.indexOf('#');
            int contentEnd = hash >= 0 ? hash : line.length();
            String code = line.substring(0, contentEnd);
            String trimmed = code.trim();
            if (!trimmed.isEmpty() && ResourceSupport.matches(trimmed, query)) {
                int start = offset + code.indexOf(trimmed);
                refs.add(new ResourceReference(file, start, start + trimmed.length(), trimmed,
                        ResourceReferenceKind.SERVICE_LOADER_PROVIDER, ResourceConfidence.HIGH,
                        query.target(), id()));
            }
            offset += rawLine.length() + 1;
        }
        return refs;
    }

    @Override
    public ResourceEditPlan planEdits(Path file, String content, ResourceRenameRequest request) {
        if (!request.rewriteExactClassNames()) {
            // Every ServiceLoader rewrite (provider lines and the §15.2 interface-file rename) is class-name-exact;
            // when exact-class rewriting is disabled there is nothing safe to do here.
            return ResourceEditPlan.EMPTY;
        }
        List<ResourceEdit> edits = new ArrayList<>();
        int offset = 0;
        for (String rawLine : content.split("\n", -1)) {
            int hash = rawLine.indexOf('#');
            int contentEnd = hash >= 0 ? hash : rawLine.length();
            String code = rawLine.substring(0, contentEnd);
            String trimmed = code.trim();
            if (!trimmed.isEmpty()) {
                String mapped = request.typeFqnMap().get(trimmed);
                if (mapped != null && !mapped.equals(trimmed)) {
                    int start = offset + code.indexOf(trimmed);
                    edits.add(new ResourceEdit(file, start, start + trimmed.length(), mapped,
                            ResourceReferenceKind.SERVICE_LOADER_PROVIDER, ResourceConfidence.HIGH, id()));
                }
            }
            offset += rawLine.length() + 1;
        }
        List<ResourceFileRename> renames = planInterfaceFileRename(file, request);
        return new ResourceEditPlan(edits, renames);
    }

    /**
     * The registration file is named after the service-interface FQN ({@code META-INF/services/<interface-fqn>}). When
     * that interface type moves, the file is renamed so {@code ServiceLoader.load(NewSpi.class)} still resolves it
     * (refactor-feature-plan-V3.md §15.2).
     */
    private List<ResourceFileRename> planInterfaceFileRename(Path file, ResourceRenameRequest request) {
        if (file.getFileName() == null) {
            return List.of();
        }
        String interfaceFqn = file.getFileName().toString();
        String mapped = request.typeFqnMap().get(interfaceFqn);
        if (mapped == null || mapped.equals(interfaceFqn)) {
            return List.of();
        }
        Path target = file.resolveSibling(mapped);
        String reason = "ServiceLoader registration file renamed " + interfaceFqn + " -> " + mapped
                + " because the service interface type moved.";
        return List.of(new ResourceFileRename(file, target, id(), reason));
    }
}
