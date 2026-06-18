package io.serena.javarefactor.v3.classops;

import io.serena.javarefactor.v3.packages.CodedRefusal;

/**
 * Structured refusal carrying a canonical registry {@code code} for the V3 class-shape operations
 * (refactor-feature-plan-V3.md §8–§10). Planners throw this when a precondition from the spec's refusal lists cannot be
 * met; the planner's {@code plan(...)} entry point catches it and renders {@code {accepted:false, refusal:{code,message}}}.
 */
public final class ClassOpsRefusal extends RuntimeException implements CodedRefusal {

    private final String code;

    public ClassOpsRefusal(String code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
