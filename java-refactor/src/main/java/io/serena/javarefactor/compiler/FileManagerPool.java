package io.serena.javarefactor.compiler;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import java.io.IOException;
import java.nio.charset.Charset;
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
        managers.put(key, manager);
        creationCount++;
        return manager;
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
