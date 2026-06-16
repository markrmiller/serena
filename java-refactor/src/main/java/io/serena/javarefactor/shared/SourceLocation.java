package io.serena.javarefactor.shared;

/** A source span using one-based line/column coordinates. */
public record SourceLocation(String relativePath, int startLine, int startColumn, int endLine, int endColumn) {}
