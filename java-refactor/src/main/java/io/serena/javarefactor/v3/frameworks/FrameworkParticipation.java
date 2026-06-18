package io.serena.javarefactor.v3.frameworks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The contribution a {@link FrameworkPlugin} makes to a pending change through
 * {@link FrameworkPlugin#participate(SymbolChange, TransformationContext)} (refactor-feature-plan-V3.md §16). A plugin
 * never edits the plan directly; it returns this carrier and the operation seam folds it in. The four contribution
 * channels mirror the §16 participation responsibilities:
 *
 * <ul>
 *   <li>{@link #blocks()} — a veto: a {@code (symbol, reason)} pair refusing to let a framework-critical symbol be
 *       deleted (e.g. a Spring {@code @Component} or a JPA {@code @Entity} that is managed outside the Java type
 *       graph). The delete seam turns each block into a {@code blocked} entry in the delete plan.</li>
 *   <li>{@link #warnings()} — review-required messages: framework-significant facts the operation cannot safely
 *       auto-rewrite (Spring string bean names, JPQL string queries, Jackson serialized-name stability, JPA access-
 *       strategy changes). The seam appends them to the plan's {@code warnings}.</li>
 *   <li>{@link #resourceEdits()} — TYPED, structured {@link FrameworkResourceEdit framework-owned resource edits} (e.g. a
 *       class name in a Spring bean-definition XML or a JPA orm.xml). Blocker B5: these are no longer human-readable
 *       strings folded into {@link #warnings()} — each is a first-class fact naming its target resource + edit kind and
 *       carrying either a concrete edit or a manual-review-required marker, so the planner can carry it through as a
 *       structured {@code frameworkBoundaryChange} (shared contract 1) rather than as indistinguishable advisory text.
 *       Real structurally-exact resource edits still ship through the package planners' §15 {@code rewrite_resources}
 *       path; these typed edits surface what the framework expects so the plan records the framework's resource impact —
 *       and escalates risk — even where no §15 provider claims the span.</li>
 *   <li>{@link #roots()} — reachability roots the framework contributes (e.g. JUnit test methods, Spring
 *       {@code @Bean} methods, request handlers): symbols that must NOT be reported dead or cascade-deleted by "no Java
 *       references" alone.</li>
 * </ul>
 */
public record FrameworkParticipation(
        List<Block> blocks, List<String> warnings, List<FrameworkResourceEdit> resourceEdits, Set<String> roots) {

    /** A veto on deleting {@code symbol}, with the framework's human-readable {@code reason}. */
    public record Block(String symbol, String reason) {
    }

    public FrameworkParticipation {
        blocks = List.copyOf(blocks);
        warnings = List.copyOf(warnings);
        resourceEdits = List.copyOf(resourceEdits);
        roots = Set.copyOf(roots);
    }

    /** An empty participation (the plugin has nothing to contribute to this change). */
    public static FrameworkParticipation empty() {
        return new FrameworkParticipation(List.of(), List.of(), List.of(), Set.of());
    }

    /** Whether this participation contributes nothing. */
    public boolean isEmpty() {
        return blocks.isEmpty() && warnings.isEmpty() && resourceEdits.isEmpty() && roots.isEmpty();
    }

    /** A mutable builder so each plugin can accumulate its contributions as it inspects the project facts. */
    public static final class Builder {
        private final List<Block> blocks = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<FrameworkResourceEdit> resourceEdits = new ArrayList<>();
        private final Set<String> roots = new LinkedHashSet<>();

        public Builder block(String symbol, String reason) {
            blocks.add(new Block(symbol, reason));
            return this;
        }

        public Builder warn(String message) {
            warnings.add(message);
            return this;
        }

        /** Adds a TYPED framework-owned resource edit (blocker B5: no longer a folded-into-warnings string). */
        public Builder resourceEdit(FrameworkResourceEdit edit) {
            resourceEdits.add(edit);
            return this;
        }

        public Builder root(String symbol) {
            roots.add(symbol);
            return this;
        }

        public FrameworkParticipation build() {
            return new FrameworkParticipation(blocks, warnings, resourceEdits, roots);
        }
    }
}
