package io.serena.javarefactor.operations.move_member;

import io.serena.javarefactor.ast.ResolvedTarget;
import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.shared.AccessAdjustmentPlanner;
import io.serena.javarefactor.shared.AccessChange;
import io.serena.javarefactor.shared.SemanticTargetGate;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.protocol.JsonUtil;
import io.serena.javarefactor.shared.ImportManager;
import io.serena.javarefactor.shared.ProjectPathResolver;
import io.serena.javarefactor.shared.SourceText;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * V2 member move dispatcher and shared-services home. The static path is owned by {@link MoveStaticMemberPlanner}
 * (G007) and the instance-method path by {@link MoveInstanceMethodPlanner} (G008); both delegate here for member
 * selection, insertion-offset, collision, path-resolution, and moved-body import services exposed through
 * package-private {@code *Shared} accessors. Member targets and reference edits are resolved with javac
 * Trees/Elements/source positions.
 */
public final class MoveMemberPlanner {
    private final Path projectRoot;
    private final JavaProjectModel model;

    public MoveMemberPlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot;
        this.model = model;
    }

    /**
     * Static member moves are owned by {@link MoveStaticMemberPlanner} (G007). This entry point is preserved for callers
     * and delegates to that named unit; the instance-method path below ({@link #moveInstanceMethod}) is unaffected.
     */
    public String moveStaticMember(Map<String, Object> fields, boolean apply) {
        return new MoveStaticMemberPlanner(projectRoot, model).move(fields, apply);
    }

    /**
     * Instance-method moves are owned by {@link MoveInstanceMethodPlanner} (G008). This entry point is preserved for
     * callers and delegates to that named unit; the shared services below ({@link #selectedSemanticMember},
     * {@link #classInsertionOffset}, collision and path resolution) are reused by it through {@code *Shared} accessors.
     */
    public String moveInstanceMethod(Map<String, Object> fields, boolean apply) {
        return new MoveInstanceMethodPlanner(projectRoot, model).move(fields, apply);
    }

    private Member selectedSemanticMember(SemanticIndex index, Path file, String source, int oneBasedLine, ResolvedTarget verified) throws Refusal {
        // Drive line-based selection with the gate-verified simple name so an overloaded/same-line member resolves to
        // exactly the proven semantic target rather than the narrowest declaration on the line.
        String nameHint = verified == null ? "" : verified.element().getSimpleName().toString();
        SemanticIndex.SemanticMember semantic = index.selectedMember(file, oneBasedLine, nameHint);
        if (semantic == null) {
            throw new Refusal("target_not_member", "Selected line does not contain a javac-resolved member declaration.");
        }
        if (semantic.kind() == SemanticIndex.SemanticMemberKind.METHOD) {
            SemanticIndex.SemanticMethod method = semantic.method();
            int start = decoratedMemberStart(source, method.declarationRange().start());
            String text = source.substring(start, method.declarationRange().end());
            String modifiers = modifiersPrefix(text, method.returnType(), method.name());
            return new Member(MemberKind.METHOD, modifiers, method.returnType(), method.name(), renderParameters(method.parameters()),
                    start, method.declarationRange().end(), text, semantic);
        }
        SemanticIndex.SemanticField field = semantic.field();
        int start = decoratedMemberStart(source, field.declarationRange().start());
        String text = source.substring(start, field.declarationRange().end());
        String modifiers = modifiersPrefix(text, field.type(), field.name());
        return new Member(MemberKind.FIELD, modifiers, field.type(), field.name(), "",
                start, field.declarationRange().end(), text, semantic);
    }

    private int decoratedMemberStart(String source, int declarationStart) {
        int start = lineStart(source, declarationStart);
        boolean insideBlock = false;
        while (start > 0) {
            int previousEnd = start;
            if (previousEnd > 0 && source.charAt(previousEnd - 1) == '\n') {
                previousEnd--;
            }
            int previousStart = source.lastIndexOf('\n', Math.max(0, previousEnd - 1)) + 1;
            String previousLine = source.substring(previousStart, previousEnd).strip();
            if (previousLine.isEmpty()) {
                break;
            }
            if (insideBlock) {
                start = previousStart;
                if (previousLine.startsWith("/**") || previousLine.startsWith("/*")) {
                    insideBlock = false;
                }
                continue;
            }
            if (previousLine.startsWith("@")) {
                start = previousStart;
                continue;
            }
            if (previousLine.endsWith("*/")) {
                start = previousStart;
                insideBlock = !previousLine.startsWith("/**") && !previousLine.startsWith("/*");
                continue;
            }
            break;
        }
        return start;
    }

    private static String modifiersPrefix(String declarationText, String type, String name) {
        String needle = type + " " + name;
        int index = declarationText.indexOf(needle);
        if (index <= 0) {
            return "";
        }
        int lineStart = declarationText.lastIndexOf('\n', index - 1) + 1;
        return declarationText.substring(lineStart, index);
    }

    private static String renderParameters(List<SemanticIndex.SemanticParameter> parameters) {
        List<String> rendered = new ArrayList<>();
        for (SemanticIndex.SemanticParameter parameter : parameters) {
            rendered.add(parameter.type() + " " + parameter.name());
        }
        return String.join(", ", rendered);
    }

    /**
     * Plans the import edits that let a moved member body compile in the TARGET file (G012 for static moves, G017 for
     * instance moves). The moved body keeps referencing the same simple type names, but the single-type imports that
     * resolve them may live only in the SOURCE file; this transplants exactly those that the target does not already
     * cover. Each referenced simple name is resolved to a fully-qualified name through the source file's own
     * single-type imports, then {@link ImportManager#planTypesForBody} decides — against the target's existing
     * imports, package, and {@code java.lang} — which imports the target still needs. Types the target already imports,
     * same-package types, and {@code java.lang} types yield no edit.
     */
    private List<PlannerSupport.TextEdit> transplantBodyImports(
            String sourceText, Path targetFile, String targetText, String movedBodyText, String kind) {
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        Map<String, String> sourceImports = singleTypeImportsBySimpleName(sourceText);
        if (!sourceImports.isEmpty()) {
            Set<String> referencedFqns = new java.util.LinkedHashSet<>();
            for (String token : ImportManager.collectReferencedTypeNames(movedBodyText)) {
                String fqn = sourceImports.get(simpleType(token));
                if (fqn != null) {
                    referencedFqns.add(fqn);
                }
            }
            if (!referencedFqns.isEmpty()) {
                edits.addAll(new ImportManager(targetText).planTypesForBody(targetFile, referencedFqns, kind));
            }
        }
        edits.addAll(transplantStaticBodyImports(sourceText, targetFile, targetText, movedBodyText, kind));
        return edits;
    }

    /**
     * Transplants the SOURCE file's single-member static imports that the moved body actually uses into the TARGET file
     * (G012/G017 static-import policy). A static import is carried over only when its member simple name appears as a
     * token in the moved body and the target does not already statically import it. {@link ImportManager#addStaticImport}
     * is consulted first: if the target already binds that member simple name to a different static import, it refuses and
     * the carry-over is skipped (the body must qualify it instead), exactly mirroring the collision contract used for
     * regular type imports. Wildcard static imports are not transplanted (they may pull in unrelated members).
     */
    private List<PlannerSupport.TextEdit> transplantStaticBodyImports(
            String sourceText, Path targetFile, String targetText, String movedBodyText, String kind) {
        Map<String, String> sourceStaticImports = staticImportsByMemberName(sourceText);
        if (sourceStaticImports.isEmpty()) {
            return List.of();
        }
        Set<String> bodyTokens = ImportManager.collectReferencedTypeNames(movedBodyText);
        ImportManager targetImports = new ImportManager(targetText);
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        for (Map.Entry<String, String> entry : sourceStaticImports.entrySet()) {
            String memberName = entry.getKey();
            String qualifiedMember = entry.getValue();
            if (!bodyTokens.contains(memberName)) {
                continue;
            }
            // addStaticImport refuses on a colliding member simple name and is a no-op when already present.
            if (targetImports.addStaticImport(qualifiedMember).isPresent()) {
                continue;
            }
            ImportManager.computeStaticImportInsertion(targetText, qualifiedMember)
                    .ifPresent(insertion -> edits.add(new PlannerSupport.TextEdit(
                            targetFile, insertion.offset(), insertion.offset(), insertion.text(), kind)));
        }
        return edits;
    }

    private static final Pattern SINGLE_STATIC_IMPORT =
            Pattern.compile("(?m)^[ \\t]*import[ \\t]+static[ \\t]+([\\w.]+)[ \\t]*;");

    /** Indexes a compilation unit's single-member (non-wildcard) static imports by their member simple name. */
    private static Map<String, String> staticImportsByMemberName(String source) {
        Map<String, String> byMember = new java.util.LinkedHashMap<>();
        Matcher matcher = SINGLE_STATIC_IMPORT.matcher(source == null ? "" : source);
        while (matcher.find()) {
            String qualifiedMember = matcher.group(1);
            if (qualifiedMember.endsWith(".*") || !qualifiedMember.contains(".")) {
                continue;
            }
            byMember.putIfAbsent(simpleType(qualifiedMember), qualifiedMember);
        }
        return byMember;
    }

    private static final Pattern SINGLE_TYPE_IMPORT =
            Pattern.compile("(?m)^[ \\t]*import[ \\t]+(?!static\\b)([\\w.]+)[ \\t]*;");

    /** Indexes a compilation unit's non-static, non-wildcard single-type imports by their simple name. */
    private static Map<String, String> singleTypeImportsBySimpleName(String source) {
        Map<String, String> bySimpleName = new java.util.LinkedHashMap<>();
        Matcher matcher = SINGLE_TYPE_IMPORT.matcher(source == null ? "" : source);
        while (matcher.find()) {
            String fqn = matcher.group(1);
            if (fqn.endsWith(".*") || !fqn.contains(".")) {
                continue;
            }
            bySimpleName.putIfAbsent(simpleType(fqn), fqn);
        }
        return bySimpleName;
    }

    // ── Shared accessors for the static-move path (G007: MoveStaticMemberPlanner) ──────────────────────────────────
    // The static path lives in MoveStaticMemberPlanner; these package-private accessors expose this planner's shared
    // member-selection, insertion, collision, and path-resolution services without duplicating them. The instance path
    // below is unaffected.

    Path sourceFileShared(Map<String, Object> fields) throws Refusal {
        return sourceFile(fields);
    }

    Path targetFileShared(Map<String, Object> fields, Path sourceFile, String relativeKey, String typeKey) throws Refusal {
        return targetFile(fields, sourceFile, relativeKey, typeKey);
    }

    Member selectedSemanticMemberShared(SemanticIndex index, Path file, String source, int oneBasedLine, ResolvedTarget verified)
            throws Refusal {
        return selectedSemanticMember(index, file, source, oneBasedLine, verified);
    }

    void refuseTargetMemberCollisionShared(SemanticIndex index, Path targetFile, Member moved) throws Refusal {
        refuseTargetMemberCollision(index, targetFile, moved);
    }

    String targetTypeNameShared(Map<String, Object> fields, Path targetFile) {
        return targetTypeName(fields, targetFile);
    }

    String packageNameShared(SemanticIndex index, Path file) {
        return packageName(index, file);
    }

    int classInsertionOffsetShared(SemanticIndex index, Path targetFile, MemberKind kind, String target) throws Refusal {
        return classInsertionOffset(index, targetFile, kind, target);
    }

    int intFieldShared(Map<String, Object> fields, String key) throws Refusal {
        return intField(fields, key);
    }

    static String stringFieldShared(Map<String, Object> fields, String key, String defaultValue) {
        return stringField(fields, key, defaultValue);
    }

    // ── Shared accessors for the instance-method path (G008: MoveInstanceMethodPlanner) ────────────────────────────

    Path targetFileForTypeShared(Map<String, Object> fields, Path sourceFile, String typeName) throws Refusal {
        return targetFileForType(fields, sourceFile, typeName);
    }

    String fieldTypeShared(SemanticIndex index, Path sourceFile, String fieldName) throws Refusal {
        return fieldType(index, sourceFile, fieldName);
    }

    Parameter parameterShared(String parameters, String name) throws Refusal {
        return parameter(parameters, name);
    }

    int parameterIndexShared(String parameters, String name) {
        return parameterIndex(parameters, name);
    }

    void refuseTargetMethodSignatureCollisionShared(
            SemanticIndex index, Path targetFile, String methodName, List<TypeMirror> movedParameterTypes) throws Refusal {
        refuseTargetMethodSignatureCollision(index, targetFile, methodName, movedParameterTypes);
    }

    List<TypeMirror> movedParameterTypesShared(Member member, int droppedParameterIndex, boolean dropParameter)
            throws Refusal {
        return movedParameterTypes(member, droppedParameterIndex, dropParameter);
    }

    List<PlannerSupport.TextEdit> transplantBodyImportsShared(
            String sourceText, Path targetFile, String targetText, String movedBodyText, String kind) {
        return transplantBodyImports(sourceText, targetFile, targetText, movedBodyText, kind);
    }

    String formatLocationShared(SemanticIndex index, SemanticIndex.SourceRange range) {
        return formatLocation(index, range);
    }

    static int lineStartShared(String source, int offset) {
        return lineStart(source, offset);
    }

    static int lineEndShared(String source, int offset) {
        return lineEnd(source, offset);
    }

    static int previousNonWhitespaceShared(String source, int index) {
        return previousNonWhitespace(source, index);
    }

    static int qualifiedReferenceStartShared(String source, int qualifierEndExclusive) {
        return qualifiedReferenceStart(source, qualifierEndExclusive);
    }

    private static int previousNonWhitespace(String source, int index) {
        while (index >= 0 && Character.isWhitespace(source.charAt(index))) {
            index--;
        }
        return index;
    }

    private static int qualifiedReferenceStart(String source, int qualifierEndExclusive) {
        int end = Math.min(qualifierEndExclusive, source.length());
        int last = previousNonWhitespace(source, end - 1);
        if (last < 0 || !Character.isJavaIdentifierPart(source.charAt(last))) {
            return -1;
        }
        int start = last;
        while (start > 0) {
            char ch = source.charAt(start - 1);
            if (!Character.isJavaIdentifierPart(ch) && ch != '.') {
                break;
            }
            start--;
        }
        return start;
    }

    private static int lineStart(String source, int offset) {
        int start = Math.min(offset, source.length());
        while (start > 0 && source.charAt(start - 1) != '\n') {
            start--;
        }
        return start;
    }

    private static int lineEnd(String source, int offset) {
        int end = Math.min(offset, source.length());
        while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
            end++;
        }
        return end;
    }

    /**
     * Plan-wide access handling for the private source members a relocated member's body references
     * (HB-08). Replaces the former blanket {@link #refusePrivateDependencies} refusal: every referenced
     * private member is analyzed by the plan-wide {@link AccessAdjustmentPlanner#requiredAccessChanges},
     * and when widening is confirmed and legal each is widened in place at its own declaration (so the
     * relocated body stays source-valid without relying on compiler-synthesized accessor bridges). Throws
     * a structured {@link Refusal} when any required change is illegal (security-sensitive private
     * widening) or not confirmed ({@code allowAccessWidening=false}).
     *
     * @return in-place visibility-widening edits for referenced private members (possibly empty)
     */
    List<PlannerSupport.TextEdit> planPrivateDependencyAccessShared(
            SemanticIndex index,
            Path sourceFile,
            Path targetFile,
            Member member,
            boolean allowAccessWidening,
            boolean allowSecuritySensitivePrivateWidening,
            String editKind) throws Refusal {
        List<SemanticIndex.SemanticMember> referenced = new ArrayList<>();
        for (SemanticIndex.SemanticMember dependency : index.privateMembers(sourceFile, member.name())) {
            if (index.referencesWithin(dependency, member.declarationRange())) {
                referenced.add(dependency);
            }
        }
        return planAccessChangesForReferencedShared(
                index, sourceFile, targetFile, referenced, allowAccessWidening, allowSecuritySensitivePrivateWidening, editKind);
    }

    /**
     * Plan-wide access handling for a caller-supplied set of referenced private source members. Extracted from
     * {@link #planPrivateDependencyAccessShared} so callers that must pre-filter the dependency set (e.g.
     * {@link MoveInstanceMethodPlanner}, which can only widen <em>static</em> private dependencies because instance
     * dependencies have no receiver at the destination) reuse the identical plan-wide widening and refusal logic.
     *
     * @return in-place visibility-widening edits for the referenced private members (possibly empty)
     */
    List<PlannerSupport.TextEdit> planAccessChangesForReferencedShared(
            SemanticIndex index,
            Path sourceFile,
            Path targetFile,
            List<SemanticIndex.SemanticMember> referenced,
            boolean allowAccessWidening,
            boolean allowSecuritySensitivePrivateWidening,
            String editKind) throws Refusal {
        if (referenced.isEmpty()) {
            return List.of();
        }
        String declaringPackage = packageNameShared(index, sourceFile);
        String destinationPackage = packageNameShared(index, targetFile);
        List<AccessAdjustmentPlanner.MemberAccessRequest> requests = new ArrayList<>();
        for (SemanticIndex.SemanticMember dependency : referenced) {
            // No source-type receiver is available at the destination, so protected would not help an
            // unrelated target type; receiverAvailable is false and widening prefers package-private then public.
            requests.add(new AccessAdjustmentPlanner.MemberAccessRequest(
                    dependency.name(), dependencyDeclaringType(dependency), dependencyModifiers(dependency), declaringPackage, false));
        }
        AccessAdjustmentPlanner planner = new AccessAdjustmentPlanner();
        List<AccessChange> changes =
                planner.requiredAccessChanges(requests, destinationPackage, allowAccessWidening, allowSecuritySensitivePrivateWidening);
        java.util.Optional<AccessChange> refusal = planner.firstRefusal(changes);
        if (refusal.isPresent()) {
            AccessChange refused = refusal.get();
            throw new Refusal(
                    refused.refusal().code(),
                    "Move requires changing access of referenced private source member '" + refused.memberName()
                            + "': " + refused.refusal().message());
        }
        List<PlannerSupport.TextEdit> edits = new ArrayList<>();
        for (int index2 = 0; index2 < referenced.size(); index2++) {
            AccessChange change = changes.get(index2);
            if (change.widens()) {
                index.visibilityWideningEdit(referenced.get(index2), change.toVisibility(), editKind).ifPresent(edits::add);
            }
        }
        return edits;
    }

    private static String dependencyModifiers(SemanticIndex.SemanticMember dependency) {
        java.util.Set<javax.lang.model.element.Modifier> modifiers =
                dependency.kind() == SemanticIndex.SemanticMemberKind.METHOD
                        ? dependency.method().modifiers()
                        : dependency.field().modifiers();
        List<String> parts = new ArrayList<>();
        for (javax.lang.model.element.Modifier modifier : modifiers) {
            parts.add(modifier.toString());
        }
        return String.join(" ", parts);
    }

    private static String dependencyDeclaringType(SemanticIndex.SemanticMember dependency) {
        return dependency.kind() == SemanticIndex.SemanticMemberKind.METHOD
                ? dependency.method().ownerQualifiedName()
                : dependency.field().ownerQualifiedName();
    }

    /** Renders a {@link SemanticIndex.SourceRange} as {@code relativePath:line:column} (one-based) for refusal messages. */
    private String formatLocation(SemanticIndex index, SemanticIndex.SourceRange range) {
        String relative = PlannerSupport.relative(projectRoot, range.file());
        CharSequence content = index.sourceText(range.file());
        if (content == null) {
            return relative;
        }
        int line = 1;
        int column = 1;
        int limit = Math.min(range.start(), content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return relative + ":" + line + ":" + column;
    }

    private String fieldType(SemanticIndex index, Path sourceFile, String fieldName) throws Refusal {
        SemanticIndex.SemanticField field = index.fieldByName(sourceFile, fieldName);
        if (field == null) {
            throw new Refusal("target_field_not_found", "The source type does not declare targetField '" + fieldName + "'.");
        }
        return field.type();
    }


    private Path sourceFile(Map<String, Object> fields) throws Refusal {
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

    private Path targetFile(Map<String, Object> fields, Path sourceFile, String relativeKey, String typeKey) throws Refusal {
        String explicit = stringField(fields, relativeKey, "");
        if (!explicit.isBlank()) {
            try {
                return ProjectPathResolver.resolveProjectRelative(projectRoot, explicit, relativeKey);
            } catch (ProjectPathResolver.Violation refusal) {
                throw new Refusal(refusal.code(), refusal.getMessage());
            }
        }
        return targetFileForType(fields, sourceFile, stringField(fields, typeKey, ""));
    }

    private String packageName(SemanticIndex index, Path file) {
        String packageName = index.packageNameOfAnyTask(file);
        return packageName == null ? "" : packageName;
    }

    private Path targetFileForType(Map<String, Object> fields, Path sourceFile, String typeName) throws Refusal {
        String explicit = stringField(fields, "targetRelativePath", "");
        if (!explicit.isBlank()) {
            try {
                return ProjectPathResolver.resolveProjectRelative(projectRoot, explicit, "targetRelativePath");
            } catch (ProjectPathResolver.Violation refusal) {
                throw new Refusal(refusal.code(), refusal.getMessage());
            }
        }
        String targetType = stringField(fields, "targetType", typeName);
        if (targetType.isBlank()) {
            throw new Refusal("missing_target_type", "targetType or targetRelativePath is required.");
        }
        String simple = simpleType(targetType);
        try {
            Path sameDirectory = sourceFile.getParent() == null ? projectRoot.resolve(simple + ".java") : sourceFile.getParent().resolve(simple + ".java");
            Path confinedSameDirectory = ProjectPathResolver.requireInsideProject(projectRoot, sameDirectory, "targetType");
            if (Files.exists(confinedSameDirectory)) {
                return confinedSameDirectory;
            }
            return ProjectPathResolver.resolveProjectRelative(projectRoot, targetType.replace('.', '/') + ".java", "targetType");
        } catch (ProjectPathResolver.Violation refusal) {
            throw new Refusal(refusal.code(), refusal.getMessage());
        }
    }

    private String targetTypeName(Map<String, Object> fields, Path targetFile) {
        String typeName = stringField(fields, "targetType", "");
        if (!typeName.isBlank()) {
            return simpleType(typeName);
        }
        return targetFile.getFileName().toString().replace(".java", "");
    }

    /**
     * Refuses only a TRUE overload clash in the target type (G007): a method whose name AND parameter-type list exactly
     * match the moved method's RESULTING signature. A target method that shares the name but differs in arity or
     * parameter types is a legal Java overload and is permitted. {@code movedParameterTypes} is the source method's
     * parameter-type list after any target-parameter receiver has been dropped, so the comparison reflects exactly what
     * will be declared in the target type. Both sides are compared via javac erased parameter-type identity
     * ({@link SemanticIndex#isSameErasedType}), the JLS §8.4.2 overload-clash rule, so simple-vs-FQN spellings, generic
     * instantiation vs erasure, and annotation/formatting differences never cause a true clash to be missed.
     */
    private void refuseTargetMethodSignatureCollision(
            SemanticIndex index, Path targetFile, String methodName, List<TypeMirror> movedParameterTypes) throws Refusal {
        SemanticIndex.SemanticType targetType = index.primaryType(targetFile);
        if (targetType == null || !(targetType.element() instanceof TypeElement typeElement)) {
            throw new Refusal("target_not_type", "Target file does not contain a javac-resolved top-level type.");
        }

        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (!(enclosed instanceof ExecutableElement method) || !method.getSimpleName().contentEquals(methodName)) {
                continue;
            }
            List<? extends VariableElement> targetParameters = method.getParameters();
            if (targetParameters.size() != movedParameterTypes.size()) {
                continue; // different arity: a legal overload
            }
            boolean sameSignature = true;
            for (int i = 0; i < targetParameters.size(); i++) {
                if (!index.isSameErasedType(targetParameters.get(i).asType(), movedParameterTypes.get(i))) {
                    sameSignature = false;
                    break;
                }
            }
            if (sameSignature) {
                throw new Refusal(
                        "AMBIGUOUS_OVERLOAD_AFTER_MOVE",
                        "Target type already declares a method '" + methodName
                                + "' with the same erased parameter signature; a legal overload requires a different parameter-type list.");
            }
        }
    }

    /**
     * The resolved parameter-type mirrors the moved instance method will declare in the target type (G007). When the
     * move binds the receiver to a target parameter ({@code dropParameter} true), that parameter is removed at
     * {@code droppedParameterIndex}; field- and explicit-receiver moves keep the full list. Each type is the javac
     * {@link TypeMirror} of the resolved parameter element, compared by erasure (see
     * {@link #refuseTargetMethodSignatureCollision}); an unresolved parameter type fails closed with a refusal rather
     * than silently degrading to a text comparison that could miss a real overload clash.
     */
    private List<TypeMirror> movedParameterTypes(Member member, int droppedParameterIndex, boolean dropParameter)
            throws Refusal {
        List<TypeMirror> parameterTypes = new ArrayList<>();
        List<SemanticIndex.SemanticParameter> parameters = member.semantic().method().parameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (dropParameter && i == droppedParameterIndex) {
                continue;
            }
            parameterTypes.add(resolvedParameterType(parameters.get(i)));
        }
        return parameterTypes;
    }

    /**
     * The resolved javac {@link TypeMirror} of a moved parameter. Refuses (fails closed) when the parameter element did
     * not resolve, because a missing type cannot be compared by erasure and a silent text fallback would risk missing a
     * real overload collision in the target type.
     */
    private static TypeMirror resolvedParameterType(SemanticIndex.SemanticParameter parameter) throws Refusal {
        Element element = parameter.element();
        if (element == null || element.asType() == null) {
            throw new Refusal(
                    "unresolved_moved_signature",
                    "Could not semantically resolve the moved parameter type '" + parameter.type().strip()
                            + "' to verify a target overload collision.");
        }
        return element.asType();
    }

    private void refuseTargetMemberCollision(SemanticIndex index, Path targetFile, Member moved) throws Refusal {
        SemanticIndex.SemanticType targetType = index.primaryType(targetFile);
        if (targetType == null || !(targetType.element() instanceof TypeElement typeElement)) {
            throw new Refusal("target_not_type", "Target file does not contain a javac-resolved top-level type.");
        }

        SemanticIndex.SemanticMethod movedMethod = moved.semantic().method();
        ExecutableElement movedExecutable =
                (movedMethod != null && movedMethod.element() instanceof ExecutableElement executable) ? executable : null;
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement method
                    && method.getSimpleName().contentEquals(moved.name())
                    && movedMethod != null) {
                if (movedExecutable == null) {
                    throw new Refusal(
                            "unresolved_moved_signature",
                            "Could not semantically resolve the moved method '" + moved.name()
                                    + "' to verify a target signature collision.");
                }
                if (index.sameErasedParameterTypes(method, movedExecutable)) {
                    throw new Refusal(
                            "target_member_exists",
                            "Target type already declares a method with the same erased signature as '" + moved.name() + "'.");
                }
            }
            if (isFieldElement(enclosed) && enclosed.getSimpleName().contentEquals(moved.name())) {
                throw new Refusal(
                        "target_member_exists",
                        "Target type already declares a field named '" + moved.name() + "'.");
            }
        }
    }

    private int classInsertionOffset(SemanticIndex index, Path targetFile, MemberKind kind, String target) throws Refusal {
        SemanticIndex.SemanticType targetType = index.primaryType(targetFile);
        if (targetType == null || !(targetType.element() instanceof TypeElement typeElement)) {
            throw new Refusal("target_not_type", "Target file does not contain a javac-resolved top-level type.");
        }

        int close = targetType.bodyRange().end() - 1;
        int lastSameKindEnd = -1;
        int firstMethodStart = -1;
        for (Element enclosed : typeElement.getEnclosedElements()) {
            boolean sameKind = kind == MemberKind.FIELD ? isFieldElement(enclosed) : enclosed.getKind() == ElementKind.METHOD;
            if (sameKind) {
                SemanticIndex.DeclarationRange range = index.declarationRange(enclosed);
                if (sameFile(targetFile, range.file()) && range.end() <= close) {
                    lastSameKindEnd = Math.max(lastSameKindEnd, range.end());
                }
            } else if (kind == MemberKind.FIELD && enclosed.getKind() == ElementKind.METHOD) {
                SemanticIndex.DeclarationRange range = index.declarationRange(enclosed);
                if (sameFile(targetFile, range.file()) && range.start() < close) {
                    firstMethodStart = firstMethodStart < 0 ? range.start() : Math.min(firstMethodStart, range.start());
                }
            }
        }

        if (lastSameKindEnd >= 0) {
            return lineEndAfter(target, lastSameKindEnd);
        }
        if (kind == MemberKind.FIELD && firstMethodStart >= 0) {
            return lineStart(target, firstMethodStart);
        }
        return close;
    }

    private int lineEndAfter(String source, int offset) {
        int cursor = Math.max(0, Math.min(offset, source.length()));
        while (cursor < source.length() && source.charAt(cursor) != '\n') {
            cursor++;
        }
        return cursor < source.length() ? cursor + 1 : cursor;
    }

    private boolean sameFile(Path left, Path right) {
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }

    private static boolean isFieldElement(Element element) {
        return element.getKind() == ElementKind.FIELD || element.getKind() == ElementKind.ENUM_CONSTANT;
    }

    private Parameter parameter(String parameters, String name) throws Refusal {
        String[] parts = parameters.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int split = trimmed.lastIndexOf(' ');
            if (split < 0) {
                continue;
            }
            String parameterName = trimmed.substring(split + 1).trim();
            if (parameterName.equals(name)) {
                String type = trimmed.substring(0, split).trim();
                return new Parameter(type, parameterName);
            }
        }
        throw new Refusal("target_parameter_not_found", "Target parameter '" + name + "' was not found.");
    }

    /** Zero-based declaration index of the parameter named {@code name}, or {@code -1} when it is not declared. */
    private int parameterIndex(String parameters, String name) {
        List<String> declared = splitParameters(parameters);
        for (int i = 0; i < declared.size(); i++) {
            String trimmed = declared.get(i);
            int split = trimmed.lastIndexOf(' ');
            if (split >= 0 && trimmed.substring(split + 1).trim().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private int intField(Map<String, Object> fields, String key) throws Refusal {
        Object value = fields.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new Refusal("missing_" + key, key + " is required.");
    }

    private static String stringField(Map<String, Object> fields, String key, String defaultValue) {
        Object value = fields.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static String simpleType(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }

    private String refusedJson(String operation, boolean apply, String code, String message) {
        // Blocker 3: route through the one canonical refusal builder so a refused result always reports applied:false
        // (never the incoming apply flag) and the actual requested mode, with centrally-derived empty counts.
        return ResponseBuilder.refusedResult(operation, apply, code, message);
    }

    enum MemberKind {
        METHOD,
        FIELD
    }

    record Member(MemberKind kind, String modifiers, String type, String name, String parameters, int removeStart, int removeEnd, String text, SemanticIndex.SemanticMember semantic) {
        boolean isStatic() {
            return modifiers.contains("static");
        }

        SemanticIndex.SourceRange declarationRange() {
            Path file = kind == MemberKind.METHOD ? semantic.method().file() : semantic.field().file();
            return new SemanticIndex.SourceRange(file, removeStart, removeEnd);
        }

        Member withModifiers(String replacementModifiers) {
            String rewritten = text.replaceFirst(Pattern.quote(modifiers), Matcher.quoteReplacement(replacementModifiers));
            return new Member(kind, replacementModifiers, type, name, parameters, removeStart, removeEnd, rewritten, semantic);
        }

        Member withName(String replacementName) {
            String rewritten;
            if (kind == MemberKind.METHOD) {
                rewritten = text.replaceFirst("\\b" + Pattern.quote(name) + "\\s*\\(", Matcher.quoteReplacement(replacementName + "("));
            } else {
                rewritten = text.replaceFirst("\\b" + Pattern.quote(name) + "\\b", Matcher.quoteReplacement(replacementName));
            }
            return new Member(kind, modifiers, type, replacementName, parameters, removeStart, removeEnd, rewritten, semantic);
        }

        Member withoutParameter(Parameter parameter) throws Refusal {
            List<String> kept = new ArrayList<>();
            for (String raw : MoveMemberPlanner.splitParameters(parameters)) {
                if (!raw.trim().endsWith(" " + parameter.name())) {
                    kept.add(raw.trim());
                }
            }
            // G015: AST-proven removal of the parameter from the header AND of its in-body receiver references, instead
            // of brittle raw-string signature/`.replace` rewrites that could touch comments, strings, or unrelated names.
            String rewritten = ReceiverRewritePlanner.astRewriteMember(text, parameter.name(), true);
            return new Member(kind, modifiers, type, name, String.join(", ", kept), removeStart, removeEnd, rewritten, semantic);
        }

        Member withoutReceiver(String receiverName) throws Refusal {
            // G015/G008: a simple-identifier receiver (target field or explicit single name) is rewritten via the proven
            // identifier AST pass; a COMPOUND explicit receiver (`(Target) raw`, `holder.targets[0]`) is rewritten via the
            // source-position pass, which removes only AST-resolved `<receiver>.member` qualifier spans and REFUSES when
            // the body's positions cannot be resolved — never a raw text replace that could corrupt comments or strings.
            String rewritten = ReceiverRewritePlanner.SIMPLE_IDENTIFIER.matcher(receiverName).matches()
                    ? ReceiverRewritePlanner.astRewriteMember(text, receiverName, false)
                    : ReceiverRewritePlanner.rewriteCompoundReceiverBody(text, receiverName);
            return new Member(kind, modifiers, type, name, parameters, removeStart, removeEnd, rewritten, semantic);
        }

        String delegateFor(Parameter parameter, String targetMethodName, io.serena.javarefactor.shared.JavaStyleProfile style) {
            List<String> forwarded = new ArrayList<>();
            for (String raw : MoveMemberPlanner.splitParameters(parameters)) {
                String trimmed = raw.trim();
                if (!trimmed.endsWith(" " + parameter.name())) {
                    String[] tokens = trimmed.split("\\s+");
                    forwarded.add(tokens[tokens.length - 1]);
                }
            }
            String invocation = parameter.name() + "." + targetMethodName + "(" + String.join(", ", forwarded) + ")";
            String returnPrefix = type.equals("void") ? "" : "return ";
            // Honor the inferred brace style, line endings, and indentation unit so the retained delegate matches the
            // surrounding source. The signature line carries no leading-indent prefix (preserving the original
            // renderer's convention); the body and closing brace are indented with the inferred member/child indent.
            String memberIndent = style.memberIndent();
            return style.normalizeLineEndings(modifiers + " " + type + " " + name + "(" + parameters + ")"
                    + style.openBrace(memberIndent) + "\n" + style.childIndent(memberIndent) + returnPrefix + invocation + ";\n"
                    + memberIndent + "}");
        }

    }

    private static List<String> splitParameters(String text) {
        List<String> result = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.trim().isEmpty()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    record Parameter(String type, String name) {}

    static final class Refusal extends Exception {
        private final String code;

        Refusal(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
