package io.serena.javarefactor.compiler;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory substitution of a project's on-disk Java source for staged-but-uncommitted edits. Used by
 * {@link JavacSession#validate(JavaProjectModel, FileOverlay)} to compile post-edit content without writing to disk.
 *
 * <p>An overlay is built from absolute paths so it can be matched against a {@link SourceSet}'s resolved
 * {@code javaFiles()}: {@code changed} maps a path to its new full content, {@code deleted} excludes a path from
 * compilation, and a rename is modelled as deleting the old path and adding the new path with content.</p>
 */
public final class FileOverlay {
    static final FileOverlay EMPTY = new FileOverlay(Map.of(), Set.of());

    private final Map<Path, String> content;
    private final Set<Path> removed;

    private FileOverlay(Map<Path, String> content, Set<Path> removed) {
        this.content = content;
        this.removed = removed;
    }

    boolean isEmpty() {
        return content.isEmpty() && removed.isEmpty();
    }

    /** Whether the overlay supplies in-memory content for an (absolute, normalized) path. */
    boolean hasContent(Path absolutePath) {
        return content.containsKey(absolutePath);
    }

    /** The in-memory content for an (absolute, normalized) path, or null when the overlay does not cover it. */
    String contentFor(Path absolutePath) {
        return content.get(absolutePath);
    }

    /** Whether the overlay deletes or renames away an (absolute, normalized) path. */
    boolean isRemoved(Path absolutePath) {
        return removed.contains(absolutePath);
    }

    /** The absolute, normalized paths the overlay supplies in-memory content for (changed and renamed-in files). */
    Set<Path> contentPaths() {
        return content.keySet();
    }

    /**
     * Builds an in-memory source file object for an (absolute, normalized) path the overlay supplies content for,
     * wrapping {@code delegate} (the on-disk object javac located) so the file manager can still infer its binary name.
     */
    JavaFileObject sourceFor(Path absolutePath, JavaFileObject delegate) {
        return new InMemorySource(absolutePath, content.get(absolutePath), delegate);
    }

    /**
     * The Java file objects a source set compiles once the overlay is applied: on-disk files that the overlay removes
     * (deleted or renamed-away) are dropped and ones it changes are replaced with in-memory content; files renamed into
     * any of this set's source roots from elsewhere are added with their in-memory content. When the overlay is empty
     * this is exactly the source set's on-disk {@code javaFiles()}.
     */
    List<JavaFileObject> fileObjectsFor(StandardJavaFileManager standardManager, SourceSet sourceSet, List<SourceSet> allSourceSets) {
        List<JavaFileObject> result = new ArrayList<>();
        List<Path> onDisk = new ArrayList<>();
        for (Path file : sourceSet.javaFiles()) {
            Path normalized = file.toAbsolutePath().normalize();
            if (removed.contains(normalized)) {
                continue;
            }
            String overlaid = content.get(normalized);
            if (overlaid != null) {
                result.add(source(normalized, overlaid));
            } else {
                onDisk.add(normalized);
            }
        }
        // Read unchanged on-disk files through the standard manager so their content and encoding are handled exactly
        // as the non-overlay path does (getJavaFileObjectsFromPaths).
        for (JavaFileObject object : standardManager.getJavaFileObjectsFromPaths(onDisk)) {
            result.add(object);
        }
        // Add files the overlay introduces (e.g. rename targets, created files) that land under this set's roots or
        // dependency source roots but are not yet part of any source set's on-disk javaFiles() listing.
        LinkedHashSet<Path> existing = new LinkedHashSet<>();
        for (SourceSet set : allSourceSets) {
            for (Path file : set.javaFiles()) {
                existing.add(file.toAbsolutePath().normalize());
            }
        }
        List<Path> visibleRoots = new ArrayList<>(sourceSet.sourceRoots());
        visibleRoots.addAll(SourceSet.crossSourceRoots(sourceSet, allSourceSets));
        for (Map.Entry<Path, String> entry : content.entrySet()) {
            Path path = entry.getKey();
            if (existing.contains(path)) {
                continue;
            }
            if (underAnyRoot(path, visibleRoots)) {
                result.add(source(path, entry.getValue()));
            }
        }
        return result;
    }

    private static boolean underAnyRoot(Path path, List<Path> roots) {
        for (Path root : roots) {
            if (path.startsWith(root.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    private static JavaFileObject source(Path path, String content) {
        return new InMemorySource(path, content, null);
    }

    /** The on-disk object an overlay source wraps (for binary-name inference), or null when there is none. */
    static JavaFileObject delegateOf(JavaFileObject object) {
        return object instanceof InMemorySource source ? source.delegate : null;
    }

    /**
     * Builds an overlay from the protocol payload. {@code changedFiles} maps a project-relative path to new full
     * content; {@code deletedFiles} lists project-relative paths to exclude; {@code renamedFiles} pairs {@code oldPath}
     * with {@code newPath} (the new path's content must be supplied in {@code changedFiles}). Relative paths are
     * resolved against and confined to {@code projectRoot}.
     */
    public static FileOverlay fromProtocol(Path projectRoot, Map<String, Object> changedFiles, List<Object> deletedFiles, List<Object> renamedFiles) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Map<Path, String> content = new LinkedHashMap<>();
        Set<Path> removed = new LinkedHashSet<>();

        for (Map.Entry<String, Object> entry : changedFiles.entrySet()) {
            content.put(resolve(root, entry.getKey()), String.valueOf(entry.getValue()));
        }
        for (Object deleted : deletedFiles) {
            removed.add(resolve(root, String.valueOf(deleted)));
        }
        for (Object rename : renamedFiles) {
            if (rename instanceof Map<?, ?> pair) {
                Object oldPath = pair.get("oldPath");
                if (oldPath != null) {
                    removed.add(resolve(root, String.valueOf(oldPath)));
                }
                // The new path's content is expected in changedFiles; nothing more is needed here.
            }
        }
        return new FileOverlay(content, removed);
    }

    private static Path resolve(Path root, String relativePath) {
        Path candidate = Path.of(relativePath);
        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException("Overlay path must be project-relative: " + relativePath);
        }
        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Overlay path escapes project root: " + relativePath);
        }
        return resolved;
    }

    /** A javac source unit backed by an in-memory string but reporting the real source path as its URI. */
    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String content;
        // The on-disk object this substitutes for, when the substitution happens during file-manager resolution (so the
        // manager can still infer a binary name). Null when this is an explicitly-supplied compilation unit.
        private final JavaFileObject delegate;

        private InMemorySource(Path path, String content, JavaFileObject delegate) {
            super(path.toUri(), Kind.SOURCE);
            this.content = content;
            this.delegate = delegate;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
