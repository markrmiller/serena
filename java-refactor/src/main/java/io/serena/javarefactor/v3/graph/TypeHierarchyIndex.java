package io.serena.javarefactor.v3.graph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The type-hierarchy view (refactor-feature-plan-V3.md §1.2): the resolved supertype/subtype closure plus the override
 * groups that tie an overriding member to the supertype contracts it satisfies.
 *
 * <p>The supertype edges come from javac's resolved {@code directSupertypes} (so they include both project and library
 * types), and the subtype edges are their inverse over the project's own types. {@link #overrideGroups()} maps a
 * supertype member's canonical key to the project member keys that override or implement it, sourced from the compiler's
 * override resolution — never a name/arity heuristic.
 *
 * @param supertypes    type FQN -> resolved direct supertype FQNs
 * @param subtypes      type FQN -> resolved direct subtype FQNs (project types only)
 * @param overrideGroups supertype member canonical key -> overriding member canonical keys
 */
public record TypeHierarchyIndex(
        Map<String, Set<String>> supertypes,
        Map<String, Set<String>> subtypes,
        Map<String, Set<String>> overrideGroups) {

    /** Transitive resolved supertypes of {@code fqn}. */
    public Set<String> ancestorsOf(String fqn) {
        return closure(fqn, supertypes);
    }

    /** Transitive resolved subtypes of {@code fqn}. */
    public Set<String> descendantsOf(String fqn) {
        return closure(fqn, subtypes);
    }

    private static Set<String> closure(String start, Map<String, Set<String>> edges) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(edges.getOrDefault(start, Set.of()));
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!seen.add(current)) {
                continue;
            }
            stack.addAll(edges.getOrDefault(current, Set.of()));
        }
        return seen;
    }
}
