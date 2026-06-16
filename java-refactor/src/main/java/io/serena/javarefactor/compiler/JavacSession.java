package io.serena.javarefactor.compiler;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import com.sun.source.util.JavacTask;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

public final class JavacSession {
    // The shared StandardJavaFileManager pool. Defaults to the process-wide singleton so production code reuses jar
    // scans across passes; tests inject a fresh pool to assert reuse in isolation.
    private final FileManagerPool fileManagerPool;

    public JavacSession() {
        this(FileManagerPool.INSTANCE);
    }

    public JavacSession(FileManagerPool fileManagerPool) {
        this.fileManagerPool = fileManagerPool;
    }

    public JavaProjectModel validate(JavaProjectModel model) {
        return validate(model, FileOverlay.EMPTY);
    }

    /**
     * Validates a discovered model with an in-memory {@link FileOverlay} substituted for on-disk source. Changed and
     * renamed files compile from the supplied content, deleted (and the old paths of renamed) files are excluded, and
     * the source path is adjusted so renamed-in files are seen at their new location. Disk is never touched. Returns
     * the same compiler-diagnostic-augmented model {@link #validate(JavaProjectModel)} produces.
     */
    JavaProjectModel validate(JavaProjectModel model, FileOverlay overlay) {
        return model.withCompilerDiagnostics(collectDiagnosticReport(model, overlay).errorStrings());
    }

    /**
     * The raw javac error diagnostics for the (optionally overlaid) model, WITHOUT the {@code allowIncompleteAnalysis}
     * error-to-warning suppression that {@link JavaProjectModel#withCompilerDiagnostics} applies. Callers that must
     * reason about real compiler errors (e.g. baseline-vs-staged diffing for apply safety) use this; the suppression is
     * only a presentation concern for the permissive status/preview surface.
     */
    public List<String> collectDiagnostics(JavaProjectModel model, FileOverlay overlay) {
        return collectDiagnosticReport(model, overlay).errorStrings();
    }

    /** Returns separate javac error and warning diagnostics for the optionally overlaid model. */
    public DiagnosticReport collectDiagnosticReport(JavaProjectModel model, FileOverlay overlay) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new DiagnosticReport(
                    List.of(DiagnosticInfo.ofMessage("error", "JDK JavaCompiler is unavailable; run Serena with a JDK rather than a JRE.")),
                    List.of());
        }
        List<DiagnosticInfo> errors = new ArrayList<>();
        List<DiagnosticInfo> warnings = new ArrayList<>();
        for (SourceSet sourceSet : model.sourceSets()) {
            DiagnosticReport report = validateSourceSet(compiler, sourceSet, model.sourceSets(), overlay);
            errors.addAll(report.errors());
            warnings.addAll(report.warnings());
        }
        return new DiagnosticReport(errors, warnings);
    }

    private DiagnosticReport validateSourceSet(JavaCompiler compiler, SourceSet sourceSet, List<SourceSet> allSourceSets, FileOverlay overlay) {
        // Resolve the source files this set actually compiles after the overlay is applied: on-disk files that the
        // overlay deletes/renames-away are dropped, files the overlay renames into this set's roots are added, and
        // overlay content (changed or renamed-in) is substituted via in-memory JavaFileObjects. When the overlay is
        // empty this reduces to the source set's own on-disk javaFiles().
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        Charset charset = Charset.forName(sourceSet.encoding() == null ? "UTF-8" : sourceSet.encoding());
        // Validate each source set with its OWN javacOptions (so divergent release levels / classpaths / encodings stay
        // correct per file), but make cross-source-set references resolvable: a test source set commonly references main
        // symbols, yet main's compiled output is typically absent (e.g. nothing has been built), so compiling the source
        // set in isolation would emit spurious "cannot find symbol" errors. We add the OTHER source sets' source roots to
        // -sourcepath so those references resolve against source, and pass -implicit:none so javac neither emits implicit
        // class warnings nor reports diagnostics for those sourcepath-loaded files (each file's diagnostics are reported
        // only in its own source set's pass, with its own options — no double-reporting, no wrong-options attribution).
        List<String> options = crossSourceSetOptions(sourceSet, allSourceSets);
        try {
            // Reuse the pooled standard file manager for this (charset, options) configuration so the classpath/jar scan
            // is not repeated every pass. The manager is owned by the pool, so it is NOT closed here; the
            // OverlayFileManager wrapper's close() is likewise a no-op for the same reason, so we let it go out of scope
            // without try-with-resources. The pool drops these managers on model change / shutdown.
            //
            // acquire() applies the source set's options (including --release N) to the file manager, so an unsupported
            // language level (e.g. --release newer than this sidecar's JDK) throws HERE. Keeping it inside the try turns
            // that into a structured per-source-set diagnostic (analysis-incomplete -> apply refused) instead of an
            // opaque exception that would surface as a malformed-request error mid-operation.
            StandardJavaFileManager standardManager = fileManagerPool.acquire(compiler, charset, options);
            OverlayFileManager fileManager = new OverlayFileManager(standardManager, overlay);
            List<JavaFileObject> files = overlay.fileObjectsFor(standardManager, sourceSet, allSourceSets);
            if (files.isEmpty()) {
                return new DiagnosticReport(List.of(), List.of());
            }
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, collector, options, null, files);
            task.parse();
            task.analyze();
        } catch (IOException | RuntimeException e) {
            List<DiagnosticInfo> errors = new ArrayList<>();
            errors.add(DiagnosticInfo.ofMessage("error", "javac session failed for source set " + sourceSet.name() + ": " + e.getMessage()));
            errors.addAll(formatDiagnostics(collector.getDiagnostics(), Diagnostic.Kind.ERROR, sourceSet.name()));
            return new DiagnosticReport(errors, formatWarnings(collector.getDiagnostics(), sourceSet.name()));
        }

        return new DiagnosticReport(
                formatDiagnostics(collector.getDiagnostics(), Diagnostic.Kind.ERROR, sourceSet.name()),
                formatWarnings(collector.getDiagnostics(), sourceSet.name()));
    }

    /**
     * The source set's own javacOptions, augmented with the other source sets' source roots on {@code -sourcepath} and
     * {@code -implicit:none}. This lets cross-source-set references (e.g. test -> main) resolve against source without
     * requiring the referenced source set to be pre-compiled, while keeping per-set options authoritative for the files
     * actually being analyzed. When there are no other source roots to add, the original options are returned unchanged
     * (so single-source-set and modular projects, which set their own --module-source-path, are not perturbed).
     */
    private List<String> crossSourceSetOptions(SourceSet sourceSet, List<SourceSet> allSourceSets) {
        // Only the source sets this one depends on (e.g. test -> main) are added to -sourcepath. main, with an empty
        // dependsOn, is validated WITHOUT visibility into test, so main can never resolve test-only symbols and an
        // illegal main -> test reference is reported as an error rather than silently resolving against source.
        List<Path> otherRoots = SourceSet.crossSourceRoots(sourceSet, allSourceSets);
        // Modular source sets resolve cross-module references via their own --module-source-path; adding a flat
        // -sourcepath would conflict, so leave their options untouched.
        if (otherRoots.isEmpty() || sourceSet.modular()) {
            return sourceSet.javacOptions();
        }
        List<String> options = new ArrayList<>(sourceSet.javacOptions());
        options.add("-sourcepath");
        options.add(otherRoots.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator)));
        options.add("-implicit:none");
        return options;
    }

    private List<DiagnosticInfo> formatWarnings(List<Diagnostic<? extends JavaFileObject>> diagnostics, String sourceSetName) {
        List<DiagnosticInfo> warnings = new ArrayList<>();
        warnings.addAll(formatDiagnostics(diagnostics, Diagnostic.Kind.WARNING, sourceSetName));
        warnings.addAll(formatDiagnostics(diagnostics, Diagnostic.Kind.MANDATORY_WARNING, sourceSetName));
        return warnings;
    }

    private List<DiagnosticInfo> formatDiagnostics(
            List<Diagnostic<? extends JavaFileObject>> diagnostics, Diagnostic.Kind kind, String sourceSetName) {
        String severity = kind == Diagnostic.Kind.ERROR ? "error" : "warning";
        List<DiagnosticInfo> result = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            if (diagnostic.getKind() != kind) {
                continue;
            }
            String source = diagnostic.getSource() == null ? "<unknown>" : Path.of(diagnostic.getSource().toUri()).toString();
            String message = diagnostic.getMessage(null);
            String display = source + ":" + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + ": " + message;
            result.add(new DiagnosticInfo(
                    severity,
                    source,
                    diagnostic.getLineNumber(),
                    diagnostic.getColumnNumber(),
                    diagnostic.getStartPosition(),
                    diagnostic.getEndPosition(),
                    diagnostic.getCode(),
                    message,
                    sourceSetName,
                    display));
        }
        return result;
    }

    /**
     * Separate javac error and warning diagnostics. The canonical carrier is structured {@link DiagnosticInfo};
     * {@link #errorStrings()}/{@link #warningStrings()} expose the derived display strings for the legacy
     * string-oriented model/status surface.
     */
    public record DiagnosticReport(List<DiagnosticInfo> errors, List<DiagnosticInfo> warnings) {
        public List<String> errorStrings() {
            return DiagnosticInfo.displays(errors);
        }

        public List<String> warningStrings() {
            return DiagnosticInfo.displays(warnings);
        }
    }
}
