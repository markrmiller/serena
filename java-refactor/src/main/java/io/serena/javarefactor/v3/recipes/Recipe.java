package io.serena.javarefactor.v3.recipes;

import io.serena.javarefactor.compiler.RecipeMatchIndex.RecipeRule;

import java.util.List;

/**
 * A parsed, structurally-validated API-migration recipe (refactor-feature-plan-V3.md §14.1): an {@code id}, a
 * human-readable {@code description}, an ordered list of match-and-template {@link RecipeRule}s, and a list of
 * structural {@link SignatureChangeRule}s ({@code changeMethodSignature}). Semantic resolution of the rules' referenced
 * types/methods happens later, against the javac model in {@link io.serena.javarefactor.compiler.RecipeMatchIndex}; the
 * signature rules are driven through the compiler-backed change-signature operation by the recipe engine.
 */
public record Recipe(String id, String description, List<RecipeRule> rules, List<SignatureChangeRule> signatureRules) {
    public Recipe {
        rules = rules == null ? List.of() : List.copyOf(rules);
        signatureRules = signatureRules == null ? List.of() : List.copyOf(signatureRules);
    }

    /** Convenience constructor for a template-only recipe (no structural signature rules). */
    public Recipe(String id, String description, List<RecipeRule> rules) {
        this(id, description, rules, List.of());
    }
}
