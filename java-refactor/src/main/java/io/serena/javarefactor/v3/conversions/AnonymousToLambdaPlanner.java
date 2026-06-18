package io.serena.javarefactor.v3.conversions;

import io.serena.javarefactor.compiler.ConversionResult;
import io.serena.javarefactor.compiler.SemanticConversionIndex;
import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.shared.ProjectPathResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * V3 compiler-backed <b>Convert Anonymous Class To Lambda</b> (refactor-feature-plan-V3.md §12). Rewrites a
 * {@code new FunctionalInterface() { @Override … }} expression into an equivalent lambda, using javac's resolved model
 * to prove the anonymous class implements a single-abstract-method interface and that the conversion is
 * semantics-preserving.
 *
 * <p><b>§12.2/§12.4 refusals enforced</b> (computed in {@link SemanticConversionIndex} against the type/AST model):
 * not an anonymous class at the location, the supertype is not a functional interface (extends a class, implements
 * zero/many interfaces, or more than one abstract method), the body declares a field, an instance initializer, or extra
 * methods, overrides an Object method, or the method body uses {@code this}/{@code super} (which rebinds under a lambda).
 * Anything subtler is caught by the sidecar's before/after javac validation.
 */
public final class AnonymousToLambdaPlanner {

    private final Path projectRoot;
    private final JavaProjectModel model;

    public AnonymousToLambdaPlanner(Path projectRoot, JavaProjectModel model) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.model = model;
    }

    public String plan(Map<String, Object> fields) {
        try {
            return planChecked(fields);
        } catch (ConversionsRefusal refusal) {
            return PlannerSupport.refusalJson(refusal.code(), refusal.getMessage());
        } catch (ProjectPathResolver.Violation violation) {
            return PlannerSupport.refusalJson(violation.code(), violation.getMessage());
        } catch (IOException error) {
            return PlannerSupport.refusalJson("convert_anonymous_to_lambda_failed",
                    "Convert anonymous class to lambda failed: " + error.getMessage());
        }
    }

    private String planChecked(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        Path sourceFile = resolveSourceFile(fields);
        ConversionResult result = checkedResult(fields, sourceFile);
        return ConversionsSupport.acceptedJson(
                projectRoot, "convertAnonymousToLambda", sourceFile, result, "ANON_TO_LAMBDA");
    }

    /**
     * The structured workspace step (refactor-feature-plan-V3.md §3) for composing this conversion into a transformation
     * workspace. Reuses the same checked {@link ConversionResult} the standalone {@link #plan} serializes, so the composed
     * edit is identical to the standalone endpoint's. Refusals surface as {@link ConversionsRefusal}/{@link
     * ProjectPathResolver.Violation}, which the caller maps to canonical refusal JSON.
     */
    public io.serena.javarefactor.v3.transformation.TransformationStep planStep(Map<String, Object> fields)
            throws IOException, ProjectPathResolver.Violation {
        Path sourceFile = resolveSourceFile(fields);
        ConversionResult result = checkedResult(fields, sourceFile);
        return ConversionsSupport.toStep("anonymousToLambda", sourceFile, result, "ANON_TO_LAMBDA");
    }

    private Path resolveSourceFile(Map<String, Object> fields) throws ProjectPathResolver.Violation {
        return ProjectPathResolver.resolveProjectRelative(
                projectRoot, ConversionsSupport.requireString(fields, "relativePath"), "relativePath");
    }

    private ConversionResult checkedResult(Map<String, Object> fields, Path sourceFile) throws IOException {
        String relativePath = PlannerSupport.relative(projectRoot, sourceFile);
        int line = ConversionsSupport.intField(fields, "line", -1);
        int column = ConversionsSupport.intField(fields, "column", -1);
        if (line <= 0) {
            throw new ConversionsRefusal("missing_field", "line (1-based) is required.");
        }
        try (SemanticIndex index = SemanticIndex.open(model, relativePath)) {
            ConversionResult result = new SemanticConversionIndex(index).anonymousToLambda(sourceFile, line, column);
            if (!result.accepted()) {
                throw new ConversionsRefusal(result.refusalCode(), result.refusalMessage());
            }
            return result;
        }
    }
}
