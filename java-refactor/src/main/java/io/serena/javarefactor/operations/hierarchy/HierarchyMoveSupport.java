package io.serena.javarefactor.operations.hierarchy;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.shared.AccessAdjustmentPlanner;
import io.serena.javarefactor.shared.ImportManager;
import io.serena.javarefactor.shared.MethodBodyModel;
import io.serena.javarefactor.shared.ProjectPathResolver;
import io.serena.javarefactor.shared.SemanticTargetGate;
import io.serena.javarefactor.shared.SourceLocation;
import io.serena.javarefactor.shared.StructuredRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * G009: shared infrastructure for the V2 hierarchy member-move planners ({@link PullUpPlanner} and
 * {@link PushDownPlanner}). Holds the project root, model, access planner, and lazily-built {@link TypeHierarchyIndex},
 * plus every helper both moves share: semantic member selection, the {@link Member} model, import transfer/cleanup,
 * access planning, collision/serialization/field-hazard refusals, the override-compatibility wiring, and the canonical
 * accepted/refused JSON rendering. The two concrete planners contribute only their operation entry point and the
 * helpers unique to that direction, keeping each unit small and independently testable.
 */
abstract class HierarchyMoveSupport {
    static final Pattern METHOD_DECLARATION = Pattern.compile(
            "(?m)^(\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|strictfp)\\s+)*)"
                    + "([A-Za-z_$][A-Za-z0-9_$.<>\\[\\]]*)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(([^)]*)\\)");
    static final Pattern FIELD_DECLARATION = Pattern.compile(
            "(?m)^(\\s*(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*)"
                    + "([A-Za-z_$][A-Za-z0-9_$.<>\\[\\]]*)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:=[^;]*)?;");
    static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    final Path projectRoot;
    final JavaProjectModel model;
    final AccessAdjustmentPlanner accessPlanner = new AccessAdjustmentPlanner();
    TypeHierarchyIndex hierarchyIndex;

    HierarchyMoveSupport(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot;
        this.model = model;
    }

    Member selectedMember(Path sourceFile, Map<String, Object> fields) throws Exception {
        Path relativePath = projectRoot.toAbsolutePath().normalize().relativize(sourceFile.toAbsolutePath().normalize());
        int oneBasedLine = intField(fields, "line");
        String memberName = stringField(fields, "memberName", "");
        try (SemanticIndex index = SemanticIndex.open(model, relativePath.toString())) {
            // Enforce full semantic-target identity before selecting: refuse mismatched/ambiguous/non-editable targets.
            io.serena.javarefactor.ast.ResolvedTarget verified = SemanticTargetGate.require(index, relativePath.toString(), fields);
            // Prefer the gate-verified simple name over the caller's memberName so an overloaded declaration resolves to
            // the proven semantic target rather than the narrowest declaration on the line.
            String nameHint = verified != null ? verified.element().getSimpleName().toString() : memberName;
            SemanticIndex.SemanticMember semantic = index.selectedMember(sourceFile, oneBasedLine, nameHint);
            if (semantic == null) {
                throw new Refusal("target_not_member", "Selected line does not contain a javac-resolved method or field declaration.");
            }
            SemanticTargetGate.confirmSelection(verified, semantic.element());
            return memberFromSemantic(index, semantic);
        }
    }

    Member memberFromSemantic(SemanticIndex index, SemanticIndex.SemanticMember semantic) throws Refusal {
        return semantic.kind() == SemanticIndex.SemanticMemberKind.METHOD
                ? methodMember(index, semantic.method())
                : fieldMember(index, semantic.field());
    }

    Member methodMember(SemanticIndex index, SemanticIndex.SemanticMethod method) throws Refusal {
        String source = index.sourceText(method.file()).toString();
        SemanticIndex.SourceRange range = method.declarationRange();
        int removeStart = absorbAttachedJavadoc(source, range.start());
        // Parameter text comes from javac parameter source ranges (slice between the first and last parameter), not by
        // scanning for the method name / brace-counting parentheses; an empty list yields "".
        String parameters = parameterText(source, method.parameters());
        // The modifier/annotation/type-parameter prefix is the source slice from the type line's own start up to the
        // javac start of the return type tree, so multiline generic return types, type-USE annotations on the return
        // type, and annotations placed between the modifiers and the type are all preserved verbatim with indentation.
        String modifiers = modifierPrefix(source, method.returnTypeRange().start());
        return new Member(
                MemberKind.METHOD,
                modifiers,
                method.returnType(),
                method.name(),
                parameters,
                removeStart,
                range.end(),
                source.substring(removeStart, range.end()),
                method.ownerQualifiedName(),
                method.element(),
                1,
                // Body brace offset (Blocker 5): lets bodyReferenceNames bind the body model to THIS overload via
                // MethodBodyModel.fromSourceAtBody. -1 for a bodyless (abstract/native) method, which has no body to analyze.
                method.bodyRange() != null ? method.bodyRange().start() : -1);
    }

    Member fieldMember(SemanticIndex index, SemanticIndex.SemanticField field) throws Refusal {
        String source = index.sourceText(field.file()).toString();
        SemanticIndex.SourceRange range = field.declarationRange();
        int removeStart = absorbAttachedJavadoc(source, lineStartBefore(source, range.start()));
        // The modifier/annotation prefix is the slice from the type line's start up to the javac start of the declared
        // type tree (robust to type-use annotations and annotations interleaved with modifiers).
        String modifiers = modifierPrefix(source, field.typeRange().start());
        return new Member(
                MemberKind.FIELD,
                modifiers,
                field.type(),
                field.name(),
                "",
                removeStart,
                range.end(),
                source.substring(removeStart, range.end()),
                field.ownerQualifiedName(),
                field.element(),
                index.fieldDeclaratorCount(field.element()),
                -1); // a field has no method body to bind
    }

    /**
     * Renders the method's parameter list text verbatim from the javac parameter source ranges. The slice spans from the
     * first parameter's range start to the last parameter's range end, so generics and varargs are reproduced exactly as
     * written without re-scanning for the method name or matching parentheses. A zero-parameter method renders as "".
     */
    String parameterText(String source, List<SemanticIndex.SemanticParameter> parameters) {
        if (parameters.isEmpty()) {
            return "";
        }
        int start = parameters.get(0).range().start();
        int end = parameters.get(parameters.size() - 1).range().end();
        if (start < 0 || end < start || end > source.length()) {
            return "";
        }
        return source.substring(start, end).trim();
    }

    /**
     * The modifier/annotation/type-parameter prefix of a member declaration, taken as the verbatim source slice from the
     * start of the type's own line up to the javac-derived start of the member's type tree ({@code typeStart}). Anchoring
     * the upper bound on the javac type position (rather than a {@code lastIndexOf(type)} text search) makes the prefix
     * correct for multiline generic return types, type-USE annotations on the type, and annotations placed between the
     * modifiers and the type — none of which a regex/indexOf scan handles. Starting at the type line's own start keeps the
     * declaration indentation (used by {@link Member#indent()}) and drops any annotation lines that precede the modifier
     * keywords (those remain in the verbatim {@link Member#text()}).
     */
    String modifierPrefix(String source, int typeStart) throws Refusal {
        if (typeStart < 0 || typeStart > source.length()) {
            throw new Refusal("target_not_member", "Selected member modifiers could not be rendered.");
        }
        return source.substring(lineStartBefore(source, typeStart), typeStart);
    }

    int absorbAttachedJavadoc(String source, int declarationStart) {
        int lineStart = lineStartBefore(source, declarationStart);
        int cursor = lineStart;
        while (cursor > 0) {
            int previousEnd = cursor - 1;
            int previousStart = lineStartBefore(source, previousEnd - 1);
            String previousLine = source.substring(previousStart, previousEnd).trim();
            if (previousLine.isEmpty()) {
                break;
            }
            if (previousLine.endsWith("*/")) {
                int blockStart = source.lastIndexOf("/**", previousStart);
                if (blockStart >= 0) {
                    return lineStartBefore(source, blockStart);
                }
                break;
            }
            if (!previousLine.startsWith("@")) {
                break;
            }
            cursor = previousStart;
        }
        return declarationStart;
    }

    int lineStartBefore(String source, int offset) {
        if (offset <= 0) {
            return 0;
        }
        int previousNewline = source.lastIndexOf('\n', offset - 1);
        return previousNewline < 0 ? 0 : previousNewline + 1;
    }

    Path sourceFile(Map<String, Object> fields) throws Refusal {
        String relative = stringField(fields, "relativePath", "");
        if (relative.isBlank()) {
            throw new Refusal("missing_relative_path", "relativePath is required.");
        }
        try {
            return ProjectPathResolver.resolveProjectRelative(projectRoot, relative, "relativePath");
        } catch (ProjectPathResolver.Violation refusal) {
            throw new Refusal(refusal.code(), refusal.getMessage());
        }
    }

    Path targetFileForType(Path sourceFile, String typeName) throws Refusal {
        if (typeName == null || typeName.isBlank()) {
            throw new Refusal("missing_target_type", "targetType is required.");
        }
        String simple = simpleType(typeName);
        try {
            Path sameDirectory = sourceFile.getParent() == null ? projectRoot.resolve(simple + ".java") : sourceFile.getParent().resolve(simple + ".java");
            Path confinedSameDirectory = ProjectPathResolver.requireInsideProject(projectRoot, sameDirectory, "targetType");
            if (Files.exists(confinedSameDirectory)) {
                return confinedSameDirectory;
            }
            return ProjectPathResolver.resolveProjectRelative(projectRoot, typeName.replace('.', '/') + ".java", "targetType");
        } catch (ProjectPathResolver.Violation refusal) {
            throw new Refusal(refusal.code(), refusal.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    List<String> targetTypes(Map<String, Object> fields) {
        Object raw = fields.get("targetTypes");
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        String single = stringField(fields, "targetType", "");
        return single.isBlank() ? List.of() : List.of(single);
    }

    int classInsertionOffset(String target) throws Refusal {
        int close = target.lastIndexOf('}');
        if (close < 0) {
            throw new Refusal("target_not_type", "Target file does not contain a class closing brace.");
        }
        return close;
    }

    /**
     * G019: resolves the insertion point at the closing brace of the SPECIFIC target type's javac body range rather than
     * the last {@code '}'} in the file. Using the type's own declaration span (from the hierarchy's javac-derived
     * {@link SourceLocation}) keeps insertion correct when the target file holds multiple top-level types, the target is
     * a nested class, or trailing declarations follow the target type. The end-of-line bound is used (instead of the raw
     * end column) so the lookup is independent of how javac counts tab columns. Falls back to the whole-file last brace
     * only when the type has no resolvable source location.
     */
    int classInsertionOffset(TypeHierarchyIndex hierarchy, String targetQualified, String target) throws Refusal {
        Optional<SourceLocation> location = hierarchy.sourceLocation(targetQualified);
        if (location.isPresent() && location.get().startLine() > 0 && location.get().endLine() > 0) {
            int startOffset = offsetAtLineStart(target, location.get().startLine());
            int searchBound = offsetAtLineEnd(target, location.get().endLine());
            int close = target.lastIndexOf('}', Math.min(searchBound, target.length() - 1));
            if (close >= startOffset) {
                return close;
            }
        }
        return classInsertionOffset(target);
    }

    /** Offset of the first character of the given one-based line (clamped to the source length). */
    int offsetAtLineStart(String source, int oneBasedLine) {
        if (oneBasedLine <= 1) {
            return 0;
        }
        int line = 1;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                line++;
                if (line == oneBasedLine) {
                    return index + 1;
                }
            }
        }
        return source.length();
    }

    /** Offset of the newline terminating the given one-based line, or the source length for the final line. */
    int offsetAtLineEnd(String source, int oneBasedLine) {
        int start = offsetAtLineStart(source, oneBasedLine);
        int newline = source.indexOf('\n', start);
        return newline < 0 ? source.length() : newline;
    }

    

    boolean isInterface(String source) {
        return Pattern.compile("\\binterface\\s+[A-Za-z_$][A-Za-z0-9_$]*").matcher(source).find();
    }

    boolean isSupportedFieldMove(Member member) {
        if (member.kind() != MemberKind.FIELD) {
            return true;
        }
        if (member.isConstant()) {
            return true;
        }
        String modifiers = member.modifiers();
        String text = member.text();
        return !modifiers.contains("static")
                && !modifiers.contains("final")
                && !modifiers.contains("volatile")
                && !text.contains(" static ")
                && !text.contains(" final ")
                && !text.contains(" volatile ")
                && !text.contains("=");
    }

    /**
     * G009: refuses a field pull-up into an interface unless the field is a genuine compile-time constant (JLS §4.12.4:
     * a {@code static final} field initialized with a constant expression of primitive or {@code String} type). Such a
     * constant has no referenced project types to import — its value is a primitive/String literal accessible from any
     * interface — so promoting it to {@code public static final} is always legal. Every other field (instance field,
     * non-constant initializer, or a {@code static final} of a non-constant type) would yield invalid interface Java and
     * is refused with a located structured code.
     */
    void refuseFieldNotInterfaceConstant(Member member, Map<String, Object> fields) throws Refusal {
        // A multi-declarator source declaration (e.g. `static final int A = 1, B = 2;`) renders from member.text(), which
        // spans BOTH declarators; promoting it would silently drag the sibling declarator into the interface as valid —
        // but unintended — Java. Refuse such a pull-up with a located code; the declarator count is taken from the javac
        // model, not a text scan, so it is exact regardless of formatting.
        if (member.hasSiblingDeclarators()) {
            throw new Refusal(
                    "multi_declarator_field_unsupported",
                    "pullUpMember into an interface refuses a field that shares its declaration with sibling declarators "
                            + "(e.g. `static final int A = 1, B = 2;`): moving it would silently promote the other "
                            + "declarator(s) too. Split the declaration into one field per line and retry.",
                    memberLocation(fields));
        }
        if (!isInterfaceConstant(member)) {
            throw new Refusal(
                    "interface_field_not_constant",
                    "pullUpMember into an interface requires a compile-time constant (static final primitive/String); "
                            + "instance fields and non-constant initializers cannot become interface constants.",
                    memberLocation(fields));
        }
    }

    /** True iff the field's element is a compile-time constant variable (a non-null {@link VariableElement#getConstantValue()}). */
    boolean isInterfaceConstant(Member member) {
        return member.kind() == MemberKind.FIELD
                && member.element() instanceof VariableElement variable
                && variable.getConstantValue() != null;
    }

    /**
     * G011: refuses a field hierarchy move when the source or any target type is {@link java.io.Serializable} (directly
     * or transitively through the hierarchy index), unless the caller explicitly accepts the serialization-compatibility
     * impact via {@code confirmSerializationImpact}/{@code confirm_serialization_impact}. Moving a field across a
     * hierarchy can change a type's serialized form (serialVersionUID, field layout) even when the code still compiles,
     * so the move is gated behind an explicit confirmation. Non-field members and non-Serializable type sets are
     * unaffected.
     */
    void refuseSerializationImpactForField(
            TypeHierarchyIndex hierarchy,
            Member member,
            String sourceQualified,
            List<String> targetQualifiedNames,
            Map<String, Object> fields)
            throws Refusal {
        if (member.kind() != MemberKind.FIELD) {
            return;
        }
        List<String> serializableTypes = new ArrayList<>();
        if (isSerializable(hierarchy, sourceQualified)) {
            serializableTypes.add(sourceQualified);
        }
        for (String targetQualified : targetQualifiedNames) {
            if (isSerializable(hierarchy, targetQualified)) {
                serializableTypes.add(targetQualified);
            }
        }
        if (serializableTypes.isEmpty()) {
            return;
        }
        if (confirmSerializationImpact(fields)) {
            return;
        }
        throw new Refusal(
                "serialization_impact",
                "Moving a field in a Serializable type can change serialization compatibility for "
                        + String.join(", ", serializableTypes)
                        + "; pass confirmSerializationImpact to proceed.",
                memberLocation(fields));
    }

    /** True iff the type itself is, or transitively extends/implements, {@code java.io.Serializable}. */
    boolean isSerializable(TypeHierarchyIndex hierarchy, String qualifiedName) {
        if ("java.io.Serializable".equals(qualifiedName)) {
            return true;
        }
        return hierarchy.allSupertypes(qualifiedName).contains("java.io.Serializable");
    }

    boolean confirmSerializationImpact(Map<String, Object> fields) {
        return boolField(fields, "confirmSerializationImpact", false)
                || boolField(fields, "confirm_serialization_impact", false);
    }

    /**
     * G003 (Cases A and B): javac-backed field pull-up safety. Re-selects the moving field inside a freshly opened
     * {@link SemanticIndex} (so the element belongs to the live compiler task, mirroring
     * {@link #refuseUnsafeSourceCallSitesSemantic}) and refuses two hazards with located structured codes:
     * <ul>
     *   <li>{@code assigned_outside_declaration} — the field is assigned anywhere outside its declaration (constructor,
     *       setter, any method), via {@link SemanticIndex#isReassigned}. Pulling such a field up would break the subclass
     *       writes that target the now-relocated declaration. Compile-time constants are exempt (they cannot be
     *       reassigned and their inline initializer is the only write).</li>
     *   <li>{@code initializer_references_subclass} — the field's initializer reads a member or type declared only in the
     *       source subtree (and absent from the target supertype), via {@link SemanticIndex#fieldInitializerReferencesType}.
     *       The relocated initializer would no longer resolve.</li>
     * </ul>
     * Non-field members are unaffected. The check is skipped when the field cannot be re-selected (the upstream
     * selection already proved the target, so a re-selection miss is treated as no additional hazard).
     */
    void refuseFieldAssignmentHazards(
            TypeHierarchyIndex hierarchy, Path sourceFile, Member member, String sourceQualified, Map<String, Object> fields)
            throws Refusal {
        if (member.kind() != MemberKind.FIELD) {
            return;
        }
        Path relativePath = projectRoot.toAbsolutePath().normalize().relativize(sourceFile.toAbsolutePath().normalize());
        try (SemanticIndex index = SemanticIndex.open(model, relativePath.toString())) {
            SemanticIndex.SemanticField field = index.selectedField(sourceFile, intField(fields, "line"), member.name());
            if (field == null) {
                return;
            }
            Element fieldElement = field.element();
            if (!member.isConstant() && index.isReassigned(fieldElement)) {
                throw new Refusal(
                        "assigned_outside_declaration",
                        "Field '" + member.name() + "' is assigned outside its declaration; pulling it up could break subclass writes.",
                        memberLocation(fields));
            }
            Set<String> sourceSubtree = new LinkedHashSet<>();
            sourceSubtree.add(sourceQualified);
            sourceSubtree.addAll(hierarchy.allSubtypes(sourceQualified));
            if (index.fieldInitializerReferencesType(fieldElement, sourceSubtree)) {
                throw new Refusal(
                        "initializer_references_subclass",
                        "Field '" + member.name() + "' has an initializer that references a subclass-only member or type; "
                                + "it cannot be moved to the supertype.",
                        memberLocation(fields));
            }
        } catch (Refusal refusal) {
            throw refusal;
        } catch (IOException error) {
            throw new Refusal(
                    "assigned_outside_declaration",
                    "Could not resolve the field's write sites to prove the pull-up is safe: "
                            + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                    memberLocation(fields));
        }
    }

    RefusalLocation memberLocation(Map<String, Object> fields) {
        try {
            return new RefusalLocation(stringField(fields, "relativePath", ""), intField(fields, "line"), intField(fields, "column"));
        } catch (Refusal ignored) {
            return null;
        }
    }

    /**
     * G002 (Branch D): renders the forwarding delegate left in the source when {@code leave_delegate} is requested on a
     * concrete pull-up. The signature reproduces the source declaration verbatim (original modifiers, return type, name,
     * and parameter text — preserving generics/varargs as written); the body forwards to {@code super.name(args)} using
     * the resolved {@link ExecutableElement} parameter simple names for the call arguments, so generic and varargs
     * parameters never need to be re-parsed out of the raw parameter string. A {@code void} method forwards without a
     * {@code return}; everything else returns the super result.
     */
    String buildDelegateBody(Member member, io.serena.javarefactor.shared.JavaStyleProfile style) {
        String indent = member.indent();
        String args = "";
        if (member.element() instanceof ExecutableElement executable) {
            StringBuilder argList = new StringBuilder();
            for (VariableElement parameter : executable.getParameters()) {
                if (argList.length() > 0) {
                    argList.append(", ");
                }
                argList.append(parameter.getSimpleName());
            }
            args = argList.toString();
        }
        String call = "super." + member.name() + "(" + args + ");";
        boolean isVoid = "void".equals(member.type().strip());
        String body = isVoid ? call : "return " + call;
        // Honor the inferred brace style (K&R vs Allman), line endings, and indentation unit for the
        // forwarding delegate so the inserted stub matches the surrounding source.
        return style.normalizeLineEndings(indent + "@Override\n"
                + indent + member.modifiers().strip() + (member.modifiers().strip().isEmpty() ? "" : " ")
                + member.type() + " " + member.name() + "(" + member.parameters() + ")" + style.openBrace(indent) + "\n"
                + style.childIndent(indent) + body + "\n"
                + indent + "}\n");
    }

    boolean hasOverrideAnnotation(String source, Member member) {
        int previousEnd = member.removeStart();
        int previousStart = source.lastIndexOf('\n', Math.max(0, previousEnd - 2));
        if (previousStart < 0) {
            previousStart = 0;
        }
        return source.substring(previousStart, previousEnd).strip().equals("@Override");
    }

    /**
     * G002 (Branch C): a sibling subtype that already declares a method with the SAME erased override key as the moving
     * method is a compatible <em>implementation</em> of the pulled-up declaration (JLS overriding), not a collision — it
     * is skipped (spec §8.2.5). A sibling method with the same name but a different override key is a legal overload and
     * was never a collision. Only a field name collision (Java has no field overloading) is an actual sibling clash and
     * is refused. G003 (Case C): the refusal carries the moving member's source location.
     */
    void refuseSiblingCollisions(TypeHierarchyIndex hierarchy, String sourceType, String targetType, Member member, Map<String, Object> fields) throws Refusal {
        Set<String> sourceSubtree = new LinkedHashSet<>();
        sourceSubtree.add(sourceType);
        sourceSubtree.addAll(hierarchy.allSubtypes(sourceType));
        for (String subtype : hierarchy.allSubtypes(targetType)) {
            if (sourceSubtree.contains(subtype)) {
                continue;
            }
            if (member.kind() == MemberKind.METHOD) {
                // Skip same-erased-key sibling methods here: in the common case the sibling simply overrides the
                // pulled-up declaration, which is correct and not a collision. This textual stage does not decide
                // override compatibility — OverrideGroupResolver.validatePullUp/validatePushDown proves it from the
                // javac type hierarchy BEFORE any edit is emitted (see PullUpPlanner/PushDownPlanner), refusing a
                // non-covariant return, incompatible generic substitution, or narrowed visibility up front. The
                // post-edit javac diagnostic delta remains a final safety net, not the override backstop. (Overloads
                // with different parameter types have a different key and are not skipped.)
                continue;
            }
            if (memberExists(hierarchy, subtype, member)) {
                throw new Refusal("sibling_member_collision", "Sibling subtype already declares a compatible member: " + subtype, memberLocation(fields));
            }
        }
    }

    /**
     * G009: turns an {@link OverrideGroupResolver} structured refusal into a located planner refusal. When the resolver
     * found a sibling/target override incompatibility (covariant return, generic substitution, or visibility), the move
     * is rejected with the resolver's structured code and message and the moving member's source location, so the caller
     * never reaches edit emission for a move that would produce an illegal override.
     */
    void refuseIncompatibleOverrides(Optional<StructuredRefusal> refusal, Map<String, Object> fields) throws Refusal {
        if (refusal.isPresent()) {
            throw new Refusal(refusal.get().code(), refusal.get().message(), memberLocation(fields));
        }
    }

    boolean declaresMember(String source, Member member) {
        if (member.kind() == MemberKind.METHOD) {
            return Pattern.compile("\\b" + Pattern.quote(member.name()) + "\\s*\\(").matcher(source).find();
        }
        return Pattern.compile("\\b" + Pattern.quote(member.name()) + "\\s*(?:=|;)").matcher(source).find();
    }

    String declaredTypeName(String source) throws Refusal {
        Matcher matcher = Pattern.compile("\\b(?:class|interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)").matcher(source);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new Refusal("source_type_not_found", "Unable to determine source type name for hierarchy refactoring.");
    }

    List<String> subtypeNames(TypeHierarchyIndex hierarchy, String sourceType, boolean includeIndirectSubtypes) {
        return (includeIndirectSubtypes ? hierarchy.allSubtypes(sourceType) : hierarchy.directSubtypes(sourceType)).stream()
                .map(HierarchyMoveSupport::simpleType)
                .toList();
    }

    void refusePathLikeTypeName(String typeName, String fieldName) throws Refusal {
        if (typeName != null && (typeName.contains("/") || typeName.contains("\\") || typeName.contains(".."))) {
            throw new Refusal("path_outside_project", fieldName + " must be a Java type name, not a path: " + typeName);
        }
    }

    /**
     * G010: resolve the target type honoring fully-qualified names. The FULL provided name is passed to
     * {@link TypeHierarchyIndex#resolveQualifiedName} (which matches an exact qualified name first and only collapses to
     * a simple-name lookup for an unqualified input). A qualified request such as {@code a.Base} therefore resolves to
     * {@code a.Base} and never collides with {@code b.Base}; an unqualified {@code Base} still resolves when unambiguous
     * and is refused as ambiguous when two types share the simple name.
     */
    String resolveRequiredType(TypeHierarchyIndex hierarchy, String typeName) throws Refusal {
        String resolveName = resolutionName(typeName);
        return hierarchy.resolveQualifiedName(resolveName)
                .orElseThrow(() -> refusalForType(hierarchy, resolveName)
                        .map(refusal -> new Refusal(refusal.code(), refusal.message()))
                        .orElseGet(() -> new Refusal("unresolved_type", "Unknown type: " + typeName)));
    }

    /**
     * The name used for hierarchy resolution: the full qualified name when the caller supplied one (it contains a
     * package/enclosing-type qualifier), otherwise the bare simple name. Generic type arguments are always stripped.
     */
    static String resolutionName(String typeName) {
        String raw = typeName == null ? "" : typeName.strip();
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        return raw.contains(".") ? raw : simpleType(raw);
    }

    Path targetFileForType(TypeHierarchyIndex hierarchy, String typeName, String fieldName) throws Refusal {
        String qualifiedName = resolveRequiredType(hierarchy, typeName);
        return hierarchy.sourceLocation(qualifiedName)
                .map(location -> {
                    try {
                        return ProjectPathResolver.resolveProjectRelative(projectRoot, location.relativePath(), fieldName);
                    } catch (ProjectPathResolver.Violation refusal) {
                        throw new IllegalArgumentException(refusal);
                    }
                })
                .orElseThrow(() -> new Refusal("target_not_source_type", "Type has no source location: " + typeName));
    }

    /**
     * G018: signature-aware collision/override check. A field collides on name+kind (Java has no field overloading). A
     * method collides only when the target already declares a method with the SAME erased override signature (JLS
     * §8.4.2): a method of the same name but different parameter types is a legal overload and must NOT be treated as a
     * collision. When the moving method's own signature cannot be resolved from the hierarchy, the check conservatively
     * falls back to a name+kind collision so safety is never relaxed.
     */
    boolean memberExists(TypeHierarchyIndex hierarchy, String typeName, Member member) {
        String descriptorKind = descriptorKind(member);
        List<MemberDescriptor> named = hierarchy.membersNamed(typeName, member.name());
        if (member.kind() != MemberKind.METHOD) {
            return named.stream().anyMatch(candidate -> candidate.kind().equals(descriptorKind));
        }
        MemberDescriptor moving = movingMethodDescriptor(hierarchy, member);
        String movingOverrideKey = moving == null ? null : moving.overrideKey();
        for (MemberDescriptor candidate : named) {
            if (!"method".equals(candidate.kind())) {
                continue;
            }
            if (movingOverrideKey == null || movingOverrideKey.isEmpty()) {
                // Could not resolve the moving method's signature: conservatively treat any same-name method as a clash.
                return true;
            }
            if (movingOverrideKey.equals(candidate.overrideKey())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the moving method's own {@link MemberDescriptor} (and thus its erased override key) within its declaring
     * type, matching on declared parameter types so an overloaded source method resolves to the exact overload being
     * moved. Returns {@code null} for non-methods or when no matching declaration is found.
     */
    MemberDescriptor movingMethodDescriptor(TypeHierarchyIndex hierarchy, Member member) {
        if (member.kind() != MemberKind.METHOD || !(member.element() instanceof ExecutableElement executable)) {
            return null;
        }
        List<String> declaredParameterTypes = executable.getParameters().stream()
                .map(parameter -> parameter.asType().toString())
                .toList();
        for (MemberDescriptor candidate : hierarchy.membersNamed(member.ownerQualifiedName(), member.name())) {
            if (!"method".equals(candidate.kind())) {
                continue;
            }
            List<String> candidateParameterTypes = candidate.parameters().stream()
                    .map(MemberDescriptor.ParameterModel::type)
                    .toList();
            if (candidateParameterTypes.equals(declaredParameterTypes)) {
                return candidate;
            }
        }
        return null;
    }

    String descriptorKind(Member member) {
        return member.kind() == MemberKind.METHOD ? "method" : "field";
    }

    void refuseBodyIncompatibleWithTarget(TypeHierarchyIndex hierarchy, String sourceText, String sourceType, String targetType, Member member) throws Refusal {
        if (member.kind() != MemberKind.METHOD) {
            return;
        }
        Set<String> targetMemberNames = new LinkedHashSet<>();
        targetMemberNames.addAll(hierarchy.members(targetType).stream().map(MemberDescriptor::name).toList());
        for (String supertype : hierarchy.allSupertypes(targetType)) {
            targetMemberNames.addAll(hierarchy.members(supertype).stream().map(MemberDescriptor::name).toList());
        }
        // Blocker 5: the dependency facts come exclusively from a javac body model bound to the SELECTED executable;
        // identifiers in comments, string literals, Javadoc, or the method's own locals are never mistaken for a
        // dependency on a source-only sibling, and there is no regex fallback. A field has no method body to analyze
        // (its initializer hazards are gated elsewhere), so the check is skipped for non-method members.
        Set<String> bodyReferenceNames = bodyReferenceNames(sourceText, member);
        if (bodyReferenceNames == null) {
            return;
        }
        for (MemberDescriptor sourceMember : hierarchy.members(sourceType)) {
            String name = sourceMember.name();
            if (name.equals(member.name()) || targetMemberNames.contains(name)) {
                continue;
            }
            if (bodyReferenceNames.contains(name)) {
                throw new Refusal("incompatible_member_body", "Member body references source-only member not available from target type: " + name);
            }
        }
    }

    /**
     * Models the selected method's body with javac, bound to the SELECTED executable by its body-brace position
     * ({@link MethodBodyModel#fromSourceAtBody}), and returns the simple names it reads, writes, or calls (Blocker 5).
     * Binding by body position — not by method name — disambiguates overloads, exactly as {@code inlineMethod} does. An
     * unbindable body (overload-ambiguous or unparseable) is a hard {@link Refusal} rather than a textual identifier scan
     * that cannot distinguish a genuine sibling dependency from a same-named local, parameter, comment, or string literal.
     * Returns {@code null} only for a non-method member (a field has no body to analyze).
     */
    Set<String> bodyReferenceNames(String sourceText, Member member) throws Refusal {
        if (member.kind() != MemberKind.METHOD) {
            return null;
        }
        MethodBodyModel body = MethodBodyModel.fromSourceAtBody(sourceText, member.name(), member.bodyStartOffset());
        if (body.methodKey() == null && body.statements().isEmpty()) {
            throw new Refusal(
                    "body_analysis_unbindable",
                    "Hierarchy move cannot prove body compatibility for '" + member.name() + "': its body could not be "
                            + "uniquely bound with the compiler (e.g. an overloaded or unparseable declaration). Refusing "
                            + "rather than relying on a textual identifier scan that may miss or invent a dependency.");
        }
        // The union reads ∪ writes ∪ calls is the dependency surface; it is invariant to whether reads and writes overlap
        // (Blocker 6), so a read-modify-write of a sibling field is still caught while body-local names remain excluded by
        // element-backed reads/writes from the bound model.
        Set<String> referenced = new LinkedHashSet<>();
        referenced.addAll(body.calls());
        referenced.addAll(body.reads());
        referenced.addAll(body.writes());
        return referenced;
    }

    /**
     * G008: transfers the imports the transplanted member needs into the target through the central {@link ImportManager}
     * rather than a bespoke per-import textual insertion. For every single-type import the member references it checks
     * {@link ImportManager#mustUseFqn(String)} (refusing with {@code import_conflict} when the simple name is already
     * claimed by a different single-type import, since a conservative move cannot rewrite the member to a fully-qualified
     * reference) and then {@link ImportManager#addImport(String)}; static references go through
     * {@link ImportManager#addStaticImport(String)}. Existing static and wildcard imports of the target are preserved by
     * the manager. The whole import block is re-rendered via {@link ImportManager#renderImportBlock()} and emitted as a
     * single edit only when an import was actually added, so the target's existing import ordering is left untouched when
     * nothing changes.
     */
    List<PlannerSupport.TextEdit> requiredImportEdits(
            Path sourceFile, String source, Path targetFile, String target, SemanticIndex.MovedBodyDependencies deps)
            throws Refusal {
        if (sourceFile.toAbsolutePath().normalize().equals(targetFile.toAbsolutePath().normalize())) {
            return List.of();
        }

        ImportManager targetImports = new ImportManager(target);
        int before = targetImports.imports().size() + targetImports.staticImports().size();
        // The imports the moved member needs are derived from the javac-resolved type/member references in the member's
        // own declaration subtree (deps), not from a regex scan of the rendered member text. A type named only in a
        // comment, string literal, or Javadoc inside the member is therefore never imported into the target.
        for (String qualifiedName : deps.referencedTypeFqns()) {
            if (targetImports.mustUseFqn(qualifiedName)) {
                throw new Refusal("import_conflict", "Target type already imports a different type named " + simpleType(qualifiedName) + ".");
            }
            Optional<StructuredRefusal> refusal = targetImports.addImport(qualifiedName);
            if (refusal.isPresent()) {
                throw new Refusal("import_conflict", refusal.get().message());
            }
        }
        for (SemanticIndex.StaticMemberRef staticRef : deps.staticMemberRefs()) {
            Optional<StructuredRefusal> refusal = targetImports.addStaticImport(staticRef.qualifiedMember());
            if (refusal.isPresent()) {
                throw new Refusal("import_conflict", refusal.get().message());
            }
        }
        int after = targetImports.imports().size() + targetImports.staticImports().size();
        if (after == before) {
            return List.of();
        }
        return importBlockEdit(targetFile, target, targetImports, "IMPORT_ADD").map(List::of).orElseGet(List::of);
    }

    /**
     * G008/HB-6: removes from the SOURCE every single-type import the relocated member no longer needs, decided entirely
     * from compiler-resolved references rather than identifier regexes. An import is dropped only when (a) the moved
     * member's javac-resolved references used its type ({@code deps}), AND (b) the post-removal compilation unit no longer
     * references that simple name — proven by re-parsing the source after the member's span is excised and asking the
     * central {@link ImportManager} (whose used-name set is computed from the AST, so a name appearing only in a comment,
     * string literal, Javadoc, or annotation value does not count as a use). Static and wildcard imports are preserved by
     * the manager. The re-rendered block is emitted only when an import was actually removed.
     */
    Optional<PlannerSupport.TextEdit> sourceImportCleanupEdit(
            Path sourceFile, String source, Member member, SemanticIndex.MovedBodyDependencies deps) {
        Set<String> memberImportedSimpleNames = new LinkedHashSet<>();
        Map<String, String> sourceImports = singleTypeImports(source);
        for (Map.Entry<String, String> entry : sourceImports.entrySet()) {
            if (deps.referencedTypeFqns().contains(entry.getValue())) {
                memberImportedSimpleNames.add(entry.getKey());
            }
        }
        if (memberImportedSimpleNames.isEmpty()) {
            return Optional.empty();
        }
        // Re-parse the unit AFTER the member is excised; the central ImportManager's unused-import set is AST-derived, so
        // it only flags imports whose simple name is genuinely no longer referenced (not occurrences in comments/strings).
        String afterRemoval = source.substring(0, member.removeStart()) + source.substring(member.removeEnd());
        Set<String> unusedAfterRemoval = new LinkedHashSet<>();
        for (String unused : new ImportManager(afterRemoval).unusedImports()) {
            unusedAfterRemoval.add(simpleType(unused));
        }
        ImportManager sourceManager = new ImportManager(source);
        boolean removedAny = false;
        for (Map.Entry<String, String> entry : sourceImports.entrySet()) {
            if (memberImportedSimpleNames.contains(entry.getKey()) && unusedAfterRemoval.contains(entry.getKey())) {
                removedAny |= sourceManager.removeImport(entry.getValue());
            }
        }
        if (!removedAny) {
            return Optional.empty();
        }
        return importBlockEdit(sourceFile, source, sourceManager, "IMPORT_REMOVE");
    }

    /**
     * HB-6: the compiler-resolved dependency surface (referenced type FQNs and static-member uses) of the moving member's
     * own declaration subtree, re-resolved inside a freshly opened {@link SemanticIndex} so the element belongs to the
     * live compiler task (mirroring {@link #refuseFieldAssignmentHazards}). Returns an empty surface when the member
     * cannot be re-selected, so a re-selection miss conservatively transfers/cleans up no imports rather than guessing
     * from text.
     */
    SemanticIndex.MovedBodyDependencies memberDependencies(Path sourceFile, Map<String, Object> fields, Member member) throws Refusal {
        Path relativePath = projectRoot.toAbsolutePath().normalize().relativize(sourceFile.toAbsolutePath().normalize());
        try (SemanticIndex index = SemanticIndex.open(model, relativePath.toString())) {
            SemanticIndex.SemanticMember semantic = index.selectedMember(sourceFile, intField(fields, "line"), member.name());
            if (semantic == null) {
                return new SemanticIndex.MovedBodyDependencies(Set.of(), List.of());
            }
            Element element = semantic.element();
            TypeElement owner = element.getEnclosingElement() instanceof TypeElement type ? type : null;
            return index.movedStaticBodyDependencies(element, owner);
        } catch (IOException error) {
            return new SemanticIndex.MovedBodyDependencies(Set.of(), List.of());
        }
    }

    /**
     * Renders {@code manager}'s import block and returns the edit that swaps it for the file's existing import block. When
     * the file already had imports the contiguous block is replaced in place; when it had none the rendered block is
     * inserted after the package declaration. Returns empty when there is nothing to render and no block to clear.
     */
    Optional<PlannerSupport.TextEdit> importBlockEdit(Path file, String source, ImportManager manager, String kind) {
        String rendered = manager.renderImportBlock();
        String lineSep = lineSeparator(source);
        int[] span = importBlockSpan(source);
        if (span != null) {
            String replacement = rendered.isEmpty() ? "" : rendered + lineSep;
            return Optional.of(new PlannerSupport.TextEdit(file, span[0], span[1], replacement, kind));
        }
        if (rendered.isEmpty()) {
            return Optional.empty();
        }
        int offset = importInsertionOffset(source);
        return Optional.of(new PlannerSupport.TextEdit(file, offset, offset, lineSep + rendered + lineSep, kind));
    }

    /** The half-open offset range of the contiguous import block (first import start to last import end), or null when there are none. */
    int[] importBlockSpan(String source) {
        Matcher matcher = Pattern.compile("(?m)^[ \\t]*import[ \\t]+(?:static[ \\t]+)?[\\w.*]+[ \\t]*;[ \\t]*\\r?\\n?").matcher(source);
        int start = -1;
        int end = -1;
        while (matcher.find()) {
            if (start < 0) {
                start = matcher.start();
            }
            end = matcher.end();
        }
        return start < 0 ? null : new int[] {start, end};
    }

    /**
     * Locates the source's single-type imports as a {@code simpleName -> qualifiedName} map. This only IDENTIFIES the
     * existing import lines so the move can match them against the compiler-resolved referenced-type FQN set; the decision
     * of which imports are unused is made semantically (see {@link #sourceImportCleanupEdit}), never from this scan.
     */
    Map<String, String> singleTypeImports(String source) {
        Map<String, String> imports = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("(?m)^\\s*import\\s+(?!static\\s+)([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;").matcher(source);
        while (matcher.find()) {
            String qualifiedName = matcher.group(1);
            imports.put(simpleType(qualifiedName), qualifiedName);
        }
        return imports;
    }

    int importInsertionOffset(String source) {
        Matcher importMatcher = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?[A-Za-z_$][A-Za-z0-9_$.]*\\s*;\\R?").matcher(source);
        int offset = -1;
        while (importMatcher.find()) {
            offset = importMatcher.end();
        }
        if (offset >= 0) {
            return offset;
        }
        Matcher packageMatcher = Pattern.compile("(?m)^\\s*package\\s+[A-Za-z_$][A-Za-z0-9_$.]*\\s*;\\R?").matcher(source);
        return packageMatcher.find() ? packageMatcher.end() : 0;
    }

    String packageNameFromQualifiedName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedName.substring(0, dot);
    }

    String lineSeparator(String source) {
        return source.contains("\r\n") ? "\r\n" : "\n";
    }

    /**
     * Javac-anchored push-down call-site safety. Unlike the text scan above, this enumerates only invocations that javac
     * actually resolves to the source declaration (via {@link SemanticIndex#methodCallSites}), then refuses removal when a
     * resolved call would no longer bind after the member is removed from the source type: any qualified/typed receiver,
     * a {@code super} call, a method reference, or an unqualified call located outside one of the subtypes that receives a
     * copy. Runs alongside the conservative text scan so the union can only add refusals, never relax one.
     */
    void refuseUnsafeSourceCallSitesSemantic(
            Path sourceFile, Map<String, Object> fields, Member member, List<String> targetQualifiedNames) throws Refusal {
        if (member.kind() != MemberKind.METHOD) {
            return;
        }
        try {
            Set<Path> subtypeFilesReceivingCopy = new LinkedHashSet<>();
            for (String qualifiedName : targetQualifiedNames) {
                hierarchyIndex.sourceLocation(qualifiedName).ifPresent(location -> {
                    try {
                        subtypeFilesReceivingCopy.add(ProjectPathResolver
                                .resolveProjectRelative(projectRoot, location.relativePath(), "targetTypes")
                                .toAbsolutePath()
                                .normalize());
                    } catch (ProjectPathResolver.Violation ignored) {
                        // A target whose file cannot be confined is already refused upstream; nothing to anchor here.
                    }
                });
            }
            Path relativePath = projectRoot.toAbsolutePath().normalize().relativize(sourceFile.toAbsolutePath().normalize());
            try (SemanticIndex index = SemanticIndex.open(model, relativePath.toString())) {
                SemanticIndex.SemanticMethod method = index.selectedMethod(sourceFile, intField(fields, "line"), member.name());
                if (method == null) {
                    return;
                }
                for (SemanticIndex.SemanticCallSite site : index.methodCallSites(method)) {
                    if (site.methodReference()) {
                        throw new Refusal("unsafe_source_call_site", "Push-down removal would orphan a method reference bound to the source declaration.");
                    }
                    String receiver = site.receiverText() == null ? "" : site.receiverText().strip();
                    Path siteFile = site.file().toAbsolutePath().normalize();
                    boolean unqualified = receiver.isEmpty() || receiver.equals("this");
                    if (unqualified) {
                        if (!subtypeFilesReceivingCopy.contains(siteFile)) {
                            throw new Refusal("unsafe_source_call_site", "Push-down removal would leave an unqualified call that no longer resolves: " + siteFile);
                        }
                    } else {
                        throw new Refusal("unsafe_source_call_site", "Push-down removal would leave a call whose receiver type no longer declares the member: " + siteFile);
                    }
                }
            }
        } catch (Refusal refusal) {
            throw refusal;
        } catch (Exception error) {
            // G020: source-call safety is now decided ONLY from resolved call sites (no regex fallback). If semantic
            // resolution itself fails we cannot prove the removal is safe, so we refuse conservatively rather than
            // silently permitting a removal that might orphan a real caller.
            throw new Refusal(
                    "unsafe_source_call_site",
                    "Push-down removal could not resolve the source method's call sites to prove removal is safe: "
                            + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    TypeHierarchyIndex hierarchyIndex(Path contextFile) throws IOException {
        if (hierarchyIndex == null) {
            Path relativePath = projectRoot.toAbsolutePath().normalize().relativize(contextFile.toAbsolutePath().normalize());
            try (io.serena.javarefactor.compiler.SemanticIndex index = io.serena.javarefactor.compiler.SemanticIndex.open(model, relativePath.toString())) {
                hierarchyIndex = index.typeHierarchyIndex();
            }
        }
        return hierarchyIndex;
    }

    Optional<io.serena.javarefactor.shared.StructuredRefusal> refusalForType(TypeHierarchyIndex hierarchy, String typeName) {
        // typeName is already a resolution name (qualified-as-given or bare simple); do not re-strip so a qualified
        // request keeps its package qualifier and an ambiguous simple name is still reported as ambiguous.
        return hierarchy.refusalForUnknownOrAmbiguous(typeName);
    }

    void refuseIfNeeded(Optional<io.serena.javarefactor.shared.StructuredRefusal> refusal) throws Refusal {
        if (refusal.isPresent()) {
            throw new Refusal(refusal.get().code(), refusal.get().message());
        }
    }

    String declaredTypeNameUnchecked(String source) {
        Matcher matcher = Pattern.compile("\\b(?:class|interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)").matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    String packageName(String source) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    int lineStartOffset(String source, int oneBasedLine) {
        int line = 1;
        for (int i = 0; i < source.length(); i++) {
            if (line == oneBasedLine) {
                return i;
            }
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return source.length();
    }

    boolean boolField(Map<String, Object> fields, String name, boolean defaultValue) {
        Object value = fields.get(name);
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * G001: pulling up or pushing down a {@code public}/{@code protected} member relocates a member that is part of the
     * type's externally visible API surface, so it requires explicit confirmation — mirroring the change-signature
     * public-API gate. The confirmation is sourced from {@code confirmPublicApi}/{@code confirmPublicApiChange}, which
     * {@code Main.applyConfiguredDefaults} populates from the {@code hierarchy.allow_public_api_change} config default.
     */
    void refusePublicApiUnlessConfirmed(Member member, String operation, Map<String, Object> fields) throws Refusal {
        if (isPublicApiMember(member)
                && !boolField(fields, "confirmPublicApi", false)
                && !boolField(fields, "confirmPublicApiChange", false)) {
            throw new Refusal(
                    "PUBLIC_API_CONFIRMATION_REQUIRED",
                    operation + " of a public or protected member changes the externally visible API surface and "
                            + "requires explicit public API confirmation (set allow_public_api_change in the hierarchy "
                            + "config or confirmPublicApi on the request).",
                    memberLocation(fields));
        }
    }

    /** Whether the selected member is part of the public API surface (declared {@code public} or {@code protected}). */
    boolean isPublicApiMember(Member member) {
        String modifiers = " " + member.modifiers().strip() + " ";
        return modifiers.contains(" public ") || modifiers.contains(" protected ");
    }

    int intField(Map<String, Object> fields, String name) throws Refusal {
        Object value = fields.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Integer.parseInt(string);
        }
        throw new Refusal("missing_" + name, name + " is required.");
    }

    String stringField(Map<String, Object> fields, String name, String defaultValue) {
        Object value = fields.get(name);
        return value == null ? defaultValue : String.valueOf(value);
    }

    static String simpleType(String typeName) {
        String raw = typeName == null ? "" : typeName.strip();
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        int dot = raw.lastIndexOf('.');
        return dot < 0 ? raw : raw.substring(dot + 1);
    }

    String memberTargetJson(Member member) {
        // Emit the javac canonical SemanticKey (kind/owner/name/signature/canonical) the session layer requires for a
        // stable target identity and apply-time re-resolution — mirroring ChangeSignaturePlanner. The synthetic
        // "hierarchyMember" descriptor used previously lacked a canonical key and was rejected as target_identity_missing.
        return SemanticKey.from(member.element()).toJson();
    }

    /**
     * G002: emits the one canonical accepted-result JSON through {@link ResponseBuilder#acceptedResult} instead of a
     * hand-rolled object, so stats/changedFiles/touchedFiles are derived from the REAL planned edit (a variable number of
     * subtype targets for push-down). Access-widening detail surfaces through the merged {@code warnings}; the structured
     * per-target access plans are no longer carried in the canonical shape. The preview carries the conservative
     * unvalidated diagnostic delta — the session layer runs the authoritative javac validation centrally.
     */
    String acceptedResult(boolean apply, String operation, Member member, List<PlannerSupport.TextEdit> edits, List<String> warnings)
            throws IOException {
        String semanticTargetJson = "{\"semanticKey\":" + memberTargetJson(member) + "}";
        return ResponseBuilder.acceptedResult(
                projectRoot,
                operation,
                apply,
                semanticTargetJson,
                edits,
                List.of(),
                warnings,
                List.of("hierarchy graph validated", "member collision checks passed"),
                ResponseBuilder.DiagnosticDelta.unvalidated(),
                false);
    }

    String refusedJson(String operation, boolean apply, String code, String message) {
        return refusedJson(operation, apply, code, message, null);
    }

    String refusedJson(String operation, boolean apply, String code, String message, RefusalLocation location) {
        // Blocker 3: one canonical refusal envelope. Always applied:false (never the incoming apply flag), the actual
        // requested mode, centrally-derived empty stats, and no stale fields (the former ad-hoc "status":"refused" and
        // hand-coded zero stats are gone). The optional member location is embedded inside the refusal object.
        return ResponseBuilder.refusedResult(
                operation, apply, code, message, location == null ? null : location.toJson(), List.of());
    }

    enum MemberKind { METHOD, FIELD }

    record Member(MemberKind kind, String modifiers, String type, String name, String parameters, int removeStart, int removeEnd, String text, String ownerQualifiedName, Element element, int declaratorCount, int bodyStartOffset) {
        boolean isConstant() {
            return modifiers.contains("static") && modifiers.contains("final");
        }

        /** True when this field shares its physical declaration with sibling declarators (e.g. {@code int A = 1, B = 2;}). */
        boolean hasSiblingDeclarators() {
            return kind == MemberKind.FIELD && declaratorCount > 1;
        }

        Member withModifiers(String replacementModifiers) {
            // A PACKAGE-PRIVATE member has a blank modifier prefix (empty, or whitespace-only indentation), so
            // Pattern.quote(modifiers) would be a zero-width / leading-whitespace match: replaceFirst would splice the new
            // visibility keyword in at offset 0 — before the leading newline/indent and any absorbed javadoc/annotation
            // lines — producing malformed code that javac rejects. Insert at the declaration position instead. The
            // non-blank path (e.g. "protected", "static final") still has a unique textual prefix to replace.
            String rewritten = modifiers.isBlank()
                    ? insertModifiersAtDeclaration(text, replacementModifiers)
                    : text.replaceFirst(Pattern.quote(modifiers), Matcher.quoteReplacement(replacementModifiers));
            return new Member(kind, replacementModifiers, type, name, parameters, removeStart, removeEnd, rewritten, ownerQualifiedName, element, declaratorCount, bodyStartOffset);
        }

        /**
         * Inserts {@code replacementModifiers} at the member's declaration line when the source had no explicit modifier
         * prefix to replace. The cursor is advanced past leading whitespace, any javadoc/line-comment block, and any
         * leading annotation line(s) so the keyword lands on the declaration line itself; the declaration line's own
         * indentation is then replaced by {@code replacementModifiers}, which already carries the correct indentation
         * (computed by {@link AccessAdjustmentPlanner#rewriteModifiers} from the original prefix).
         */
        private static String insertModifiersAtDeclaration(String text, String replacementModifiers) {
            int cursor = 0;
            int length = text.length();
            while (cursor < length) {
                while (cursor < length && Character.isWhitespace(text.charAt(cursor))) {
                    cursor++;
                }
                if (cursor >= length) {
                    break;
                }
                if (text.startsWith("/*", cursor)) {
                    int end = text.indexOf("*/", cursor);
                    cursor = end < 0 ? length : end + 2;
                    continue;
                }
                if (text.startsWith("//", cursor)) {
                    int end = text.indexOf('\n', cursor);
                    cursor = end < 0 ? length : end + 1;
                    continue;
                }
                if (text.charAt(cursor) == '@') {
                    int end = text.indexOf('\n', cursor);
                    cursor = end < 0 ? length : end + 1;
                    continue;
                }
                break;
            }
            int lineStart = text.lastIndexOf('\n', cursor - 1) + 1;
            return text.substring(0, lineStart) + replacementModifiers + text.substring(cursor);
        }

        String abstractText() {
            String normalized = modifiers.replace("final ", "").replace("static ", "");
            if (!normalized.contains("abstract")) {
                normalized = normalized + "abstract ";
            }
            return normalized + type + " " + name + "(" + parameters + ");";
        }

        String interfaceDeclaration() {
            return indent() + type + " " + name + "(" + parameters + ");";
        }

        /**
         * Renders this field as a legal interface constant: {@code public static final <type> <name> [= <init>];} with
         * the implicit interface-field modifiers made explicit. The declared type, name, and any initializer are taken
         * from the original declaration text so the constant value is preserved verbatim; redundant source modifiers
         * (public/static/final/visibility) are dropped in favor of the canonical interface-constant prefix.
         */
        String interfaceConstantDeclaration() {
            String declaration = text.stripTrailing();
            // Isolate the declaration's own line (drop any absorbed javadoc/annotation lines preceding the field).
            int nameOffset = declaration.indexOf(name);
            int lineStart = nameOffset < 0 ? 0 : declaration.lastIndexOf('\n', nameOffset) + 1;
            String body = declaration.substring(Math.max(lineStart, 0)).strip();
            // Strip any leading source modifiers (public/protected/private/static/final/transient/volatile) so they are
            // not duplicated; what remains begins with the declared type.
            body = body.replaceFirst("^(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*", "");
            return indent() + "public static final " + body;
        }

        String indent() {
            int index = 0;
            while (index < modifiers.length() && Character.isWhitespace(modifiers.charAt(index))) {
                index++;
            }
            return modifiers.substring(0, index);
        }
    }

    static final class Refusal extends Exception {
        final String code;
        final RefusalLocation location;

        Refusal(String code, String message) {
            this(code, message, null);
        }

        Refusal(String code, String message, RefusalLocation location) {
            super(message);
            this.code = code;
            this.location = location;
        }
    }

    /** One-based source location attached to a structured refusal so callers can point at the offending member. */
    record RefusalLocation(String relativePath, int line, int column) {
        String toJson() {
            return "{\"relativePath\":" + JsonUtil.quote(relativePath)
                    + ",\"line\":" + line + ",\"column\":" + column + "}";
        }
    }
}
