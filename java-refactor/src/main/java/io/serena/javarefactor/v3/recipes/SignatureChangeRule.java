package io.serena.javarefactor.v3.recipes;

import java.util.List;
import java.util.Map;

/**
 * A parsed {@code changeMethodSignature} recipe rule (refactor-feature-plan-V3.md §14.1). Unlike the match-and-template
 * {@link io.serena.javarefactor.compiler.RecipeMatchIndex.RecipeRule}s, this is a structural signature change delegated
 * to the compiler-backed change-signature operation: {@code owner} (fully-qualified type) + simple {@code name} +
 * optional erased {@code paramTypes} locate the target declaration, and {@code change} carries the desired-signature
 * fields ({@code parameters}/{@code newName}/{@code newReturnType}/{@code confirmPublicApi}/{@code removeParameters}/…)
 * passed straight to that operation. Resolving the declaration location and validating the change is the engine's job;
 * this record only holds the structurally-validated request.
 */
public record SignatureChangeRule(String id, String owner, String name, List<String> paramTypes,
                                  Map<String, Object> change) {
    public SignatureChangeRule {
        paramTypes = paramTypes == null ? List.of() : List.copyOf(paramTypes);
        change = change == null ? Map.of() : Map.copyOf(change);
    }
}
