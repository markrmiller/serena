package io.serena.javarefactor.v3.classops;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticField;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticMethod;
import io.serena.javarefactor.compiler.SemanticIndex.SemanticType;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.PlannerSupport.TextEdit;
import io.serena.javarefactor.edits.ResponseBuilder.FileOperation;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.shared.ProjectPathResolver;
import io.serena.javarefactor.v3.transformation.TransformationStep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * V3 compiler-backed <b>Extract Superclass</b> (refactor-feature-plan-V3.md §9). Hoists members that are common to a set
 * of sibling classes into a brand-new abstract superclass and points each sibling's {@code extends} clause at it.
 *
 * <p><b>§9.4 interposition rule:</b> every selected class must occupy the same inheritance position — all extend
 * {@code java.lang.Object}, or all extend one identical non-Object superclass (which the new superclass is then slotted
 * beneath, copying that superclass into the generated {@code extends} clause). A mix of direct superclasses is refused
 * ({@code extract_superclass_existing_superclass}). Each subclass's own {@code implements} clause is preserved.
 *
 * <p>Hoisted members must be non-private, except an initializer-less private field, which is moved wholesale together
 * with generated constructor wiring: a {@code protected} superclass constructor receives the fields and each subclass
 * constructor is rewritten to forward them via {@code super(...)}. Constructor propagation is not combined with a
 * pre-existing common superclass ({@code extract_superclass_constructor_with_existing_super}).
 */
public final class ExtractSuperclassPlanner {

    private static final Pattern EXTENDS_PATTERN = Pattern.compile("\\bextends\\b");
    private static final Pattern IMPLEMENTS_PATTERN = Pattern.compile("\\bimplements\\b");
    private static final Pattern ABSTRACT_MODIFIER = Pattern.compile("\\babstract\\b");
    private static final Pattern ACCESS_MODIFIER = Pattern.compile("\\b(public|protected|private)\\b");
    private static final Pattern OVERRIDE_ANNOTATION = Pattern.compile("@\\s*Override\\b");

    private final Path projectRoot;
    private final JavaProjectModel model;

    public ExtractSuperclassPlanner(Path projectRoot, JavaProjectModel model) {
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
            return PlannerSupport.refusalJson("extract_superclass_failed",
                    "Extract superclass failed: " + error.getMessage());
        }
    }

    /**
     * The pre-serialization structured result of an extract-superclass plan: the subclass-rewrite text edits, the new
     * abstract superclass file (project-relative path + content) emitted as a CREATE file operation, the superclass's
     * fully-qualified name, and the counts the standalone stats reports. Shared by {@link #planChecked} (standalone JSON)
     * and {@link #planStep} (workspace composition) so both carry the identical edits and create.
     */
    private record ExtractSuperclassPlan(List<TextEdit> edits, String superclassRelative, String superclassSource,
                                         String superclassFqn, int classes, int members, List<String> warnings) {
    }

    private String planChecked(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        ExtractSuperclassPlan plan = compute(fields);
        String statsJson = "{\"classes\":" + plan.classes() + ",\"members\":" + plan.members() + "}";
        return "{"
                + "\"accepted\":true,"
                + "\"operation\":\"extractSuperclass\","
                + "\"superclass\":" + JsonUtil.quote(plan.superclassFqn()) + ","
                + "\"workspaceEdit\":{"
                + "\"changes\":" + PlannerSupport.changesJson(projectRoot, plan.edits()) + ","
                + "\"fileOperations\":[" + PlannerSupport.createFileOp(plan.superclassRelative(), plan.superclassSource()) + "]"
                + "},"
                + "\"warnings\":" + PlannerSupport.warningsJson(plan.warnings()) + ","
                + "\"riskFacts\":{\"publicApiChanges\":" + PlannerSupport.warningsJson(plan.warnings()) + "},"
                + "\"stats\":" + statsJson
                + "}";
    }

    /**
     * The structured workspace step (refactor-feature-plan-V3.md §3) for composing the extraction into a transformation
     * workspace: the subclass rewrites plus the new superclass as a real {@link FileOperation#create}. Refusals surface as
     * {@link ClassOpsRefusal}/{@link ProjectPathResolver.Violation}, mapped to canonical refusal JSON by the caller.
     */
    public TransformationStep planStep(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        ExtractSuperclassPlan plan = compute(fields);
        return new TransformationStep(
                "extractSuperclass", plan.edits(),
                List.of(FileOperation.create(plan.superclassRelative(), plan.superclassSource())),
                plan.warnings(),
                "{\"operation\":\"extractSuperclass\",\"superclass\":" + JsonUtil.quote(plan.superclassFqn()) + "}",
                "{\"publicApiChanges\":" + PlannerSupport.warningsJson(plan.warnings()) + "}");
    }

    private ExtractSuperclassPlan compute(Map<String, Object> fields)
            throws IOException, ProjectPathResolver.Violation {
        String superclassName = requireString(fields, "superclassName");
        List<String> classPaths = stringList(fields.get("classes"));
        if (classPaths.isEmpty()) {
            throw new ClassOpsRefusal("insufficient_classes",
                    "Extract superclass requires at least one source class.");
        }
        List<String> memberSelectors = stringList(fields.get("members"));
        refuseDivergentSelectedMemberText(classPaths, memberSelectors);
        if (memberSelectors.isEmpty()) {
            throw new ClassOpsRefusal("no_members", "Extract superclass requires at least one member selector.");
        }

        List<Path> sourceFiles = new ArrayList<>();
        for (String classPath : classPaths) {
            sourceFiles.add(resolveClassInput(classPath));
        }
        String representative = PlannerSupport.relative(projectRoot, sourceFiles.get(0));

        try (SemanticIndex index = SemanticIndex.open(model, representative)) {
            List<SemanticType> types = new ArrayList<>();
            for (Path file : sourceFiles) {
                SemanticType type = index.primaryType(file);
                if (type == null) {
                    throw new ClassOpsRefusal("source_type_not_found",
                            "File " + PlannerSupport.relative(projectRoot, file) + " has no resolvable top-level type.");
                }
                if (!"class".equals(type.kind())) {
                    throw new ClassOpsRefusal("unsupported_source_kind",
                            "Extract superclass only supports plain classes (got " + type.kind() + ").");
                }
                types.add(type);
            }

            // §9.4 interposition rule: every selected class must occupy the SAME inheritance position — all extend
            // java.lang.Object, or all extend one identical non-Object superclass (which the new superclass is then
            // slotted beneath). A mix, or divergent superclasses, is refused: a single new superclass cannot be
            // inserted into different hierarchies without per-class verification.
            Set<String> existingSupers = new LinkedHashSet<>();
            for (SemanticType type : types) {
                String qn = existingSuperQualifiedName(type);
                existingSupers.add(qn == null ? "" : qn);
            }
            if (existingSupers.size() > 1) {
                throw new ClassOpsRefusal("extract_superclass_existing_superclass",
                        "Selected classes do not share a common direct superclass " + existingSupers
                                + "; refused to avoid an unverified hierarchy change.");
            }
            boolean hasCommonSuper = existingSupers.size() == 1 && !existingSupers.iterator().next().isEmpty();

            SemanticType anchor = types.get(0);
            String targetPackage = str(fields, "targetPackage", anchor.packageName());

            // R10 (refactor-feature-plan-V3.md §9): collision preflight BEFORE building a plan, so a target-name / file /
            // type clash surfaces as an operation-specific structured refusal — not a generic applier file-op staging
            // failure after an otherwise "accepted" plan.
            String superclassFqn = (targetPackage.isEmpty() ? "" : targetPackage + ".") + superclassName;
            Path superclassFile = superclassFile(anchor.file(), anchor.packageName(), targetPackage, superclassName);
            String superclassRelative = PlannerSupport.relative(projectRoot, superclassFile);
            for (SemanticType type : types) {
                if (superclassFqn.equals(type.qualifiedName())) {
                    throw new ClassOpsRefusal("extract_superclass_target_is_source",
                            "New superclass '" + superclassFqn + "' is one of the selected classes; choose a different"
                                    + " superclassName or targetPackage.");
                }
            }
            if (index.typeExists(superclassFqn)) {
                throw new ClassOpsRefusal("extract_superclass_target_type_exists",
                        "A type named '" + superclassFqn + "' already exists; extract superclass would collide with it.");
            }
            if (Files.exists(superclassFile)) {
                throw new ClassOpsRefusal("extract_superclass_target_file_exists",
                        "Target file '" + superclassRelative + "' already exists; extract superclass would overwrite it.");
            }

            String anchorSource = String.valueOf(index.sourceText(anchor.file()));
            // When all classes already extend a common superclass S, the new superclass is interposed beneath S; copy
            // S's exact source spelling (and the anchor's imports) so the generated file resolves it as the siblings do.
            String superExtendsClause = hasCommonSuper ? parseExtends(anchor.inheritanceTailRange().text(index)) : null;

            // §9.2: when make_abstract is requested, hoisted methods become ABSTRACT declarations in the new superclass
            // while each subclass KEEPS its concrete override (annotated @Override). Fields ignore the flag — they are
            // always pulled up wholesale.
            boolean makeAbstract = boolField(fields, "makeAbstract", true);

            // Hoist member text from the anchor; verify every member exists in every selected class. Collect any hoisted
            // field with no initializer — it must be supplied through a constructor, driving constructor propagation.
            // For abstracted methods, the concrete declaration stays in each subclass and only gets an @Override prefix,
            // so the per-class removal list is left untouched for those members.
            List<String> hoisted = new ArrayList<>();
            List<List<SemanticIndex.SourceRange>> removalsPerClass = new ArrayList<>();
            List<List<Integer>> overrideInsertsPerClass = new ArrayList<>();
            for (SemanticType type : types) {
                removalsPerClass.add(new ArrayList<>());
                overrideInsertsPerClass.add(new ArrayList<>());
            }
            List<SemanticField> noInitFields = new ArrayList<>();
            List<String> noInitFieldNames = new ArrayList<>();
            boolean anyConcreteMemberHoisted = false;
            boolean anyFieldHoisted = false;
            for (String raw : memberSelectors) {
                ClassOpsSupport.Selector selector = ClassOpsSupport.parseSelector(raw);
                boolean abstractMethod = makeAbstract && !selector.isField();
                for (int i = 0; i < types.size(); i++) {
                    if (abstractMethod) {
                        // The concrete method stays in the subclass; resolve it (still verifying it exists on every
                        // selected class) and record an @Override insertion at its declaration start.
                        SemanticMethod method = ClassOpsSupport.resolveMethod(index, types.get(i), selector);
                        if (method.isPrivate()) {
                            throw new ClassOpsRefusal("extract_superclass_private_member",
                                    "Method '" + method.name() + "' is private; widen it before hoisting.");
                        }
                        if (method.bodyRange() == null) {
                            throw new ClassOpsRefusal("extract_superclass_abstract_member",
                                    "Method '" + method.name() + "' has no body; only concrete methods can be"
                                            + " hoisted as abstract declarations.");
                        }
                        if (i == 0) {
                            hoisted.add(abstractDeclaration(method.headerRange().text(index)));
                        }
                        if (!hasOverrideAnnotation(index, types.get(i), method)) {
                            overrideInsertsPerClass.get(i).add(method.declarationRange().start());
                        }
                    } else {
                        SemanticIndex.SourceRange range = resolveMemberRange(index, types.get(i), selector);
                        removalsPerClass.get(i).add(range);
                        if (i == 0) {
                            hoisted.add(range.text(index).strip());
                        }
                    }
                }
                if (selector.isField()) {
                    anyFieldHoisted = true;
                    SemanticField anchorField = index.fieldByName(anchor.file(), selector.name());
                    if (anchorField != null && anchorField.initializerRange() == null) {
                        noInitFields.add(anchorField);
                        noInitFieldNames.add(anchorField.name());
                    }
                } else if (!abstractMethod) {
                    anyConcreteMemberHoisted = true;
                }
            }

            // Constructor propagation: hoisted initializer-less fields are supplied through a generated superclass
            // constructor, with each subclass constructor rewritten to forward them via super(...).
            List<SemanticIndex.SuperclassConstructorPlan> ctorPlans = new ArrayList<>();
            String generatedConstructor = null;
            if (!noInitFields.isEmpty()) {
                if (hasCommonSuper) {
                    throw new ClassOpsRefusal("extract_superclass_constructor_with_existing_super",
                            "Constructor-propagated field hoisting into a superclass interposed beneath "
                                    + superExtendsClause + " is not supported; the generated super(...) chain to the"
                                    + " existing superclass cannot be verified.");
                }
                for (SemanticType type : types) {
                    SemanticIndex.SuperclassConstructorPlan ctorPlan =
                            index.planSuperclassConstructorPropagation(type, noInitFieldNames);
                    if (!ctorPlan.resolved()) {
                        throw new ClassOpsRefusal("extract_superclass_constructor_unanalyzable",
                                "Could not analyze " + type.qualifiedName() + "'s constructor for field hoisting.");
                    }
                    if (ctorPlan.refused()) {
                        throw new ClassOpsRefusal(ctorPlan.refusalCode(), ctorPlan.refusalMessage());
                    }
                    ctorPlans.add(ctorPlan);
                }
                generatedConstructor = synthesizeConstructor(superclassName, noInitFields);
            }

            String superclassSource = synthesizeSuperclass(superclassName, targetPackage, anchorSource, hoisted,
                    superExtendsClause, generatedConstructor);
            String extendsType = targetPackage.equals(anchor.packageName())
                    ? superclassName : targetPackage + "." + superclassName;

            List<TextEdit> edits = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                SemanticType type = types.get(i);
                for (SemanticIndex.SourceRange range : removalsPerClass.get(i)) {
                    edits.add(new TextEdit(range.file(), range.start(), range.end(), "",
                            "EXTRACT_SUPERCLASS_REMOVE_MEMBER"));
                }
                // Abstracted methods stay concrete in the subclass and gain an @Override so the compiler verifies they
                // actually implement the new superclass's abstract declaration.
                for (int offset : overrideInsertsPerClass.get(i)) {
                    edits.add(new TextEdit(type.file(), offset, offset, "@Override\n    ",
                            "EXTRACT_SUPERCLASS_OVERRIDE"));
                }
                // Replace the whole inheritance tail so an existing `extends S` is swapped for the new superclass while
                // any `implements` clause on the subclass is preserved verbatim.
                SemanticIndex.SourceRange tail = type.inheritanceTailRange();
                String implementsText = parseImplements(tail.text(index));
                String newTail = " extends " + extendsType
                        + (implementsText.isEmpty() ? " " : " implements " + implementsText + " ");
                edits.add(new TextEdit(type.file(), tail.start(), tail.end(), newTail, "EXTRACT_SUPERCLASS_EXTENDS"));

                if (!ctorPlans.isEmpty()) {
                    SemanticIndex.SuperclassConstructorPlan ctorPlan = ctorPlans.get(i);
                    for (long[] span : ctorPlan.assignmentSpansToRemove()) {
                        edits.add(new TextEdit(type.file(), (int) span[0], (int) span[1], "",
                                "EXTRACT_SUPERCLASS_REMOVE_CTOR_INIT"));
                    }
                    String superCall = "\n        super(" + String.join(", ", ctorPlan.superArguments()) + ");";
                    edits.add(new TextEdit(type.file(), ctorPlan.superInsertOffset(), ctorPlan.superInsertOffset(),
                            superCall, "EXTRACT_SUPERCLASS_SUPER_CALL"));
                }
            }

            // §9 interface alternative: a superclass that carries no state and no concrete behaviour (only abstract
            // method declarations) is better expressed as an interface. Surface this as a suggestion — extraction still
            // proceeds — so the caller can reconsider when no fields, no concrete methods, and no constructor are hoisted.
            List<String> warnings = new ArrayList<>();
        warnings.add("public_api_hierarchy_change: extracting superclass '" + superclassFqn
                + "' changes the published type/member hierarchy and requires review before apply.");
            if (!anyFieldHoisted && !anyConcreteMemberHoisted && generatedConstructor == null && !hasCommonSuper) {
                warnings.add("interface_alternative_suggested: the extracted superclass '" + superclassName
                        + "' declares only abstract methods and holds no state; consider extracting an interface instead.");
            }

            return new ExtractSuperclassPlan(edits, superclassRelative, superclassSource, superclassFqn,
                    types.size(), memberSelectors.size(), warnings);
        }
    }

    private SemanticIndex.SourceRange resolveMemberRange(SemanticIndex index, SemanticType type,
            ClassOpsSupport.Selector selector) {
        if (selector.isField()) {
            SemanticField field = index.fieldByName(type.file(), selector.name());
            if (field == null) {
                throw new ClassOpsRefusal("extract_superclass_member_not_common",
                        "Field '" + selector.name() + "' is not present on " + type.qualifiedName() + ".");
            }
            // A private field that carries an initializer must be widened first (it would lose visibility once moved).
            // A private field with NO initializer is hoisted wholesale with generated constructor wiring, so the
            // subclass never references it directly — its access modifier is irrelevant and need not be widened.
            if (field.isPrivate() && field.initializerRange() != null) {
                throw new ClassOpsRefusal("extract_superclass_private_member",
                        "Field '" + field.name() + "' is private; widen it before hoisting.");
            }
            return field.declarationRange();
        }
        SemanticMethod method = ClassOpsSupport.resolveMethod(index, type, selector);
        if (method.isPrivate()) {
            throw new ClassOpsRefusal("extract_superclass_private_member",
                    "Method '" + method.name() + "' is private; widen it before hoisting.");
        }
        if (method.bodyRange() == null) {
            throw new ClassOpsRefusal("extract_superclass_abstract_member",
                    "Method '" + method.name() + "' has no body; pulling a bodiless method up requires make_abstract=true (an abstract declaration).");
        }
        return method.declarationRange();
    }

    private String synthesizeSuperclass(String superclassName, String targetPackage, String anchorSource,
            List<String> hoisted, String superExtendsClause, String generatedConstructor) {
        StringBuilder out = new StringBuilder();
        if (!targetPackage.isEmpty()) {
            out.append("package ").append(targetPackage).append(";\n\n");
        }
        Set<String> imports = new LinkedHashSet<>(ClassOpsSupport.importLines(anchorSource));
        for (String imp : imports) {
            out.append(imp).append('\n');
        }
        if (!imports.isEmpty()) {
            out.append('\n');
        }
        out.append("public abstract class ").append(superclassName);
        if (superExtendsClause != null && !superExtendsClause.isEmpty()) {
            out.append(" extends ").append(superExtendsClause);
        }
        out.append(" {\n");
        for (int i = 0; i < hoisted.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append("    ").append(hoisted.get(i).replace("\n", "\n    ")).append('\n');
        }
        if (generatedConstructor != null) {
            if (!hoisted.isEmpty()) {
                out.append('\n');
            }
            out.append(generatedConstructor);
        }
        out.append("}\n");
        return out.toString();
    }

    /**
     * Builds the generated superclass constructor that receives the hoisted initializer-less fields and assigns them.
     * It is {@code protected} so only subclasses can chain to it via {@code super(...)}.
     */
    private String synthesizeConstructor(String superclassName, List<SemanticField> fields) {
        StringBuilder params = new StringBuilder();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            SemanticField field = fields.get(i);
            if (i > 0) {
                params.append(", ");
            }
            params.append(field.type()).append(' ').append(field.name());
            body.append("        this.").append(field.name()).append(" = ").append(field.name()).append(";\n");
        }
        return "    protected " + superclassName + "(" + params + ") {\n" + body + "    }\n";
    }

    /**
     * Renders an abstract method declaration from a concrete method's header text (modifiers + return type + name +
     * parameter list, as javac spelled it). Leading annotations (e.g. {@code @Override}) are dropped — they belong on
     * the concrete subclass override, not the abstract declaration — the {@code abstract} modifier is injected if absent,
     * and the declaration is terminated with a semicolon.
     */
    private static String abstractDeclaration(String headerText) {
        String header = stripLeadingAnnotations(headerText).strip();
        if (!ABSTRACT_MODIFIER.matcher(header).find()) {
            Matcher accessMatcher = ACCESS_MODIFIER.matcher(header);
            if (accessMatcher.find()) {
                header = header.substring(0, accessMatcher.end()) + " abstract" + header.substring(accessMatcher.end());
            } else {
                header = "abstract " + header;
            }
        }
        return header + ";";
    }

    /** Strips any run of leading annotations (each {@code @Name...} token, including its arguments) from a declaration. */
    private static String stripLeadingAnnotations(String text) {
        String remaining = text.strip();
        while (remaining.startsWith("@")) {
            int cursor = 1;
            while (cursor < remaining.length() && (Character.isJavaIdentifierPart(remaining.charAt(cursor))
                    || remaining.charAt(cursor) == '.')) {
                cursor++;
            }
            // Skip a parenthesised annotation argument list, honouring nesting.
            while (cursor < remaining.length() && Character.isWhitespace(remaining.charAt(cursor))) {
                cursor++;
            }
            if (cursor < remaining.length() && remaining.charAt(cursor) == '(') {
                int depth = 0;
                while (cursor < remaining.length()) {
                    char ch = remaining.charAt(cursor++);
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                }
            }
            remaining = remaining.substring(cursor).strip();
        }
        return remaining;
    }

    /** True when a method's javac-spelled header already carries an {@code @Override} annotation. */
    private static boolean hasOverrideAnnotation(SemanticIndex index, SemanticType type, SemanticMethod method) {
        String header = method.headerRange().text(index);
        return OVERRIDE_ANNOTATION.matcher(header).find();
    }

    private static boolean boolField(Map<String, Object> fields, String key, boolean fallback) {
        Object value = fields.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    /** Compiler-resolved qualified name of the class's direct superclass, or {@code null} when it is java.lang.Object. */
    private static String existingSuperQualifiedName(SemanticType type) {
        if (!(type.element() instanceof TypeElement element)) {
            return null;
        }
        TypeMirror superclass = element.getSuperclass();
        if (superclass == null || superclass.getKind() != TypeKind.DECLARED) {
            return null;
        }
        if (((DeclaredType) superclass).asElement() instanceof TypeElement superElement) {
            String qn = superElement.getQualifiedName().toString();
            return "java.lang.Object".equals(qn) ? null : qn;
        }
        return null;
    }

    /**
     * Extracts the source-spelled type that follows {@code extends} in an inheritance tail (e.g. {@code Vehicle} from
     * {@code " extends Vehicle implements Foo "}), or {@code null} when the tail has no {@code extends} clause.
     */
    private static String parseExtends(String tail) {
        Matcher matcher = EXTENDS_PATTERN.matcher(tail);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.end();
        Matcher implementsMatcher = IMPLEMENTS_PATTERN.matcher(tail);
        int end = implementsMatcher.find(start) ? implementsMatcher.start() : tail.length();
        String extracted = tail.substring(start, end).strip();
        return extracted.isEmpty() ? null : extracted;
    }

    /**
     * Extracts the source-spelled interface list that follows {@code implements} in an inheritance tail (e.g.
     * {@code "Foo, Bar"} from {@code " extends Vehicle implements Foo, Bar "}), or {@code ""} when there is none.
     */
    private static String parseImplements(String tail) {
        Matcher matcher = IMPLEMENTS_PATTERN.matcher(tail);
        if (!matcher.find()) {
            return "";
        }
        return tail.substring(matcher.end()).strip();
    }

    private Path superclassFile(Path anchorFile, String anchorPackage, String targetPackage, String superclassName) {
        Path dir = anchorFile.getParent();
        if (!targetPackage.equals(anchorPackage)) {
            Path root = dir;
            if (!anchorPackage.isEmpty()) {
                for (int i = 0; i < anchorPackage.split("\\.").length; i++) {
                    root = root.getParent();
                }
            }
            dir = targetPackage.isEmpty() ? root : root.resolve(targetPackage.replace('.', '/'));
        }
        return dir.resolve(superclassName + ".java");
    }

    private void refuseDivergentSelectedMemberText(List<String> classNames, List<String> selectors) {
        Map<String, String> firstBySelector = new HashMap<>();
        for (String className : classNames) {
            String source = readClassSource(className);
            if (source == null) {
                continue;
            }
            for (String selector : selectors) {
                String memberText = selectedMemberText(source, selector);
                if (memberText == null) {
                    continue;
                }
                String normalized = memberText.replaceAll("\\s+", " ").trim();
                String first = firstBySelector.putIfAbsent(selector, normalized);
                if (first != null && !first.equals(normalized)) {
                    throw new ClassOpsRefusal("extract_superclass_member_not_equivalent",
                            "Selected member '" + selector + "' has different implementations across source classes.");
                }
            }
        }
    }

    private String readClassSource(String className) {
        String suffix = className.replace('.', '/') + ".java";
        try (var stream = Files.walk(projectRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().replace('\\', '/').endsWith(suffix))
                    .findFirst()
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String selectedMemberText(String source, String selector) {
        SelectorParts parts = selectorParts(selector);
        if (!"field".equals(parts.kind())) {
            MethodMatch match = selectedMethodText(source, parts);
            if (match.ambiguous()) {
                throw new ClassOpsRefusal("extract_superclass_member_ambiguous",
                        "Selected member '" + selector + "' is overloaded; use an exact signature.");
            }
            if (match.text() != null) {
                return match.text();
            }
        }
        if ("method".equals(parts.kind())) {
            return null;
        }
        Matcher field = Pattern.compile("(?m)^.*\\b" + Pattern.quote(parts.name()) + "\\b[^;]*;").matcher(source);
        return field.find() ? field.group() : null;
    }

    private static MethodMatch selectedMethodText(String source, SelectorParts selector) {
        Matcher matcher = Pattern.compile("(?m)^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:public|protected|private)?\\s*(?:static\\s+|final\\s+|abstract\\s+|synchronized\\s+|native\\s+)*[\\w<>\\[\\]., ?]+\\s+"
                + Pattern.quote(selector.name()) + "\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[^\\{]+)?\\{").matcher(source);
        String only = null;
        int count = 0;
        while (matcher.find()) {
            if (selector.params() != null && !paramsMatch(selector.params(), matcher.group(1))) {
                continue;
            }
            count++;
            int brace = source.indexOf('{', matcher.end() - 1);
            if (brace < 0) {
                continue;
            }
            int start = source.lastIndexOf('\n', matcher.start());
            start = start < 0 ? 0 : start + 1;
            int depth = 0;
            for (int i = brace; i < source.length(); i++) {
                char ch = source.charAt(i);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        only = source.substring(start, i + 1);
                        break;
                    }
                }
            }
        }
        return new MethodMatch(only, selector.params() == null && count > 1);
    }

    private static boolean paramsMatch(List<String> selectorParams, String declarationParams) {
        List<String> declared = new ArrayList<>();
        String trimmed = declarationParams.trim();
        if (!trimmed.isEmpty()) {
            for (String param : trimmed.split(",")) {
                declared.add(normalizeParamType(param));
            }
        }
        if (declared.size() != selectorParams.size()) {
            return false;
        }
        for (int i = 0; i < declared.size(); i++) {
            String expected = normalizeSelectorType(selectorParams.get(i));
            String actual = declared.get(i);
            if (!actual.equals(expected) && !actual.endsWith("." + expected)) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeParamType(String param) {
        String normalized = param.replace("...", "[]")
                .replaceAll("@\\w+(?:\\([^)]*\\))?\\s*", "")
                .replaceAll("\\bfinal\\s+", "")
                .trim();
        int lastSpace = normalized.lastIndexOf(' ');
        if (lastSpace >= 0) {
            normalized = normalized.substring(0, lastSpace).trim();
        }
        return normalizeSelectorType(normalized);
    }

    private static String normalizeSelectorType(String type) {
        return type.replace("...", "[]").replaceAll("\\s+", "").trim();
    }

    private static SelectorParts selectorParts(String selector) {
        String kind = null;
        int colon = selector.indexOf(':');
        if (colon >= 0) {
            kind = selector.substring(0, colon);
            selector = selector.substring(colon + 1);
        }
        int paren = selector.indexOf('(');
        List<String> params = null;
        if (paren >= 0) {
            int end = selector.indexOf(')', paren);
            String rawParams = end >= 0 ? selector.substring(paren + 1, end) : selector.substring(paren + 1);
            params = new ArrayList<>();
            if (!rawParams.isBlank()) {
                for (String param : rawParams.split(",")) {
                    params.add(param.trim());
                }
            }
            selector = selector.substring(0, paren);
        }
        int dot = selector.lastIndexOf('.');
        String name = dot >= 0 ? selector.substring(dot + 1) : selector;
        return new SelectorParts(kind, name, params);
    }

    private record SelectorParts(String kind, String name, List<String> params) {}

    private record MethodMatch(String text, boolean ambiguous) {}


    private Path resolveClassInput(String input) throws ProjectPathResolver.Violation {
        String value = input == null ? "" : input.trim();
        if (value.startsWith("fqn:")) {
            value = value.substring("fqn:".length());
        } else if (value.startsWith("symbol:")) {
            value = value.substring("symbol:".length());
        }
        if (value.endsWith(".java") || value.contains("/") || value.contains("\\")) {
            return ProjectPathResolver.resolveProjectRelative(projectRoot, value, "classes");
        }

        String relativeClassFile = value.replace('.', '/') + ".java";
        for (SourceSet sourceSet : model.sourceSets()) {
            for (Path root : sourceSet.sourceRoots()) {
                Path candidate = root.resolve(relativeClassFile).toAbsolutePath().normalize();
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        throw new ClassOpsRefusal("source_type_not_found",
                "Class identifier '" + input + "' did not resolve to a source file.");
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
