package io.serena.javarefactor.shared;

import io.serena.javarefactor.operations.hierarchy.MemberDescriptor;
import io.serena.javarefactor.protocol.JsonUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared access-adjustment planner for V2 refactors that relocate or synthesize members. */
public final class AccessAdjustmentPlanner {
    private static final Pattern EXPLICIT_VISIBILITY = Pattern.compile("\\b(public|protected|private)\\b\\s*");
    private static final Set<String> SECURITY_SENSITIVE_NAME_PARTS = Set.of("password", "secret", "token", "credential", "key");

    /** Plans the least-widening visibility that keeps a relocated textual member source-valid. */
    public AccessPlan plan(
            String modifiers,
            String declaringPackage,
            String usePackage,
            boolean receiverAvailable,
            String memberName,
            boolean allowSecuritySensitivePrivateWidening) {
        String current = currentVisibility(modifiers);
        if ("public".equals(current)) {
            return AccessPlan.allowed("unchanged");
        }
        if (declaringPackage.equals(usePackage)) {
            if ("private".equals(current)) {
                return AccessPlan.allowed("package-private");
            }
            return AccessPlan.allowed("unchanged");
        }
        if ("private".equals(current) && isSecuritySensitive(memberName) && !allowSecuritySensitivePrivateWidening) {
            return AccessPlan.refused(
                    "security_sensitive_private_widening",
                    "Refusing to widen security-sensitive private member '" + memberName + "' without explicit allowSecuritySensitivePrivateWidening.");
        }
        if ("protected".equals(current) && receiverAvailable) {
            return AccessPlan.allowed("unchanged");
        }
        if (receiverAvailable) {
            return AccessPlan.allowed("protected");
        }
        return AccessPlan.allowed("public", true, List.of(
                "Access adjustment widens member '" + memberName + "' to public API so cross-package references remain source-valid."));
    }

    /**
     * Plans the least-widening visibility with an explicit access-widening gate.
     *
     * <p>Callers that relocate members (moveInstanceMethod, pullUpMember, pushDownMember) MUST use this overload and
     * supply the caller-confirmed {@code allowAccessWidening} flag. When the computed plan would widen the member's
     * visibility (i.e. {@code requiredVisibility} is not {@code "unchanged"}) and {@code allowAccessWidening} is
     * {@code false}, this method returns a refused {@link AccessPlan} with code {@code "access_widening_not_confirmed"}
     * rather than an allowed plan with a warning, so the operation is blocked by default.
     *
     * <p>The security-sensitive private-widening gate ({@code allowSecuritySensitivePrivateWidening}) is applied first;
     * when it triggers, the refusal code is {@code "security_sensitive_private_widening"} regardless of the
     * access-widening flag.
     *
     * @param allowAccessWidening {@code true} to permit visibility widening; {@code false} to refuse it
     * @param allowSecuritySensitivePrivateWidening {@code true} to permit widening of security-sensitive private members
     */
    public AccessPlan plan(
            String modifiers,
            String declaringPackage,
            String usePackage,
            boolean receiverAvailable,
            String memberName,
            boolean allowAccessWidening,
            boolean allowSecuritySensitivePrivateWidening) {
        AccessPlan base = plan(modifiers, declaringPackage, usePackage, receiverAvailable, memberName, allowSecuritySensitivePrivateWidening);
        if (base.allowed()
                && !allowAccessWidening
                && base.requiredVisibility() != null
                && !"unchanged".equals(base.requiredVisibility())) {
            return AccessPlan.refused(
                    "access_widening_not_confirmed",
                    "Access adjustment would widen member '" + memberName + "' to " + base.requiredVisibility()
                            + "; pass allowAccessWidening=true to opt in.");
        }
        return base;
    }

    /** Compatibility plan for symbol-descriptor callers. */
    public AccessPlan plan(MemberDescriptor member, String declaringPackage, String usePackage, boolean receiverAvailable) {
        return plan(String.join(" ", member.modifiers()), declaringPackage, usePackage, receiverAvailable, member.name(), false);
    }

    /**
     * A single source member that a relocated or extracted body references and that must remain
     * accessible from the destination. Drives the plan-wide {@link #requiredAccessChanges} analysis.
     *
     * @param memberName the member's simple name
     * @param declaringType the qualified (or simple) name of the type declaring the member
     * @param modifiers the member's current modifier text (e.g. {@code "private static"})
     * @param declaringPackage the package the member is declared in
     * @param receiverAvailable whether a receiver of the declaring type is available at the destination
     *     (true permits {@code protected} widening instead of {@code public})
     */
    public record MemberAccessRequest(
            String memberName,
            String declaringType,
            String modifiers,
            String declaringPackage,
            boolean receiverAvailable) {
    }

    /**
     * Plan-wide access analysis: computes the minimal legal visibility change for <em>every</em>
     * referenced source member so a relocated or extracted body remains source-valid from
     * {@code destinationPackage}. This is the V2 plan-wide replacement for per-target visibility
     * rewriting (refactor-feature-plan-V2.md §4.5): it walks all planned body references and emits one
     * structured {@link AccessChange} per member.
     *
     * <p>Each member is run through the gated per-member {@link #plan} logic, so the aggregate honors:
     * minimal widening (package-private &gt; protected &gt; public); never silently widening public API
     * (the entry carries {@code publicApiWidening} and the per-member warning); refusal of
     * security-sensitive private widening unless {@code allowSecuritySensitivePrivateWidening}; and
     * refusal of any widening at all unless {@code allowAccessWidening}. Members that already live in
     * {@code destinationPackage} and need no change yield an {@code unchanged} entry.
     *
     * <p>Because each widening is emitted against the member's own source declaration, private members
     * accessed from a relocated nested/sibling body are made source-valid directly rather than relying
     * on compiler-synthesized accessor bridges.
     *
     * @return one {@link AccessChange} per request, in input order; refused entries carry a non-null refusal
     */
    public List<AccessChange> requiredAccessChanges(
            List<MemberAccessRequest> referencedMembers,
            String destinationPackage,
            boolean allowAccessWidening,
            boolean allowSecuritySensitivePrivateWidening) {
        List<AccessChange> changes = new ArrayList<>();
        if (referencedMembers == null) {
            return changes;
        }
        for (MemberAccessRequest request : referencedMembers) {
            String from = currentVisibility(request.modifiers());
            AccessPlan plan = plan(
                    request.modifiers(),
                    request.declaringPackage(),
                    destinationPackage,
                    request.receiverAvailable(),
                    request.memberName(),
                    allowAccessWidening,
                    allowSecuritySensitivePrivateWidening);
            if (!plan.allowed()) {
                changes.add(new AccessChange(
                        request.memberName(), request.declaringType(), from, null,
                        plan.refusal().message(), false, plan.refusal()));
                continue;
            }
            String to = "unchanged".equals(plan.requiredVisibility()) ? from : plan.requiredVisibility();
            String reason = from.equals(to)
                    ? "No access change required."
                    : (plan.warnings().isEmpty()
                            ? "Widened '" + request.memberName() + "' to " + to
                                    + " so the relocated body's reference stays source-valid from package '" + destinationPackage + "'."
                            : String.join(" ", plan.warnings()));
            changes.add(new AccessChange(
                    request.memberName(), request.declaringType(), from, to, reason, plan.publicApiWidening(), null));
        }
        return changes;
    }

    /**
     * The first refused entry among {@code changes}, if any. A plan-wide access operation must refuse as
     * a whole when any single member cannot be legally widened, so callers gate on this before applying
     * the allowed entries.
     */
    public Optional<AccessChange> firstRefusal(List<AccessChange> changes) {
        if (changes == null) {
            return Optional.empty();
        }
        return changes.stream().filter(AccessChange::refused).findFirst();
    }

    /** Serializes plan-wide access changes for preview payloads. */
    public String changesJson(List<AccessChange> changes) {
        List<String> rendered = new ArrayList<>();
        for (AccessChange change : changes) {
            String refusal = change.refusal() == null
                    ? "null"
                    : "{\"code\":" + JsonUtil.quote(change.refusal().code())
                            + ",\"message\":" + JsonUtil.quote(change.refusal().message()) + "}";
            rendered.add("{\"memberName\":" + JsonUtil.quote(change.memberName())
                    + ",\"declaringType\":" + JsonUtil.quote(change.declaringType())
                    + ",\"fromVisibility\":" + JsonUtil.quote(change.fromVisibility())
                    + ",\"toVisibility\":" + JsonUtil.quote(change.toVisibility())
                    + ",\"reason\":" + JsonUtil.quote(change.reason())
                    + ",\"publicApiWidening\":" + change.publicApiWidening()
                    + ",\"refusal\":" + refusal + "}");
        }
        return "[" + String.join(",", rendered) + "]";
    }

    /** Rewrites a declaration modifier prefix according to an access plan while preserving non-access modifiers. */
    public String rewriteModifiers(String modifiers, AccessPlan plan) {
        if (!plan.allowed() || plan.requiredVisibility() == null || "unchanged".equals(plan.requiredVisibility())) {
            return modifiers;
        }

        int bodyStart = 0;
        while (bodyStart < modifiers.length() && Character.isWhitespace(modifiers.charAt(bodyStart))) {
            bodyStart++;
        }
        String indentation = modifiers.substring(0, bodyStart);
        String modifierBody = modifiers.substring(bodyStart);
        String normalized = EXPLICIT_VISIBILITY.matcher(modifierBody).replaceAll("").strip();

        if ("package-private".equals(plan.requiredVisibility())) {
            return normalized.isBlank() ? indentation : indentation + normalized + " ";
        }
        return normalized.isBlank()
                ? indentation + plan.requiredVisibility() + " "
                : indentation + plan.requiredVisibility() + " " + normalized + " ";
    }

    /** Serializes access plans for preview payloads. */
    public String plansJson(List<AccessPlan> plans) {
        List<String> rendered = new ArrayList<>();
        for (AccessPlan plan : plans) {
            String refusal = plan.refusal() == null
                    ? "null"
                    : "{\"code\":" + JsonUtil.quote(plan.refusal().code())
                            + ",\"message\":" + JsonUtil.quote(plan.refusal().message()) + "}";
            rendered.add("{\"allowed\":" + plan.allowed()
                    + ",\"requiredVisibility\":" + JsonUtil.quote(plan.requiredVisibility())
                    + ",\"publicApiWidening\":" + plan.publicApiWidening()
                    + ",\"warnings\":" + warningsJson(plan.warnings())
                    + ",\"refusal\":" + refusal + "}");
        }
        return "[" + String.join(",", rendered) + "]";
    }

    /** Extracts warnings emitted by access plans. */
    public List<String> warnings(List<AccessPlan> plans) {
        List<String> warnings = new ArrayList<>();
        for (AccessPlan plan : plans) {
            warnings.addAll(plan.warnings());
        }
        return warnings;
    }

    private static String currentVisibility(String modifiers) {
        if (modifiers.contains("public")) {
            return "public";
        }
        if (modifiers.contains("protected")) {
            return "protected";
        }
        if (modifiers.contains("private")) {
            return "private";
        }
        return "package-private";
    }

    private static boolean isSecuritySensitive(String memberName) {
        String lowered = memberName.toLowerCase(java.util.Locale.ROOT);
        return SECURITY_SENSITIVE_NAME_PARTS.stream().anyMatch(lowered::contains);
    }

    private static String warningsJson(List<String> warnings) {
        List<String> quoted = new ArrayList<>();
        for (String warning : warnings) {
            quoted.add(JsonUtil.quote(warning));
        }
        return "[" + String.join(",", quoted) + "]";
    }
}
