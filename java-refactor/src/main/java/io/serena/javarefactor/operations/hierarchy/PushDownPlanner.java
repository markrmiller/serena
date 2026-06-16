package io.serena.javarefactor.operations.hierarchy;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.shared.AccessPlan;
import io.serena.javarefactor.shared.SemanticTargetGate;
import io.serena.javarefactor.shared.SourceText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * G009: the push-down half of the V2 hierarchy member-move engine. Copies a method or field from a supertype into one or
 * more direct/indirect subtypes, optionally removing it from the source. Before emitting edits it enforces semantic
 * member identity, the public-API gate, subtype-target membership, per-target collisions, sibling/inherited override
 * compatibility (via {@link OverrideGroupResolver}), serializable-field gating, javac-resolved source call-site safety
 * when removing, access adjustment, and import transfer. Shared mechanics live in {@link HierarchyMoveSupport}; this unit
 * holds only the push-down orchestration.
 */
public final class PushDownPlanner extends HierarchyMoveSupport {

    public PushDownPlanner(Path projectRoot, JavaProjectModel model) {
        super(projectRoot, model);
    }

    public String pushDownMember(Map<String, Object> fields, boolean apply) {
        try {
            Path sourceFile = sourceFile(fields);
            String source = SourceText.read(model, sourceFile);
            Member member = selectedMember(sourceFile, fields);
            TypeHierarchyIndex hierarchy = hierarchyIndex(sourceFile);
            String sourceQualified = member.ownerQualifiedName();
            List<String> targetTypes = new ArrayList<>(targetTypes(fields));
            boolean includeIndirectSubtypes = boolField(fields, "includeIndirectSubtypes", false);
            if (targetTypes.isEmpty()) {
                targetTypes = subtypeNames(hierarchy, sourceQualified, includeIndirectSubtypes);
            }

            if (targetTypes.isEmpty()) {
                throw new Refusal("missing_target_types", "targetTypes is required when no subtypes can be discovered for V2 push-down.");
            }

            if (member.kind() == MemberKind.FIELD && !isSupportedFieldMove(member)) {
                throw new Refusal(
                        "unsafe_field_push_down",
                        "V2 pushDownMember supports constants and simple instance fields without initializer/static/final/volatile hazards.");
            }

            boolean removeFromSource = boolField(fields, "removeFromSource", false);
            List<String> targetQualifiedNames = new ArrayList<>();
            for (String targetType : targetTypes) {
                refusePathLikeTypeName(targetType, "targetTypes");
                String targetQualified = resolveRequiredType(hierarchy, targetType);
                if (!hierarchy.allSubtypes(sourceQualified).contains(targetQualified)) {
                    throw new Refusal("target_not_subtype", "pushDownMember target is not a subtype: " + targetType);
                }
                if (!includeIndirectSubtypes && !hierarchy.directSubtypes(sourceQualified).contains(targetQualified)) {
                    throw new Refusal("target_not_subtype", "pushDownMember target is not a direct subtype: " + targetType);
                }
                targetQualifiedNames.add(targetQualified);
            }

            refuseSerializationImpactForField(hierarchy, member, sourceQualified, targetQualifiedNames, fields);

            // G009: a pushed-down copy must remain a legal override of any same-erased-signature method it still inherits
            // from an intermediate supertype (covariant return, generic substitution, visibility), proven structurally
            // before edits rather than left to a later compile.
            refuseIncompatibleOverrides(
                    new OverrideGroupResolver(hierarchy).validatePushDown(movingMethodDescriptor(hierarchy, member), sourceQualified, targetQualifiedNames),
                    fields);

            if (removeFromSource) {
                // G020: push-down source-call safety is decided entirely from javac-resolved call sites and their
                // receiver types, not from broad regex scans of variable declarations/calls.
                refuseUnsafeSourceCallSitesSemantic(sourceFile, fields, member, targetQualifiedNames);
            }

            // HB-6: import transfer into each subtype and cleanup of the source use the member's javac-resolved
            // type/member references, not an identifier regex over the rendered member text.
            SemanticIndex.MovedBodyDependencies deps = memberDependencies(sourceFile, fields, member);
            List<PlannerSupport.TextEdit> edits = new ArrayList<>();
            List<AccessPlan> accessPlans = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            for (String targetQualified : targetQualifiedNames) {
                Path targetFile = targetFileForType(hierarchy, targetQualified, "targetTypes");
                String target = SourceText.read(model, targetFile);
                if (memberExists(hierarchy, targetQualified, member)) {
                    throw new Refusal("target_member_exists", "Subtype declares a compatible member with this name: " + targetQualified);
                }
                AccessPlan accessPlan = accessPlanner.plan(
                        member.modifiers(),
                        packageName(source),
                        packageName(target),
                        false,
                        member.name(),
                        Boolean.TRUE.equals(fields.get("allowAccessWidening")),
                        Boolean.TRUE.equals(fields.get("allowSecuritySensitivePrivateWidening")));
                if (!accessPlan.allowed()) {
                    throw new Refusal(accessPlan.refusal().code(), accessPlan.refusal().message());
                }
                accessPlans.add(accessPlan);
                Member adjustedMember = member.withModifiers(accessPlanner.rewriteModifiers(member.modifiers(), accessPlan));
                String text = adjustedMember.text().stripTrailing();
                edits.addAll(requiredImportEdits(sourceFile, source, targetFile, target, deps));
                int pushDownInsertion = classInsertionOffset(hierarchy, targetQualified, target);
                edits.add(new PlannerSupport.TextEdit(targetFile, pushDownInsertion, pushDownInsertion, "\n" + text + "\n", "PUSH_DOWN_INSERT"));
            }
            if (removeFromSource) {
                edits.add(new PlannerSupport.TextEdit(sourceFile, member.removeStart(), member.removeEnd(), "", "PUSH_DOWN_REMOVE"));
                sourceImportCleanupEdit(sourceFile, source, member, deps).ifPresent(edits::add);
            }
            // G017: the public-API confirmation gate is the FINAL gate before acceptance, fired only once the push-down
            // is proven otherwise safe. Evaluating it after the semantic safety checks (subtype targets, collisions,
            // override compatibility, serialization, and source call-site removal safety) keeps the more specific safety
            // refusals from being masked — an unsafe push-down surfaces its true cause (e.g. unsafe_source_call_site)
            // rather than asking the caller to confirm a public-API change that would never be safe to apply.
            refusePublicApiUnlessConfirmed(member, "pushDownMember", fields);
            warnings.addAll(accessPlanner.warnings(accessPlans));
            warnings.add("V2 pushDownMember validates direct/indirect subtype targets, collisions, imports, and source call-site safety through shared indexes.");
            return acceptedResult(apply, "pushDownMember", member, edits, warnings);
        } catch (SemanticTargetGate.Refused refused) {
            return refusedJson("pushDownMember", apply, refused.code(), refused.getMessage());
        } catch (Refusal refusal) {
            return refusedJson("pushDownMember", apply, refusal.code, refusal.getMessage(), refusal.location);
        } catch (Exception error) {
            return refusedJson("pushDownMember", apply, "push_down_member_failed", error.getMessage());
        }
    }
}
