package io.serena.javarefactor.v3.conversions;

import io.serena.javarefactor.v3.packages.CodedRefusal;

/**
 * Structured refusal carrying a canonical registry {@code code} for the V3 conversion operations
 * (refactor-feature-plan-V3.md §12 anonymous-class-to-lambda and §13 lambda-to-method-reference). Planners throw this
 * when a precondition from the spec's refusal lists cannot be met; the planner's {@code plan(...)} entry point catches it
 * and renders {@code {accepted:false, refusal:{code,message}}}.
 */
public final class ConversionsRefusal extends RuntimeException implements CodedRefusal {

    private final String code;

    public ConversionsRefusal(String code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
