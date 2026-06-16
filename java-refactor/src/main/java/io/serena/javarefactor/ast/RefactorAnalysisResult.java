package io.serena.javarefactor.ast;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public record RefactorAnalysisResult(ResolvedTarget target, List<IdentifierSpan> references) {
    public String targetJson(Path projectRoot) {
        return target == null ? "null" : target.toJson(projectRoot);
    }

    public String referencesJson(Path projectRoot) {
        return references.stream().map(reference -> reference.toJson(projectRoot)).collect(Collectors.joining(",", "[", "]"));
    }
}
