package io.serena.javarefactor.v3.classops;

import io.serena.javarefactor.ast.IdentifierSpan;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticCallSite;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticField;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticMember;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticMemberKind;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticMethod;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticParameter;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticType;
import io.serena.javarefactor.compiler.SemanticIndex.SourceRange;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.PlannerSupport.TextEdit;
import io.serena.javarefactor.edits.ResponseBuilder.FileOperation;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.shared.ProjectPathResolver;
import io.serena.javarefactor.v3.transformation.TransformationStep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.Modifier;

/**
 * V3 compiler-backed <b>Extract Class</b> (refactor-feature-plan-V3.md §8). Pulls a selected set of fields and methods
 * out of a source class into a brand-new collaborator class, leaves a delegate field in the source, and (by default)
 * replaces each moved method body with a forwarding call to the collaborator so existing internal/external callers keep
 * compiling. The composed edit is validated by the sidecar's before/after javac validator.
 *
 * <p><b>Dependency classification (§8.3 step 4).</b> Each dependency of a moved method is classified rather than blanket-
 * refused:
 * <ul>
 *   <li><b>MOVE_WITH</b> — the dependency is itself selected, so it travels into the collaborator.</li>
 *   <li><b>PASS_AS_CONSTRUCTOR_PARAMETER</b> — a retained (unselected) instance field a moved method reads is injected
 *       into the collaborator's constructor and held as a same-named collaborator field; the source builds the
 *       collaborator passing {@code this.<field>} so the moved body's plain {@code field} reference resolves locally.</li>
 *   <li><b>KEEP_DELEGATE_CALL</b> — a retained (unselected) instance method a moved method calls is reached through a
 *       back-reference to the source: the collaborator takes the source instance as a constructor parameter (an
 *       {@code owner} field) and the moved body's {@code callee(...)} call is rewritten to {@code owner.callee(...)}.</li>
 *   <li><b>BLOCK</b> — only the genuinely-unrepresentable §8.4 cases (super dispatch, synchronized-on-receiver, source
 *       type-parameter dependency, native, unanalyzable, public API without delegates, and — when the source has no
 *       single analyzable constructor to thread the injected arguments through — an unpreservable initialization order).</li>
 * </ul>
 *
 * <p><b>External-usage rewrite (§8.3 step 8).</b> When {@code leaveDelegateMethods=false} AND {@code updateUsages=true},
 * a removed method that is referenced from OUTSIDE the source type is NOT refused: a public accessor returning the
 * delegate is generated on the source and every external call site is rewritten to go through it
 * ({@code obj.m(args)} → {@code obj.<delegateAccessor>().m(args)}). The {@code private final} delegate field therefore
 * stays encapsulated while external callers reach the moved behavior through the accessor. With
 * {@code updateUsages=false} the old member-attributed refusal ({@code extract_class_external_usage}) is preserved.
 */
public final class ExtractClassPlanner {

    private final Path projectRoot;
    private final JavaProjectModel model;

    public ExtractClassPlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
    }

    public String plan(Map<String, Object> fields) {
        try {
            return planChecked(fields);
        } catch (ClassOpsRefusal refusal) {
            return PlannerSupport.refusalJson(refusal.code(), refusal.getMessage());
        } catch (ProjectPathResolver.Violation violation) {
            return PlannerSupport.refusalJson(violation.code(), violation.getMessage());
        } catch (IOException error) {
            return PlannerSupport.refusalJson("extract_class_failed", "Extract class failed: " + error.getMessage());
        }
    }

    /**
     * The pre-serialization structured result of an extract-class plan: the source-file text edits, the new collaborator
     * file (project-relative path + content) emitted as a CREATE file operation, the collaborator's fully-qualified name,
     * and the counts the standalone stats reports. Shared by {@link #planChecked} (standalone JSON) and {@link #planStep}
     * (workspace composition) so both carry the identical edit and create.
     */
    private record ExtractClassPlan(List<TextEdit> edits, String newClassRelative, String newClassSource,
                                    String newClassFqn, int movedFields, int movedMethods,
                                    boolean leaveDelegateMethods, List<String> publicApiChanges) {
    }

    private String planChecked(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        ExtractClassPlan plan = compute(fields);
        String statsJson = "{\"movedFields\":" + plan.movedFields() + ",\"movedMethods\":" + plan.movedMethods()
                + ",\"leaveDelegateMethods\":" + plan.leaveDelegateMethods() + "}";
        // B6 (shared contract 1): an accepted extract that changes the source type's API surface in a PERMITTED way
        // (a non-private method removed from the source when leaveDelegateMethods=false — no forwarding stub is left, so
        // the method disappears from the published API even though in-project callers were rewritten / there were none)
        // must surface a structured `riskFacts.publicApiChanges` so CanonicalEnvelope.classifyRisk escalates to
        // needs_review instead of mis-classifying it as "safe". A genuinely safe extract emits no riskFacts.
        String riskFacts = riskFactsJson(plan.publicApiChanges());
        return "{"
                + "\"accepted\":true,"
                + "\"operation\":\"extractClass\","
                + "\"newClass\":" + JsonUtil.quote(plan.newClassFqn()) + ","
                + "\"workspaceEdit\":{"
                + "\"changes\":" + PlannerSupport.changesJson(projectRoot, plan.edits()) + ","
                + "\"fileOperations\":[" + PlannerSupport.createFileOp(plan.newClassRelative(), plan.newClassSource()) + "]"
                + "},"
                + "\"warnings\":" + PlannerSupport.warningsJson(List.of()) + ","
                + (riskFacts.isEmpty() ? "" : "\"riskFacts\":" + riskFacts + ",")
                + "\"stats\":" + statsJson
                + "}";
    }

    /**
     * The structured workspace step (refactor-feature-plan-V3.md §3) for composing the extraction into a transformation
     * workspace: the source edits plus the new collaborator as a real {@link FileOperation#create} (so the composer's
     * file-op conflict check sees a structured create, not a JSON string). Refusals surface as {@link ClassOpsRefusal}/
     * {@link ProjectPathResolver.Violation}, mapped to canonical refusal JSON by the caller.
     */
    public TransformationStep planStep(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        ExtractClassPlan plan = compute(fields);
        // B6 (shared contract 1): the workspace composer propagates only a step's `warnings` list (and semanticTarget)
        // to the composed top-level `workspaceEdit.warnings`, which CanonicalEnvelope.classifyRisk escalates on. There is
        // no top-level riskFacts channel through composition, so the same permitted public-API-surface change reasons are
        // surfaced here as step warnings — equivalent to the standalone path's `riskFacts.publicApiChanges` and likewise
        // forcing needs_review. A genuinely safe extract contributes no warning and stays "safe".
        return new TransformationStep(
                "extractClass", plan.edits(),
                List.of(FileOperation.create(plan.newClassRelative(), plan.newClassSource())),
                List.copyOf(plan.publicApiChanges()),
                "{\"operation\":\"extractClass\",\"newClass\":" + JsonUtil.quote(plan.newClassFqn()) + "}");
    }

    private ExtractClassPlan compute(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        String newClassName = requireString(fields, "newClassName");
        List<String> memberSelectors = stringList(fields.get("members"));
        if (memberSelectors.isEmpty()) {
            throw new ClassOpsRefusal("no_members", "Extract class requires at least one member selector.");
        }
        boolean leaveDelegateMethods = bool(fields, "leaveDelegateMethods", true);
        boolean confirmPublicApiChange = bool(fields, "confirmPublicApiChange", false);
        boolean updateUsages = bool(fields, "updateUsages", false);

        Path sourceFile = ProjectPathResolver.resolveProjectRelative(
                projectRoot, requireString(fields, "relativePath"), "relativePath");
        String relativePath = PlannerSupport.relative(projectRoot, sourceFile);

        try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
            SemanticType source = index.primaryType(sourceFile);
            if (source == null) {
                throw new ClassOpsRefusal("source_type_not_found",
                        "Source file must contain a javac-resolved top-level type.");
            }
            if (!"class".equals(source.kind())) {
                throw new ClassOpsRefusal("unsupported_source_kind",
                        "Extract class only supports a plain class source (got " + source.kind() + ").");
            }
            String sourcePackage = source.packageName();
            String targetPackage = str(fields, "targetPackage", sourcePackage);

            // R10 (refactor-feature-plan-V3.md §8): collision preflight BEFORE building a plan, so a target-name / file /
            // type clash surfaces as an operation-specific structured refusal — not a generic applier file-op staging
            // failure after an otherwise "accepted" plan.
            String newClassFqn = (targetPackage.isEmpty() ? "" : targetPackage + ".") + newClassName;
            Path newClassFile = collaboratorFile(sourceFile, sourcePackage, targetPackage, newClassName);
            String newClassRelative = PlannerSupport.relative(projectRoot, newClassFile);
            if (newClassFqn.equals(source.qualifiedName())) {
                throw new ClassOpsRefusal("extract_class_target_is_source",
                        "New class '" + newClassFqn + "' is the source type itself; choose a different newClassName or"
                                + " targetPackage.");
            }
            if (index.typeExists(newClassFqn)) {
                throw new ClassOpsRefusal("extract_class_target_type_exists",
                        "A type named '" + newClassFqn + "' already exists; extract class would collide with it.");
            }
            if (Files.exists(newClassFile)) {
                throw new ClassOpsRefusal("extract_class_target_file_exists",
                        "Target file '" + newClassRelative + "' already exists; extract class would overwrite it.");
            }

            String source0 = String.valueOf(index.sourceText(sourceFile));

            // Resolve selected members.
            List<SemanticField> movedFields = new ArrayList<>();
            List<SemanticMethod> movedMethods = new ArrayList<>();
            for (String raw : memberSelectors) {
                ClassOpsSupport.Selector selector = ClassOpsSupport.parseSelector(raw);
                if (selector.isField()) {
                    SemanticField field = index.fieldByName(sourceFile, selector.name());
                    if (field == null) {
                        throw new ClassOpsRefusal("member_not_found",
                                "No field '" + selector.name() + "' on " + source.qualifiedName() + ".");
                    }
                    if (field.isStatic()) {
                        throw new ClassOpsRefusal("extract_class_static_field",
                                "Static field '" + field.name() + "' cannot be moved into an instance collaborator.");
                    }
                    movedFields.add(field);
                } else {
                    SemanticMethod method = ClassOpsSupport.resolveMethod(index, source, selector);
                    guardMethodMovable(index, method, leaveDelegateMethods, confirmPublicApiChange);
                    movedMethods.add(method);
                }
            }

            // Dependency closure + classification (§8.3 step 3-4). A reference the selection leaves behind is not blanket-
            // refused: a retained FIELD is passed as a constructor parameter (PASS_AS_CONSTRUCTOR_PARAMETER) and a
            // retained METHOD is reached through a back-reference to the source (KEEP_DELEGATE_CALL). Only the genuinely
            // unrepresentable §8.4 cases (guarded above and below) BLOCK.
            Set<String> movedFieldNames = new LinkedHashSet<>();
            for (SemanticField field : movedFields) {
                movedFieldNames.add(field.name());
            }
            Set<String> movedMethodNames = new LinkedHashSet<>();
            for (SemanticMethod method : movedMethods) {
                movedMethodNames.add(method.name());
            }
            // Retained dependencies, keyed by name to dedupe across multiple moved methods (insertion-ordered).
            Map<String, SemanticField> retainedFieldDeps = new LinkedHashMap<>();
            Map<String, SemanticMethod> retainedMethodDeps = new LinkedHashMap<>();
            for (SemanticMethod method : movedMethods) {
                SemanticIndex.MemberDependencies deps = index.memberDependencies(method);
                if (!deps.resolved()) {
                    throw new ClassOpsRefusal("extract_class_unanalyzable_method",
                            "Method '" + method.name() + "' could not be analyzed for its dependency closure.");
                }
                for (String fieldName : deps.instanceFields()) {
                    if (movedFieldNames.contains(fieldName) || retainedFieldDeps.containsKey(fieldName)) {
                        continue;
                    }
                    SemanticField retained = index.fieldByName(sourceFile, fieldName);
                    if (retained == null) {
                        throw new ClassOpsRefusal("extract_class_unselected_field_dependency",
                                "Method '" + method.name() + "' uses source field '" + fieldName + "', which could not"
                                        + " be resolved to pass as a constructor parameter.");
                    }
                    retainedFieldDeps.put(fieldName, retained);
                }
                for (String calleeName : deps.instanceMethods()) {
                    if (movedMethodNames.contains(calleeName) || retainedMethodDeps.containsKey(calleeName)) {
                        continue;
                    }
                    SemanticMethod callee = resolveRetainedMethod(index, source, calleeName);
                    if (callee == null) {
                        throw new ClassOpsRefusal("extract_class_unselected_method_dependency",
                                "Method '" + method.name() + "' calls source method '" + calleeName + "', which could"
                                        + " not be resolved to keep as a delegate call.");
                    }
                    retainedMethodDeps.put(calleeName, callee);
                }
            }
            boolean needsBackReference = !retainedMethodDeps.isEmpty();

            // Constructor-aware extraction (§8.3 step 6): moved fields without a declaration initializer are
            // constructor-injected in the source, and any PASS_AS_CONSTRUCTOR_PARAMETER retained field / back-reference
            // is threaded through the SAME generated collaborator constructor. All of these require the source to build
            // the collaborator from a single analyzable constructor.
            List<SemanticField> injectedFields = new ArrayList<>();
            for (SemanticField field : movedFields) {
                if (field.initializerRange() == null) {
                    injectedFields.add(field);
                }
            }
            boolean needsConstructorInjection =
                    !injectedFields.isEmpty() || !retainedFieldDeps.isEmpty() || needsBackReference;
            SemanticIndex.ConstructorInjectionPlan injection = null;
            if (needsConstructorInjection) {
                List<String> injectedNames = new ArrayList<>();
                for (SemanticField field : injectedFields) {
                    injectedNames.add(field.name());
                }
                injection = index.planConstructorInjection(source, injectedNames);
                if (!injection.resolved()) {
                    throw new ClassOpsRefusal("extract_class_constructor_unanalyzable",
                            "The source constructor could not be analyzed for injected-field relocation.");
                }
                if (injection.refused()) {
                    throw new ClassOpsRefusal(injection.refusalCode(), injection.refusalMessage());
                }
            }

            // External-usage handling (§8.3 step 8). With updateUsages=false a removed member referenced from outside the
            // source type is refused early (member-attributed). With updateUsages=true that refusal becomes a rewrite:
            // a public delegate accessor is generated and the external call sites are routed through it.
            String delegateField = ClassOpsSupport.decapitalize(newClassName);
            List<TextEdit> externalRewrites = new ArrayList<>();
            boolean accessorNeeded = guardOrRewriteExternalUsage(index, source, sourceFile, movedFields, movedMethods,
                    leaveDelegateMethods, updateUsages, delegateField, externalRewrites);

            String delegateType = targetPackage.equals(sourcePackage)
                    ? newClassName : targetPackage + "." + newClassName;
            List<SemanticField> retainedFieldList = new ArrayList<>(retainedFieldDeps.values());
            List<SemanticMethod> retainedMethodList = new ArrayList<>(retainedMethodDeps.values());
            String newClassSource = synthesizeCollaborator(index, source0, newClassName, targetPackage,
                    movedFields, injectedFields, retainedFieldList, movedMethods, retainedMethodList,
                    needsBackReference, source.name(), leaveDelegateMethods);

            // Edit the source: delegate field, then move/forward each member.
            List<TextEdit> edits = new ArrayList<>();
            int bodyOpen = source.bodyRange().start();
            String delegateDecl = needsConstructorInjection
                    ? "\n    private final " + delegateType + " " + delegateField + ";\n"
                    : "\n    private final " + delegateType + " " + delegateField + " = new " + delegateType + "();\n";
            edits.add(new TextEdit(sourceFile, bodyOpen + 1, bodyOpen + 1, delegateDecl, "EXTRACT_CLASS_DELEGATE_FIELD"));

            // A public accessor exposing the delegate so external callers of removed methods can reach the moved
            // behavior while the delegate field itself stays private (the delegate-accessibility approach for §8.3 step 8).
            if (accessorNeeded) {
                edits.add(new TextEdit(sourceFile, bodyOpen + 1, bodyOpen + 1,
                        "\n    public " + delegateType + " " + delegateField + "() {\n        return "
                                + delegateField + ";\n    }\n",
                        "EXTRACT_CLASS_DELEGATE_ACCESSOR"));
            }

            if (needsConstructorInjection) {
                for (long[] span : injection.assignmentSpansToRemove()) {
                    edits.add(new TextEdit(sourceFile, (int) span[0], (int) span[1], "", "EXTRACT_CLASS_CTOR_REMOVE_INIT"));
                }
                List<String> args = new ArrayList<>(injection.constructorArguments());
                for (SemanticField retained : retainedFieldList) {
                    args.add("this." + retained.name());
                }
                if (needsBackReference) {
                    args.add("this");
                }
                int at = injection.delegateInitOffset();
                edits.add(new TextEdit(sourceFile, at, at,
                        "    this." + delegateField + " = new " + delegateType + "(" + String.join(", ", args) + ");\n    ",
                        "EXTRACT_CLASS_DELEGATE_INIT"));
            }

            for (SemanticField field : movedFields) {
                edits.add(deleteDeclaration(field.declarationRange()));
            }
            for (SemanticMethod method : movedMethods) {
                if (leaveDelegateMethods) {
                    if (method.bodyRange() == null) {
                        throw new ClassOpsRefusal("extract_class_abstract_method",
                                "Abstract method '" + method.name() + "' cannot keep a delegate body.");
                    }
                    edits.add(new TextEdit(sourceFile, method.bodyRange().start(), method.bodyRange().end(),
                            forwardingBody(delegateField, method), "EXTRACT_CLASS_DELEGATE_BODY"));
                } else {
                    edits.add(deleteDeclaration(method.declarationRange()));
                }
            }
            edits.addAll(externalRewrites);

            List<String> publicApiChanges = collectPublicApiChanges(source, movedMethods, leaveDelegateMethods);

            return new ExtractClassPlan(edits, newClassRelative, newClassSource, newClassFqn,
                    movedFields.size(), movedMethods.size(), leaveDelegateMethods, publicApiChanges);
        }
    }

    /** Resolves a retained instance method by simple name on the source type (skips static; null if ambiguous/absent). */
    private SemanticMethod resolveRetainedMethod(SemanticIndex index, SemanticType source, String name) {
        try {
            SemanticMethod method = ClassOpsSupport.resolveMethod(index, source,
                    new ClassOpsSupport.Selector("method", name, null));
            return method != null && !method.isStatic() ? method : null;
        } catch (ClassOpsRefusal refusal) {
            // Ambiguous (overloaded) or unresolvable retained call cannot be safely delegated through the back-reference.
            return null;
        }
    }

    /** Enforces the §8.4 method refusal list using javac-derived move facts. */
    private void guardMethodMovable(SemanticIndex index, SemanticMethod method, boolean leaveDelegateMethods, boolean confirmPublicApiChange) {
        if (method.isStatic()) {
            throw new ClassOpsRefusal("extract_class_static_method",
                    "Static method '" + method.name() + "' is not an instance collaborator member.");
        }
        if (method.modifiers().contains(Modifier.NATIVE)) {
            throw new ClassOpsRefusal("extract_class_native_method",
                    "Native method '" + method.name() + "' cannot be relocated.");
        }
        if (method.modifiers().contains(Modifier.PUBLIC) && !leaveDelegateMethods && !confirmPublicApiChange) {
            throw new ClassOpsRefusal("extract_class_public_api_without_delegates",
                    "Public method '" + method.name()
                            + "' cannot be removed without explicit public API confirmation; set leaveDelegateMethods=true or confirmPublicApiChange=true.");
        }
        SemanticIndex.InstanceMoveFacts facts = index.instanceMoveFacts(method);
        if (!facts.resolved()) {
            throw new ClassOpsRefusal("extract_class_unanalyzable_method",
                    "Method '" + method.name() + "' could not be analyzed for safe relocation.");
        }
        if (facts.usesSuper()) {
            throw new ClassOpsRefusal("extract_class_uses_super",
                    "Method '" + method.name() + "' dispatches through super and cannot move to a collaborator.");
        }
        if (facts.synchronizedOnReceiver()) {
            throw new ClassOpsRefusal("extract_class_synchronized_receiver",
                    "Method '" + method.name() + "' synchronizes on the source instance; the monitor would change.");
        }
        if (facts.sourceTypeParameterDependency() != null) {
            throw new ClassOpsRefusal("extract_class_source_type_parameter",
                    "Method '" + method.name() + "' depends on source type parameter "
                            + facts.sourceTypeParameterDependency() + ", which the collaborator cannot supply.");
        }
    }

    /**
     * External-usage handling (§8.3 step 8 / §8.4). A member genuinely removed from the source (a moved field, always
     * deleted; or a removed method when {@code leaveDelegateMethods==false}) that is referenced from OUTSIDE the source
     * type would not compile at the external site. With {@code updateUsages==false} this is refused early
     * ({@code extract_class_external_usage}). With {@code updateUsages==true} the external METHOD call sites are instead
     * rewritten to go through a generated public delegate accessor ({@code obj.m(args)} → {@code obj.<delegate>().m(args)});
     * the method returns {@code true} to signal the accessor must be emitted on the source. Externally-read removed FIELDS
     * have no behavior-preserving rewrite (no getter contract to forward through), so they remain a refusal.
     *
     * @return whether a public delegate accessor must be generated on the source (true only when external method call
     *         sites were rewritten).
     */
    private boolean guardOrRewriteExternalUsage(SemanticIndex index, SemanticType source, Path sourceFile,
            List<SemanticField> movedFields, List<SemanticMethod> movedMethods, boolean leaveDelegateMethods,
            boolean updateUsages, String delegateField, List<TextEdit> externalRewrites) {
        Path srcFile = sourceFile.toAbsolutePath().normalize();
        SourceRange body = source.bodyRange();
        for (SemanticField field : movedFields) {
            // Moved fields are always deleted with no accessor. Private fields can have no cross-type reference. A field
            // read has no forwarding contract, so it is refused even under updateUsages (there is no behavior-preserving
            // rewrite of a bare field access to a moved-away field).
            if (field.isPrivate()) {
                continue;
            }
            // B7 (§8 public-API rules): a public OR protected instance field being MOVED out of the source is a published
            // API-surface removal — it is consumed by downstream dependents OUTSIDE this project, not just by the
            // in-project references refuseIfExternallyReferenced can see. Unlike a method (which can keep a forwarding
            // delegate body via leaveDelegateMethods, or be re-routed through a generated accessor under updateUsages),
            // a field has no preservation opt-in: there is no forwarding contract for a bare field access. Conservatively
            // refuse rather than silently breaking the API, even when the current project shows zero references.
            if (field.modifiers().contains(Modifier.PUBLIC) || field.modifiers().contains(Modifier.PROTECTED)) {
                String access = field.modifiers().contains(Modifier.PUBLIC) ? "Public" : "Protected";
                throw new ClassOpsRefusal("extract_class_public_api_field",
                        access + " field '" + field.name() + "' is part of " + source.qualifiedName()
                                + "'s published API; moving it into the collaborator removes it from the source type's API"
                                + " surface and would break downstream dependents (a field has no forwarding delegate). Keep"
                                + " the field in the source, or first reduce its visibility to private/package-private if it"
                                + " is not actually part of the public API.");
            }
            refuseIfExternallyReferenced(index, new SemanticMember(SemanticMemberKind.FIELD, null, field),
                    "Field", field.name(), source, srcFile, body);
        }
        boolean accessorNeeded = false;
        if (!leaveDelegateMethods) {
            for (SemanticMethod method : movedMethods) {
                // Public methods are already refused earlier; private methods have no external callers. Only the
                // package-private / protected removed-method case can break an external caller without a forwarding stub.
                if (method.isPrivate() || method.modifiers().contains(Modifier.PUBLIC)) {
                    continue;
                }
                List<SemanticCallSite> external = externalCallSites(index, method, srcFile, body);
                if (external.isEmpty()) {
                    continue;
                }
                if (!updateUsages) {
                    throw new ClassOpsRefusal("extract_class_external_usage",
                            "Method '" + method.name() + "' is removed from " + source.qualifiedName()
                                    + " but is referenced from outside the source type; set updateUsages=true to rewrite"
                                    + " external callers, or leaveDelegateMethods=true to keep a forwarding stub.");
                }
                for (SemanticCallSite site : external) {
                    if (site.methodReference() || site.receiverRange() == null) {
                        // A method reference (Type::m) or an unqualified/implicit-receiver external call cannot be safely
                        // re-routed through the delegate accessor by a positional splice; fall back to a refusal.
                        throw new ClassOpsRefusal("extract_class_external_usage",
                                "Method '" + method.name() + "' is referenced from outside " + source.qualifiedName()
                                        + " in a form that cannot be rewritten through the delegate accessor.");
                    }
                    int at = site.receiverRange().end();
                    externalRewrites.add(new TextEdit(site.file(), at, at, "." + delegateField + "()",
                            "EXTRACT_CLASS_EXTERNAL_REWRITE"));
                    accessorNeeded = true;
                }
            }
        }
        return accessorNeeded;
    }

    /**
     * The accepted-but-reviewable public-API-surface changes (shared contract 1, {@code riskFacts.publicApiChanges}) of
     * this extract. An accepted extract that REMOVES a non-private method from the source type — i.e.
     * {@code leaveDelegateMethods==false}, so no forwarding stub remains — changes the type's published API surface even
     * when the move is permitted (public removals are refused earlier; protected / package-private removals are accepted,
     * optionally re-routing in-project callers through the generated accessor). Downstream dependents outside this project
     * may still rely on the removed declaration, so a human must confirm. When {@code leaveDelegateMethods==true} every
     * moved method keeps a forwarding delegate on the source, so the API surface is preserved and no fact is emitted.
     */
    private List<String> collectPublicApiChanges(SemanticType source, List<SemanticMethod> movedMethods,
            boolean leaveDelegateMethods) {
        List<String> changes = new ArrayList<>();
        if (leaveDelegateMethods) {
            return changes;
        }
        for (SemanticMethod method : movedMethods) {
            if (method.isPrivate()) {
                continue;
            }
            String access = method.modifiers().contains(Modifier.PUBLIC) ? "public"
                    : method.modifiers().contains(Modifier.PROTECTED) ? "protected" : "package-private";
            changes.add("Extract class removes " + access + " method '" + method.name() + "' from "
                    + source.qualifiedName() + "'s API surface (leaveDelegateMethods=false leaves no forwarding stub);"
                    + " downstream dependents outside this project may rely on it. Confirm the API change.");
        }
        return changes;
    }

    /**
     * Builds the top-level {@code riskFacts} JSON object (shared contract 1) for an accepted-but-reviewable result, or an
     * empty string when there is no reviewable fact (so a genuinely safe extract emits no {@code riskFacts} and stays
     * "safe"). Only {@code publicApiChanges} is populated here; the other contract arrays are omitted (treated as empty
     * by {@link io.serena.javarefactor.protocol.CanonicalEnvelope}).
     */
    private static String riskFactsJson(List<String> publicApiChanges) {
        if (publicApiChanges.isEmpty()) {
            return "";
        }
        return "{\"publicApiChanges\":" + PlannerSupport.warningsJson(publicApiChanges) + "}";
    }

    /** The call sites of {@code method} that lie OUTSIDE the source type's body (cross-type external usages). */
    private List<SemanticCallSite> externalCallSites(SemanticIndex index, SemanticMethod method, Path srcFile,
            SourceRange body) {
        List<SemanticCallSite> external = new ArrayList<>();
        for (SemanticCallSite site : index.methodCallSites(method)) {
            boolean internal = site.file().toAbsolutePath().normalize().equals(srcFile)
                    && site.invocationRange().start() >= body.start()
                    && site.invocationRange().end() <= body.end();
            if (!internal) {
                external.add(site);
            }
        }
        return external;
    }

    private void refuseIfExternallyReferenced(SemanticIndex index, SemanticMember member, String memberKind,
            String memberName, SemanticType source, Path srcFile, SourceRange body) {
        List<String> sites = new ArrayList<>();
        for (IdentifierSpan span : index.referencesTo(member)) {
            boolean internal = span.file().toAbsolutePath().normalize().equals(srcFile)
                    && span.startOffset() >= body.start()
                    && span.endOffset() <= body.end();
            if (!internal) {
                sites.add(PlannerSupport.relative(projectRoot, span.file()) + ":" + span.line() + ":" + span.column());
            }
        }
        if (!sites.isEmpty()) {
            throw new ClassOpsRefusal("extract_class_external_usage",
                    memberKind + " '" + memberName + "' is removed from " + source.qualifiedName()
                            + " but is referenced from outside the source type at: " + String.join(", ", sites)
                            + "; external callers would not compile. Select a delegate-preserving option or keep the"
                            + " member.");
        }
    }

    private String synthesizeCollaborator(SemanticIndex index, String source0, String newClassName, String targetPackage,
            List<SemanticField> movedFields, List<SemanticField> injectedFields, List<SemanticField> retainedFieldDeps,
            List<SemanticMethod> movedMethods, List<SemanticMethod> retainedMethodDeps, boolean needsBackReference,
            String sourceSimpleName, boolean leaveDelegateMethods) {
        StringBuilder out = new StringBuilder();
        if (!targetPackage.isEmpty()) {
            out.append("package ").append(targetPackage).append(";\n\n");
        }
        Set<String> imports = new LinkedHashSet<>(ClassOpsSupport.importLines(source0));
        for (String imp : imports) {
            out.append(imp).append('\n');
        }
        if (!imports.isEmpty()) {
            out.append('\n');
        }
        out.append("public class ").append(newClassName).append(" {\n");
        for (SemanticField field : movedFields) {
            out.append("    ").append(field.declarationRange().text(index).strip()).append('\n');
        }
        // PASS_AS_CONSTRUCTOR_PARAMETER: a retained source field is held as a same-named collaborator field so the moved
        // method body's bare `field` reference resolves locally (no body rewrite needed).
        for (SemanticField retained : retainedFieldDeps) {
            out.append("    private final ").append(retained.type()).append(' ').append(retained.name()).append(";\n");
        }
        // KEEP_DELEGATE_CALL: a back-reference to the source so the collaborator can call retained source methods.
        if (needsBackReference) {
            out.append("    private final ").append(sourceSimpleName).append(" owner;\n");
        }
        // Generated constructor for the injected moved fields, the passed-as-parameter retained fields, and the optional
        // back-reference — each assigned from a same-named parameter.
        if (!injectedFields.isEmpty() || !retainedFieldDeps.isEmpty() || needsBackReference) {
            out.append('\n');
            List<String> params = new ArrayList<>();
            for (SemanticField field : injectedFields) {
                params.add(field.type() + " " + field.name());
            }
            for (SemanticField retained : retainedFieldDeps) {
                params.add(retained.type() + " " + retained.name());
            }
            if (needsBackReference) {
                params.add(sourceSimpleName + " owner");
            }
            out.append("    public ").append(newClassName).append('(').append(String.join(", ", params)).append(") {\n");
            for (SemanticField field : injectedFields) {
                out.append("        this.").append(field.name()).append(" = ").append(field.name()).append(";\n");
            }
            for (SemanticField retained : retainedFieldDeps) {
                out.append("        this.").append(retained.name()).append(" = ").append(retained.name()).append(";\n");
            }
            if (needsBackReference) {
                out.append("        this.owner = owner;\n");
            }
            out.append("    }\n");
        }
        if (!movedFields.isEmpty() && !movedMethods.isEmpty()) {
            out.append('\n');
        }
        Set<String> retainedMethodNames = new LinkedHashSet<>();
        for (SemanticMethod retained : retainedMethodDeps) {
            retainedMethodNames.add(retained.name());
        }
        for (SemanticMethod method : movedMethods) {
            String declaration = method.declarationRange().text(index).strip();
            // When a delegate is left in the source, a private collaborator method would be uncallable through the
            // delegate field; widen it to package-private so the forwarding stub resolves.
            if (leaveDelegateMethods && method.isPrivate()) {
                declaration = declaration.replaceFirst("(?s)^private\\s+", "");
            }
            // KEEP_DELEGATE_CALL body rewrite: route each call to a retained source method through the back-reference.
            declaration = rewriteRetainedCalls(index, method, declaration, retainedMethodNames);
            out.append("    ").append(declaration.replace("\n", "\n    ")).append('\n');
        }
        out.append("}\n");
        return out.toString();
    }

    /**
     * Rewrites, within {@code method}'s declaration text, every unqualified call to a retained source method
     * ({@code callee(args)}) into a back-reference call ({@code owner.callee(args)}). Offsets come from javac call sites
     * scoped to the method body, applied right-to-left so earlier splices do not shift later ones.
     */
    private String rewriteRetainedCalls(SemanticIndex index, SemanticMethod method, String declaration,
            Set<String> retainedMethodNames) {
        if (retainedMethodNames.isEmpty() || method.bodyRange() == null) {
            return declaration;
        }
        int declStart = method.declarationRange().start();
        int bodyStart = method.bodyRange().start();
        int bodyEnd = method.bodyRange().end();
        // Collect (offset-into-declaration) name-token starts of unqualified retained-method calls, descending.
        List<Integer> insertOffsets = new ArrayList<>();
        for (String calleeName : retainedMethodNames) {
            SemanticMethod callee = resolveRetainedMethod(index, index.primaryType(method.file()), calleeName);
            if (callee == null) {
                continue;
            }
            for (SemanticCallSite site : index.methodCallSites(callee)) {
                if (!site.file().toAbsolutePath().normalize().equals(method.file().toAbsolutePath().normalize())) {
                    continue;
                }
                if (site.nameRange() == null || site.receiverRange() != null || site.methodReference()) {
                    // Only unqualified `callee(...)` calls (no explicit receiver) need the back-reference prefix.
                    continue;
                }
                int nameStart = site.nameRange().start();
                if (nameStart < bodyStart || nameStart >= bodyEnd) {
                    continue;
                }
                insertOffsets.add(nameStart - declStart);
            }
        }
        insertOffsets.sort((a, b) -> Integer.compare(b, a));
        StringBuilder sb = new StringBuilder(declaration);
        for (int offset : insertOffsets) {
            if (offset >= 0 && offset <= sb.length()) {
                sb.insert(offset, "owner.");
            }
        }
        return sb.toString();
    }

    private static String forwardingBody(String delegateField, SemanticMethod method) {
        String args = String.join(", ", method.parameters().stream().map(SemanticParameter::name).toList());
        String call = delegateField + "." + method.name() + "(" + args + ")";
        boolean isVoid = "void".equals(method.returnType().strip());
        String stmt = isVoid ? "        " + call + ";\n" : "        return " + call + ";\n";
        return "{\n" + stmt + "    }";
    }

    private static TextEdit deleteDeclaration(SemanticIndex.SourceRange range) {
        return new TextEdit(range.file(), range.start(), range.end(), "", "EXTRACT_CLASS_REMOVE_MEMBER");
    }

    private Path collaboratorFile(Path sourceFile, String sourcePackage, String targetPackage, String newClassName) {
        Path dir = sourceFile.getParent();
        if (!targetPackage.equals(sourcePackage)) {
            Path sourceRoot = sourceRoot(sourceFile.getParent(), sourcePackage);
            dir = targetPackage.isEmpty() ? sourceRoot : sourceRoot.resolve(targetPackage.replace('.', '/'));
        }
        return dir.resolve(newClassName + ".java");
    }

    private static Path sourceRoot(Path packageDir, String packageName) {
        Path root = packageDir;
        if (!packageName.isEmpty()) {
            for (int i = 0; i < packageName.split("\\.").length; i++) {
                root = root.getParent();
            }
        }
        return root;
    }

    private static String requireString(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ClassOpsRefusal("missing_field", key + " is required.");
        }
        return text;
    }

    private static String str(Map<String, Object> fields, String key, String fallback) {
        Object value = fields.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static boolean bool(Map<String, Object> fields, String key, boolean fallback) {
        Object value = fields.get(key);
        return value instanceof Boolean flag ? flag : fallback;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String text && !text.isBlank()) {
                    out.add(text);
                }
            }
        }
        return out;
    }
}
