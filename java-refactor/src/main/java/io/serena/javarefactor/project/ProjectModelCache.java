package io.serena.javarefactor.project;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-process cache for the validated {@link JavaProjectModel}.
 *
 * <p>{@code status}, {@code preview}, and {@code apply} all re-discover and re-validate the project on every call,
 * and the validation pass runs javac. This cache reuses the last validated model when nothing relevant changed,
 * keyed by the project root, configuration, and the state of every invalidation file and every discovered
 * Java source file. Source and build files are stamped by a SHA-256 content hash, so a content-only edit (one that
 * preserves the file's mtime and size) still changes the key and forces a fresh validation; the cache therefore
 * cannot serve stale analysis. Classpath, module-path and annotation-processor-path jars (and any regular file on
 * those paths) are stamped by mtime+size instead; directory entries on those paths and every compiler output directory
 * are recursively fingerprinted (see {@link #keyFor}).</p>
 */
public final class ProjectModelCache {
    private String cachedKey;
    private JavaProjectModel cachedModel;

    /** The persistent cache file, relative to the Serena project-data directory passed by the client. */
    private static final String CACHE_FILE = "java-refactor/project-model.cache.json";

    public JavaProjectModel get(String key) {
        return key != null && key.equals(cachedKey) ? cachedModel : null;
    }

    public void put(String key, JavaProjectModel model) {
        this.cachedKey = key;
        this.cachedModel = model;
    }

    /**
     * Reads the persisted validation result from the Serena project-data directory and, if its key matches the current
     * project state, rehydrates the (freshly re-discovered, unchanged) model with the cached errors/warnings — letting a
     * restarted sidecar reuse a prior javac validation instead of re-running it. Returns null on any miss/parse error.
     */
    @SuppressWarnings("unchecked")
    public static JavaProjectModel loadPersistent(Path dataDir, String key, JavaProjectModel unvalidated) {
        if (dataDir == null) {
            return null;
        }
        Path file = dataDir.resolve(CACHE_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Map<String, Object> entry = Json.parseObject(Files.readString(file));
            if (!key.equals(entry.get("key"))) {
                return null;
            }
            // A pre-compilerDiagnostics cache entry cannot say which errors were compiler diagnostics (the basis of
            // the incomplete-analysis preview/apply gating), so treat it as a miss and re-validate instead of guessing.
            if (!entry.containsKey("compilerDiagnostics")) {
                return null;
            }
            List<String> errors = stringList(entry.get("errors"));
            List<String> warnings = stringList(entry.get("warnings"));
            List<String> compilerDiagnostics = stringList(entry.get("compilerDiagnostics"));
            return unvalidated.withValidatedDiagnostics(errors, warnings, compilerDiagnostics);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Persists the validated model's key and diagnostics under the Serena project-data directory (best-effort). */
    public static void storePersistent(Path dataDir, String key, JavaProjectModel validated) {
        if (dataDir == null) {
            return;
        }
        Path file = dataDir.resolve(CACHE_FILE);
        try {
            Files.createDirectories(file.getParent());
            Map<String, String> fields = new java.util.LinkedHashMap<>();
            fields.put("key", JsonUtil.quote(key));
            fields.put("errors", JsonUtil.array(validated.errors()));
            fields.put("warnings", JsonUtil.array(validated.warnings()));
            fields.put("compilerDiagnostics", JsonUtil.array(validated.compilerDiagnostics()));
            Files.writeString(file, JsonUtil.object(fields));
        } catch (IOException | RuntimeException ignored) {
            // A cache write failure is non-fatal: the next run simply re-validates.
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    /** Builds a content-sensitive cache key from the configuration plus the state of all relevant files. */
    public static String keyFor(JavaProjectModel unvalidatedModel, String configuration) {
        StringBuilder builder = new StringBuilder();
        builder.append(unvalidatedModel.projectRoot()).append('\n');
        builder.append("config=").append(configuration == null ? "" : configuration).append('\n');

        // Source and build files are content-hashed (see below). Classpath / module-path / annotation-processor-path
        // jars (and any regular FILE entry on those paths) are mtime+size stamped: they are often large and numerous and
        // rarely change, so SHA-256-ing every dependency jar on every status/preview/apply would be too slow. This is a
        // deliberate, documented performance boundary. DIRECTORY entries on those paths, and every compiler output
        // directory, are instead recursively fingerprinted (each contained file stamped by path+mtime+size in sorted
        // order) so a changed/added/removed class file under them still invalidates the validated model.
        List<Path> sourceAndBuildFiles = new ArrayList<>(unvalidatedModel.invalidationFiles());
        Set<Path> compilerArtifacts = new HashSet<>();
        for (SourceSet sourceSet : unvalidatedModel.sourceSets()) {
            sourceAndBuildFiles.addAll(sourceSet.javaFiles());
            // Fold extracted classpath / module-path / annotation-processor-path entries into the key so a dependency
            // upgrade that swaps a jar on disk (or a changed processor) without changing build-file text still
            // invalidates the validated model.
            for (Path entry : sourceSet.classpath()) {
                compilerArtifacts.add(entry.toAbsolutePath().normalize());
            }
            for (Path entry : sourceSet.modulePath()) {
                compilerArtifacts.add(entry.toAbsolutePath().normalize());
            }
            for (Path entry : sourceSet.annotationProcessorPath()) {
                compilerArtifacts.add(entry.toAbsolutePath().normalize());
            }
            // Compiler output directories: a rebuild that changes the emitted .class files (without touching sources we
            // already hash) changes resolution/diagnostics, so they must invalidate the key. Always directories.
            for (Path entry : sourceSet.outputDirs()) {
                compilerArtifacts.add(entry.toAbsolutePath().normalize());
            }
        }

        Set<Path> contentHashedFiles = new HashSet<>();
        for (Path file : sourceAndBuildFiles) {
            contentHashedFiles.add(file.toAbsolutePath().normalize());
        }
        // A path that is both a source and (improbably) a compiler artifact is hashed as a source, not mtime-stamped.
        compilerArtifacts.removeAll(contentHashedFiles);

        List<Path> allFiles = new ArrayList<>(contentHashedFiles);
        allFiles.addAll(compilerArtifacts);
        allFiles.stream()
                .distinct()
                .sorted()
                .forEach(path -> builder.append(contentHashedFiles.contains(path) ? contentStamp(path) : artifactStamp(path)).append('\n'));
        return builder.toString();
    }

    /**
     * Stamps a compiler-path artifact. A regular file (jar/class file) is mtime+size stamped, consistent with the
     * documented performance boundary (dependency jars are never content-hashed). A directory is recursively
     * fingerprinted so a changed/added/removed file under it invalidates the key. A missing path stamps as ":missing".
     */
    private static String artifactStamp(Path path) {
        if (Files.isDirectory(path)) {
            return directoryFingerprint(path);
        }
        return mtimeStamp(path);
    }

    /**
     * Recursively fingerprints a directory: walks the whole tree and stamps each regular file by path+mtime+size in
     * deterministic sorted order. Deterministic and bounded by the file count; never content-hashes. A missing or
     * unreadable directory stamps as ":missing" so its later appearance/disappearance still changes the key.
     */
    private static String directoryFingerprint(Path dir) {
        StringBuilder builder = new StringBuilder(dir.toString()).append(":dir[");
        List<String> entries = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                    entries.add(file.toAbsolutePath().normalize() + ":" + attributes.lastModifiedTime().toMillis()
                            + ":" + attributes.size());
                } catch (IOException e) {
                    entries.add(file.toAbsolutePath().normalize() + ":missing");
                }
            });
        } catch (IOException e) {
            return dir + ":missing";
        }
        entries.sort(null);
        for (String entry : entries) {
            builder.append(entry).append(';');
        }
        return builder.append(']').toString();
    }

    /** Stamps a source/build file by its SHA-256 content hash so content-only edits invalidate the cache. */
    private static String contentStamp(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return path + ":" + hex;
        } catch (IOException | NoSuchAlgorithmException e) {
            return path + ":missing";
        }
    }

    /** Stamps a classpath jar by mtime+size (content-hashing every dependency jar would be too slow). */
    private static String mtimeStamp(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return path + ":" + attributes.lastModifiedTime().toMillis() + ":" + attributes.size();
        } catch (IOException e) {
            return path + ":missing";
        }
    }
}
