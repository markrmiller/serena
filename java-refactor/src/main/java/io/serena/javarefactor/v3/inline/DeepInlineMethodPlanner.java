package io.serena.javarefactor.v3.inline;

import io.serena.javarefactor.compiler.DeepInlineIndex;
import io.serena.javarefactor.compiler.DeepInlineResult;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.shared.ProjectPathResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * V3 compiler-backed <b>Generalized Inline Method</b> (refactor-feature-plan-V3.md §11). Inlines a {@code private},
 * non-recursive method whose body is "straight-line" (local declarations and expression statements with at most one
 * trailing {@code return}) into each of its call sites, substituting parameters with arguments, hoisting
 * side-effecting arguments into temporaries to preserve evaluation order, and renaming inlined locals that would
 * collide with the call-site scope.
 *
 * <p><b>§11 refusals enforced</b> (computed in {@link DeepInlineIndex} against the javac model): the target is not a
 * private method, is generic, is recursive, has a non-straight-line body (loops/branches/early returns/{@code super}),
 * has no call sites, a call site is not a standalone statement (so the multi-statement body cannot be block-inlined), or
 * the body can throw a checked exception that a call site's enclosing method/try does not handle or declare
 * ({@code checked_exception_mismatch}, §11.1). Anything subtler is caught by the sidecar's before/after javac validation.
 */
public final class DeepInlineMethodPlanner {

    private final Path projectRoot;
    private final JavaProjectModel model;

    public DeepInlineMethodPlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
    }

    public String plan(Map<String, Object> fields) {
        try {
            return planChecked(fields);
        } catch (InlineRefactorRefusal refusal) {
            return PlannerSupport.refusalJson(refusal.code(), refusal.getMessage());
        } catch (ProjectPathResolver.Violation violation) {
            return PlannerSupport.refusalJson(violation.code(), violation.getMessage());
        } catch (IOException error) {
            return PlannerSupport.refusalJson("deep_inline_method_failed",
                    "Generalized inline method failed: " + error.getMessage());
        }
    }

    /** Default call-site cap when neither the per-call {@code maxCallSites} field nor the config override is set. */
    static final int DEFAULT_MAX_CALL_SITES = 25;

    private String planChecked(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        Path sourceFile = resolveSourceFile(fields);
        DeepInlineResult result = checkedResult(fields, sourceFile);
        return InlineRefactorSupport.acceptedJson(
                projectRoot, "deepInlineMethod", sourceFile, result, "DEEP_INLINE_METHOD");
    }

    /**
     * The structured workspace step (refactor-feature-plan-V3.md §3) for composing the inline into a transformation
     * workspace. Reuses the same checked {@link DeepInlineResult} the standalone {@link #plan} serializes, carrying the
     * per-call-site edits and the planner's warnings. Refusals surface as {@link InlineRefactorRefusal}/{@link
     * ProjectPathResolver.Violation}, mapped to canonical refusal JSON by the caller.
     */
    public io.serena.javarefactor.v3.transformation.TransformationStep planStep(Map<String, Object> fields)
            throws IOException, ProjectPathResolver.Violation {
        Path sourceFile = resolveSourceFile(fields);
        DeepInlineResult result = checkedResult(fields, sourceFile);
        return InlineRefactorSupport.toStep("deepInlineMethod", sourceFile, result, "DEEP_INLINE_METHOD");
    }

    private Path resolveSourceFile(Map<String, Object> fields) throws ProjectPathResolver.Violation {
        return ProjectPathResolver.resolveProjectRelative(
                projectRoot, InlineRefactorSupport.requireString(fields, "relativePath"), "relativePath");
    }

    private DeepInlineResult checkedResult(Map<String, Object> fields, Path sourceFile) throws IOException {
        String relativePath = PlannerSupport.relative(projectRoot, sourceFile);
        int line = InlineRefactorSupport.intField(fields, "line", -1);
        int column = InlineRefactorSupport.intField(fields, "column", -1);
        String methodName = InlineRefactorSupport.optionalString(fields, "methodName");
        boolean deleteMethod = InlineRefactorSupport.boolField(fields, "deleteMethod", false)
                || InlineRefactorSupport.boolField(fields, "deleteInlinedMethod", false)
                || InlineRefactorSupport.boolField(fields, "delete_inlined_method", false);
        // Effective limit: explicit per-call value wins, then configured default (injected by Main as maxCallSites from
        // java_refactor.v3.inline.max_call_sites), then the hard-coded fallback.
        int maxCallSites = InlineRefactorSupport.intField(fields, "maxCallSites", DEFAULT_MAX_CALL_SITES);
        if (line <= 0 && (methodName == null || methodName.isBlank())) {
            throw new InlineRefactorRefusal("missing_field", "line (1-based) is required unless methodName is supplied.");
        }
        try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
            DeepInlineResult result = new DeepInlineIndex(index)
                    .inlineMethod(sourceFile, line, column, methodName, deleteMethod, maxCallSites);
            if (!result.accepted()) {
                throw new InlineRefactorRefusal(result.refusalCode(), result.refusalMessage());
            }
            return result;
        }
    }
}
