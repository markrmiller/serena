package io.serena.javarefactor.operations.encapsulate_field;

import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.shared.SemanticTargetGate;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.shared.ExpressionPurity;
import io.serena.javarefactor.shared.ExpressionPurityAnalyzer;
import io.serena.javarefactor.shared.ImportConflictResolvers;
import io.serena.javarefactor.shared.ImportManager;
import io.serena.javarefactor.shared.JavaStyleProfile;
import io.serena.javarefactor.shared.ProjectPathResolver;

import io.serena.javarefactor.shared.SourceText;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import java.util.ArrayList;

/** V2 field refactor planner backed by javac class/field/reference resolution and source positions. */
public final class FieldRefactorPlanner {
    private final Path projectRoot;
    private final JavaProjectModel model;

    public FieldRefactorPlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
    }

    public String introduceField(Map<String, Object> fields, boolean apply) {
        try {
            Path file = sourceFile(fields);
            String source = SourceText.read(model, file);
            String fieldName = requiredIdentifier(fields, "fieldName");
            boolean constant = Boolean.TRUE.equals(fields.get("constant"));
            boolean initializeInConstructor = Boolean.TRUE.equals(fields.get("initializeInConstructor"));
            if (constant && initializeInConstructor) {
                throw new Refusal("conflicting_field_modes", "introduceField accepts either constant or constructor initialization, not both.");
            }

            String relativePath = PlannerSupport.relative(projectRoot, file);
            try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
                // Verify the position-named target (enclosing type/method) before planning. introduceField's edited
                // declaration is the enclosing type, which the position may legitimately differ from, so the cross-check
                // is the gate's name/kind/arity/origin verification rather than confirmSelection against the type.
                SemanticTargetGate.require(index, relativePath, fields);
                if (index.fieldNameExists(file, fieldName)) {
                    throw new Refusal("field_already_exists", "A field with the requested fieldName already exists.");
                }

                SemanticIndex.SemanticType sourceType = index.primaryType(file);
                if (sourceType == null) {
                    throw new Refusal("source_type_not_found", "Source file must contain a javac-resolved top-level class declaration.");
                }

                SelectedInitializer initializer = selectedInitializer(index, file, source, fields);
                validateInitializer(index, file, initializer, constant, initializeInConstructor);

                String requestedType = stringField(fields, "fieldType", "").trim();
                String inferredType = requestedType.isBlank() ? initializer.type() : requestedType;
                if (inferredType == null || inferredType.isBlank()) {
                    throw new Refusal("expression_type_unknown", "introduceField could not infer the selected expression type; pass fieldType explicitly.");
                }

                JavaStyleProfile style = JavaStyleProfile.infer(source);
                String indent = style.memberIndent();
                String bodyIndent = style.childIndent(indent);
                // Config mapping end-to-end: the operation_defaults.visibility default (and any introduce_field override)
                // is merged into fields["visibility"] by Main.applyConfiguredDefaults. Honor it here so a configured
                // default access level is not silently dropped; the unset/blank case keeps the historical "private".
                String accessModifier = fieldVisibility(fields);
                List<PlannerSupport.TextEdit> edits = new ArrayList<>();
                TypeRender typeRender = renderFieldType(index, file, source, inferredType);
                edits.addAll(typeRender.importEdits());

                String declaration;
                if (constant) {
                    declaration = style.renderField(joinModifiers(accessModifier, "static final"), typeRender.text(), fieldName, initializer.text());
                } else if (initializeInConstructor) {
                    declaration = style.renderField(joinModifiers(accessModifier, "final"), typeRender.text(), fieldName, "");
                    if (index.constructors(file).isEmpty()) {
                        String constructorVisibility = sourceType.element().getModifiers().contains(javax.lang.model.element.Modifier.PUBLIC) ? "public " : "";
                        declaration = declaration + style.normalizeLineEndings("\n" + indent + constructorVisibility + sourceType.name() + "()" + style.openBrace(indent) + "\n"
                                + bodyIndent + "this." + fieldName + " = " + initializer.text() + ";\n" + indent + "}\n");
                    } else {
                        String constructorStrategy = stringField(fields, "constructorStrategy", "").trim();
                        addConstructorAssignments(index, sourceType, file, source, style, bodyIndent, fieldName, initializer.text(), constructorStrategy, edits);
                    }
                } else {
                    declaration = style.renderField(joinModifiers(accessModifier, "final"), typeRender.text(), fieldName, initializer.text());
                }

                edits.add(new PlannerSupport.TextEdit(file, sourceType.bodyRange().start() + 1, sourceType.bodyRange().start() + 1, declaration, "INTRODUCE_FIELD_DECLARATION"));
                if (initializer.replaceStart() >= 0) {
                    String replacement = fieldReplacementReference(index, initializer.selection(), sourceType, fieldName, constant);
                    edits.add(new PlannerSupport.TextEdit(file, initializer.replaceStart(), initializer.replaceEnd(), replacement, "INTRODUCE_FIELD_USE"));
                }

                String introduceKeyJson = SemanticKey.from(sourceType.element()).toJson();
                return ResponseBuilder.acceptedResult(projectRoot, "introduceField", apply,
                        "{\"identity\":" + introduceKeyJson + ",\"semanticKey\":" + introduceKeyJson + "}",
                        edits, java.util.List.of(), List.of(
                        "V2 introduceField uses javac expression selection, inferred types, constructor fan-out, and import rendering; unsupported capture/order cases are refused with diagnostics."),
                        java.util.List.of("field refactor semantic target resolved by javac"), ResponseBuilder.DiagnosticDelta.unvalidated(), false);
            }
        } catch (Refusal refusal) {
            return PlannerSupport.refusalJson("introduceField", apply, refusal.code, refusal.getMessage());
        } catch (SemanticTargetGate.Refused refused) {
            return PlannerSupport.refusalJson("introduceField", apply, refused.code(), refused.getMessage());
        } catch (Exception error) {
            return PlannerSupport.refusalJson("introduceField", apply, "introduce_field_failed", error.getMessage());
        }
    }


    /**
     * Returns a whole-file preview that makes a simple field private and routes direct reads/writes through accessors.
     *
     * <p>G003: the encapsulate-field logic now lives in its named {@link EncapsulateFieldPlanner}; this method delegates
     * to it so the operation→class naming lines up with the V2 plan while existing callers of {@code FieldRefactorPlanner}
     * keep working. Behavior is unchanged.
     */
    public String encapsulateField(Map<String, Object> fields, boolean apply) {
        return new EncapsulateFieldPlanner(projectRoot, model).encapsulateField(fields, apply);
    }

    /**
     * G028/HB-7: chooses a scope-correct reference to the freshly introduced field for the replacement site. The default
     * is the unqualified {@code fieldName}; it is qualified ONLY when an unqualified reference would bind to a same-named
     * local value binding (local/parameter/pattern/resource/catch/for-loop variable) visible at the selection site,
     * decided from javac's lexical scope ({@link SemanticIndex#selectionNameBindsToLocal}) rather than a declaration-shaped
     * regex over the enclosing method text. A constant (static final) field is qualified as {@code ClassName.FIELD}; an
     * instance field is qualified as {@code this.fieldName}. This guarantees the replacement resolves to the new field and
     * can never silently bind to a shadowing local/parameter (including implicitly-typed lambda parameters and pattern
     * bindings the old regex missed), nor be fooled by declaration-like text in a comment or string.
     */
    private String fieldReplacementReference(
            SemanticIndex index, SemanticIndex.SemanticExpressionSelection selection, SemanticIndex.SemanticType type,
            String fieldName, boolean constant) {
        if (selection != null && index.selectionNameBindsToLocal(selection, fieldName)) {
            return constant ? type.name() + "." + fieldName : "this." + fieldName;
        }
        return fieldName;
    }

    private Path sourceFile(Map<String, Object> fields) {
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

    private record SelectedInitializer(String text, String type, int replaceStart, int replaceEnd, SemanticIndex.SemanticExpressionSelection selection) {
    }

    private record TypeRender(String text, List<PlannerSupport.TextEdit> importEdits) {
    }

    private SelectedInitializer selectedInitializer(SemanticIndex index, Path file, String source, Map<String, Object> fields) throws Refusal {
        Object selectionRaw = fields.get("selection");
        if (selectionRaw instanceof Map<?, ?> selection) {
            SemanticIndex.SemanticExpressionSelection selected = index.selectedExpression(
                    file,
                    intField(selection, "startLine"),
                    intField(selection, "startColumn"),
                    intField(selection, "endLine"),
                    intField(selection, "endColumn"));
            if (selected == null) {
                throw new Refusal("missing_selected_expression", "introduceField requires a javac-resolved expression selection range.");
            }
            String text = selected.text().trim();
            if (text.isBlank()) {
                throw new Refusal("missing_initializer", "Selected expression is empty.");
            }
            return new SelectedInitializer(text, selected.type(), selected.range().start(), selected.range().end(), selected);
        }

        String initializer = stringField(fields, "initializer", "").trim();
        if (initializer.isBlank()) {
            throw new Refusal("missing_selected_expression", "introduceField requires selection or initializer.");
        }
        return new SelectedInitializer(initializer, "", -1, -1, null);
    }

    private void validateInitializer(SemanticIndex index, Path file, SelectedInitializer initializer, boolean constant, boolean initializeInConstructor) throws Refusal {
        if (initializer.selection() == null) {
            ExpressionPurity purity = new ExpressionPurityAnalyzer().classify(initializer.text());
            if (purity != ExpressionPurity.PURE) {
                throw new Refusal("unsafe_initializer", "Initializer text must parse as a pure Java expression; use selection for semantic capture diagnostics.");
            }
            if (constant && !isLiteralConstantText(initializer.text())) {
                throw new Refusal("non_constant_initializer", "static final introduceField requires a literal initializer when no semantic selection is provided.");
            }
            if (!constant && !initializeInConstructor && !isLiteralConstantText(initializer.text())) {
                // G027 migration: a detached initializer string carries no resolvable symbols, so its reorder safety can
                // never be proven (ExpressionPurityAnalyzer.isReorderSafe(String) is always false). Only a literal
                // constant is provably safe to inline without a semantic selection; anything else must use a semantic
                // selection (for a real javac proof) or request constructor initialization.
                throw new Refusal("initialization_order_unsupported", "Inline initialization from detached initializer text cannot be proven reorder-safe; pass a semantic selection, request constructor initialization, or provide a literal constant.");
            }
            return;
        }

        SemanticIndex.SemanticExpressionSelection selection = initializer.selection();
        if (selection.purity() == ExpressionPurity.SIDE_EFFECTING || selection.purity() == ExpressionPurity.UNKNOWN) {
            throw new Refusal("unsafe_selected_expression", "Selected expression is not provably pure; introduceField refuses side-effecting or unknown initializers.");
        }
        if (!selection.inputs().isEmpty()) {
            String captured = selection.inputs().stream().map(SemanticIndex.SemanticExtractVariable::name).distinct().sorted().toList().toString();
            throw new Refusal("local_variable_capture", "Selected expression captures local variables or parameters unavailable to a field initializer: " + captured + ".");
        }
        if (!selection.checkedExceptions().isEmpty()) {
            throw new Refusal("checked_exception_initializer", "Selected expression can throw checked exceptions that a field initializer cannot declare: " + selection.checkedExceptions() + ".");
        }
        if (constant) {
            if (selection.usesThis() || selection.usesSuper() || !selection.compileTimeConstant()) {
                throw new Refusal("non_constant_initializer", "static final introduceField requires a javac-proven compile-time constant expression with no instance dependencies.");
            }
            return;
        }
        if (!initializeInConstructor && selection.enclosingExecutable() != null && !selection.compileTimeConstant()) {
            // G027: a non-constant inline instance-field initializer evaluates the expression at construction time
            // instead of at its original method-body position. Allow it ONLY when the expression is provably safe to
            // hoist: a side-effect-free fresh allocation (ALLOCATION_ONLY) selected from an instance context that reads
            // no instance state (this/super), captures no locals/parameters, and throws no checked exceptions. Such an
            // allocation has no evaluation-order dependency on mutable state, so the inline initializer position is
            // order-equivalent. Anything else (a mutable-state read, an UNKNOWN call) cannot be proven reorder-safe —
            // mirroring ExpressionPurityAnalyzer's reorder-safety contract — and is refused.
            // G004: the inline-init green-light is the canonical javac TreePath verdict, not the coarse classify() purity.
            // The expression must be a fresh allocation (ALLOCATION_ONLY) whose real AST node is additionally proven
            // reorder-safe by the SemanticIndex bridge (side-effect free, reads only stable final state) — so e.g. a
            // `new Foo(mutableField)` allocation that reads non-final state is refused rather than hoisted.
            boolean safeInlineAllocation = selection.purity() == ExpressionPurity.ALLOCATION_ONLY
                    && index.isExpressionReorderSafe(file, selection.range())
                    && !selection.enclosingMethodStatic()
                    && !selection.usesThis()
                    && !selection.usesSuper()
                    && selection.inputs().isEmpty()
                    && selection.checkedExceptions().isEmpty();
            if (!safeInlineAllocation) {
                throw new Refusal("initialization_order_unsupported", "Inline field initialization would move a runtime expression out of its executable evaluation order; request constructor initialization or select a compile-time constant or a side-effect-free fresh allocation.");
            }
        }
        if (initializeInConstructor && (selection.usesThis() || selection.usesSuper())) {
            throw new Refusal("initialization_order_unsupported", "Constructor initialization cannot safely move expressions that depend on this/super before constructor body ordering is analyzed.");
        }
    }

    private boolean isLiteralConstantText(String text) {
        String value = text.trim();
        if (value.equals("true") || value.equals("false")) {
            return true;
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return true;
        }
        if (value.length() >= 3 && value.startsWith("'") && value.endsWith("'")) {
            return true;
        }
        try {
            Double.parseDouble(value.replace("_", ""));
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void addConstructorAssignments(
            SemanticIndex index,
            SemanticIndex.SemanticType sourceType,
            Path file,
            String source,
            JavaStyleProfile style,
            String bodyIndent,
            String fieldName,
            String initializer,
            String constructorStrategy,
            List<PlannerSupport.TextEdit> edits) {
        List<SemanticIndex.SemanticConstructor> constructors = index.constructors(file);
        if (constructors.isEmpty()) {
            throw new Refusal("constructor_required", "Constructor initialization requires an explicit constructor; introduceField will not broaden a default constructor.");
        }
        if (constructors.size() > 1 && constructorStrategy.isBlank()) {
            throw new Refusal("constructor_strategy_required", "Multiple constructors require explicit constructorStrategy=allTerminal before introduceField will fan out assignments.");
        }
        if (!constructorStrategy.isBlank() && !"allTerminal".equals(constructorStrategy)) {
            throw new Refusal("unsupported_constructor_strategy", "introduceField supports constructorStrategy=allTerminal only.");
        }
        String assignment = style.normalizeLineEndings("\n" + bodyIndent + "this." + fieldName + " = " + initializer + ";");
        boolean assignedAny = false;
        for (SemanticIndex.SemanticConstructor constructor : constructors) {
            if (constructor.delegatesToThis()) {
                continue;
            }
            edits.add(new PlannerSupport.TextEdit(file, constructor.assignmentOffset(), constructor.assignmentOffset(), assignment, "INTRODUCE_FIELD_CONSTRUCTOR_ASSIGNMENT"));
            assignedAny = true;
        }
        if (!assignedAny) {
            edits.add(new PlannerSupport.TextEdit(file, constructors.get(0).assignmentOffset(), constructors.get(0).assignmentOffset(), assignment, "INTRODUCE_FIELD_CONSTRUCTOR_ASSIGNMENT"));
        }
    }

    private TypeRender renderFieldType(SemanticIndex index, Path file, String source, String fieldType) {
        // Deep planning imports nested/generic/array/varargs/wildcard/annotation components of the field type, not just
        // the outer raw type; the conflict resolver leaves a same-package/project-colliding simple name fully qualified.
        ImportManager.TypeUse typeUse = new ImportManager(source)
                .withConflictResolver(ImportConflictResolvers.samePackageAndProject(index, file, index.packageNameOf(file)))
                .planTypeUsageDeep(file, fieldType, "INTRODUCE_FIELD_IMPORT");
        return new TypeRender(typeUse.renderedType(), typeUse.importEdits());
    }

    private long offset(String source, int oneBasedLine, int oneBasedColumn) {
        int line = 1;
        int column = 1;
        for (int index = 0; index < source.length(); index++) {
            if (line == oneBasedLine && column == oneBasedColumn) {
                return index;
            }
            char ch = source.charAt(index);
            if (ch == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        if (line == oneBasedLine && column == oneBasedColumn) {
            return source.length();
        }
        throw new Refusal("selection_out_of_range", "Selection is outside the source file.");
    }

    /**
     * Resolves the introduced field's access modifier from the effective {@code visibility} default (sourced from the
     * V2 {@code operation_defaults.visibility} block, or an {@code introduce_field} override, by
     * {@code Main.applyConfiguredDefaults}). Recognized values are {@code private}/{@code protected}/{@code public} and
     * {@code package}/{@code package-private}/{@code default} (which render as no access keyword). A blank/unset value
     * keeps the historical {@code private} default; an unrecognized value is refused with a structured reason code
     * rather than being silently dropped.
     */
    private String fieldVisibility(Map<String, Object> fields) {
        String requested = stringField(fields, "visibility", "").trim();
        if (requested.isBlank()) {
            return "private";
        }
        switch (requested.toLowerCase(java.util.Locale.ROOT)) {
            case "private":
                return "private";
            case "protected":
                return "protected";
            case "public":
                return "public";
            case "package":
            case "package-private":
            case "package_private":
            case "default":
                // Package-private has no Java access keyword; the field declaration omits the access modifier entirely.
                return "";
            default:
                throw new Refusal("invalid_visibility",
                        "introduceField visibility must be one of private/protected/public/package; got '" + requested + "'.");
        }
    }

    /**
     * Joins a resolved access keyword (possibly empty for package-private) with the remaining modifier keywords,
     * collapsing the leading space so a package-private field renders {@code final Type name} rather than
     * {@code  final Type name}.
     */
    private String joinModifiers(String accessModifier, String otherModifiers) {
        if (accessModifier.isBlank()) {
            return otherModifiers;
        }
        return accessModifier + " " + otherModifiers;
    }

    private String requiredIdentifier(Map<String, Object> fields, String name) {
        String value = stringField(fields, name, "");
        if (!value.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new Refusal("invalid_" + name, name + " must be a Java identifier.");
        }
        return value;
    }

    private int intField(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new Refusal("missing_" + name, name + " is required.");
    }

    private String stringField(Map<String, Object> fields, String name, String fallback) {
        Object value = fields.get(name);
        return value instanceof String text ? text : fallback;
    }

    private static final class Refusal extends RuntimeException {
        private final String code;

        private Refusal(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
