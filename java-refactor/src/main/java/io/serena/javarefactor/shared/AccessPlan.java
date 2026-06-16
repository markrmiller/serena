package io.serena.javarefactor.shared;

/** A conservative access rewrite plan for V2 member moves and hierarchy refactors. */
public record AccessPlan(
        boolean allowed,
        String requiredVisibility,
        StructuredRefusal refusal,
        boolean publicApiWidening,
        java.util.List<String> warnings) {
    public static AccessPlan allowed(String requiredVisibility) {
        return new AccessPlan(true, requiredVisibility, null, false, java.util.List.of());
    }

    public static AccessPlan allowed(String requiredVisibility, boolean publicApiWidening, java.util.List<String> warnings) {
        return new AccessPlan(true, requiredVisibility, null, publicApiWidening, java.util.List.copyOf(warnings));
    }

    public static AccessPlan refused(String code, String message) {
        return new AccessPlan(false, null, new StructuredRefusal(code, message), false, java.util.List.of());
    }
}
