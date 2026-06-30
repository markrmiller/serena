package io.serena.javarefactor.v3.resources;

import io.serena.javarefactor.v3.graph.GraphCacheLimits;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Project-wide orchestrator for the resource-reference SPI (refactor-feature-plan-V3.md §15). It is the single engine
 * behind every resource-aware operation: package rename/move delegate their resource rewriting here via
 * {@link #plan(ResourceRenameRequest, ResourceScanScope)}, and safe-delete / impact consult
 * {@link #referencesTo(Collection, ResourceScanScope)} so a symbol with live resource references is reported rather than
 * silently broken.
 *
 * <p>Each resource file is handled by exactly one provider — the most specific that {@link ResourceProviderRegistry}
 * claims — so a file is never double-counted. The walk reads each file once.</p>
 *
 * <p><b>Scan completeness (story R06).</b> A file the scan could not fully examine — it is unreadable (IOException) or it
 * exceeds the configured {@code maxFileBytes} cap so its content was never read — means the planner could not determine
 * whether that file references the symbol(s) the operation touches. This is NOT a benign best-effort skip: it is an
 * <em>incompleteness signal</em> that every resource record below carries via {@link ScanCompleteness}. A resource-
 * participating operation MUST treat an incomplete scan as a risk escalation (at-least review-required) and MUST NOT
 * auto-apply resource edits planned from it — the specific incomplete files are still surfaced so a human can review
 * them. Downgrading incompleteness to a bare warning on an otherwise-auto-applied edit is exactly the narrowing R06
 * forbids.</p>
 */
public final class ResourcePlanner {

    /** No cap: every resource file is read for planning regardless of size. */
    public static final long NO_FILE_SIZE_CAP = 0L;

    private final Path projectRoot;
    private final JavaProjectModel model;
    private final ResourceProviderRegistry registry = new ResourceProviderRegistry();
    private final long maxFileBytes;

    public ResourcePlanner(Path projectRoot, JavaProjectModel model) {
        this(projectRoot, model, GraphCacheLimits.DEFAULT_MAX_RESOURCE_FILE_BYTES);
    }

    /**
     * @param maxFileBytes the resource max-file-size cap in bytes; {@code 0} (or negative) disables the cap. A file
     *     larger than the cap is NOT read for planning; it is recorded as an over-cap incompleteness signal (the same
     *     completeness gate the unreadable-file case feeds) rather than silently scanned or dropped.
     */
    public ResourcePlanner(Path projectRoot, JavaProjectModel model, long maxFileBytes) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
        this.maxFileBytes = maxFileBytes;
    }

    /**
     * The set of in-scope resource files the scan could not fully examine for a request, and therefore the gate that
     * tells a resource-participating operation it must escalate to review and must not auto-apply (story R06). A scan is
     * complete ({@link #isComplete()}) when this carries no incomplete files. Each incomplete file is project-relative.
     */
    public record ScanCompleteness(List<String> unreadable, List<String> overCap) {

        /** An empty completeness state: every in-scope resource file was fully examined. */
        public static ScanCompleteness complete() {
            return new ScanCompleteness(List.of(), List.of());
        }

        /** Whether every in-scope resource file was fully examined (no unreadable / over-cap files). */
        public boolean isComplete() {
            return unreadable.isEmpty() && overCap.isEmpty();
        }

        /** Every incomplete file (unreadable first, then over-cap), de-duplicated and order-stable, for surfacing. */
        public List<String> incompleteFiles() {
            Set<String> ordered = new LinkedHashSet<>(unreadable);
            ordered.addAll(overCap);
            return new ArrayList<>(ordered);
        }
    }

    /** Mutable accumulator the per-file walks feed; finalized into an immutable {@link ScanCompleteness}. */
    private static final class CompletenessAccumulator {
        private final List<String> unreadable = new ArrayList<>();
        private final List<String> overCap = new ArrayList<>();

        ScanCompleteness toCompleteness() {
            return new ScanCompleteness(List.copyOf(unreadable), List.copyOf(overCap));
        }
    }

    /**
     * Aggregated resource edits, file renames and warnings produced across the whole project for one request, plus the
     * {@link ScanCompleteness scan-completeness gate}: when {@code completeness.isComplete()} is false the scan that
     * informed these edits was incomplete, so the consumer must escalate to review-required and must not auto-apply.
     */
    public record ResourcePlan(List<ResourceEdit> edits, List<ResourceFileRename> fileRenames, List<String> warnings,
            ScanCompleteness completeness) {
    }

    /**
     * Plans every resource edit and file rename for {@code request}, scanning only the kinds {@code scope} permits. Each
     * scannable file is dispatched to its single owning provider's {@link ResourceReferenceProvider#planEdits}.
     */
    public ResourcePlan plan(ResourceRenameRequest request, ResourceScanScope scope) throws IOException {
        List<ResourceEdit> edits = new ArrayList<>();
        List<ResourceFileRename> fileRenames = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        CompletenessAccumulator completeness = new CompletenessAccumulator();
        for (Path file : ResourceSupport.resourceFiles(model)) {
            if (!scope.scannable(file)) {
                continue;
            }
            ResourceReferenceProvider provider = registry.providerFor(file);
            if (provider == null) {
                continue;
            }
            String content = readScannable(file, warnings, completeness);
            if (content == null) {
                continue;
            }
            ResourceEditPlan plan = provider.planEdits(file, content, request);
            edits.addAll(plan.edits());
            fileRenames.addAll(plan.fileRenames());
        }
        return new ResourcePlan(edits, fileRenames, warnings, completeness.toCompleteness());
    }

    /**
     * Every resource reference to any of {@code queries}' targets, scanning only the kinds {@code scope} permits. Used by
     * read-only participation (safe-delete safety analysis, impact reporting): a deleted/affected type whose FQN still
     * appears in a resource file is surfaced as a fact rather than left dangling.
     */
    public List<ResourceReference> referencesTo(Collection<ResourceQuery> queries, ResourceScanScope scope)
            throws IOException {
        return referencesToChecked(queries, scope).references();
    }

    /**
     * Every resource reference to any of {@code queries}' targets PLUS the {@link ScanCompleteness scan-completeness gate}
     * for the same walk — the read-only-participation counterpart of {@link #plan} that lets safe-delete / dangling-
     * reference validation escalate to review and refuse auto-apply when an in-scope resource file could not be examined
     * (story R06). The bare {@link #referencesTo(Collection, ResourceScanScope)} overload discards the completeness gate
     * for callers that only need the references.
     */
    public ReferenceScan referencesToChecked(Collection<ResourceQuery> queries, ResourceScanScope scope)
            throws IOException {
        List<ResourceReference> references = new ArrayList<>();
        if (queries.isEmpty()) {
            return new ReferenceScan(references, ScanCompleteness.complete());
        }
        List<String> ignoredWarnings = new ArrayList<>();
        CompletenessAccumulator completeness = new CompletenessAccumulator();
        for (Path file : ResourceSupport.resourceFiles(model)) {
            if (!scope.scannable(file)) {
                continue;
            }
            ResourceReferenceProvider provider = registry.providerFor(file);
            if (provider == null) {
                continue;
            }
            String content = readScannable(file, ignoredWarnings, completeness);
            if (content == null) {
                continue;
            }
            for (ResourceQuery query : queries) {
                references.addAll(provider.findReferences(file, content, query));
            }
        }
        return new ReferenceScan(references, completeness.toCompleteness());
    }

    /** The references found for a read-only participation scan plus its {@link ScanCompleteness scan-completeness gate}. */
    public record ReferenceScan(List<ResourceReference> references, ScanCompleteness completeness) {
    }

    /**
     * The unambiguous bean-element removals plus the ambiguous-reference warnings for a propagating safe delete, and the
     * {@link ScanCompleteness scan-completeness gate} of the underlying resource walk: when the scan was incomplete the
     * delete's resource participation could not be fully determined, so the caller must escalate to review-required and
     * must not auto-apply.
     */
    public record BeanRemovalPlan(List<ResourceEdit> edits, List<String> warnings, ScanCompleteness completeness) {
    }

    /**
     * Plans the removal of unambiguous Spring XML {@code <bean class="..."/>} definitions whose {@code class} is one of
     * {@code deletedFqns} (refactor-feature-plan-V3.md §7.3 step 8 / §7.5). A bean is removed only when its sole role is
     * instantiating the deleted type ({@link XmlResourceProvider#removableBeanElementSpan} returns a span); a bean that
     * other beans still wire to (so removing it would dangle that wiring) is left in place and surfaced as a warning so
     * the caller knows a resource reference to the deleted type remains for human review.
     *
     * <p>Only {@link ResourceReferenceKind#SPRING_BEAN_CLASS} references participate here; every other resource-reference
     * kind (generic exact-class tokens, JPA/Jackson, reflective candidates) stays review-only and is handled by the
     * caller's read-only {@code findReferences} path.
     */
    public BeanRemovalPlan beanRemovalEdits(Collection<String> deletedFqns, ResourceScanScope scope)
            throws IOException {
        List<ResourceEdit> edits = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (deletedFqns.isEmpty()) {
            return new BeanRemovalPlan(edits, warnings, ScanCompleteness.complete());
        }
        List<ResourceQuery> queries = new ArrayList<>();
        for (String fqn : deletedFqns) {
            queries.add(new ResourceQuery(fqn, false));
        }
        ReferenceScan scan = referencesToChecked(queries, scope);
        List<ResourceReference> references = scan.references();
        for (ResourceReference reference : references) {
            if (reference.kind() != ResourceReferenceKind.SPRING_BEAN_CLASS) {
                continue;
            }
            String content;
            try {
                content = Files.readString(reference.file());
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            int[] span = XmlResourceProvider.removableBeanElementSpan(content, reference.startOffset());
            if (span != null) {
                edits.add(new ResourceEdit(reference.file(), span[0], span[1], "",
                        ResourceReferenceKind.SPRING_BEAN_CLASS, ResourceConfidence.HIGH, "xml"));
            } else {
                warnings.add("Deleted type '" + reference.target() + "' is referenced by a Spring <bean> in resource '"
                        + PlannerSupport.relative(projectRoot, reference.file()) + "' that other beans still wire to;"
                        + " the bean definition is not auto-removed and must be reviewed.");
            }
        }
        return new BeanRemovalPlan(edits, warnings, scan.completeness());
    }

    /**
     * Every {@link ResourceConfidence#LOW} resource reference to any of {@code queries}' targets — the reflective /
     * free-text candidates that §18.4 says are "never auto-applied" and must instead be surfaced review-only. These come
     * from the {@link ReflectionResourceProvider} fallback, which by design claims files OUTSIDE the structured-scan
     * extensions ({@code .xml}/{@code .properties}/{@code .yml}/{@code .yaml}/{@code .json}/{@code META-INF/services});
     * so, unlike {@link #referencesTo(Collection, ResourceScanScope)}, this walk is deliberately NOT gated by a
     * {@link ResourceScanScope} — gating it would structurally exclude every file a LOW candidate can live in and make
     * the review-only warning dead. The plan/apply paths still never edit these; they are returned only so a caller can
     * warn that a possible reference remains for human review.
     */
    public List<ResourceReference> reviewOnlyReferences(Collection<ResourceQuery> queries) throws IOException {
        List<ResourceReference> references = new ArrayList<>();
        if (queries.isEmpty()) {
            return references;
        }
        for (Path file : ResourceSupport.resourceFiles(model)) {
            ResourceReferenceProvider provider = registry.providerFor(file);
            if (provider == null) {
                continue;
            }
            String content = readScannable(file, new ArrayList<>(), new CompletenessAccumulator());
            if (content == null) {
                continue;
            }
            for (ResourceQuery query : queries) {
                for (ResourceReference ref : provider.findReferences(file, content, query)) {
                    if (ref.confidence() == ResourceConfidence.LOW) {
                        references.add(ref);
                    }
                }
            }
        }
        return references;
    }

    /** Whether {@code file} exceeds the configured cap and therefore must not be read for token scanning/planning. */
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
     * Reads {@code file} for scanning/planning, or returns {@code null} when it could not be fully examined — recording an
     * incompleteness signal (story R06) in BOTH {@code warnings} (the human-readable diagnostic, never dropped) AND
     * {@code completeness} (the structured gate that blocks auto-apply / forces review). A file is incomplete when it is
     * unreadable (IOException) or it exceeds the configured size cap so its content was never read.
     */
    private String readScannable(Path file, List<String> warnings, CompletenessAccumulator completeness) {
        String relative = PlannerSupport.relative(projectRoot, file);
        if (exceedsCap(file)) {
            warnings.add("Resource " + relative + " exceeds the configured max-file-size cap (" + maxFileBytes
                    + " bytes) and was not scanned; its references could not be determined and must be reviewed.");
            completeness.overCap.add(relative);
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException unreadable) {
            warnings.add("Skipped unreadable resource " + relative
                    + " (" + unreadable.getClass().getSimpleName() + "); its references could not be determined"
                    + " and must be reviewed.");
            completeness.unreadable.add(relative);
            return null;
        }
    }
}
