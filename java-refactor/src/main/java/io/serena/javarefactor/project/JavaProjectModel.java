package io.serena.javarefactor.project;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record JavaProjectModel(
        Path projectRoot,
        String discoveryKind,
        List<SourceSet> sourceSets,
        List<String> errors,
        List<String> warnings,
        List<Path> invalidationFiles,
        boolean allowIncompleteAnalysis,
        // True when real build-tool extraction failed and discovery degraded to a classpath-less conventional layout
        // (only possible with allowConventionalFallback). Such a model cannot be trusted to compile against the true
        // classpath, so the apply path refuses to commit edits derived from it (preview stays permissive).
        boolean conventionalFallbackUsed,
        // Compiler diagnostics produced by the javac validation pass, kept SEPARATE from the hard discovery errors so
        // the operation entrypoints can implement the V1 incomplete-analysis contract: a project whose only problems
        // are unresolved compiler diagnostics still gets a warning-only PREVIEW, while apply is refused unless
        // allowIncompleteAnalysis is set. (They additionally flow into errors/warnings — see withCompilerDiagnostics —
        // so status readiness reporting keeps its established semantics.)
        List<String> compilerDiagnostics
) {
    public JavaProjectModel {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        sourceSets = List.copyOf(sourceSets);
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
        invalidationFiles = List.copyOf(invalidationFiles);
        compilerDiagnostics = List.copyOf(compilerDiagnostics);
    }

    /** Whether javac validation reported unresolved diagnostics (the V1 "incomplete analysis" condition). */
    public boolean analysisIncomplete() {
        return !compilerDiagnostics.isEmpty();
    }

    /**
     * G003 build-model completeness: true when any source set used by the operation could not have its dependency
     * classpath proven during build-model extraction (see {@link SourceSet#classpathProven()} and
     * {@link BuildModel.ModelSourceSet#classpathProven()}). This is a first-class model-incompleteness signal,
     * INDEPENDENT of javac diagnostics: an incomplete classpath can leave javac "clean" on the edited file while
     * corrupting semantic planning (overload resolution, type hierarchy) elsewhere, so the apply gate refuses on it
     * exactly as it does on {@link #analysisIncomplete()}, unless {@link #allowIncompleteAnalysis()} is set. Derived
     * from the source sets (like {@link #modular()}), so a re-discovered model preserves it without separate storage.
     */
    public boolean classpathUnproven() {
        return sourceSets.stream().anyMatch(sourceSet -> !sourceSet.classpathProven());
    }

    /** The names of the source sets whose dependency classpath could not be proven (for model-safety messaging). */
    public List<String> unprovenClasspathSourceSets() {
        return sourceSets.stream().filter(sourceSet -> !sourceSet.classpathProven()).map(SourceSet::name).toList();
    }

    /**
     * The hard (non-compiler-diagnostic) errors: discovery/extraction failures that make the model unusable for ANY
     * operation, preview included. Computed by removing each compiler diagnostic once from {@code errors} (the
     * diagnostics are appended there by {@link #withCompilerDiagnostics} when {@code allowIncompleteAnalysis} is off),
     * so this is exactly the pre-validation error list regardless of configuration.
     *
     * <p>INVARIANT this relies on: {@code compilerDiagnostics} is only ever populated by validation, and validation
     * only runs on a model whose discovery {@code errors} were empty (see {@code Main.discoverModelTimed} and
     * {@code Main.validateEditJson}, which both return early on discovery errors). So whenever
     * {@code compilerDiagnostics} is non-empty, {@code errors} contains exactly the appended diagnostics (or nothing,
     * under {@code allowIncompleteAnalysis}) and the multiset removal yields the empty hard-error list. If a future
     * change allows discovery errors and diagnostics to coexist, replace this string subtraction with an explicitly
     * stored discovery-error component — string-equality removal could otherwise strip a genuine hard error that
     * happens to match a diagnostic.</p>
     */
    public List<String> hardErrors() {
        if (compilerDiagnostics.isEmpty()) {
            return errors;
        }
        List<String> result = new ArrayList<>(errors);
        for (String diagnostic : compilerDiagnostics) {
            result.remove(diagnostic);
        }
        return List.copyOf(result);
    }

    public int javaFileCount() {
        return sourceSets.stream().mapToInt(sourceSet -> sourceSet.javaFiles().size()).sum();
    }

    /** De-duplicated, stably-ordered union of every source set's Java files. */
    List<Path> allJavaFiles() {
        return union(SourceSet::javaFiles);
    }

    /** De-duplicated, stably-ordered union of every source set's compile classpath. */
    public List<Path> classpath() {
        return union(SourceSet::classpath);
    }

    /** De-duplicated, stably-ordered union of every source set's module path. */
    List<Path> modulePath() {
        return union(SourceSet::modulePath);
    }

    /** De-duplicated, stably-ordered union of every source set's generated source roots. */
    public List<Path> generatedSourceRoots() {
        return union(SourceSet::generatedRoots);
    }

    /** The representative {@code --release} across source sets (first non-blank), or null when none declares one. */
    public String release() {
        return firstNonBlank(SourceSet::releaseVersion);
    }

    /** The representative {@code -source} across source sets (first non-blank), or null when none declares one. */
    public String source() {
        return firstNonBlank(SourceSet::sourceVersion);
    }

    /** The representative {@code -target} across source sets (first non-blank), or null when none declares one. */
    public String target() {
        return firstNonBlank(SourceSet::targetVersion);
    }

    /** The representative source {@code -encoding} across source sets (first non-blank), or null when none declares one. */
    public String encoding() {
        return firstNonBlank(SourceSet::encoding);
    }

    /** Whether any source set is modular (a module path / {@code module-info} drives compilation). */
    public boolean modular() {
        return sourceSets.stream().anyMatch(SourceSet::modular);
    }

    /** De-duplicated, order-preserving union of every source set's effective javac options. */
    public List<String> javacOptions() {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (SourceSet sourceSet : sourceSets) {
            result.addAll(sourceSet.javacOptions());
        }
        return List.copyOf(result);
    }

    private String firstNonBlank(java.util.function.Function<SourceSet, String> extractor) {
        for (SourceSet sourceSet : sourceSets) {
            String value = extractor.apply(sourceSet);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Whether every source set analyzed with annotation processing disabled (javac {@code -proc:none}). */
    public boolean annotationProcessingDisabled() {
        return !sourceSets.isEmpty() && sourceSets.stream().allMatch(sourceSet -> "none".equals(sourceSet.annotationProcessing()));
    }

    /** De-duplicated, stably-ordered union of every source set's compiler output directories. */
    List<Path> outputDirs() {
        return union(SourceSet::outputDirs);
    }

    private List<Path> union(java.util.function.Function<SourceSet, List<Path>> extractor) {
        java.util.LinkedHashSet<Path> result = new java.util.LinkedHashSet<>();
        for (SourceSet sourceSet : sourceSets) {
            for (Path path : extractor.apply(sourceSet)) {
                result.add(path.toAbsolutePath().normalize());
            }
        }
        return List.copyOf(result);
    }

    /**
     * A copy carrying exactly the supplied validated errors/warnings, used to rehydrate a model from the persistent
     * project-model cache (the cached validation result is reapplied to a freshly re-discovered, unchanged model).
     */
    JavaProjectModel withValidatedDiagnostics(List<String> validatedErrors, List<String> validatedWarnings, List<String> validatedCompilerDiagnostics) {
        return new JavaProjectModel(projectRoot, discoveryKind, sourceSets, validatedErrors, validatedWarnings, invalidationFiles, allowIncompleteAnalysis, conventionalFallbackUsed, validatedCompilerDiagnostics);
    }

    public JavaProjectModel withCompilerDiagnostics(List<String> diagnostics) {
        if (diagnostics.isEmpty()) {
            return this;
        }

        List<String> updatedErrors = new ArrayList<>(errors);
        List<String> updatedWarnings = new ArrayList<>(warnings);
        if (allowIncompleteAnalysis) {
            updatedWarnings.addAll(diagnostics);
        } else {
            updatedErrors.addAll(diagnostics);
        }
        return new JavaProjectModel(projectRoot, discoveryKind, sourceSets, updatedErrors, updatedWarnings, invalidationFiles, allowIncompleteAnalysis, conventionalFallbackUsed, diagnostics);
    }

    String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(projectRoot.toString().getBytes());
            digest.update(discoveryKind.getBytes());
            for (Path path : invalidationFiles) {
                digest.update(path.toString().getBytes());
            }
            for (SourceSet sourceSet : sourceSets) {
                digest.update(sourceSet.name().getBytes());
                for (String option : sourceSet.javacOptions()) {
                    digest.update(option.getBytes());
                }
            }
            // The aggregate classpath/module path are part of compiler identity: a resolved-dependency change that does
            // not touch a build file (e.g. a SNAPSHOT republished under the same coordinate) still changes correctness.
            for (Path entry : classpath()) {
                digest.update(entry.toString().getBytes());
            }
            for (Path entry : modulePath()) {
                digest.update(entry.toString().getBytes());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    public String toJson() {
        Map<String, String> fields = new LinkedHashMap<>(toJsonFields());
        fields.put("modelHash", JsonUtil.quote(revisionDigest()));
        return JsonUtil.object(fields);
    }

    private Map<String, String> toJsonFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("projectRoot", JsonUtil.quote(projectRoot.toString()));
        fields.put("discoveryKind", JsonUtil.quote(discoveryKind));
        fields.put("sourceSetCount", Integer.toString(sourceSets.size()));
        fields.put("javaFileCount", Integer.toString(javaFileCount()));
        fields.put("allowIncompleteAnalysis", Boolean.toString(allowIncompleteAnalysis));
        fields.put("conventionalFallbackUsed", Boolean.toString(conventionalFallbackUsed));
        fields.put("fingerprint", JsonUtil.quote(fingerprint()));
        fields.put("sourceSets", sourceSets.stream().map(sourceSet -> sourceSet.toJson(projectRoot)).collect(Collectors.joining(",", "[", "]")));
        fields.put("allJavaFiles", JsonUtil.array(toRelativeStrings(allJavaFiles())));
        fields.put("classpath", JsonUtil.array(toAbsoluteStrings(classpath())));
        fields.put("modulePath", JsonUtil.array(toAbsoluteStrings(modulePath())));
        fields.put("generatedSourceRoots", JsonUtil.array(toRelativeStrings(generatedSourceRoots())));
        fields.put("outputDirs", JsonUtil.array(toRelativeStrings(outputDirs())));
        // Designed V1 project-model contract: explicit project-level compiler settings surfaced even though the internal
        // record stores them per source set (refactor-feature-plan.md §Project model). Derivability is not sufficient.
        fields.put("release", JsonUtil.quote(release()));
        fields.put("source", JsonUtil.quote(source()));
        fields.put("target", JsonUtil.quote(target()));
        fields.put("encoding", JsonUtil.quote(encoding()));
        fields.put("modular", Boolean.toString(modular()));
        fields.put("javacOptions", JsonUtil.array(javacOptions()));
        fields.put("errors", JsonUtil.array(errors));
        fields.put("warnings", JsonUtil.array(warnings));
        fields.put("compilerDiagnostics", JsonUtil.array(compilerDiagnostics));
        fields.put("analysisIncomplete", Boolean.toString(analysisIncomplete()));
        fields.put("classpathUnproven", Boolean.toString(classpathUnproven()));
        fields.put("unprovenClasspathSourceSets", JsonUtil.array(unprovenClasspathSourceSets()));
        fields.put("invalidationFiles", JsonUtil.array(toRelativeStrings(invalidationFiles)));
        return fields;
    }

    /**
     * G003: a mechanically-derived revision digest over the project model for the incremental-apply guard. It hashes
     * the COMPLETE {@link #toJson()} field set minus exactly two categories that legitimately drift mid-session: the
     * {@code .java} file INVENTORY (which an in-flight partial apply mutates by creating/editing files) and the volatile
     * analysis OUTPUTS (errors/warnings/diagnostics/unproven flags, re-derived by the apply-time recompile). Because it
     * starts from the full field map and removes a fixed denylist, every NEW correctness field added to the model is
     * captured by default — closing the silent-escape gap of the old hand-curated invalidation-input allowlist, which
     * never compared inventory-independent fields like {@code discoveryKind}, {@code modulePath}, or {@code outputDirs}.
     */
    public String revisionDigest() {
        Map<String, String> fields = new LinkedHashMap<>(toJsonFields());
        // The .java inventory: any legitimate in-flight session apply creates/edits files, so excluding it wholesale
        // (rather than only the session's own paths) is what lets multi-step incremental applies proceed.
        fields.remove("javaFileCount");
        fields.remove("allJavaFiles");
        // Volatile analysis OUTPUTS: pure functions of the (retained) source/classpath/option inputs, re-derived by the
        // apply-time recompile, so comparing them would falsely reject benign continuations without adding safety.
        fields.remove("errors");
        fields.remove("warnings");
        fields.remove("compilerDiagnostics");
        fields.remove("analysisIncomplete");
        fields.remove("classpathUnproven");
        fields.remove("unprovenClasspathSourceSets");
        // Each source set embeds its own javaFiles inventory and classpathUnproven output; rebuild them without those.
        fields.put("sourceSets", sourceSets.stream()
                .map(sourceSet -> sourceSet.revisionDigestJson(projectRoot))
                .collect(Collectors.joining(",", "[", "]")));
        return sha256(JsonUtil.object(fields));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private static List<String> toAbsoluteStrings(List<Path> paths) {
        List<String> result = new ArrayList<>();
        for (Path path : paths) {
            result.add(path.toAbsolutePath().normalize().toString());
        }
        return result;
    }

    private List<String> toRelativeStrings(List<Path> paths) {
        List<String> result = new ArrayList<>();
        for (Path path : paths) {
            Path normalized = path.toAbsolutePath().normalize();
            if (normalized.startsWith(projectRoot)) {
                result.add(projectRoot.relativize(normalized).toString().replace('\\', '/'));
            } else {
                result.add(normalized.toString());
            }
        }
        return result;
    }
}
