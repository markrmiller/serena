package io.serena.javarefactor.shared;

import io.serena.javarefactor.operations.hierarchy.MemberDescriptor;

/** Compatibility facade for the shared access-adjustment planner. */
public final class AccessPlanner {
    private final AccessAdjustmentPlanner delegate = new AccessAdjustmentPlanner();

    public AccessPlan plan(MemberDescriptor member, String declaringPackage, String usePackage, boolean receiverAvailable) {
        return delegate.plan(member, declaringPackage, usePackage, receiverAvailable);
    }

    /**
     * Plans the least-widening visibility with an explicit access-widening gate.
     *
     * <p>Use this overload for relocation operations (moveInstanceMethod, pullUpMember, pushDownMember). Returns a
     * refused plan with code {@code "access_widening_not_confirmed"} when widening is required but
     * {@code allowAccessWidening} is {@code false}.
     *
     * @see AccessAdjustmentPlanner#plan(String, String, String, boolean, String, boolean, boolean)
     */
    public AccessPlan plan(
            String modifiers,
            String declaringPackage,
            String usePackage,
            boolean receiverAvailable,
            String memberName,
            boolean allowAccessWidening,
            boolean allowSecuritySensitivePrivateWidening) {
        return delegate.plan(modifiers, declaringPackage, usePackage, receiverAvailable, memberName, allowAccessWidening, allowSecuritySensitivePrivateWidening);
    }
}
