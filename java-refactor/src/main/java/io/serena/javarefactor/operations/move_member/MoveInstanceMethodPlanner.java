package io.serena.javarefactor.operations.move_member;

import io.serena.javarefactor.ast.IdentifierSpan;
import io.serena.javarefactor.ast.ResolvedTarget;
import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.shared.AccessAdjustmentPlanner;
import io.serena.javarefactor.shared.AccessPlan;
import io.serena.javarefactor.shared.SemanticTargetGate;
import io.serena.javarefactor.shared.SourceText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * V2 planner for the <em>instance-method</em> move (plan §3 {@code move_member}, hard blocker 8 / G008).
 *
 * <p>This is the named home of the instance-method move path, separated from {@link MoveMemberPlanner} (which still owns
 * shared member-selection, insertion, collision, and path-resolution services, and the static path via
 * {@link MoveStaticMemberPlanner}). It reuses those services through package-private {@code *Shared} accessors and the
 * shared {@link AccessAdjustmentPlanner} for the visibility decision; receiver-strategy AST work lives in
 * {@link ReceiverRewritePlanner}.
 *
 * <h2>G008 receiver/argument safety</h2>
 *
 * <p>The receiver of the moved method comes from one of three strategies — a target parameter, a target field, or an
 * explicit receiver expression. Promoting a call-site expression to that receiver, or relocating an existing call site's
 * receiver, reorders it relative to the rest of the call, so every such promotion is gated on the canonical javac
 * {@code TreePath}-backed reorder-safety verdict ({@link SemanticIndex#isCallArgumentReorderSafe},
 * {@link SemanticIndex#isExpressionReorderSafe}). An expression that is side-effecting, order-sensitive (assignment,
 * increment/decrement, side-effecting array index, cast over an unprovable operand), or simply UNKNOWN is refused with a
 * located {@code SIDE_EFFECTING_RECEIVER_EXPRESSION} rather than relocated.
 *
 * <p>An <em>explicit</em> {@code targetReceiver} is detached request text with no resolvable {@code TreePath}, so the
 * canonical verdict cannot be evaluated for it. Only a simple navigation receiver (a bare identifier or dotted field
 * navigation such as {@code target} or {@code holder.target}), which is side-effect-free by construction, is admitted;
 * any richer shape is refused with {@code non_simple_receiver_unsupported} rather than green-lit by a detached purity
 * classification.
 */
public final class MoveInstanceMethodPlanner {
    private final Path projectRoot;
    private final JavaProjectModel model;
    private final MoveMemberPlanner shared;
    private final AccessAdjustmentPlanner accessPlanner = new AccessAdjustmentPlanner();

    public MoveInstanceMethodPlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot;
        this.model = model;
        this.shared = new MoveMemberPlanner(projectRoot, model);
    }

    public String move(Map<String, Object> fields, boolean apply) {
        try {
            Path sourceFile = shared.sourceFileShared(fields);
            String source = SourceText.read(model, sourceFile);
            String relativePath = PlannerSupport.relative(projectRoot, sourceFile);
            try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
                ResolvedTarget verified = SemanticTargetGate.require(index, relativePath, fields);
                MoveMemberPlanner.Member member = shared.selectedSemanticMemberShared(
                        index, sourceFile, source, shared.intFieldShared(fields, "line"), verified);
                SemanticTargetGate.confirmSelection(verified, member.semantic().element());
                if (member.kind() != MoveMemberPlanner.MemberKind.METHOD || member.isStatic()) {
                    throw new MoveMemberPlanner.Refusal("not_instance_method", "moveInstanceMethod requires a non-static method target.");
                }

                String targetParameter = stringField(fields, "targetParameter", "");
                String targetField = stringField(fields, "targetField", "");
                String targetReceiver = stringField(fields, "targetReceiver", stringField(fields, "receiverExpression", ""));

                // G002: an AST-backed receiver SELECTION RANGE resolves to a real javac TreePath, so the canonical
                // purity/reorder-safety verdict CAN be evaluated for it — unlike detached targetReceiver text. When a
                // selection is supplied we resolve it, prove it reorder-safe, and use its source text as the receiver.
                // This admits pure NON-simple receivers (a cast, a parenthesized navigation, etc.) that the detached-text
                // path must refuse, while refusing side-effecting/order-sensitive/assignment/increment/unknown receivers
                // with located evidence and refusing an unresolvable range outright (it can never be proven safe).
                SemanticIndex.SemanticExpressionSelection receiverSelection = resolveReceiverSelection(index, sourceFile, fields);
                boolean astReceiver = receiverSelection != null;
                String astReceiverType = "";
                if (astReceiver) {
                    if (!targetReceiver.isBlank()) {
                        throw new MoveMemberPlanner.Refusal(
                                "ambiguous_target_receiver",
                                "moveInstanceMethod accepts either a receiverSelection range or a detached targetReceiver, not both.");
                    }
                    if (!index.isExpressionReorderSafe(sourceFile, receiverSelection.range())) {
                        throw new MoveMemberPlanner.Refusal(
                                "unsafe_explicit_receiver",
                                "Receiver selection '" + receiverSelection.text().strip() + "' (offsets "
                                        + receiverSelection.range().start() + "-" + receiverSelection.range().end()
                                        + " in " + relativePath + ", purity=" + receiverSelection.purity()
                                        + ") is not provably reorder-safe: a side-effecting, order-sensitive, assignment, "
                                        + "increment/decrement, or unknown receiver cannot become the moved method's this.");
                    }
                    targetReceiver = receiverSelection.text().strip();
                    astReceiverType = receiverSelection.type();
                }

                if (targetParameter.isBlank() && targetField.isBlank() && targetReceiver.isBlank()) {
                    throw new MoveMemberPlanner.Refusal(
                            "missing_target_receiver",
                            "targetParameter, targetField, targetReceiver, or receiverSelection is required for V2 instance-method moves.");
                }
                if ((!targetParameter.isBlank() && !targetField.isBlank())
                        || (!targetParameter.isBlank() && !targetReceiver.isBlank())
                        || (!targetField.isBlank() && !targetReceiver.isBlank())) {
                    throw new MoveMemberPlanner.Refusal(
                            "ambiguous_target_receiver", "moveInstanceMethod accepts exactly one target receiver strategy.");
                }
                // A DETACHED targetReceiver is supplied as request text with no resolvable TreePath, so the canonical
                // reorder-safety verdict cannot be evaluated for it. For that path we still admit only a SIMPLE navigation
                // receiver (a bare identifier or dotted field navigation), which is side-effect-free by construction; any
                // richer shape must instead be supplied as a receiverSelection range (handled above) so its evaluation-order
                // safety is AST-proven rather than string-green-lit.
                if (!astReceiver && !targetReceiver.isBlank()
                        && !ReceiverRewritePlanner.SIMPLE_RECEIVER.matcher(targetReceiver.strip()).matches()) {
                    throw new MoveMemberPlanner.Refusal(
                            "non_simple_receiver_unsupported",
                            "Explicit targetReceiver '" + targetReceiver.strip() + "' is not a simple navigation path "
                                    + "(an identifier or dotted field navigation). A detached receiver expression carries "
                                    + "no resolvable AST range, so its evaluation-order safety at relocated call sites "
                                    + "cannot be proven; supply it as a receiverSelection range, or use a simple receiver, a "
                                    + "target field, or a target parameter.");
                }

                // The source state that acts as the new receiver is legitimate and must be excluded from the
                // source-state-required guard; a target parameter is not a source member, so it contributes no excluded
                // field. For an AST-resolved receiver SELECTION the excluded set is the source fields the receiver
                // expression reads (its inputs) — e.g. `raw` in the receiver `(Target) raw` — because those references in
                // the body are part of the receiver navigation that becomes `this`. The javac diagnostic delta on apply
                // still rejects any standalone use of such a field that the receiver rewrite leaves dangling.
                java.util.Set<String> receiverFieldNames = new java.util.LinkedHashSet<>();
                if (!targetField.isBlank()) {
                    receiverFieldNames.add(targetField);
                } else if (astReceiver) {
                    receiverFieldNames.addAll(index.fieldNamesReadBy(sourceFile, receiverSelection.range()));
                } else if (!targetReceiver.isBlank()
                        && ReceiverRewritePlanner.SIMPLE_IDENTIFIER.matcher(targetReceiver).matches()) {
                    receiverFieldNames.add(targetReceiver);
                }
                refuseInstanceMoveBlockers(index, sourceFile, member, receiverFieldNames);

                MoveMemberPlanner.Parameter parameter = null;
                int targetParameterIndex = -1;
                String receiverName;
                String inferredTargetType;
                if (!targetParameter.isBlank()) {
                    parameter = shared.parameterShared(member.parameters(), targetParameter);
                    targetParameterIndex = shared.parameterIndexShared(member.parameters(), targetParameter);
                    receiverName = targetParameter;
                    inferredTargetType = parameter.type();
                } else if (!targetField.isBlank()) {
                    receiverName = targetField;
                    inferredTargetType = shared.fieldTypeShared(index, sourceFile, targetField);
                } else {
                    receiverName = targetReceiver;
                    // G002: an AST-resolved receiver selection carries its javac-resolved type, so the target type can be
                    // inferred from the receiver expression itself; a detached receiver still requires an explicit targetType.
                    inferredTargetType = astReceiverType;
                }

                // G008 (config): rewriteCallSites and keepDelegate/leaveDelegate both default to TRUE. Main.java copies
                // rewrite_call_sites_default=true into rewriteCallSites and leave_delegate_default=true into keepDelegate,
                // so an unset field means the documented default, not "off". Reading both as `!Boolean.FALSE.equals(...)`
                // makes a default request keep a delegate AND rewrite call sites, matching the move config contract.
                boolean rewriteCallSites = !Boolean.FALSE.equals(fields.get("rewriteCallSites"));
                boolean keepDelegate = !Boolean.FALSE.equals(fields.get("keepDelegate"));
                boolean externalReferences = hasExternalReferences(index, sourceFile, member);
                if (!rewriteCallSites && !keepDelegate && externalReferences) {
                    throw new MoveMemberPlanner.Refusal(
                            "call_site_rewrite_required",
                            "moveInstanceMethod would leave external call sites unresolved; enable rewriteCallSites or keepDelegate.");
                }
                if (!rewriteCallSites && keepDelegate && parameter == null && externalReferences) {
                    throw new MoveMemberPlanner.Refusal(
                            "delegate_receiver_strategy_unsupported",
                            "Delegates are only supported for target-parameter moves when call-site rewriting is disabled.");
                }

                // Method references (`Source::m`, `this::m`, `obj::m`) bind to the SOURCE-type instance method and its
                // functional-interface target shape (receiver type + arity). Moving the method to a different receiver
                // type changes that shape, so a reference cannot be retargeted by call-site rewriting the way a normal
                // invocation can. Skipping is provably safe only when a delegate with the original name/signature remains
                // in the source type (keepDelegate AND a target-parameter move), because the reference then still resolves
                // to that delegate. In every other case the source declaration is removed and the reference would dangle,
                // so we refuse with located evidence rather than silently leaving a stale reference.
                boolean delegateRemains = keepDelegate && parameter != null;
                if (!delegateRemains) {
                    List<String> methodReferenceLocations = externalMethodReferenceLocations(index, sourceFile, member);
                    if (!methodReferenceLocations.isEmpty()) {
                        throw new MoveMemberPlanner.Refusal(
                                "method_reference_unsupported",
                                "V2 moveInstanceMethod refuses moving a method that is captured by method reference; the "
                                        + "functional-interface target shape would change with the new receiver type. "
                                        + "Method reference(s) at: " + String.join(", ", methodReferenceLocations)
                                        + ". Keep a delegate (keepDelegate=true with a targetParameter move) to preserve them.");
                    }
                }

                Path targetFile = shared.targetFileForTypeShared(fields, sourceFile, inferredTargetType);
                String target = SourceText.read(model, targetFile);
                String replacementName = stringField(fields, "newName", "");
                MoveMemberPlanner.Member namedMember = replacementName.isBlank() ? member : member.withName(replacementName);
                shared.refuseTargetMethodSignatureCollisionShared(
                        index, targetFile, namedMember.name(),
                        shared.movedParameterTypesShared(member, targetParameterIndex, parameter != null));

                // Relocating an instance method to a new receiver type can require widening the moved member's visibility
                // so cross-package call sites stay source-valid. Use the GATED 7-arg overload so widening is refused by
                // default — access_widening_not_confirmed unless allowAccessWidening is opted in — rather than silently
                // widened to public with only a warning.
                AccessPlan accessPlan = accessPlanner.plan(
                        member.modifiers(),
                        shared.packageNameShared(index, sourceFile),
                        shared.packageNameShared(index, targetFile),
                        false,
                        namedMember.name(),
                        Boolean.TRUE.equals(fields.get("allowAccessWidening")),
                        Boolean.TRUE.equals(fields.get("allowSecuritySensitivePrivateWidening")));
                if (!accessPlan.allowed()) {
                    throw new MoveMemberPlanner.Refusal(accessPlan.refusal().code(), accessPlan.refusal().message());
                }
                MoveMemberPlanner.Member moved = (parameter == null
                                ? namedMember.withoutReceiver(receiverName)
                                : namedMember.withoutParameter(parameter))
                        .withModifiers(accessPlanner.rewriteModifiers(member.modifiers(), accessPlan));

                List<PlannerSupport.TextEdit> edits = new ArrayList<>();
                edits.addAll(planPrivateDependencyAccess(index, sourceFile, targetFile, member, fields));
                if (keepDelegate && parameter != null) {
                    edits.add(new PlannerSupport.TextEdit(
                            sourceFile,
                            member.removeStart(),
                            member.removeEnd(),
                            member.delegateFor(parameter, namedMember.name(), io.serena.javarefactor.shared.JavaStyleProfile.infer(source)),
                            "MOVE_INSTANCE_METHOD_DELEGATE"));
                } else {
                    edits.add(new PlannerSupport.TextEdit(
                            sourceFile, member.removeStart(), member.removeEnd(), "", "MOVE_INSTANCE_METHOD_REMOVE"));
                }
                int insertionOffset = shared.classInsertionOffsetShared(index, targetFile, moved.kind(), target);
                edits.add(new PlannerSupport.TextEdit(
                        targetFile,
                        insertionOffset,
                        insertionOffset,
                        "\n" + moved.text().stripTrailing() + "\n",
                        "MOVE_INSTANCE_METHOD_INSERT"));
                edits.addAll(shared.transplantBodyImportsShared(source, targetFile, target, member.text(), "MOVE_INSTANCE_METHOD_IMPORT"));
                if (rewriteCallSites) {
                    edits.addAll(semanticInstanceCallEdits(
                            index, sourceFile, member, targetParameter, targetParameterIndex, targetField, targetReceiver, namedMember.name()));
                }
                List<String> warnings = new ArrayList<>(accessPlanner.warnings(List.of(accessPlan)));
                warnings.add("V2 moveInstanceMethod supports constrained receiver moves to parameter, field, or explicit receiver strategies.");
                warnings.add(PlannerSupport.reflectionResourceCaveat("instance method '" + member.name() + "'"));
                return ResponseBuilder.acceptedResult(
                        projectRoot,
                        "moveInstanceMethod",
                        apply,
                        "{\"semanticKey\":" + SemanticKey.from(member.semantic().element()).toJson() + "}",
                        edits,
                        List.of(),
                        warnings,
                        List.of(),
                        ResponseBuilder.DiagnosticDelta.unvalidated(),
                        false,
                        accessPlanner.plansJson(List.of(accessPlan)));
            }
        } catch (MoveMemberPlanner.Refusal refusal) {
            return refusedJson("moveInstanceMethod", apply, refusal.code(), refusal.getMessage());
        } catch (SemanticTargetGate.Refused refused) {
            return refusedJson("moveInstanceMethod", apply, refused.code(), refused.getMessage());
        } catch (Exception error) {
            return refusedJson("moveInstanceMethod", apply, "move_instance_method_failed", error.getMessage());
        }
    }

    private void refuseInstanceMoveBlockers(
            SemanticIndex index, Path sourceFile, MoveMemberPlanner.Member member, java.util.Set<String> receiverFieldNames)
            throws MoveMemberPlanner.Refusal {
        // HB-4: the super-dispatch, synchronized-on-receiver, source-type-variable, and this-escape blockers are derived
        // from the method's resolved javac AST and symbol bindings (SemanticIndex.instanceMoveFacts), never from raw
        // source text. Text scanning produced both false misses (`super . foo()` with spaces; the legacy
        // `text.contains("super.")` missed it) and false refusals (a type-parameter name appearing only in a Javadoc
        // comment or string literal). If the AST cannot be analyzed we fail closed rather than relocate unverified code.
        SemanticIndex.InstanceMoveFacts facts = index.instanceMoveFacts(member.semantic().method());
        if (!facts.resolved()) {
            throw new MoveMemberPlanner.Refusal(
                    "instance_move_analysis_unavailable",
                    "V2 moveInstanceMethod could not resolve the method's AST to prove receiver-relocation safety; refusing.");
        }
        if (facts.usesSuper()) {
            throw new MoveMemberPlanner.Refusal("super_reference_unsupported", "V2 moveInstanceMethod refuses methods that depend on super dispatch.");
        }
        // A synchronized instance method (or a `synchronized (this)` block) locks on the SOURCE instance's monitor.
        // After the move the receiver becomes a different object, so the monitor the body acquires would silently change
        // — a semantic difference V2 refuses rather than relocate.
        if (facts.synchronizedOnReceiver()) {
            throw new MoveMemberPlanner.Refusal(
                    "synchronized_receiver_unsupported",
                    "V2 moveInstanceMethod refuses synchronized-on-this methods because the monitor object changes with the receiver.");
        }
        if (member.semantic().element() != null && member.semantic().element().getModifiers().contains(Modifier.PROTECTED)) {
            throw new MoveMemberPlanner.Refusal("protected_access_semantic_change", "V2 moveInstanceMethod refuses protected members because receiver semantics may change.");
        }
        if (facts.sourceTypeParameterDependency() != null) {
            throw new MoveMemberPlanner.Refusal(
                    "source_type_parameter_unsupported",
                    "V2 moveInstanceMethod refuses members that depend on source type parameter: " + facts.sourceTypeParameterDependency());
        }
        // G020 (B4): private source dependencies are no longer blanket-refused here. Private *instance* dependencies are
        // caught by planPrivateDependencyAccess (called from move() once the destination package is known), which widens
        // private *static* dependencies through the shared AccessAdjustmentPlanner and refuses private instance state with
        // proof that no receiver can supply it.
        for (SemanticIndex.SemanticCallSite callSite : index.methodInvocationsNamed(member.name())) {
            if (callSite.methodReference()) {
                // Method references carry no receiver invocation to screen for side-effecting receivers here; their move
                // safety is decided separately by the method-reference guard in move() (refuse unless a delegate
                // remains), so skipping them in this side-effect screen is sound, not a silent drop.
                continue;
            }
            if (callSite.invocationRange().file().toAbsolutePath().normalize().equals(sourceFile.toAbsolutePath().normalize())
                    && callSite.invocationRange().start() >= member.removeStart()
                    && callSite.invocationRange().end() <= member.removeEnd()) {
                continue;
            }
            // G008: replace the `receiver.contains("(")` heuristic with the canonical javac TreePath-backed reorder
            // safety verdict over the resolved receiver expression. An assignment, increment/decrement, side-effecting
            // array index, cast over an unprovable operand, a call, or any UNKNOWN/non-final-state read resolves to a
            // non-reorder-safe expression and is refused; a provably stable receiver (final field / effectively-final
            // local navigation) is admitted. A receiver with no resolvable range cannot be proven safe and is refused.
            if (callSite.receiverRange() != null
                    && !index.isExpressionReorderSafe(callSite.receiverRange().file(), callSite.receiverRange())) {
                throw new MoveMemberPlanner.Refusal(
                        "SIDE_EFFECTING_RECEIVER_EXPRESSION",
                        "V2 moveInstanceMethod refuses call sites whose receiver expression '"
                                + (callSite.receiverText() == null ? "" : callSite.receiverText().strip())
                                + "' is not provably reorder-safe (side-effecting, order-sensitive, or unresolvable).");
            }
        }
        // Refuse when the moved body depends on SOURCE instance state that no receiver move can supply. This complements
        // the private-source-state, super-dispatch, synchronized, protected, and type-parameter guards above by also
        // modelling NON-private source instance fields/methods read through implicit `this`. The chosen target field
        // (which becomes the new receiver) and the moved method itself are excluded; static members need no receiver and
        // are ignored.
        SemanticIndex.SemanticType sourceType = index.primaryType(sourceFile);
        if (sourceType != null && sourceType.element() instanceof TypeElement owner) {
            for (Element enclosed : owner.getEnclosedElements()) {
                if (enclosed.getModifiers().contains(Modifier.STATIC) || enclosed.getModifiers().contains(Modifier.PRIVATE)) {
                    continue;
                }
                if (enclosed.equals(member.semantic().element())) {
                    continue;
                }
                if (receiverFieldNames.contains(enclosed.getSimpleName().toString())) {
                    continue;
                }
                boolean instanceMember = enclosed.getKind() == ElementKind.METHOD || isFieldElement(enclosed);
                if (!instanceMember) {
                    continue;
                }
                SemanticIndex.SemanticMember dependency = sourceInstanceMember(index, sourceFile, enclosed);
                if (dependency != null && index.referencesWithin(dependency, member.declarationRange())) {
                    throw new MoveMemberPlanner.Refusal(
                            "source_state_required",
                            "V2 moveInstanceMethod refuses members that depend on source instance state: " + enclosed.getSimpleName());
                }
            }
        }
        // refuse when the source `this` itself escapes (passed/returned/assigned, or captured as `this::m`); such a
        // reference binds to the original instance and cannot be relocated to the new receiver. Derived from the
        // resolved AST (facts.thisEscapes) so `this` inside a comment or string is ignored.
        if (facts.thisEscapes()) {
            throw new MoveMemberPlanner.Refusal(
                    "source_this_escape_unsupported",
                    "V2 moveInstanceMethod refuses methods that use the source `this` as a value; it cannot be relocated to the new receiver.");
        }
    }

    /** Builds a {@link SemanticIndex.SemanticMember} for a non-private source instance field/method, or {@code null}. */
    private SemanticIndex.SemanticMember sourceInstanceMember(SemanticIndex index, Path sourceFile, Element element) {
        if (element.getKind() == ElementKind.METHOD && element instanceof ExecutableElement) {
            SemanticIndex.SemanticMethod method = index.semanticMethod(element);
            return method == null ? null : new SemanticIndex.SemanticMember(SemanticIndex.SemanticMemberKind.METHOD, method, null);
        }
        if (isFieldElement(element)) {
            SemanticIndex.SemanticField field = index.fieldByName(sourceFile, element.getSimpleName().toString());
            return field == null ? null : new SemanticIndex.SemanticMember(SemanticIndex.SemanticMemberKind.FIELD, null, field);
        }
        return null;
    }

    /**
     * Locations (as {@code relativePath:line:column}) of every method-reference capture of the moved method that lies
     * outside the moved declaration itself. Semantic call sites with {@link SemanticIndex.SemanticCallSite#methodReference()}
     * are returned so the caller can refuse with located evidence instead of silently leaving a stale reference.
     */
    private List<String> externalMethodReferenceLocations(SemanticIndex index, Path sourceFile, MoveMemberPlanner.Member member) {
        if (member.semantic().method() == null) {
            return List.of();
        }
        Path normalizedSource = sourceFile.toAbsolutePath().normalize();
        List<String> locations = new ArrayList<>();
        for (SemanticIndex.SemanticCallSite callSite : index.methodCallSites(member.semantic().method())) {
            if (!callSite.methodReference()) {
                continue;
            }
            SemanticIndex.SourceRange range = callSite.invocationRange();
            if (range.file().toAbsolutePath().normalize().equals(normalizedSource)
                    && range.start() >= member.removeStart() && range.end() <= member.removeEnd()) {
                continue;
            }
            locations.add(shared.formatLocationShared(index, range));
        }
        return locations;
    }

    private boolean hasExternalReferences(SemanticIndex index, Path sourceFile, MoveMemberPlanner.Member member) {
        Path normalized = sourceFile.toAbsolutePath().normalize();
        for (IdentifierSpan span : index.referencesTo(member.semantic())) {
            if (!span.file().toAbsolutePath().normalize().equals(normalized)) {
                return true;
            }
            if (span.startOffset() < member.removeStart() || span.endOffset() > member.removeEnd()) {
                return true;
            }
        }
        return false;
    }

    private List<PlannerSupport.TextEdit> semanticInstanceCallEdits(
            SemanticIndex index,
            Path sourceFile,
            MoveMemberPlanner.Member member,
            String targetParameter,
            int targetParameterIndex,
            String targetField,
            String targetReceiver,
            String replacementName)
            throws MoveMemberPlanner.Refusal {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        for (SemanticIndex.SemanticCallSite callSite : index.methodCallSites(member.semantic().method())) {
            if (callSite.methodReference()) {
                // Reaching here means a delegate with the original name/signature remains in the source type (the
                // non-delegate case is already refused with located evidence in move()), so the method reference still
                // resolves to that delegate and needs no edit. Rewriting a `::` capture into a `.m(...)` invocation would
                // be ill-typed, so we deliberately leave it bound to the surviving delegate.
                continue;
            }
            if (callSite.invocationRange().file().toAbsolutePath().normalize().equals(sourceFile.toAbsolutePath().normalize())
                    && callSite.invocationRange().start() >= member.removeStart()
                    && callSite.invocationRange().end() <= member.removeEnd()) {
                continue;
            }

            List<SemanticIndex.SemanticArgument> arguments = callSite.arguments();
            String receiverExpression;
            List<String> remainingArguments;
            if (!targetParameter.isBlank()) {
                // Bind the receiver to whatever expression occupies the target parameter's POSITION, not to a literal-name
                // match in the first slot. Java binds arguments positionally, so the argument at the parameter's declared
                // index is exactly the value that becomes the moved method's receiver — whether that is the parameter
                // name, a differently named variable, a field access, or any other expression.
                if (targetParameterIndex < 0 || arguments.size() <= targetParameterIndex) {
                    throw new MoveMemberPlanner.Refusal(
                            "target_parameter_argument_not_found",
                            "A call site does not supply an argument for target parameter '" + targetParameter
                                    + "' at position " + (targetParameterIndex + 1) + ".");
                }
                SemanticIndex.SemanticArgument receiverArgumentNode = arguments.get(targetParameterIndex);
                String receiverArgument = receiverArgumentNode.text().strip();
                // G008: gate the promotion on the canonical javac TreePath-backed reorder-safety verdict for the argument
                // at this position. The former screen refused only SIDE_EFFECTING; an UNKNOWN expression (an opaque call
                // such as `factory.next()`) or an order-sensitive read is equally unsafe to hoist to the receiver, so we
                // now refuse anything not provably reorder-safe (the verdict fails closed when the argument has no
                // resolvable range).
                boolean reorderSafe = index.isCallArgumentReorderSafe(callSite, targetParameterIndex);
                if (!reorderSafe) {
                    throw new MoveMemberPlanner.Refusal(
                            "SIDE_EFFECTING_RECEIVER_EXPRESSION",
                            "Target-parameter argument '" + receiverArgument
                                    + "' is not provably reorder-safe (side-effecting, order-sensitive, or unknown) and "
                                    + "cannot be promoted to the moved method's receiver.");
                }
                receiverExpression = ReceiverRewritePlanner.asReceiver(receiverArgument);
                List<String> kept = new ArrayList<>();
                for (int i = 0; i < arguments.size(); i++) {
                    if (i != targetParameterIndex) {
                        kept.add(arguments.get(i).text());
                    }
                }
                remainingArguments = kept;
            } else if (!targetField.isBlank()) {
                String sourceReceiver = callSite.receiverText() == null ? "" : callSite.receiverText().strip();
                receiverExpression = sourceReceiver.isBlank() ? targetField : sourceReceiver + "." + targetField;
                remainingArguments = arguments.stream().map(SemanticIndex.SemanticArgument::text).toList();
            } else {
                // Only a simple navigation receiver (a bare identifier or dotted field access) reaches here; non-simple
                // receivers are refused earlier (non_simple_receiver_unsupported). asReceiver() parenthesizes defensively
                // so appending `.method(...)` binds to the whole receiver, which is a no-op for these simple forms.
                receiverExpression = ReceiverRewritePlanner.asReceiver(targetReceiver.strip());
                remainingArguments = arguments.stream().map(SemanticIndex.SemanticArgument::text).toList();
            }

            String replacement = receiverExpression + "." + replacementName + "(" + String.join(", ", remainingArguments) + ")";
            edits.add(new PlannerSupport.TextEdit(
                    callSite.file(),
                    callSite.invocationRange().start(),
                    callSite.invocationRange().end(),
                    replacement,
                    "MOVE_INSTANCE_CALL"));
        }
        return edits;
    }

    /**
     * G020 (B4): plan access for the private source members the moved instance method references. Unlike the static move,
     * an instance move relocates the body to a <em>different</em> receiver, so the source {@code this} is not available at
     * the destination. A private <em>instance</em> field/method is reached only through that receiver, so no visibility
     * widening can make it source-valid — it is refused with proof ({@code source_state_required}). A private
     * <em>static</em> dependency needs no receiver, so it is widened in place through the shared
     * {@link AccessAdjustmentPlanner} path (gated by {@code allowAccessWidening} /
     * {@code allowSecuritySensitivePrivateWidening}), identically to the static move.
     *
     * @return in-place visibility-widening edits for referenced private static members (possibly empty)
     */
    private List<PlannerSupport.TextEdit> planPrivateDependencyAccess(
            SemanticIndex index, Path sourceFile, Path targetFile, MoveMemberPlanner.Member member, Map<String, Object> fields)
            throws MoveMemberPlanner.Refusal {
        List<SemanticIndex.SemanticMember> staticDependencies = new ArrayList<>();
        for (SemanticIndex.SemanticMember dependency : index.privateMembers(sourceFile, member.name())) {
            if (!index.referencesWithin(dependency, member.declarationRange())) {
                continue;
            }
            if (isStaticDependency(dependency)) {
                staticDependencies.add(dependency);
            } else {
                throw new MoveMemberPlanner.Refusal(
                        "source_state_required",
                        "V2 moveInstanceMethod refuses members that depend on private source instance state: "
                                + dependency.element().getSimpleName()
                                + ". It is reached through the source receiver, which no access widening can supply at the destination.");
            }
        }
        return shared.planAccessChangesForReferencedShared(
                index,
                sourceFile,
                targetFile,
                staticDependencies,
                Boolean.TRUE.equals(fields.get("allowAccessWidening")),
                Boolean.TRUE.equals(fields.get("allowSecuritySensitivePrivateWidening")),
                "MOVE_INSTANCE_METHOD_WIDEN");
    }

    private static boolean isStaticDependency(SemanticIndex.SemanticMember dependency) {
        return dependency.kind() == SemanticIndex.SemanticMemberKind.METHOD
                ? dependency.method().modifiers().contains(Modifier.STATIC)
                : dependency.field().modifiers().contains(Modifier.STATIC);
    }

    private static boolean isFieldElement(Element element) {
        return element.getKind() == ElementKind.FIELD || element.getKind() == ElementKind.ENUM_CONSTANT;
    }

    private static String stringField(Map<String, Object> fields, String key, String defaultValue) {
        return MoveMemberPlanner.stringFieldShared(fields, key, defaultValue);
    }

    /**
     * G002: resolve an AST-backed receiver selection range to its {@link SemanticIndex.SemanticExpressionSelection}, or
     * {@code null} when no selection field is present. Accepts a nested {@code receiverSelection} object with
     * {@code startLine}/{@code startColumn}/{@code endLine}/{@code endColumn}, or the flat
     * {@code receiver_selection_start_line} family. A partially-specified range is refused, and a range that does not
     * resolve to a Java expression node is refused with {@code receiver_selection_unresolved} (an unresolvable receiver
     * can never be proven reorder-safe).
     */
    private SemanticIndex.SemanticExpressionSelection resolveReceiverSelection(
            SemanticIndex index, Path sourceFile, Map<String, Object> fields) throws MoveMemberPlanner.Refusal {
        Integer startLine;
        Integer startColumn;
        Integer endLine;
        Integer endColumn;
        Object raw = fields.get("receiverSelection");
        if (raw instanceof Map<?, ?> map) {
            startLine = optInt(map, "startLine");
            startColumn = optInt(map, "startColumn");
            endLine = optInt(map, "endLine");
            endColumn = optInt(map, "endColumn");
        } else {
            startLine = optInt(fields, "receiver_selection_start_line");
            startColumn = optInt(fields, "receiver_selection_start_column");
            endLine = optInt(fields, "receiver_selection_end_line");
            endColumn = optInt(fields, "receiver_selection_end_column");
        }
        if (startLine == null && startColumn == null && endLine == null && endColumn == null) {
            return null;
        }
        if (startLine == null || startColumn == null || endLine == null || endColumn == null) {
            throw new MoveMemberPlanner.Refusal(
                    "receiver_selection_incomplete",
                    "receiverSelection requires startLine, startColumn, endLine, and endColumn.");
        }
        SemanticIndex.SemanticExpressionSelection selection;
        try {
            selection = index.selectedExpression(sourceFile, startLine, startColumn, endLine, endColumn);
        } catch (IllegalArgumentException error) {
            throw new MoveMemberPlanner.Refusal("receiver_selection_unresolved", error.getMessage());
        }
        if (selection == null) {
            throw new MoveMemberPlanner.Refusal(
                    "receiver_selection_unresolved",
                    "receiverSelection range does not resolve to a Java expression node.");
        }
        return selection;
    }

    private static Integer optInt(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    // G002: route through the one canonical refusal envelope so a refusal can never hand-roll a shape that disagrees with
    // itself — in particular {@code applied} is ALWAYS false (the previous hand-rolled JSON echoed the incoming apply
    // flag, so a direct apply=true refusal could report applied:true) and {@code mode} reflects the ACTUAL requested mode.
    private String refusedJson(String operation, boolean apply, String code, String message) {
        return ResponseBuilder.refusedResult(operation, apply, code, message);
    }
}
