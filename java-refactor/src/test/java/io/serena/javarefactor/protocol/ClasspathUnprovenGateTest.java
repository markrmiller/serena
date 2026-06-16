package io.serena.javarefactor.protocol;

import io.serena.javarefactor.edits.PlannerSupport;
import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G003 build-model completeness gate: a model with an UNPROVEN dependency classpath must be refused on APPLY with the
 * dedicated {@code classpath_unproven_apply_refused} reason code, while PREVIEW is permitted (and surfaces a model-safety
 * warning naming the unresolved source set). With {@code allowIncompleteAnalysis} set, apply is permitted — mirroring the
 * documented {@code java_refactor.allow_incomplete_analysis} override used by the javac-diagnostic gate.
 *
 * <p>The gate ({@code Main.modelGateRefusal}) is private; this test drives it via reflection, exactly the layer the live
 * operation entrypoints call before applying an edit.</p>
 */
class ClasspathUnprovenGateTest {

    @Test
    void applyIsRefusedAndPreviewIsPermittedForUnprovenClasspath(@TempDir Path projectRoot) throws Exception {
        JavaProjectModel model = unprovenModel(projectRoot, false);
        assertTrue(model.classpathUnproven(), "precondition: the model's classpath is unproven");

        String previewRefusal = invokeGate(model, false);
        assertNull(previewRefusal, "preview must be permitted for an unproven classpath (warning-only)");

        String applyRefusal = invokeGate(model, true);
        assertNotNull(applyRefusal, "apply must be refused for an unproven classpath");
        assertTrue(applyRefusal.contains("classpath_unproven_apply_refused"),
                () -> "apply refusal must use the dedicated reason code: " + applyRefusal);
        assertTrue(applyRefusal.contains("main"), () -> "refusal must name the unresolved source set: " + applyRefusal);
    }

    @Test
    void allowIncompleteAnalysisLetsApplyProceed(@TempDir Path projectRoot) throws Exception {
        JavaProjectModel model = unprovenModel(projectRoot, true);
        assertTrue(model.classpathUnproven(), "precondition: the model's classpath is still unproven");

        String applyRefusal = invokeGate(model, true);
        assertNull(applyRefusal, "apply must proceed when allowIncompleteAnalysis is true");
    }

    @Test
    void previewSurfacesModelSafetyWarningNamingTheModule(@TempDir Path projectRoot) {
        JavaProjectModel model = unprovenModel(projectRoot, false);
        List<String> warnings = PlannerSupport.modelSafetyWarnings(model);
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("prove the dependency classpath")
                        && warning.contains("main")),
                () -> "a model-safety warning must name the unproven source set: " + warnings);
    }

    /** A single-source-set model whose one source set is marked classpath-unproven. */
    private static JavaProjectModel unprovenModel(Path projectRoot, boolean allowIncompleteAnalysis) {
        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(),            // sourceRoots
                List.of(),            // javaFiles
                List.of(),            // outputDirs
                List.of(),            // classpath
                List.of(),            // modulePath
                List.of(),            // generatedRoots
                "17",                 // releaseVersion
                null,                 // sourceVersion
                null,                 // targetVersion
                "UTF-8",              // encoding
                false,                // modular
                "none",               // annotationProcessing
                List.of(),            // annotationProcessorPath
                allowIncompleteAnalysis,
                List.of(),            // javacOptions
                List.of(),            // invalidationFiles
                List.of(),            // dependsOn
                false                 // classpathProven -> UNPROVEN
        );
        return new JavaProjectModel(
                projectRoot,
                "maven",
                List.of(sourceSet),
                List.of(),            // errors
                List.of(),            // warnings
                List.of(),            // invalidationFiles
                allowIncompleteAnalysis,
                false,                // conventionalFallbackUsed
                List.of()             // compilerDiagnostics
        );
    }

    /** Invokes the private {@code Main.modelGateRefusal(JavaProjectModel, boolean)} via reflection. */
    private static String invokeGate(JavaProjectModel model, boolean apply) throws Exception {
        Constructor<Main> constructor = Main.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Main main = constructor.newInstance();
        Method gate = Main.class.getDeclaredMethod("modelGateRefusal", JavaProjectModel.class, boolean.class);
        gate.setAccessible(true);
        return (String) gate.invoke(main, model, apply);
    }
}
