package io.serena.javarefactor.compiler;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link FileManagerPool} shares a {@link StandardJavaFileManager} across javac tasks so the costly classpath/jar
 * scan is performed once per distinct (charset, options) configuration rather than once per pass. These tests use a
 * FRESH {@code new FileManagerPool()} per test (never {@link FileManagerPool#INSTANCE}) so they stay independent and
 * parallel-safe, and assert both the pool's keying/lifecycle directly and that a repeated {@link JavacSession}
 * validation pass reuses the pooled managers instead of recreating them.
 */
class FileManagerPoolTest {

    @Test
    void sameKeyReturnsSameInstanceWithoutRecreating() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        FileManagerPool pool = new FileManagerPool();
        List<String> options = List.of("-source", "17", "-target", "17");

        StandardJavaFileManager first = pool.acquire(compiler, StandardCharsets.UTF_8, options);
        StandardJavaFileManager second = pool.acquire(compiler, StandardCharsets.UTF_8, options);
        StandardJavaFileManager third = pool.acquire(compiler, StandardCharsets.UTF_8, List.of("-source", "17", "-target", "17"));

        assertSame(first, second);
        assertSame(first, third);
        assertEquals(1, pool.creationCount());
    }

    @Test
    void differentCharsetOrOptionsReturnDifferentInstances() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        FileManagerPool pool = new FileManagerPool();

        StandardJavaFileManager utf8 = pool.acquire(compiler, StandardCharsets.UTF_8, List.of("-source", "17"));
        StandardJavaFileManager latin1 = pool.acquire(compiler, StandardCharsets.ISO_8859_1, List.of("-source", "17"));
        StandardJavaFileManager differentOptions = pool.acquire(compiler, StandardCharsets.UTF_8, List.of("-source", "21"));

        assertNotSame(utf8, latin1);
        assertNotSame(utf8, differentOptions);
        assertEquals(3, pool.creationCount());
    }

    @Test
    void invalidateThenAcquireCreatesNewInstance() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        FileManagerPool pool = new FileManagerPool();
        List<String> options = List.of("-source", "17");

        StandardJavaFileManager before = pool.acquire(compiler, StandardCharsets.UTF_8, options);
        assertEquals(1, pool.creationCount());

        pool.invalidate();

        StandardJavaFileManager after = pool.acquire(compiler, StandardCharsets.UTF_8, options);
        assertNotSame(before, after);
        assertEquals(2, pool.creationCount());
    }

    @Test
    void repeatedValidationReusesPooledManagers(@TempDir Path tmp) throws IOException {
        // A repeated validation pass over the same model must NOT recreate file managers: the second pass acquires the
        // same pooled instance keyed by (charset, options), so the classpath/jar scan is done once.
        FileManagerPool pool = new FileManagerPool();
        JavaProjectModel model = singleFileModel(tmp);
        JavacSession session = new JavacSession(pool);

        session.collectDiagnostics(model, FileOverlay.EMPTY);
        int afterFirstPass = pool.creationCount();
        assertTrue(afterFirstPass >= 1, "first pass should have created at least one pooled manager");

        session.collectDiagnostics(model, FileOverlay.EMPTY);
        assertEquals(afterFirstPass, pool.creationCount(), "second pass must reuse pooled managers, not recreate them");
    }

    /** A minimal one-source-set, one-file project model rooted at {@code root} that compiles cleanly. */
    private static JavaProjectModel singleFileModel(Path root) throws IOException {
        Path sourceRoot = root.resolve("src");
        Path pkg = sourceRoot.resolve("demo");
        Files.createDirectories(pkg);
        Path javaFile = pkg.resolve("Demo.java");
        Files.writeString(javaFile, "package demo;\npublic final class Demo {\n    public int value() { return 1; }\n}\n");

        SourceSet sourceSet = new SourceSet(
                "main",
                List.of(sourceRoot),
                List.of(javaFile),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "17",
                null,
                null,
                "UTF-8",
                false,
                "none",
                List.of(),
                false,
                List.of("-source", "17", "-target", "17", "-encoding", "UTF-8"),
                List.of(),
                List.of());
        return new JavaProjectModel(
                root,
                "test",
                List.of(sourceSet),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                List.of());
    }
}
