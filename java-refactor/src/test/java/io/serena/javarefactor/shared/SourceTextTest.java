package io.serena.javarefactor.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.serena.javarefactor.project.JavaProjectModel;
import io.serena.javarefactor.project.SourceSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Verifies that {@link SourceText} reads source files using the model-configured charset rather
 * than a hard-coded UTF-8, so non-UTF-8 projects (e.g. ISO-8859-1) produce correct source text
 * and offset-based substring extraction.
 */
class SourceTextTest {

    // ── charset resolution ───────────────────────────────────────────────────

    @Test
    void charsetOfReturnsModelEncodingWhenDeclared() {
        JavaProjectModel model = modelWithEncoding("ISO-8859-1");
        assertEquals(Charset.forName("ISO-8859-1"), SourceText.charsetOf(model));
    }

    @Test
    void charsetOfDefaultsToUtf8WhenEncodingIsAbsent() {
        JavaProjectModel model = modelWithEncoding(null);
        assertEquals(StandardCharsets.UTF_8, SourceText.charsetOf(model));
    }

    @Test
    void charsetOfDefaultsToUtf8WhenEncodingIsBlank() {
        JavaProjectModel model = modelWithEncoding("  ");
        assertEquals(StandardCharsets.UTF_8, SourceText.charsetOf(model));
    }

    // ── ISO-8859-1 round-trip ────────────────────────────────────────────────

    /**
     * Writes a Java source file encoded in ISO-8859-1 containing a Latin-1 character
     * (ü, U+00FC, byte 0xFC in ISO-8859-1) inside a string literal, then reads it back
     * through {@link SourceText#read} with a model that declares that encoding.
     * The character must survive the round-trip intact; if the read fell back to UTF-8
     * it would produce a replacement character or throw a MalformedInputException.
     */
    @Test
    void readUsesModelCharsetForIso88591File(@TempDir Path tmp) throws IOException {
        // "Grüß Gott" — ü (0xFC) and ß (0xDF) are not valid UTF-8 bytes when ISO-8859-1 encoded
        String sourceText = "package demo;\npublic class Greet {\n    String msg = \"Grüß Gott\";\n}\n";
        Path srcFile = writeEncoded(tmp, "Greet.java", sourceText, Charset.forName("ISO-8859-1"));

        JavaProjectModel model = modelWithEncodingAndFile(tmp, "ISO-8859-1", srcFile);
        String read = SourceText.read(model, srcFile);

        assertEquals(sourceText, read, "ISO-8859-1 characters must round-trip correctly");
    }

    @Test
    void readUsesUtf8ForUtf8FileWhenNoEncodingDeclared(@TempDir Path tmp) throws IOException {
        String sourceText = "package demo;\npublic class Hello {\n    String s = \"é\";\n}\n";
        Path srcFile = writeEncoded(tmp, "Hello.java", sourceText, StandardCharsets.UTF_8);

        JavaProjectModel model = modelWithEncodingAndFile(tmp, null, srcFile);
        String read = SourceText.read(model, srcFile);

        assertEquals(sourceText, read);
    }

    // ── substring helper ────────────────────────────────────────────────────

    @Test
    void substringExtractsCorrectRange() {
        String s = "hello world";
        assertEquals("world", SourceText.substring(s, 6, 11));
    }

    @Test
    void substringClampsNegativeStart() {
        assertEquals("hel", SourceText.substring("hello", -5, 3));
    }

    @Test
    void substringClampsEndBeyondLength() {
        assertEquals("hello", SourceText.substring("hello", 0, 999));
    }

    @Test
    void substringHandlesStartBeyondLength() {
        assertEquals("", SourceText.substring("hello", 10, 20));
    }

    @Test
    void substringWithIso88591OffsetsDerivedFromCompilerPositions(@TempDir Path tmp) throws IOException {
        // Simulate what a compiler position-based extraction would do: byte offsets in decoded text.
        // "Grüß" in ISO-8859-1 is 4 chars, so char-offset of "Gott" starts at 5 (after "Grüß ").
        String sourceText = "Grüß Gott";
        Path srcFile = writeEncoded(tmp, "Snippet.java", sourceText, Charset.forName("ISO-8859-1"));

        JavaProjectModel model = modelWithEncodingAndFile(tmp, "ISO-8859-1", srcFile);
        String decoded = SourceText.read(model, srcFile);

        // Extract "Gott" starting at char offset 5
        assertEquals("Gott", SourceText.substring(decoded, 5, 9));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Path writeEncoded(Path dir, String name, String content, Charset charset) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, content.getBytes(charset));
        return file;
    }

    /** Model with the given encoding string and no source files registered (for charset-resolution tests). */
    private static JavaProjectModel modelWithEncoding(String encoding) {
        SourceSet sourceSet = minimalSourceSet(encoding, List.of());
        return new JavaProjectModel(
                Path.of(System.getProperty("java.io.tmpdir")),
                "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }

    /** Model with the given encoding and a single registered source file (for read tests). */
    private static JavaProjectModel modelWithEncodingAndFile(Path root, String encoding, Path file) {
        SourceSet sourceSet = minimalSourceSet(encoding, List.of(file));
        return new JavaProjectModel(root, "test", List.of(sourceSet), List.of(), List.of(), List.of(), false, false, List.of());
    }

    private static SourceSet minimalSourceSet(String encoding, List<Path> javaFiles) {
        return new SourceSet(
                "main",
                List.of(),
                javaFiles,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "17",
                null,
                null,
                encoding,
                false,
                "none",
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of());
    }
}
