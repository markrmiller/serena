package io.serena.javarefactor.v3.graph;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The call view (refactor-feature-plan-V3.md §1.2): the resolved caller&rarr;callee edges among project members.
 *
 * <p>Unlike a source-text approximation, every edge is javac-resolved: the keys are canonical SemanticKeys of the
 * declared members and the edges are the actual methods/constructors each member invokes (or method-references). The
 * three categories the plan distinguishes are kept separate so consumers can reason about ordinary calls, constructor
 * invocations, and method references independently. {@code resolved} is always {@code true} because the edges come from
 * the compiler model, never from unresolved source text.
 *
 * @param callEdges            caller member key -> invoked method/constructor keys
 * @param constructorEdges     caller member key -> invoked constructor keys
 * @param methodReferenceEdges caller member key -> method-reference target keys
 * @param memberCount          number of distinct project members indexed
 */
public record CallGraph(
        Map<String, Set<String>> callEdges,
        Map<String, Set<String>> constructorEdges,
        Map<String, Set<String>> methodReferenceEdges,
        int memberCount) {

    /** Members invoked (calls + constructors + method references) by {@code callerKey}. */
    public Set<String> calleesOf(String callerKey) {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(callEdges.getOrDefault(callerKey, Set.of()));
        all.addAll(constructorEdges.getOrDefault(callerKey, Set.of()));
        all.addAll(methodReferenceEdges.getOrDefault(callerKey, Set.of()));
        return all;
    }

    /** Always {@code true}: these edges are compiler-resolved, not source-text guesses. */
    public boolean resolved() {
        return true;
    }
}
