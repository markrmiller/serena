package io.serena.javarefactor.operations.encapsulate_field;

import io.serena.javarefactor.ast.ResolvedTarget;
import io.serena.javarefactor.ast.SemanticKey;
import io.serena.javarefactor.compiler.FrameworkEncapsulationReview;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.edits.ResponseBuilder;
import io.serena.javarefactor.project.GeneratedSourcePolicy;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.shared.JavaStyleProfile;
import io.serena.javarefactor.shared.ProjectPathResolver;
import io.serena.javarefactor.shared.SemanticTargetGate;
import io.serena.javarefactor.shared.SourceText;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2 planner that owns the <em>encapsulate-field</em> operation (plan §3 {@code encapsulate_field}).
 *
 * <p>G003: the V2 class layout maps each operation to a like-named planner. Encapsulate-field used to share
 * {@link FieldRefactorPlanner} with introduce-field, so {@code grep EncapsulateFieldPlanner} found nothing and the
 * operation→class naming drifted from the plan. This class is the named home of the encapsulate-field path; it resolves
 * the field and its same-project references with javac Elements/source positions, makes the declaration private, and
 * routes direct reads/writes through synthesized JavaBean accessors. {@link FieldRefactorPlanner#encapsulateField} now
 * delegates here so existing callers keep working while the logic lives in its named class.
 */
public final class EncapsulateFieldPlanner {
    /** Matches a leading run of whitespace and field annotations (e.g. {@code @Deprecated @Nullable}) so a visibility
     * keyword can be inserted after the annotation block rather than in front of it. */
    private static final Pattern LEADING_ANNOTATIONS =
            Pattern.compile("^(\\s*(?:@[\\w.]+(?:\\s*\\([^)]*\\))?\\s*)*)");

    private final Path projectRoot;
    private final JavaProjectModel model;

    public EncapsulateFieldPlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
    }

    /** Returns a whole-file preview that makes a simple field private and routes direct reads/writes through accessors. */
    public String encapsulateField(Map<String, Object> fields, boolean apply) {
        try {
            Path file = sourceFile(fields);
            String source = SourceText.read(model, file);
            String fieldName = stringField(fields, "fieldName", "");
            String relativePath = PlannerSupport.relative(projectRoot, file);
            // Planner-level editability gate (defense in depth): refuse encapsulating a field that lives in generated,
            // @Generated, or Lombok-managed source unless the caller explicitly opts in. This mirrors the protocol-level
            // pre-dispatch gate in Main so a direct planner invocation cannot silently rewrite a non-editable / synthetic
            // field, and is driven by the build model's generated source roots (authoritative) plus the shared fallback
            // heuristics rather than a coarse single source-file check.
            assertEditableField(file, relativePath, source, fields);
            try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
            ResolvedTarget verified = SemanticTargetGate.require(index, relativePath, fields);
            if (verified != null) {
                // Drive field selection by the gate-verified simple name so a same-line/overloaded collision resolves to
                // the proven field rather than the narrowest declaration on the line.
                fieldName = verified.element().getSimpleName().toString();
            }
            SemanticIndex.SemanticType sourceType = index.primaryType(file);
            if (sourceType == null) {
                throw new Refusal("source_type_not_found", "Source file must contain a javac-resolved top-level class declaration.");
            }
            if ("record".equals(sourceType.kind())) {
                throw new Refusal("record_component_unsupported", "V2 encapsulate field refuses record components.");
            }
            if ("enum".equals(sourceType.kind())) {
                throw new Refusal("enum_constants_unsupported", "V2 encapsulate field refuses enum constants.");
            }
            SemanticIndex.SemanticField field = index.selectedField(file, fields.containsKey("line") ? intField(fields, "line") : -1, fieldName);
            if (field == null) {
                throw new Refusal("field_not_found", "No javac-resolved instance field matched the request.");
            }
            SemanticTargetGate.confirmSelection(verified, field.element());
            boolean staticField = field.modifiers().contains(javax.lang.model.element.Modifier.STATIC);
            // G029: static fields are supported with static accessors and static reference rewriting. A static FINAL
            // constant has no encapsulation semantics (no setter is possible and reads are compile-time-inlined), so it
            // is the one static case that remains unsupported.
            if (staticField && field.modifiers().contains(javax.lang.model.element.Modifier.FINAL)) {
                throw new Refusal("static_field_unsupported", "V2 encapsulate field supports mutable static fields but refuses static final constants, which have no encapsulation semantics.");
            }
            // G030: volatile / concurrency-sensitive fields are refused UNLESS the caller explicitly opts in. A simple
            // get/set accessor performs a single volatile read/write that preserves the field's volatile semantics; the
            // field keeps its volatile modifier and compound / increment usages remain refused by the reference
            // rewriter, so the opt-in cannot silently introduce a non-atomic read-modify-write.
            boolean allowVolatile = boolField(fields, "allowVolatile") || boolField(fields, "allowConcurrency");
            if (field.modifiers().contains(javax.lang.model.element.Modifier.VOLATILE) && !allowVolatile) {
                throw new Refusal("concurrency_sensitive_field", "V2 encapsulate field refuses volatile/concurrency-sensitive fields unless allowVolatile (or allowConcurrency) is set.");
            }
            String type = field.type();
            String name = field.name();
            String getterName = stringField(fields, "getterName", AccessorSynthesizer.defaultGetterName(type, name));
            boolean generateSetter = !Boolean.FALSE.equals(fields.get("setter"));
            String setterName = stringField(fields, "setterName", generateSetter ? AccessorSynthesizer.defaultSetterName(name) : "");
            // Refuse only on a genuine signature collision: a getter is no-arg and a setter is single-arg, so an
            // existing method with the same name AND matching arity would clash, while a distinct overload is left
            // alone. The refusal carries the colliding declaration's source location.
            SemanticIndex.SourceRange getterCollision = index.accessorCollisionRange(file, getterName, 0);
            if (getterCollision != null) {
                throw new Refusal("accessor_collision",
                        "Requested getter '" + getterName + "()' collides with an existing no-argument method at "
                                + locationLabel(source, getterCollision) + ".");
            }
            if (generateSetter) {
                SemanticIndex.SourceRange setterCollision = index.accessorCollisionRange(file, setterName, 1);
                if (setterCollision != null) {
                    throw new Refusal("accessor_collision",
                            "Requested setter '" + setterName + "(...)' collides with an existing single-argument method at "
                                    + locationLabel(source, setterCollision) + ".");
                }
            }
            boolean updateReferences = !Boolean.FALSE.equals(fields.get("updateReferences"));
            // G031: the same-class direct-access policy is controlled independently of the global updateReferences
            // switch. updateReferences=false suppresses ALL accessor rewriting; with updateReferences=true the
            // rewriteInternalUsages policy decides whether direct accesses inside the declaring class are also routed
            // through the accessors (true, the default) or left as direct field access (false). External references are
            // always rewritten when updateReferences=true regardless of this policy.
            // G001: the rewriteInternalUsages default mirrors the Python config default (false) — when the key is absent
            // direct accesses inside the declaring class are LEFT as direct field access; only an explicit true routes
            // them through the accessors.
            boolean rewriteInternalUsages = boolField(fields, "rewriteInternalUsages");
            // G002 (V2 hard scope): compound assignment and increment/decrement on an encapsulated field ALWAYS produce
            // structured refusals in V2. There is no opt-out: the config key refuse_compound_assignments is not mapped
            // (see Main config rules) and this value is hardwired true, so the expression-preserving compound rewrite in
            // SemanticIndex is unreachable from V2. (A later plan may promote that rewrite behind its own acceptance
            // matrix.)
            boolean refuseCompoundAssignments = true;
            List<PlannerSupport.TextEdit> semanticEdits = new ArrayList<>();
            String originalField = source.substring(field.declarationRange().start(), field.declarationRange().end());
            String rewrittenField = rewriteVisibilityToPrivate(originalField, field.modifiers());
            semanticEdits.add(new PlannerSupport.TextEdit(file, field.declarationRange().start(), field.declarationRange().end(), rewrittenField, "ENCAPSULATE_FIELD_VISIBILITY"));
            if (updateReferences) {
                List<PlannerSupport.TextEdit> referenceEdits;
                try {
                    referenceEdits = FieldAccessRewriter.plan(
                            index, field, getterName, setterName, generateSetter,
                            rewriteInternalUsages, refuseCompoundAssignments, sourceType.bodyRange());
                } catch (FieldAccessRewriter.Refused refused) {
                    throw new Refusal(refused.code(), refused.getMessage());
                }
                semanticEdits.addAll(referenceEdits);
            }
            JavaStyleProfile style = JavaStyleProfile.infer(source);
            String indent = style.memberIndent();
            String bodyIndent = style.childIndent(indent);
            // G029: a static field gets static accessors; the setter assigns through the declaring type name (a static
            // context cannot use this.), which also disambiguates a field literally named "value" from the parameter.
            String setterTarget = staticField ? sourceType.name() + "." + name : "this." + name;
            String accessors = AccessorSynthesizer.renderAccessors(
                    style, indent, bodyIndent, staticField, type, getterName, name, generateSetter, setterName, setterTarget);
            semanticEdits.add(new PlannerSupport.TextEdit(file, sourceType.bodyRange().end() - 1, sourceType.bodyRange().end() - 1, accessors, "ENCAPSULATE_FIELD_ACCESSORS"));
            String encapsulateKeyJson = SemanticKey.from(field.element()).toJson();
            List<String> warnings = new ArrayList<>();
            warnings.add("V2 encapsulateField resolves the field and same-project references with javac Elements/source positions, refusing unsupported write contexts.");
            // Framework participation (§16.2/§16.3): exact-FQN-gated review warnings when the encapsulated field carries a
            // JPA mapping annotation (field-access entity) or a Jackson member binding the new accessors could alter.
            javax.lang.model.element.Element fieldOwner = field.element().getEnclosingElement();
            if (fieldOwner instanceof javax.lang.model.element.TypeElement ownerType) {
                warnings.addAll(FrameworkEncapsulationReview.reviewWarnings(
                        index, ownerType.getQualifiedName().toString(), field.element().getSimpleName().toString()));
            }
            return ResponseBuilder.acceptedResult(projectRoot, "encapsulateField", apply,
                    "{\"identity\":" + encapsulateKeyJson + ",\"semanticKey\":" + encapsulateKeyJson + "}",
                    semanticEdits, java.util.List.of(), warnings,
                    java.util.List.of("field refactor semantic target resolved by javac"), ResponseBuilder.DiagnosticDelta.unvalidated(), false);
            }
        } catch (Refusal refusal) {
            return PlannerSupport.refusalJson("encapsulateField", apply, refusal.code, refusal.getMessage());
        } catch (SemanticTargetGate.Refused refused) {
            return PlannerSupport.refusalJson("encapsulateField", apply, refused.code(), refused.getMessage());
        } catch (Exception error) {
            return PlannerSupport.refusalJson("encapsulateField", apply, "encapsulate_field_failed", error.getMessage());
        }
    }

    /**
     * Rewrites a field declaration's access modifier to {@code private}, driven by the field's resolved javac
     * {@link javax.lang.model.element.Modifier} set rather than by guessing from the leading token.
     *
     * <ul>
     *   <li>Already {@code private}: returned unchanged (the edit is a no-op replacement, preserving edit count).</li>
     *   <li>{@code public}/{@code protected}: the exact access keyword is replaced in place, so other modifiers
     *       ({@code static}, {@code final}, {@code transient}) and any annotations keep their position.</li>
     *   <li>Package-private: {@code private } is inserted after any leading annotation block and before the first
     *       remaining modifier/type token, never in front of the annotations.</li>
     * </ul>
     */
    private String rewriteVisibilityToPrivate(String declaration, java.util.Set<javax.lang.model.element.Modifier> modifiers) {
        if (modifiers.contains(javax.lang.model.element.Modifier.PRIVATE)) {
            return declaration;
        }
        if (modifiers.contains(javax.lang.model.element.Modifier.PUBLIC)) {
            return declaration.replaceFirst("\\bpublic\\b", "private");
        }
        if (modifiers.contains(javax.lang.model.element.Modifier.PROTECTED)) {
            return declaration.replaceFirst("\\bprotected\\b", "private");
        }
        Matcher annotations = LEADING_ANNOTATIONS.matcher(declaration);
        if (annotations.find()) {
            int insertAt = annotations.end();
            return declaration.substring(0, insertAt) + "private " + declaration.substring(insertAt);
        }
        return "private " + declaration;
    }

    /**
     * Refuses encapsulation of a field whose declaring source is generated or Lombok-managed unless the caller opts in
     * via {@code allowGenerated}/{@code allowLombok}. The build model's generated source roots are the authoritative
     * signal (a field under such a root has no editable declaration); the path-naming, {@code @Generated} source-text,
     * and Lombok source-text checks are shared fallbacks for sources the build model does not surface.
     */
    private void assertEditableField(Path file, String relativePath, String source, Map<String, Object> fields) {
        boolean allowGenerated = boolField(fields, "allowGenerated");
        boolean allowLombok = boolField(fields, "allowLombok");
        if (!allowGenerated && GeneratedSourcePolicy.isUnderGeneratedRoot(model.generatedSourceRoots(), file)) {
            throw new Refusal("non_editable_target",
                    "Refusing edit: target field is declared in generated source under a build-model generated source root.");
        }
        if (!allowGenerated && GeneratedSourcePolicy.matchesGeneratedPathHeuristic(relativePath)) {
            throw new Refusal("generated_source_refused", "Generated Java sources are refused unless allowGenerated is true.");
        }
        if (!allowLombok && GeneratedSourcePolicy.matchesLombokSourceText(source)) {
            throw new Refusal("lombok_managed_source_refused", "Lombok-managed Java sources are refused unless allowLombok is true.");
        }
        if (!allowGenerated && GeneratedSourcePolicy.matchesGeneratedSourceText(source)) {
            throw new Refusal("generated_source_refused", "@Generated Java sources are refused unless allowGenerated is true.");
        }
    }

    private boolean boolField(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
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

    /** A {@code relativePath:line:column} label for a source range, for precise collision-refusal locations. */
    private String locationLabel(String source, SemanticIndex.SourceRange range) {
        String relative = PlannerSupport.relative(projectRoot, range.file());
        int offset = Math.max(0, Math.min(range.start(), source.length()));
        int line = 1;
        int column = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return relative + ":" + line + ":" + column;
    }

    private static final class Refusal extends RuntimeException {
        private final String code;

        private Refusal(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
