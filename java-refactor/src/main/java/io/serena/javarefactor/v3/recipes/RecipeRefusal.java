package io.serena.javarefactor.v3.recipes;

import io.serena.javarefactor.v3.packages.CodedRefusal;

/**
 * Structured refusal carrying a canonical registry {@code code} for the V3 API-migration recipe engine
 * (refactor-feature-plan-V3.md §14). The engine throws this when a recipe cannot be parsed, a referenced
 * symbol does not resolve, an unsupported rule kind is used, or no matches are found; the planner entry point catches it
 * and renders {@code {accepted:false, refusal:{code,message}}}.
 *
 * <p>Canonical codes: {@code recipe_not_found}, {@code malformed_recipe}, {@code recipe_unresolved_symbol},
 * {@code recipe_unknown_rule_kind}, {@code recipe_no_matches}, {@code recipe_unsupported_template},
 * {@code recipe_overlapping_edits}.
 */
public final class RecipeRefusal extends RuntimeException implements CodedRefusal {

    private final String code;

    public RecipeRefusal(String code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
