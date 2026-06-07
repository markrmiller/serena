package io.serena.javarefactor.ast;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;

import java.nio.file.Path;

public record IdentifierSpan(Path file, long startOffset, long endOffset, long line, long column, String text) {
    public static IdentifierSpan fromOffsets(Path file, CompilationUnitTree unit, CharSequence source, long startOffset, long endOffset) {
        LineMap lineMap = unit.getLineMap();
        String text = source.subSequence((int) startOffset, (int) endOffset).toString();
        return new IdentifierSpan(
                file.toAbsolutePath().normalize(),
                startOffset,
                endOffset,
                lineMap.getLineNumber(startOffset),
                lineMap.getColumnNumber(startOffset),
                text
        );
    }

    String toJson(Path projectRoot) {
        String relative = projectRoot.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return "{"
                + "\"relativePath\":" + JsonUtil.quote(relative) + ","
                + "\"startOffset\":" + startOffset + ","
                + "\"endOffset\":" + endOffset + ","
                + "\"line\":" + line + ","
                + "\"column\":" + column + ","
                + "\"text\":" + JsonUtil.quote(text)
                + "}";
    }
}
