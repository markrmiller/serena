package io.serena.javarefactor.compiler;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A session-scoped pool of {@link StandardJavaFileManager} instances shared across javac tasks.
 *
 * <p>{@link JavaCompiler} explicitly documents that a single file manager may be reused across multiple
 * {@code getTask} invocations so the costly file-system and jar scanning it performs (resolving and indexing the
 * classpath/module-path archives) is done once and cached for every subsequent task. Creating a fresh standard file
 * manager per validation/index pass — as every javac call site here previously did — repeats that jar scan on every
 * pass and is the dominant per-pass cost on a real classpath. This pool hands each call site the shared manager for
 * its configuration instead, so the scan happens once per distinct configuration and is amortized across passes.</p>
 *
 * <p>Managers are keyed by the source charset plus the javac options list relevant to file-manager state
 * ({@code charsetName + '\0' + String.join("\0", options)}). {@code getTask} applies a task's options to its file
 * manager (e.g. {@code -classpath}, {@code -sourcepath}, {@code --module-path} configure the manager's locations), so
 * two tasks with the SAME charset and options end up with the same location state and can safely share one manager;
 * two tasks with DIFFERENT options must NOT share, or one task's locations would leak into the other. Keying on the
 * full options string gives exactly that: identical configuration reuses, differing configuration gets its own pooled
 * instance, and location state never crosses between differently-configured tasks.</p>
 *
 * <p>Managers are created with a {@code null} {@link javax.tools.DiagnosticListener}. File-manager-level diagnostics
 * (raised while the manager itself resolves files) are rare; the per-task {@code DiagnosticCollector}s that call sites
 * pass to {@code getTask} are unaffected and continue to capture every compilation diagnostic, so error reporting is
 * unchanged.</p>
 *
 * <p>{@link #INSTANCE} is a process-wide singleton: one sidecar process serves exactly one project and the protocol
 * loop in {@code Main} is single-threaded, so a single session-scoped pool is the simplest safe lifecycle. The pooled
 * managers must be dropped whenever the project model changes (its classpath jars may have been rebuilt), which
 * {@link #invalidate()} does. The methods are nonetheless {@code synchronized} so the JVM unit tests — and any future
 * multi-threaded use — stay correct.</p>
 */
public final class FileManagerPool {
    /**
     * The process-wide pool. One sidecar serves one project; {@code Main} drives it single-threaded and invalidates the
     * pool when the project model key changes and on shutdown.
     */
    public static final FileManagerPool INSTANCE = new FileManagerPool();

    private final Map<String, StandardJavaFileManager> managers = new LinkedHashMap<>();
    private int creationCount;

    /**
     * Returns the pooled {@link StandardJavaFileManager} for {@code (charset, options)}, creating it on first use.
     * The returned manager is owned by the pool: callers must NOT close it (only {@link #invalidate()} does). Pass the
     * same charset and options that the task built from this manager will be given to {@code getTask}, so the cached
     * location state matches.
     */
    public synchronized StandardJavaFileManager acquire(JavaCompiler compiler, Charset charset, List<String> options) {
        String key = keyFor(charset, options);
        StandardJavaFileManager existing = managers.get(key);
        if (existing != null) {
            return existing;
        }
        // null DiagnosticListener: file-manager-level diagnostics are rare and the per-task DiagnosticCollectors that
        // call sites pass to getTask still capture every compilation diagnostic, so reporting is unchanged.
        StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, charset);
        applyModuleSourcePath(manager, options);
        managers.put(key, manager);
        creationCount++;
        return manager;
    }

    /**
     * Applies every module-specific {@code --module-source-path <module>=<root>} option to the file manager ONCE, at
     * creation. Unlike the path options ({@code -classpath}, {@code --module-path}, {@code -sourcepath}) — which javac
     * re-applies idempotently from a task's options every {@code getTask} — a module's source path may be set only once:
     * javac fails a second {@code getTask} that re-specifies it ("{@code --module-source-path specified more than once
     * for module X}") when that task shares this pooled manager. So the module-specific entries are configured here, on
     * the manager, via {@link StandardJavaFileManager#setLocationForModule}, and callers pass {@link #taskOptions} (the
     * same option list with these pairs removed) to {@code getTask}: the module source path is established exactly once
     * while the manager stays freely reusable across passes. A pattern-form {@code --module-source-path} (no
     * {@code module=}) is left in the task options for javac to handle.
     */
    private static void applyModuleSourcePath(StandardJavaFileManager manager, List<String> options) {
        if (options == null) {
            return;
        }
        for (int i = 0; i + 1 < options.size(); i++) {
            if (!options.get(i).equals("--module-source-path")) {
                continue;
            }
            String value = options.get(i + 1);
            int eq = value.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String moduleName = value.substring(0, eq);
            Path root = Path.of(value.substring(eq + 1));
            try {
                manager.setLocationForModule(StandardLocation.MODULE_SOURCE_PATH, moduleName, List.of(root));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "failed to set module source path for module " + moduleName + " -> " + root, e);
            }
        }
    }

    /**
     * The options to pass to {@code getTask} for a manager from {@link #acquire}: the full option list with each
     * module-specific {@code --module-source-path <module>=<root>} pair removed, because {@link #acquire} has already
     * applied those to the manager and javac forbids specifying a module's source path a second time. Other options
     * (including a pattern-form {@code --module-source-path} with no {@code module=}) pass through unchanged.
     */
    public static List<String> taskOptions(List<String> options) {
        if (options == null) {
            return List.of();
        }
        List<String> filtered = new ArrayList<>(options.size());
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equals("--module-source-path") && i + 1 < options.size()
                    && options.get(i + 1).indexOf('=') > 0) {
                i++;
                continue;
            }
            filtered.add(options.get(i));
        }
        return filtered;
    }

    /**
     * Closes and drops every pooled manager. Called when the project model changes (a rebuilt classpath jar must not be
     * served from a stale scan) and on shutdown. A close failure is swallowed (logged) so one bad manager cannot block
     * the others from being released.
     */
    public synchronized void invalidate() {
        for (StandardJavaFileManager manager : managers.values()) {
            try {
                manager.close();
            } catch (IOException e) {
                System.err.println("FileManagerPool: failed to close a pooled file manager: " + e.getMessage());
            }
        }
        managers.clear();
    }

    /** The number of managers this pool has created. Tests assert reuse by checking this does not grow across passes. */
    public synchronized int creationCount() {
        return creationCount;
    }

    /**
     * The pool key for a configuration: charset name, then the options joined on NUL. NUL cannot appear in an option
     * token, so the join is unambiguous; identical (charset, options) collapse to one key, any difference splits it.
     */
    private static String keyFor(Charset charset, List<String> options) {
        List<String> safe = options == null ? List.of() : new ArrayList<>(options);
        return charset.name() + '\0' + String.join("\0", safe);
    }
}
