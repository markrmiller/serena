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
 * V3 compiler-backed <b>Convert Lambda To Method Reference</b> (refactor-feature-plan-V3.md §13). Rewrites a lambda
 * whose body is exactly one method or constructor invocation that forwards the lambda's parameters in order into the
 * equivalent method reference ({@code this::foo}, {@code String::trim}, {@code SomeType::create}, {@code Foo::new}).
 *
 * <p><b>§13.3 refusals enforced</b> (computed in {@link SemanticConversionIndex} against the AST/type model): the body
 * is not a single invocation, an argument is transformed (e.g. {@code x -> foo(transform(x))}), the parameters are
 * reordered (e.g. {@code (x, y) -> foo(y, x)}), only some parameters are forwarded (partial application), or the
 * receiver itself references a parameter. Anything subtler (overload/target-type changes) is caught by the sidecar's
 * before/after javac validation.
 */
public final class LambdaToMethodReferencePlanner {

    private final Path projectRoot;
    private final JavaProjectModel model;

    public LambdaToMethodReferencePlanner(Path projectRoot, JavaProjectModel model) {
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
            return PlannerSupport.refusalJson("convert_lambda_to_method_reference_failed",
                    "Convert lambda to method reference failed: " + error.getMessage());
        }
    }

    private String planChecked(Map<String, Object> fields) throws IOException, ProjectPathResolver.Violation {
        Path sourceFile = resolveSourceFile(fields);
        ConversionResult result = checkedResult(fields, sourceFile);
        return ConversionsSupport.acceptedJson(
                projectRoot, "convertLambdaToMethodReference", sourceFile, result, "LAMBDA_TO_METHOD_REF");
    }

    /**
     * The structured workspace step (refactor-feature-plan-V3.md §3) for composing this conversion into a transformation
     * workspace. Reuses the same checked {@link ConversionResult} the standalone {@link #plan} serializes. Refusals
     * surface as {@link ConversionsRefusal}/{@link ProjectPathResolver.Violation}, mapped to refusal JSON by the caller.
     */
    public io.serena.javarefactor.v3.transformation.TransformationStep planStep(Map<String, Object> fields)
            throws IOException, ProjectPathResolver.Violation {
        Path sourceFile = resolveSourceFile(fields);
        ConversionResult result = checkedResult(fields, sourceFile);
        return ConversionsSupport.toStep("lambdaToMethodReference", sourceFile, result, "LAMBDA_TO_METHOD_REF");
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
            ConversionResult result = new SemanticConversionIndex(index).lambdaToMethodReference(sourceFile, line, column);
            if (!result.accepted()) {
                throw new ConversionsRefusal(result.refusalCode(), result.refusalMessage());
            }
            return result;
        }
    }
}
