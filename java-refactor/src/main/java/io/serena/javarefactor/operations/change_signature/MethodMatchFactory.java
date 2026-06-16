package io.serena.javarefactor.operations.change_signature;

import io.serena.javarefactor.compiler.SemanticIndex;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link MethodMatch} from a javac-resolved {@link SemanticIndex.SemanticMethod}: locates the header start,
 * splits out the modifier text, normalizes the display name (the owner name for constructors), and splits each
 * parameter's declared text into its annotation/{@code final} prefix and bare core type. Shared by the planner and
 * {@link OverrideSignatureUpdater} so every declaration in an override group is modeled identically.
 */
public final class MethodMatchFactory {

    private MethodMatchFactory() {
    }

    public static MethodMatch from(SemanticIndex index, SemanticIndex.SemanticMethod method) throws SignatureRefusal {
        CharSequence sourceText = index.sourceText(method.file());
        if (sourceText == null) {
            throw new SignatureRefusal("OVERRIDE_GROUP_INCOMPLETE", "Cannot resolve source text for override-group declaration in " + method.file() + ".");
        }
        String source = sourceText.toString();
        int headerStart = lineStart(source, method.headerRange().start());
        int headerEnd = method.headerRange().end();
        String header = source.substring(headerStart, headerEnd);
        boolean constructor = isConstructor(method);
        String displayName = constructor ? method.ownerName() : method.name();
        int nameInHeader = header.indexOf(displayName);
        if (nameInHeader < 0) {
            nameInHeader = Math.max(0, method.headerRange().start() - headerStart);
        }
        String modifiers;
        if (constructor) {
            modifiers = header.substring(0, nameInHeader);
        } else {
            int returnStart = method.returnType().isBlank() ? -1 : header.lastIndexOf(method.returnType() + " " + method.name());
            modifiers = returnStart >= 0 ? header.substring(0, returnStart) : header.substring(0, nameInHeader);
        }
        List<ParameterSpec> parameters = new ArrayList<>();
        for (SemanticIndex.SemanticParameter parameter : method.parameters()) {
            MethodSignatureModel.ParameterPrefix split = MethodSignatureModel.splitParameterPrefix(parameter.type().trim());
            parameters.add(new ParameterSpec(split.coreType(), parameter.name(), null, null, split.prefix()));
        }
        return new MethodMatch(
                headerStart,
                headerStart + nameInHeader,
                headerEnd,
                modifiers,
                constructor ? "" : method.returnType(),
                displayName,
                parameters,
                method,
                constructor);
    }

    public static boolean isConstructor(SemanticIndex.SemanticMethod method) {
        return method.element() != null && method.element().getKind().name().equals("CONSTRUCTOR");
    }

    private static int lineStart(String source, int offset) {
        int start = Math.min(offset, source.length());
        while (start > 0 && source.charAt(start - 1) != '\n') {
            start--;
        }
        return start;
    }
}
