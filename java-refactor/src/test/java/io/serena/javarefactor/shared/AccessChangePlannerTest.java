package io.serena.javarefactor.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.serena.javarefactor.shared.AccessAdjustmentPlanner.MemberAccessRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan-wide access analysis: {@link AccessAdjustmentPlanner#requiredAccessChanges}. */
class AccessChangePlannerTest {

    private final AccessAdjustmentPlanner planner = new AccessAdjustmentPlanner();

    private static MemberAccessRequest member(String modifiers, String name, String pkg, boolean receiver) {
        return new MemberAccessRequest(name, pkg + "." + "Owner", modifiers, pkg, receiver);
    }

    @Test
    void widensSamePackagePrivateMemberToPackagePrivate() {
        List<AccessChange> changes = planner.requiredAccessChanges(
                List.of(member("private", "value", "demo", false)), "demo", true, false);

        AccessChange change = changes.get(0);
        assertEquals("private", change.fromVisibility());
        assertEquals("package-private", change.toVisibility());
        assertTrue(change.widens());
        assertFalse(change.publicApiWidening());
        assertTrue(planner.firstRefusal(changes).isEmpty());
    }

    @Test
    void prefersProtectedOverPublicWhenReceiverAvailableCrossPackage() {
        List<AccessChange> changes = planner.requiredAccessChanges(
                List.of(member("", "counter", "demo", true)), "other", true, false);

        assertEquals("package-private", changes.get(0).fromVisibility());
        assertEquals("protected", changes.get(0).toVisibility());
        assertFalse(changes.get(0).publicApiWidening());
    }

    @Test
    void widensToPublicAndWarnsWhenNoReceiverCrossPackage() {
        List<AccessChange> changes = planner.requiredAccessChanges(
                List.of(member("", "counter", "demo", false)), "other", true, false);

        AccessChange change = changes.get(0);
        assertEquals("public", change.toVisibility());
        assertTrue(change.publicApiWidening());
        assertTrue(change.reason().toLowerCase().contains("public"));
    }

    @Test
    void leavesPublicMembersUnchanged() {
        List<AccessChange> changes = planner.requiredAccessChanges(
                List.of(member("public", "open", "demo", false)), "other", true, false);

        assertEquals("public", changes.get(0).fromVisibility());
        assertEquals("public", changes.get(0).toVisibility());
        assertFalse(changes.get(0).widens());
    }

    @Test
    void refusesSecuritySensitivePrivateWideningUnlessExplicit() {
        List<AccessChange> refused = planner.requiredAccessChanges(
                List.of(member("private", "apiToken", "demo", false)), "other", true, false);
        assertTrue(refused.get(0).refused());
        assertEquals("security_sensitive_private_widening", refused.get(0).refusal().code());
        assertTrue(planner.firstRefusal(refused).isPresent());

        List<AccessChange> allowed = planner.requiredAccessChanges(
                List.of(member("private", "apiToken", "demo", false)), "other", true, true);
        assertFalse(allowed.get(0).refused());
        assertEquals("public", allowed.get(0).toVisibility());
    }

    @Test
    void refusesWideningWhenNotConfirmed() {
        List<AccessChange> changes = planner.requiredAccessChanges(
                List.of(member("private", "value", "demo", false)), "other", false, false);

        assertTrue(changes.get(0).refused());
        assertEquals("access_widening_not_confirmed", changes.get(0).refusal().code());
    }

    @Test
    void analyzesEveryReferencedMemberInOrder() {
        List<AccessChange> changes = planner.requiredAccessChanges(
                List.of(
                        member("public", "open", "demo", false),
                        member("private", "value", "demo", false),
                        member("", "counter", "demo", true)),
                "demo", true, false);

        assertEquals(3, changes.size());
        assertEquals("open", changes.get(0).memberName());
        assertEquals("value", changes.get(1).memberName());
        assertEquals("counter", changes.get(2).memberName());
        // Same destination package: private widens to package-private; package-private stays unchanged.
        assertEquals("package-private", changes.get(1).toVisibility());
        assertFalse(changes.get(2).widens());
    }

    @Test
    void serializesChangesJson() {
        List<AccessChange> changes = planner.requiredAccessChanges(
                List.of(member("private", "value", "demo", false)), "other", true, false);

        String json = planner.changesJson(changes);
        assertTrue(json.contains("\"memberName\":\"value\""));
        assertTrue(json.contains("\"toVisibility\":\"public\""));
        assertTrue(json.contains("\"publicApiWidening\":true"));
    }
}
