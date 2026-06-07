package io.serena.javarefactor.ast;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import javax.lang.model.element.Element;
import java.nio.file.Path;

public record ResolvedTarget(Element element, SemanticKey key, IdentifierSpan span) {
    public String toJson(Path projectRoot) {
        return "{"
                + "\"semanticKey\":" + key.toJson() + ","
                + "\"span\":" + span.toJson(projectRoot)
                + "}";
    }
}
