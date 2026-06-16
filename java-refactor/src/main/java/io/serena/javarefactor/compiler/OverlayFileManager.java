package io.serena.javarefactor.compiler;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A {@link ForwardingJavaFileManager} that substitutes a {@link FileOverlay} for on-disk source when javac resolves
 * files indirectly (e.g. cross-source-set references loaded via {@code -sourcepath}). Files the overlay changes are
 * served from in-memory content, files it deletes or renames away are hidden, and everything else is delegated to the
 * underlying {@link StandardJavaFileManager}. Disk is never written.
 */
public final class OverlayFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final FileOverlay overlay;

    OverlayFileManager(StandardJavaFileManager fileManager, FileOverlay overlay) {
        super(fileManager);
        this.overlay = overlay;
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse)
            throws IOException {
        Iterable<JavaFileObject> listed = super.list(location, packageName, kinds, recurse);
        if (overlay.isEmpty()) {
            return listed;
        }
        List<JavaFileObject> result = new ArrayList<>();
        for (JavaFileObject object : listed) {
            Path path = pathOf(object);
            if (path != null && overlay.isRemoved(path)) {
                continue;
            }
            if (path != null && overlay.hasContent(path)) {
                result.add(overlay.sourceFor(path, object));
            } else {
                result.add(object);
            }
        }
        return result;
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) throws IOException {
        JavaFileObject object = super.getJavaFileForInput(location, className, kind);
        if (overlay.isEmpty()) {
            return object;
        }
        if (object != null) {
            Path path = pathOf(object);
            if (path == null) {
                return object;
            }
            if (overlay.isRemoved(path)) {
                return null;
            }
            return overlay.hasContent(path) ? overlay.sourceFor(path, object) : object;
        }
        // The class is not on disk (e.g. a file renamed/created by the overlay into a package another source set resolves
        // via -sourcepath). Serve it from the overlay so cross-source-set references to moved/created types resolve.
        return overlayAddedFile(className, kind);
    }

    /**
     * The overlay-supplied file for {@code className} that has no on-disk counterpart, or null. A path-backed (but
     * possibly non-existent) delegate is created so javac can still infer the binary name from the file's location.
     */
    private JavaFileObject overlayAddedFile(String className, JavaFileObject.Kind kind) {
        String relative = className.replace('.', '/') + kind.extension;
        for (Path path : overlay.contentPaths()) {
            if (overlay.isRemoved(path)) {
                continue;
            }
            String normalized = path.toString().replace('\\', '/');
            if (normalized.equals(relative) || normalized.endsWith("/" + relative)) {
                JavaFileObject delegate = null;
                for (JavaFileObject candidate : fileManager.getJavaFileObjectsFromPaths(List.of(path))) {
                    delegate = candidate;
                    break;
                }
                return overlay.sourceFor(path, delegate);
            }
        }
        return null;
    }

    @Override
    public void close() {
        // The delegate StandardJavaFileManager is owned by FileManagerPool and shared across javac tasks, so closing it
        // here would discard the cached jar/file-system scan the pool exists to preserve. This wrapper holds no
        // resources of its own beyond the delegate, so closing it is a no-op; the pool closes the delegate on
        // invalidate().
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        // Overlay-substituted sources are not file objects the standard manager recognizes; infer their binary name from
        // the on-disk object they wrapped instead.
        JavaFileObject delegate = FileOverlay.delegateOf(file);
        return super.inferBinaryName(location, delegate != null ? delegate : file);
    }

    private static Path pathOf(FileObject object) {
        try {
            return Path.of(object.toUri()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            // Not a file-backed object (e.g. jar entry); the overlay only governs source files on disk.
            return null;
        }
    }
}
