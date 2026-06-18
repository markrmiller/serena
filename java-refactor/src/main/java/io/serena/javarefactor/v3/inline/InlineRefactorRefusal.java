package io.serena.javarefactor.v3.inline;

import io.serena.javarefactor.v3.packages.CodedRefusal;

/**
 * Structured refusal carrying a canonical registry {@code code} for the V3 generalized inline-method operation
 * (refactor-feature-plan-V3.md §11). Thrown when a precondition from the spec's supported-scope/refusal list cannot be
 * met; the planner's {@code plan(...)} entry point catches it and renders {@code {accepted:false, refusal:{code,message}}}.
 */
public final class InlineRefactorRefusal extends RuntimeException implements CodedRefusal {

    private final String code;

    public InlineRefactorRefusal(String code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
