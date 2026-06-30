package io.serena.javarefactor.v3.classops;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticType;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.PlannerSupport.TextEdit;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.shared.ImportConflictResolvers;
import io.serena.javarefactor.shared.ImportManager;
import io.serena.javarefactor.shared.ProjectPathResolver;
import io.serena.javarefactor.v3.transformation.TransformationStep;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * V3 compiler-backed <b>Replace Inheritance With Delegation</b> (refactor-feature-plan-V3.md §10). Turns
 * {@code class C extends Base} into {@code class C { private final Base base = new Base(); ... base.foo() }}: it removes
 * the {@code extends} clause, adds a delegate field, and synthesizes a forwarding method for each inherited public
 * instance method that clients rely on.
 *
 * <p>An {@code implements} clause on the subclass is <b>preserved</b> (only the {@code extends} relationship is severed),
 * and forwarder signatures reference simple type names with the required imports added (via the shared
 * {@link io.serena.javarefactor.shared.ImportManager}).
 *
 * <p><b>§10.3 refusals enforced:</b> no superclass to replace, a sealed superclass, a generic superclass (or subclass),
 * a superclass without an accessible no-argument constructor, a dependency on a {@code protected} superclass member
 * (genuinely unrepresentable through a delegate instance), an override that calls {@code super} (detected directly from
 * the element/AST model — the base's self-call dispatch cannot survive delegation), and a public-API change that the
 * caller has not confirmed ({@code confirmPublicApiChange}). The sidecar's before/after javac validation remains a
 * backstop for residual cases, not the primary mechanism for these design-named hazards.
 */
public final class ReplaceInheritanceWithDelegationPlanner {

    private static final Set<String> OBJECT_METHODS = Set.of(
            "toString", "hashCode", "equals", "getClass", "clone", "finalize", "wait", "notify", "notifyAll");

    private final Path projectRoot;
    private final JavaProjectModel model;

    public ReplaceInheritanceWithDelegationPlanner(Path projectRoot, JavaProjectModel model) {
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
            return PlannerSupport.refusalJson("replace_inheritance_failed",
                    "Replace inheritance with delegation failed: " + error.getMessage());
        }
    }

    /**
     * The pre-serialization structured result of a replace-inheritance plan: the in-place source edits (extends removal,
     * delegate field + forwarders, constructor/super-access rewrites), the replaced superclass's fully-qualified name,
     * and the forwarded-method count the standalone stats reports. This operation creates no files, so there is no file
     * operation. Shared by {@link #planChecked} (standalone JSON) and {@link #planStep} (workspace composition).
     */
    private record ReplaceInheritancePlan(List<TextEdit> edits, String superclassFqn, int forwardedMethods) {
    }

    private String planChecked(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        ReplaceInheritancePlan plan = compute(fields);
        return "{"
                + "\"accepted\":true,"
                + "\"operation\":\"replaceInheritanceWithDelegation\","
                + "\"superclass\":" + JsonUtil.quote(plan.superclassFqn()) + ","
                + "\"workspaceEdit\":{"
                + "\"changes\":" + PlannerSupport.changesJson(projectRoot, plan.edits()) + ","
                + "\"fileOperations\":[]"
                + "},"
                + "\"warnings\":" + PlannerSupport.warningsJson(List.of()) + ","
                + "\"riskFacts\":" + publicApiRiskFacts(plan.superclassFqn()) + ","
                + "\"stats\":{\"forwardedMethods\":" + plan.forwardedMethods() + "}"
                + "}";
    }

    /**
     * The structured workspace step (refactor-feature-plan-V3.md §3) for composing the delegation rewrite into a
     * transformation workspace: the in-place source edits and no file operations. Refusals surface as {@link
     * ClassOpsRefusal}/{@link ProjectPathResolver.Violation}, mapped to canonical refusal JSON by the caller.
     */
    public TransformationStep planStep(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        ReplaceInheritancePlan plan = compute(fields);
        return new TransformationStep(
                "replaceInheritanceWithDelegation", plan.edits(), List.of(), List.of(),
                "{\"operation\":\"replaceInheritanceWithDelegation\",\"superclass\":"
                        + JsonUtil.quote(plan.superclassFqn()) + "}",
                publicApiRiskFacts(plan.superclassFqn()));
    }

    private ReplaceInheritancePlan compute(Map<String, Object> fields)
            throws IOException, ProjectPathResolver.Violation {
        Path sourceFile = ProjectPathResolver.resolveProjectRelative(
                projectRoot, requireString(fields, "relativePath"), "relativePath");
        String relativePath = PlannerSupport.relative(projectRoot, sourceFile);
        List<String> selectedMembers = stringList(fields.get("members"));

        try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
            SemanticType subclass = index.primaryType(sourceFile);
            if (subclass == null || !(subclass.element() instanceof TypeElement subElement)) {
                throw new ClassOpsRefusal("source_type_not_found",
                        "Source file must contain a javac-resolved top-level class.");
            }
            if (!"class".equals(subclass.kind())) {
                throw new ClassOpsRefusal("unsupported_source_kind",
                        "Replace inheritance only supports a plain class (got " + subclass.kind() + ").");
            }
            if (!subElement.getTypeParameters().isEmpty()) {
                throw new ClassOpsRefusal("replace_inheritance_generic_subclass",
                        subclass.qualifiedName() + " is generic; replacing inheritance with delegation on a generic subclass is outside V3 scope (refused).");
            }

            TypeMirror superMirror = subElement.getSuperclass();
            if (superMirror == null || superMirror.getKind() != TypeKind.DECLARED) {
                throw new ClassOpsRefusal("replace_inheritance_no_superclass",
                        subclass.qualifiedName() + " has no superclass to replace.");
            }
            DeclaredType superDeclared = (DeclaredType) superMirror;
            if (!(superDeclared.asElement() instanceof TypeElement baseElement)) {
                throw new ClassOpsRefusal("replace_inheritance_no_superclass",
                        "Superclass of " + subclass.qualifiedName() + " could not be resolved.");
            }
            String baseQualified = baseElement.getQualifiedName().toString();
            if (baseQualified.equals("java.lang.Object")) {
                throw new ClassOpsRefusal("replace_inheritance_no_superclass",
                        subclass.qualifiedName() + " only extends Object; nothing to replace.");
            }
            // Optional caller-asserted superclass: when supplied, the javac-resolved direct superclass must match,
            // guarding against position/identity drift before any edit is composed.
            String expectedSuper = str(fields, "superclassFqn", null);
            if (expectedSuper != null && !expectedSuper.equals(baseQualified)) {
                throw new ClassOpsRefusal("replace_inheritance_superclass_mismatch",
                        "Expected direct superclass " + expectedSuper + " but " + subclass.qualifiedName()
                                + " directly extends " + baseQualified + ".");
            }
            if (baseElement.getModifiers().contains(Modifier.SEALED)) {
                throw new ClassOpsRefusal("replace_inheritance_sealed_superclass",
                        baseQualified + " is sealed; its hierarchy cannot be replaced with delegation.");
            }
            if (!superDeclared.getTypeArguments().isEmpty() || !baseElement.getTypeParameters().isEmpty()) {
                throw new ClassOpsRefusal("replace_inheritance_generic_superclass",
                        baseQualified + " is generic; a generic superclass is not representable as a delegate field "
                                + "and is outside V3 scope (refused).");
            }
            boolean baseHasNoArg = hasAccessibleNoArgConstructor(baseElement);

            // §10 deliverable: an `implements` clause on the subclass is PRESERVED, not refused. Only the `extends Base`
            // relationship is severed; the class keeps implementing its interfaces (the delegate forwarders re-expose
            // the inherited members those interfaces may require). The surgical extends-only removal is computed below.
            String tail = subclass.inheritanceTailRange().text(index);
            ExtendsClauseSpan extendsSpan = locateExtendsClause(tail, subclass.inheritanceTailRange().start());
            if (extendsSpan == null) {
                throw new ClassOpsRefusal("replace_inheritance_no_superclass",
                        subclass.qualifiedName() + " has no resolvable extends clause to replace.");
            }

            // §10.3 public-API control: severing `extends Base` removes the supertype from the subclass's public type
            // signature (an instance of the subclass is no longer assignable to Base, and inherited members vanish from
            // its public surface unless re-exposed). Per the plan this is a public-API change that is refused unless the
            // caller explicitly confirms it. Default: blocked.
            if (!confirmPublicApiChange(fields)) {
                throw new ClassOpsRefusal("replace_inheritance_public_api_change",
                        "Replacing inheritance on " + subclass.qualifiedName() + " drops supertype " + baseQualified
                                + " from its public API; re-run with confirmPublicApiChange=true to apply.");
            }

            // §10.2 steps 6–7: translate the subclass's super-construction into delegate construction and re-target
            // any super.member / super.method(...) accesses at the delegate. Refuses when the constructor shape cannot
            // build the delegate faithfully (e.g. a base with no no-arg constructor reached without an explicit super).
            SemanticIndex.DelegationAdaptationPlan adaptation =
                    index.planDelegationAdaptation(subclass, baseHasNoArg);
            if (!adaptation.resolved()) {
                throw new ClassOpsRefusal("replace_inheritance_constructor_unanalyzable",
                        "Could not analyze " + subclass.qualifiedName() + "'s constructors for delegation.");
            }
            if (adaptation.refused()) {
                throw new ClassOpsRefusal(adaptation.refusalCode(), adaptation.refusalMessage());
            }
            if (!baseHasNoArg && adaptation.superCallSpans().isEmpty()) {
                throw new ClassOpsRefusal("replace_inheritance_base_constructor_args",
                        baseQualified + " has no accessible no-argument constructor and " + subclass.qualifiedName()
                                + " never chains to super(...); delegate construction is unsafe.");
            }

            // §10.3 hazard: a subclass that depends on a `protected` member (field or method) inherited from the
            // superclass cannot delegate soundly — protected access is granted through inheritance, so after the
            // `extends` is severed a `delegate.protectedMember` reference from this (now-unrelated) class would not
            // compile. This is a genuinely unrepresentable case, so it is refused rather than silently rewritten.
            String protectedMember = index.firstProtectedSuperclassDependency(subclass, baseElement);
            if (protectedMember != null) {
                throw new ClassOpsRefusal("replace_inheritance_protected_member_dependency",
                        subclass.qualifiedName() + " depends on protected superclass member '" + protectedMember
                                + "'; delegation cannot expose it soundly.");
            }

            // §10.3 hazard, detected directly (not deferred to the javac backstop): a subclass method that OVERRIDES a
            // base method and calls super.method(...) cannot be delegated soundly. After `extends` is severed the
            // override becomes an ordinary method and its super.method(...) is retargeted at the delegate, but the
            // delegate (a plain base instance) dispatches its own methods internally — so the override no longer
            // participates in the base's self-/template-call path and behavior changes silently. Refuse, with a
            // design-named code, before composing any edit.
            String overrideCallingSuper = index.firstOverrideThatCallsSuper(subclass, baseElement);
            if (overrideCallingSuper != null) {
                throw new ClassOpsRefusal("replace_inheritance_override_calls_super",
                        subclass.qualifiedName() + " overrides '" + overrideCallingSuper + "' and calls super; "
                                + "delegation cannot preserve the base's self-call dispatch (refused).");
            }

            String delegateField = str(fields, "delegateFieldName",
                    ClassOpsSupport.decapitalize(baseElement.getSimpleName().toString()));

            List<ExecutableElement> forwarded = inheritedForwardableMethods(subElement, baseElement, selectedMembers);

            // §10.2 step 8: generate forwarders that reference SIMPLE type names and add the imports they require, rather
            // than emitting fully-qualified type names. The shared ImportManager (the same import-normalization engine the
            // change-signature / extract-interface / move planners use) plans each type reference in the file's own import
            // style, simplifying it and emitting the needed import edits; a type whose simple name would collide is left
            // fully qualified. The conflict resolver consults same-package/project facts the import section cannot see.
            String wholeSource = readSource(sourceFile);
            ImportManager importPlanner = new ImportManager(wholeSource)
                    .withConflictResolver(ImportConflictResolvers.samePackageAndProject(
                            index, sourceFile, subclass.packageName()));
            List<TextEdit> importEdits = new ArrayList<>();
            String baseReference = renderType(importPlanner, sourceFile, baseQualified, importEdits);

            StringBuilder body = new StringBuilder();
            body.append("\n    private final ").append(baseReference).append(' ').append(delegateField);
            if (adaptation.fieldHasInitializer()) {
                body.append(" = new ").append(baseReference).append("()");
            }
            body.append(";\n");
            for (ExecutableElement method : forwarded) {
                body.append('\n').append(renderForwardingMethod(method, delegateField, importPlanner, sourceFile, importEdits));
            }

            List<TextEdit> edits = new ArrayList<>();
            // §10.2 step 5: sever ONLY the `extends Base` clause, leaving any `implements` clause (and the type
            // parameters / surrounding layout) intact. The span was located within the proven inheritance-tail range.
            edits.add(new TextEdit(sourceFile, extendsSpan.start(), extendsSpan.end(), extendsSpan.replacement(),
                    "REPLACE_INHERITANCE_REMOVE_EXTENDS"));
            int brace = subclass.bodyRange().start();
            edits.add(new TextEdit(sourceFile, brace + 1, brace + 1, body.toString(),
                    "REPLACE_INHERITANCE_DELEGATE"));
            edits.addAll(importEdits);

            // Constructor adaptation: explicit super(args) -> delegate construction; implicit super() constructors
            // assign the delegate at body start when the field carries no inline initializer.
            List<long[]> superCallSpans = adaptation.superCallSpans();
            List<String> superCallArguments = adaptation.superCallArguments();
            for (int i = 0; i < superCallSpans.size(); i++) {
                long[] span = superCallSpans.get(i);
                String assignment = "this." + delegateField + " = new " + baseReference
                        + "(" + superCallArguments.get(i) + ");";
                edits.add(new TextEdit(sourceFile, (int) span[0], (int) span[1], assignment,
                        "REPLACE_INHERITANCE_SUPER_CTOR"));
            }
            for (int offset : adaptation.implicitInsertOffsets()) {
                edits.add(new TextEdit(sourceFile, offset, offset,
                        "\n        this." + delegateField + " = new " + baseReference + "();",
                        "REPLACE_INHERITANCE_IMPLICIT_CTOR"));
            }
            // super.member / super.method(...) -> delegate.member / delegate.method(...)
            for (long[] span : adaptation.superMemberSpans()) {
                edits.add(new TextEdit(sourceFile, (int) span[0], (int) span[1], delegateField,
                        "REPLACE_INHERITANCE_SUPER_ACCESS"));
            }
            // Drop @Override annotations that referred to the now-removed base (no longer override anything).
            for (long[] span : adaptation.overrideAnnotationSpans()) {
                edits.add(new TextEdit(sourceFile, (int) span[0], (int) span[1], "",
                        "REPLACE_INHERITANCE_DROP_OVERRIDE"));
            }

            return new ReplaceInheritancePlan(edits, baseQualified, forwarded.size());
        }
    }

    /**
     * Collects every public instance method that the subclass currently inherits and that clients can rely on — walking
     * the <b>full superclass chain</b> (the immediate base and all of its ancestors up to, but excluding, {@code Object}),
     * not just the methods declared directly on the immediate base. A method declared lower in the chain shadows an
     * inherited one with the same erased signature, so each forwarding method is emitted exactly once with its
     * most-derived declaration. Without this, replacing {@code Child extends Mid} (where {@code Mid extends Grand})
     * would silently drop {@code Grand}'s public API from {@code Child}.
     */
    private List<ExecutableElement> inheritedForwardableMethods(
            TypeElement subElement, TypeElement baseElement, List<String> selected) {
        List<ExecutableElement> result = new ArrayList<>();
        Set<String> seenSignatures = new HashSet<>();
        // Pre-seed with the subclass's own instance methods: a method the subclass already declares (an override of a
        // base method, or a new method that happens to share a signature) must NOT receive a forwarder — the existing
        // declaration stays in place (its super.x(...) calls are redirected to the delegate separately).
        for (Element enclosed : subElement.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement method && method.getKind() == ElementKind.METHOD
                    && !method.getModifiers().contains(Modifier.STATIC)) {
                seenSignatures.add(signatureKey(method));
            }
        }
        for (TypeElement current = baseElement; current != null; current = superclassElement(current)) {
            if (current.getQualifiedName().contentEquals("java.lang.Object")) {
                break;
            }
            for (Element enclosed : current.getEnclosedElements()) {
                if (!(enclosed instanceof ExecutableElement method) || method.getKind() != ElementKind.METHOD) {
                    continue;
                }
                Set<Modifier> modifiers = method.getModifiers();
                if (!modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.STATIC)
                        || modifiers.contains(Modifier.ABSTRACT)) {
                    continue;
                }
                if (!method.getTypeParameters().isEmpty()) {
                    throw new ClassOpsRefusal(
                            "replace_inheritance_generic_method",
                            "Inherited generic method "
                                    + method.getSimpleName()
                                    + " from "
                                    + method.getEnclosingElement()
                                    + " cannot be forwarded safely.");
                }
                String name = method.getSimpleName().toString();
                if (OBJECT_METHODS.contains(name)) {
                    continue;
                }
                if (!selected.isEmpty() && !selected.contains(name) && !selected.contains("method:" + name)) {
                    continue;
                }
                if (!seenSignatures.add(signatureKey(method))) {
                    continue; // a more-derived class already provided this signature (override shadows the ancestor)
                }
                result.add(method);
            }
        }
        return result;
    }

    /** The superclass of {@code type} as a {@link TypeElement}, or {@code null} when it is {@code Object}/unresolved. */
    private static TypeElement superclassElement(TypeElement type) {
        TypeMirror superMirror = type.getSuperclass();
        if (superMirror == null || superMirror.getKind() != TypeKind.DECLARED) {
            return null;
        }
        return ((DeclaredType) superMirror).asElement() instanceof TypeElement element ? element : null;
    }

    /** Name + erased parameter types: identifies a method across the chain so overrides collapse to one forwarder. */
    private static String signatureKey(ExecutableElement method) {
        StringBuilder key = new StringBuilder(method.getSimpleName().toString()).append('(');
        for (VariableElement parameter : method.getParameters()) {
            key.append(parameter.asType().toString()).append(',');
        }
        return key.append(')').toString();
    }

    private static String renderForwardingMethod(
            ExecutableElement method, String delegateField, ImportManager importPlanner, Path sourceFile,
            List<TextEdit> importEdits) {
        String name = method.getSimpleName().toString();
        boolean isVoid = method.getReturnType().getKind() == TypeKind.VOID;
        // §10.2 step 8: every type that appears in the forwarder's signature is rendered with its simple name (importing
        // it as needed) rather than fully qualified, so the synthesized method matches the file's import-using style.
        String returnType = renderType(importPlanner, sourceFile, method.getReturnType().toString(), importEdits);

        List<String> paramDecls = new ArrayList<>();
        List<String> argNames = new ArrayList<>();
        int index = 0;
        for (VariableElement parameter : method.getParameters()) {
            String paramName = parameter.getSimpleName().toString();
            if (paramName.isEmpty()) {
                paramName = "arg" + index;
            }
            String paramType = renderType(importPlanner, sourceFile, parameter.asType().toString(), importEdits);
            paramDecls.add(paramType + " " + paramName);
            argNames.add(paramName);
            index++;
        }

        StringBuilder throwsClause = new StringBuilder();
        List<? extends TypeMirror> thrown = method.getThrownTypes();
        if (!thrown.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (TypeMirror type : thrown) {
                names.add(renderType(importPlanner, sourceFile, type.toString(), importEdits));
            }
            throwsClause.append(" throws ").append(String.join(", ", names));
        }

        String call = delegateField + "." + name + "(" + String.join(", ", argNames) + ")";
        String invocation = isVoid ? "        " + call + ";\n" : "        return " + call + ";\n";
        return "    public " + returnType + " " + name + "(" + String.join(", ", paramDecls) + ")"
                + throwsClause + " {\n" + invocation + "    }\n";
    }

    /**
     * Renders a type reference using simple names where safe, accumulating the import edits it requires. Delegates to the
     * shared {@link ImportManager} type-usage engine (deduping repeated import edits across the field and every forwarder
     * so the same {@code import} line is never emitted twice). A type whose simple name collides is left fully qualified.
     */
    private static String renderType(
            ImportManager importPlanner, Path sourceFile, String type, List<TextEdit> importEdits) {
        ImportManager.TypeUse use = importPlanner.planTypeUsageDeep(sourceFile, type, "REPLACE_INHERITANCE_IMPORT");
        for (TextEdit edit : use.importEdits()) {
            if (!importEdits.contains(edit)) {
                importEdits.add(edit);
            }
        }
        return use.renderedType();
    }

    /** Reads the whole source file so the {@link ImportManager} can model its package, imports, and used simple names. */
    private static String readSource(Path sourceFile) throws IOException {
        return java.nio.file.Files.readString(sourceFile);
    }

    private static String publicApiRiskFacts(String superclassFqn) {
        return "{\"publicApiChanges\":["
                + JsonUtil.quote("Dropping supertype '" + superclassFqn + "' from the subclass public API.")
                + "]}";
    }


    /** Whether the caller explicitly confirmed the public-API change of dropping the supertype (§10.3). */
    private static boolean confirmPublicApiChange(Map<String, Object> fields) {
        return boolField(fields, "confirmPublicApiChange") || boolField(fields, "confirmPublicApi");
    }

    private static boolean boolField(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * The absolute span of the {@code extends Base} clause inside a class header and the text to replace it with, so the
     * clause can be severed WITHOUT disturbing a co-located {@code implements} clause. When the class also implements
     * interfaces, the {@code extends} clause is removed up to (but not including) the top-level {@code implements}
     * keyword, collapsing the whitespace to a single space; otherwise the whole {@code extends} run is replaced by a
     * single space. Returns {@code null} when no top-level {@code extends} keyword is present.
     */
    private record ExtendsClauseSpan(int start, int end, String replacement) {
    }

    /**
     * Locates the {@code extends Base[, ...]} clause within a class's inheritance-tail text (the source between the class
     * name and its opening brace). Angle-bracket depth is tracked so an {@code extends} inside a generic bound is never
     * mistaken for the inheritance clause. {@code tailStart} is the tail text's absolute offset in the source file.
     */
    private static ExtendsClauseSpan locateExtendsClause(String tailText, int tailStart) {
        int extendsAt = topLevelKeywordIndex(tailText, "extends");
        if (extendsAt < 0) {
            return null;
        }
        int implementsAt = topLevelKeywordIndex(tailText, "implements");
        int clauseEnd = implementsAt > extendsAt ? implementsAt : tailText.length();
        // Replace the extends run with a single space so the header keeps a clean separator before `{` or `implements`.
        return new ExtendsClauseSpan(tailStart + extendsAt, tailStart + clauseEnd, " ");
    }

    /** Index of the first whole-word {@code keyword} at angle-bracket depth zero in {@code text}, or {@code -1}. */
    private static int topLevelKeywordIndex(String text, String keyword) {
        int depth = 0;
        for (int i = 0; i + keyword.length() <= text.length(); i++) {
            char current = text.charAt(i);
            if (current == '<') {
                depth++;
                continue;
            }
            if (current == '>') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth != 0 || !text.regionMatches(i, keyword, 0, keyword.length())) {
                continue;
            }
            boolean leftBoundary = i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1));
            int after = i + keyword.length();
            boolean rightBoundary = after >= text.length() || !Character.isJavaIdentifierPart(text.charAt(after));
            if (leftBoundary && rightBoundary) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasAccessibleNoArgConstructor(TypeElement baseElement) {
        boolean sawConstructor = false;
        for (Element enclosed : baseElement.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement ctor && ctor.getKind() == ElementKind.CONSTRUCTOR) {
                sawConstructor = true;
                if (ctor.getParameters().isEmpty() && !ctor.getModifiers().contains(Modifier.PRIVATE)) {
                    return true;
                }
            }
        }
        return !sawConstructor; // no explicit constructor => implicit accessible no-arg constructor
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
