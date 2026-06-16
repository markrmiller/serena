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
 * G009: the pull-up half of the V2 hierarchy member-move engine. Relocates a method or field from a subtype up into a
 * real supertype (class or interface), enforcing — before any edit is emitted — semantic member identity, the public-API
 * gate, target-supertype membership, collision and sibling-override compatibility (via {@link OverrideGroupResolver}),
 * field/serializable/initializer hazards, interface-constant legality, body compatibility, access adjustment, and import
 * transfer. Shared mechanics live in {@link HierarchyMoveSupport}; this unit holds only the pull-up orchestration.
 */
public final class PullUpPlanner extends HierarchyMoveSupport {

    public PullUpPlanner(Path projectRoot, JavaProjectModel model) {
        super(projectRoot, model);
    }

    public String pullUpMember(Map<String, Object> fields, boolean apply) {
        try {
            Path sourceFile = sourceFile(fields);
            String targetType = stringField(fields, "targetType", "");
            refusePathLikeTypeName(targetType, "targetType");
            String source = SourceText.read(model, sourceFile);
            Member member = selectedMember(sourceFile, fields);
            TypeHierarchyIndex hierarchy = hierarchyIndex(sourceFile);
            String sourceQualified = member.ownerQualifiedName();
            String targetQualified = resolveRequiredType(hierarchy, targetType);
            if (!hierarchy.allSupertypes(sourceQualified).contains(targetQualified)) {
                throw new Refusal("target_not_supertype", "pullUpMember requires the target type to be a real supertype of the source type.");
            }

            Path targetFile = targetFileForType(hierarchy, targetQualified, "targetType");
            String target = SourceText.read(model, targetFile);
            if (memberExists(hierarchy, targetQualified, member)) {
                throw new Refusal("target_member_exists", "Target type already declares a compatible member with this name.", memberLocation(fields));
            }
            refuseSiblingCollisions(hierarchy, sourceQualified, targetQualified, member, fields);
            // G009: prove every sibling subtype method that shares this method's erased signature remains a LEGAL override
            // of the relocated supertype declaration (covariant return, generic substitution, visibility) BEFORE emitting
            // edits, rather than deferring to a post-edit javac compile to surface an illegal override.
            refuseIncompatibleOverrides(
                    new OverrideGroupResolver(hierarchy).validatePullUp(movingMethodDescriptor(hierarchy, member), sourceQualified, targetQualified),
                    fields);
            boolean makeAbstract = boolField(fields, "makeAbstract", false);
            boolean targetInterface = isInterface(target);
            if (member.kind() == MemberKind.FIELD && targetInterface) {
                // G009: a field pulled into an interface must become a legal interface constant. Only a compile-time
                // constant whose referenced types/imports remain accessible from the interface may be moved; everything
                // else (instance field, non-constant initializer, inaccessible referenced type) is refused with a
                // located structured code rather than emitting invalid Java.
                refuseFieldNotInterfaceConstant(member, fields);
            } else if (member.kind() == MemberKind.FIELD && !isSupportedFieldMove(member)) {
                throw new Refusal(
                        "unsafe_field_pull_up",
                        "V2 pullUpMember supports constants and simple instance fields without initializer/static/final/volatile hazards.",
                        memberLocation(fields));
            }

            refuseFieldAssignmentHazards(hierarchy, sourceFile, member, sourceQualified, fields);

            refuseSerializationImpactForField(hierarchy, member, sourceQualified, List.of(targetQualified), fields);

            if (!makeAbstract && !targetInterface) {
                refuseBodyIncompatibleWithTarget(hierarchy, source, sourceQualified, targetQualified, member);
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
            Member adjustedMember = targetInterface ? member : member.withModifiers(accessPlanner.rewriteModifiers(member.modifiers(), accessPlan));
            String text;
            if (targetInterface && member.kind() == MemberKind.METHOD) {
                text = member.interfaceDeclaration();
            } else if (targetInterface && member.kind() == MemberKind.FIELD) {
                // G009: render the constant as a legal interface field with the implicit public static final made explicit.
                text = member.interfaceConstantDeclaration();
            } else if (makeAbstract && member.kind() == MemberKind.METHOD) {
                text = adjustedMember.abstractText();
            } else {
                text = adjustedMember.text().stripTrailing();
            }

            boolean leaveDelegate = boolField(fields, "leaveDelegate", false) || boolField(fields, "keepDelegate", false);
            // HB-6: the imports the moved member needs (transfer) and no longer needs (cleanup) are derived from the
            // member's javac-resolved type/member references, not an identifier regex over the rendered member text.
            SemanticIndex.MovedBodyDependencies deps = memberDependencies(sourceFile, fields, member);
            List<PlannerSupport.TextEdit> edits = new ArrayList<>();
            edits.addAll(requiredImportEdits(sourceFile, source, targetFile, target, deps));
            if ((makeAbstract || targetInterface) && member.kind() == MemberKind.METHOD) {
                if (!hasOverrideAnnotation(source, member)) {
                    edits.add(new PlannerSupport.TextEdit(sourceFile, member.removeStart(), member.removeStart(), member.indent() + "@Override\n", "PULL_UP_OVERRIDE"));
                }
            } else if (leaveDelegate && member.kind() == MemberKind.METHOD) {
                // G002 (Branch D): the concrete method moves to the supertype but the source keeps a forwarding @Override
                // stub that delegates to super, so existing references to the source override continue to resolve. The
                // source declaration's body is replaced by the delegate; references stay valid, so no import cleanup runs.
                edits.add(new PlannerSupport.TextEdit(sourceFile, member.removeStart(), member.removeEnd(), buildDelegateBody(member, io.serena.javarefactor.shared.JavaStyleProfile.infer(source)), "PULL_UP_DELEGATE"));
            } else {
                edits.add(new PlannerSupport.TextEdit(sourceFile, member.removeStart(), member.removeEnd(), "", "PULL_UP_REMOVE"));
                sourceImportCleanupEdit(sourceFile, source, member, deps).ifPresent(edits::add);
            }
            int pullUpInsertion = classInsertionOffset(hierarchy, targetQualified, target);
            edits.add(new PlannerSupport.TextEdit(targetFile, pullUpInsertion, pullUpInsertion, "\n" + text + "\n", "PULL_UP_INSERT"));
            // G017: the public-API confirmation gate is the FINAL gate before acceptance, fired only once the move is
            // proven otherwise safe. Evaluating it after the semantic safety checks (target membership, collisions,
            // override/body/field/access hazards) keeps the more specific safety refusals from being masked — an unsafe
            // pull-up surfaces its true cause (e.g. incompatible_member_body) rather than asking the caller to confirm a
            // public-API change that would never be safe to apply.
            refusePublicApiUnlessConfirmed(member, "pullUpMember", fields);
            List<String> warnings = new ArrayList<>(accessPlanner.warnings(List.of(accessPlan)));
            warnings.add("V2 pullUpMember validates hierarchy, member compatibility, imports, body safety, constants, and sibling collisions through shared indexes.");
            return acceptedResult(apply, "pullUpMember", member, edits, warnings);
        } catch (SemanticTargetGate.Refused refused) {
            return refusedJson("pullUpMember", apply, refused.code(), refused.getMessage());
        } catch (Refusal refusal) {
            return refusedJson("pullUpMember", apply, refusal.code, refusal.getMessage(), refusal.location);
        } catch (Exception error) {
            return refusedJson("pullUpMember", apply, "pull_up_member_failed", error.getMessage());
        }
    }
}
