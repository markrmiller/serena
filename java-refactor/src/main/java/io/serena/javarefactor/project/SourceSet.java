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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public record SourceSet(
        String name,
        List<Path> sourceRoots,
        List<Path> javaFiles,
        List<Path> outputDirs,
        List<Path> classpath,
        List<Path> modulePath,
        List<Path> generatedRoots,
        String releaseVersion,
        String sourceVersion,
        String targetVersion,
        String encoding,
        boolean modular,
        String annotationProcessing,
        List<Path> annotationProcessorPath,
        boolean allowIncompleteAnalysis,
        List<String> javacOptions,
        List<Path> invalidationFiles,
        // Names of the source sets this one depends on (e.g. "test" depends on "main"). Models the directional
        // build graph: a dependent set may resolve the depended-on set's symbols, but NOT vice versa. main must
        // never see test, so main's dependsOn is empty.
        List<String> dependsOn,
        // G003 build-model completeness: false when this source set's dependency classpath could NOT be proven during
        // build-model extraction (see BuildModel.ModelSourceSet#classpathProven). An unproven classpath is a first-class
        // model-incompleteness signal that refuses apply via the same gate as javac diagnostics, because it can leave
        // javac "clean" on the edited file while corrupting semantic planning elsewhere.
        boolean classpathProven
) {
    /**
     * Backward-compatible constructor for the many call sites (and every proven-classpath source set) that pre-date the
     * G003 {@code classpathProven} signal: defaults {@code classpathProven} to {@code true}.
     */
    public SourceSet(String name, List<Path> sourceRoots, List<Path> javaFiles, List<Path> outputDirs,
            List<Path> classpath, List<Path> modulePath, List<Path> generatedRoots, String releaseVersion,
            String sourceVersion, String targetVersion, String encoding, boolean modular, String annotationProcessing,
            List<Path> annotationProcessorPath, boolean allowIncompleteAnalysis, List<String> javacOptions,
            List<Path> invalidationFiles, List<String> dependsOn) {
        this(name, sourceRoots, javaFiles, outputDirs, classpath, modulePath, generatedRoots, releaseVersion,
                sourceVersion, targetVersion, encoding, modular, annotationProcessing, annotationProcessorPath,
                allowIncompleteAnalysis, javacOptions, invalidationFiles, dependsOn, true);
    }

    public SourceSet {
        sourceRoots = List.copyOf(sourceRoots);
        javaFiles = List.copyOf(javaFiles);
        outputDirs = List.copyOf(outputDirs);
        classpath = List.copyOf(classpath);
        modulePath = List.copyOf(modulePath);
        generatedRoots = List.copyOf(generatedRoots);
        annotationProcessorPath = List.copyOf(annotationProcessorPath);
        javacOptions = List.copyOf(javacOptions);
        invalidationFiles = List.copyOf(invalidationFiles);
        dependsOn = List.copyOf(dependsOn);
    }

    /**
     * Normalized absolute source roots of the source sets this one depends on (transitive closure of
     * {@link #dependsOn()}), excluding this set's own roots. These are the only roots that may be added to a
     * source set's {@code -sourcepath} so cross-set references resolve against source: a test set sees main, but
     * main (empty {@code dependsOn}) sees nothing of test. Returns an insertion-ordered, de-duplicated list.
     */
    public static List<Path> crossSourceRoots(SourceSet target, List<SourceSet> allSourceSets) {
        Map<String, SourceSet> byName = new LinkedHashMap<>();
        for (SourceSet candidate : allSourceSets) {
            byName.putIfAbsent(candidate.name(), candidate);
        }
        LinkedHashSet<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(target.dependsOn());
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (name.equals(target.name()) || !reachable.add(name)) {
                continue;
            }
            SourceSet dependency = byName.get(name);
            if (dependency != null) {
                queue.addAll(dependency.dependsOn());
            }
        }
        LinkedHashSet<Path> ownRoots = target.sourceRoots().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (String name : reachable) {
            SourceSet dependency = byName.get(name);
            if (dependency == null) {
                continue;
            }
            for (Path root : dependency.sourceRoots()) {
                Path normalized = root.toAbsolutePath().normalize();
                if (!ownRoots.contains(normalized)) {
                    roots.add(normalized);
                }
            }
        }
        return new ArrayList<>(roots);
    }

    String toJson(Path projectRoot) {
        return JsonUtil.object(toJsonFields(projectRoot));
    }

    /**
     * G003: the source set's field set with the inventory ({@code javaFiles}) and the volatile {@code classpathUnproven}
     * analysis output removed, for inclusion in the model's mechanically-derived incremental-apply revision digest.
     */
    String revisionDigestJson(Path projectRoot) {
        Map<String, String> fields = new LinkedHashMap<>(toJsonFields(projectRoot));
        fields.remove("javaFiles");
        fields.remove("classpathUnproven");
        return JsonUtil.object(fields);
    }

    private Map<String, String> toJsonFields(Path projectRoot) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", JsonUtil.quote(name));
        fields.put("sourceRoots", JsonUtil.array(toRelativeStrings(projectRoot, sourceRoots)));
        fields.put("javaFiles", JsonUtil.array(toRelativeStrings(projectRoot, javaFiles)));
        fields.put("outputDirs", JsonUtil.array(toRelativeStrings(projectRoot, outputDirs)));
        fields.put("classpath", JsonUtil.array(toStrings(classpath)));
        fields.put("modulePath", JsonUtil.array(toStrings(modulePath)));
        fields.put("generatedRoots", JsonUtil.array(toRelativeStrings(projectRoot, generatedRoots)));
        fields.put("release", JsonUtil.quote(releaseVersion));
        fields.put("source", JsonUtil.quote(sourceVersion));
        fields.put("target", JsonUtil.quote(targetVersion));
        fields.put("encoding", JsonUtil.quote(encoding));
        fields.put("modular", Boolean.toString(modular));
        fields.put("annotationProcessing", JsonUtil.quote(annotationProcessing));
        fields.put("annotationProcessorPath", JsonUtil.array(toStrings(annotationProcessorPath)));
        fields.put("allowIncompleteAnalysis", Boolean.toString(allowIncompleteAnalysis));
        fields.put("javacOptions", JsonUtil.array(javacOptions));
        fields.put("invalidationFiles", JsonUtil.array(toRelativeStrings(projectRoot, invalidationFiles)));
        fields.put("dependsOn", JsonUtil.array(dependsOn));
        fields.put("classpathUnproven", Boolean.toString(!classpathProven));
        return fields;
    }

    private static List<String> toRelativeStrings(Path projectRoot, List<Path> paths) {
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

    private static List<String> toStrings(List<Path> paths) {
        List<String> result = new ArrayList<>();
        for (Path path : paths) {
            result.add(path.toAbsolutePath().normalize().toString());
        }
        return result;
    }
}
