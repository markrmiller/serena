package io.serena.javarefactor.shared;

import io.serena.javarefactor.compiler.SemanticIndex;
import io.serena.javarefactor.project.JavaProjectModel;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Charset-correct source text utilities for planner source reads.
 *
 * <p>All reads use the model-configured encoding (sourced from the source set's {@code -encoding}
 * compiler option, falling back to UTF-8 when none is declared). Planners must use this class
 * instead of hard-coding {@code StandardCharsets.UTF_8} so that projects with non-UTF-8 encodings
 * (e.g. ISO-8859-1, Windows-1252) produce correct source positions and substring extraction.
 *
 * <p>Phase-2 planner agents should adopt {@link #read(JavaProjectModel, Path)} and
 * {@link #substring(String, int, int)} for all source-text access.
 */
public final class SourceText {
    private SourceText() {}

    /**
     * Returns the effective source charset for {@code model}: the first valid {@code -encoding}
     * declared across its source sets, or UTF-8 when none is present.
     */
    public static Charset charsetOf(JavaProjectModel model) {
        return SemanticIndex.charsetOf(model);
    }

    /**
     * Reads {@code path} using the charset declared by {@code model}.
     *
     * @throws IOException if the file cannot be read
     */
    public static String read(JavaProjectModel model, Path path) throws IOException {
        return Files.readString(path, charsetOf(model));
    }

    /**
     * Returns the substring of {@code source} from {@code start} (inclusive) to {@code end}
     * (exclusive), clamping both indices to valid bounds so callers do not need to guard against
     * off-by-one compiler positions.
     */
    public static String substring(String source, int start, int end) {
        int s = Math.max(0, Math.min(start, source.length()));
        int e = Math.max(s, Math.min(end, source.length()));
        return source.substring(s, e);
    }
}
