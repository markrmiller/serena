package io.serena.javarefactor.v3.resources;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.protocol.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry point for the read-only resource-reference SPI op {@code resources.findReferences}
 * (refactor-feature-plan-V3.md §15). Walks the project's resource directories, dispatches each file to the single
 * provider that claims it, and returns every reference to the requested Java type/package as a JSON envelope.
 *
 * <p>This op is purely diagnostic: it never edits files. The rewrite half of the SPI is {@code resources.planEdits}
 * (see {@link ResourceEditPlanner}); package rename/move drive resource rewrites through {@link ResourcePlanner}, and
 * safe-delete consults {@link ResourcePlanner#referencesTo} so a type with live resource references is reported.
 */
public final class ResourceReferenceScanner {

    /**
     * Sentinel target emitted (LOW confidence) for a resource file that exceeds the configured max-file-size cap. The
     * file is NOT silently skipped: this surfaces it so a consumer knows a possible reference was not scanned and must be
     * reviewed manually (refactor-feature-plan-V3.md story R05 acceptance #2; deepened by R06).
     */
    public static final String OVER_CAP_TARGET = "<resource-file-exceeds-max-size>";

    /** No cap: every resource file is scanned regardless of size. */
    public static final long NO_FILE_SIZE_CAP = 0L;

    private final Path projectRoot;
    private final JavaProjectModel model;
    private final ResourceProviderRegistry registry = new ResourceProviderRegistry();
    private final long maxFileBytes;

    public ResourceReferenceScanner(Path projectRoot, JavaProjectModel model) {
        this(projectRoot, model, NO_FILE_SIZE_CAP);
    }

    /**
     * @param maxFileBytes the resource max-file-size cap in bytes; {@code 0} (or negative) disables the cap. A file
     *     larger than the cap is surfaced via the over-cap signal rather than silently scanned or dropped.
     */
    public ResourceReferenceScanner(Path projectRoot, JavaProjectModel model, long maxFileBytes) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
        this.maxFileBytes = maxFileBytes;
    }

    /** Whether {@code file} exceeds the configured cap and therefore must not be read for token scanning. */
    private boolean exceedsCap(Path file) {
        if (maxFileBytes <= NO_FILE_SIZE_CAP) {
            return false;
        }
        try {
            return Files.size(file) > maxFileBytes;
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * The provider-backed references found for a {@link #referencesFor} scan PLUS the
     * {@link ResourcePlanner.ScanCompleteness scan-completeness gate} of the same walk (story R06). An in-scope resource
     * file the scan could not fully examine (unreadable, or over the size cap so its content was never read) means the
     * scanner could not determine whether that file references the targets, so {@code completeness.isComplete()} is false
     * and the consumer MUST escalate the operation to at-least-review and MUST NOT auto-apply resource changes from this
     * incomplete scan. The over-cap case additionally still carries its synthetic {@link #OVER_CAP_TARGET} reference (so
     * a count-driven consumer also sees it), exactly as before.
     */
    public record ScanResult(List<ResourceReference> references, ResourcePlanner.ScanCompleteness completeness) {
    }

    /**
     * Provider-backed scan for references to ANY of {@code targetFqns} across the project's resource files, reusing the
     * exact same {@link ResourceProviderRegistry} dispatch and per-provider {@link ResourceReferenceProvider#findReferences}
     * logic as the {@code resources.findReferences} op. Each returned {@link ResourceReference} carries its provider id,
     * structural {@link ResourceReferenceKind kind}, {@link ResourceConfidence confidence}, exact offsets, and matched
     * text — never a bare substring hit. This is the seam the impact-facts analyzer (which lives in the {@code compiler}
     * package and cannot reach the package-private registry/providers directly) consumes so impact resource refs are
     * provider-backed and confidence-aware rather than text-scanned.
     *
     * <p><b>Completeness (story R06).</b> Unlike the prior best-effort version, this does NOT silently {@code continue}
     * past an unreadable in-scope resource file. An unreadable or over-cap file is recorded as an incompleteness signal in
     * the returned {@link ScanResult#completeness()} (mirroring {@link ResourcePlanner#readScannable}); the consumer can
     * then surface the gap and escalate risk rather than emitting a falsely-{@code safe} result from a partial scan.
     *
     * @param targetFqns the fully-qualified class names whose resource references to surface (each scanned as a class
     *     target, not a package prefix)
     * @return every provider-backed reference (in (file, document-order) order; empty when no target matches) together
     *     with the scan-completeness gate
     * @throws IOException if a resource directory cannot be walked
     */
    public ScanResult referencesFor(Set<String> targetFqns) throws IOException {
        List<ResourceReference> references = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<String> overCap = new ArrayList<>();
        if (targetFqns == null || targetFqns.isEmpty()) {
            return new ScanResult(references, ResourcePlanner.ScanCompleteness.complete());
        }
        for (Path file : ResourceSupport.resourceFiles(model)) {
            ResourceReferenceProvider provider = registry.providerFor(file);
            if (provider == null) {
                continue;
            }
            if (exceedsCap(file)) {
                // Surfaced two ways, never silently dropped: a LOW-confidence over-cap signal so a count-driven consumer
                // sees it, AND an incompleteness signal in the completeness gate so risk escalates and auto-apply blocks.
                references.add(overCapReference(file));
                overCap.add(PlannerSupport.relative(projectRoot, file));
                continue;
            }
            String content;
            try {
                content = Files.readString(file);
            } catch (IOException | RuntimeException readError) {
                // Story R06: an in-scope resource we could not read is an incompleteness signal, not a benign skip — the
                // scanner cannot determine whether it references the targets, so record it for the completeness gate
                // (mirrors ResourcePlanner.readScannable's identical IOException/RuntimeException handling).
                unreadable.add(PlannerSupport.relative(projectRoot, file));
                continue;
            }
            for (String target : targetFqns) {
                if (target == null || target.isBlank()) {
                    continue;
                }
                references.addAll(provider.findReferences(file, content, new ResourceQuery(target.trim(), false)));
            }
        }
        ResourcePlanner.ScanCompleteness completeness =
                new ResourcePlanner.ScanCompleteness(List.copyOf(unreadable), List.copyOf(overCap));
        return new ScanResult(references, completeness);
    }

    /** A synthetic LOW-confidence reference marking that {@code file} exceeded the cap and was not scanned. */
    private ResourceReference overCapReference(Path file) {
        return new ResourceReference(file, 0, 0, "", ResourceReferenceKind.REFLECTIVE_STRING_CANDIDATE,
                ResourceConfidence.LOW, OVER_CAP_TARGET, "over-cap");
    }

    /** Project-relative path of a resource reference's file (the same projection the {@code resources.*} ops emit). */
    public String relativePathOf(ResourceReference reference) {
        return PlannerSupport.relative(projectRoot, reference.file());
    }

    public String findReferences(Map<String, Object> fields) {
        try {
            return findReferencesChecked(fields);
        } catch (ResourceRefusal refusal) {
            return PlannerSupport.refusalJson("findResourceReferences", false, refusal.code(), refusal.getMessage());
        } catch (IOException e) {
            return PlannerSupport.refusalJson("findResourceReferences", false, "io_error", String.valueOf(e.getMessage()));
        }
    }

    private String findReferencesChecked(Map<String, Object> fields) throws IOException {
        ResourceQuery query = resolveQuery(fields);
        Set<ResourceReferenceKind> kindFilter = resolveKindFilter(fields);

        List<ResourceReference> references = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Path file : ResourceSupport.resourceFiles(model)) {
            ResourceReferenceProvider provider = registry.providerFor(file);
            if (provider == null) {
                continue;
            }
            if (exceedsCap(file)) {
                // Surfaced, never silently dropped: warn so the over-cap file is reviewed manually (R05 acceptance #2).
                warnings.add("Resource " + PlannerSupport.relative(projectRoot, file)
                        + " exceeds the configured max-file-size cap (" + maxFileBytes
                        + " bytes) and was not scanned; review it manually for references.");
                continue;
            }
            String content;
            try {
                content = Files.readString(file);
            } catch (IOException | RuntimeException unreadable) {
                warnings.add("Skipped unreadable resource " + PlannerSupport.relative(projectRoot, file)
                        + " (" + unreadable.getClass().getSimpleName() + ").");
                continue;
            }
            for (ResourceReference ref : provider.findReferences(file, content, query)) {
                if (kindFilter == null || kindFilter.contains(ref.kind())) {
                    references.add(ref);
                }
            }
        }

        return envelope(query, references, warnings);
    }

    private ResourceQuery resolveQuery(Map<String, Object> fields) {
        String target = optString(fields, "target");
        if (target == null || target.isBlank()) {
            throw new ResourceRefusal("resource_target_unresolved",
                    "A non-empty 'target' (fully-qualified class or package name) is required.");
        }
        boolean isPackage = bool(fields, "targetIsPackage", false) || bool(fields, "is_package", false);
        return new ResourceQuery(target.trim(), isPackage);
    }

    /** Optional {@code kinds} filter; returns {@code null} when unset (all kinds). Refuses unknown kinds. */
    private Set<ResourceReferenceKind> resolveKindFilter(Map<String, Object> fields) {
        Object raw = fields.get("kinds");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Set<ResourceReferenceKind> kinds = EnumSet.noneOf(ResourceReferenceKind.class);
        for (Object element : list) {
            String name = String.valueOf(element).trim();
            try {
                kinds.add(ResourceReferenceKind.valueOf(name));
            } catch (IllegalArgumentException unknown) {
                throw new ResourceRefusal("unsupported_resource_kind",
                        "Unknown resource-reference kind '" + name + "'.");
            }
        }
        return kinds;
    }

    private String envelope(ResourceQuery query, List<ResourceReference> references, List<String> warnings) {
        StringBuilder refs = new StringBuilder("[");
        Set<String> kindsSeen = new LinkedHashSet<>();
        for (int i = 0; i < references.size(); i++) {
            if (i > 0) {
                refs.append(",");
            }
            refs.append(referenceJson(references.get(i)));
            kindsSeen.add(references.get(i).kind().name());
        }
        refs.append("]");

        return "{"
                + "\"accepted\":true,"
                + "\"operation\":\"findResourceReferences\","
                + "\"target\":" + JsonUtil.quote(query.target()) + ","
                + "\"targetIsPackage\":" + query.isPackage() + ","
                + "\"references\":" + refs + ","
                + "\"stats\":{\"count\":" + references.size()
                + ",\"distinctKinds\":" + kindsSeen.size() + "},"
                + "\"warnings\":" + PlannerSupport.warningsJson(warnings)
                + "}";
    }

    private String referenceJson(ResourceReference ref) {
        return "{"
                + "\"path\":" + JsonUtil.quote(PlannerSupport.relative(projectRoot, ref.file())) + ","
                + "\"startOffset\":" + ref.startOffset() + ","
                + "\"endOffset\":" + ref.endOffset() + ","
                + "\"oldText\":" + JsonUtil.quote(ref.oldText()) + ","
                + "\"kind\":" + JsonUtil.quote(ref.kind().name()) + ","
                + "\"confidence\":" + JsonUtil.quote(ref.confidence().name()) + ","
                + "\"provider\":" + JsonUtil.quote(ref.providerId()) + ","
                + "\"target\":" + JsonUtil.quote(ref.target())
                + "}";
    }

    private static String optString(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> fields, String key, boolean fallback) {
        Object value = fields.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }
}
