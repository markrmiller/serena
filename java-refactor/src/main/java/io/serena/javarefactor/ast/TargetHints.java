package io.serena.javarefactor.ast;
import io.serena.javarefactor.protocol.*;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Caller-supplied identity of the symbol a refactoring was requested FOR, verified against what position-based
 * resolution actually found.
 *
 * <p>Serena selects refactoring targets by {@code name_path} and resolves them to a line/column via its language
 * server. That position round-trip is lossy: on an overloaded method, a same-line sibling declaration, an enclosing
 * declaration's line, or a field/parameter sharing a simple name, a slightly-off position can resolve to a DIFFERENT
 * semantic element than the one the caller named — and the refactoring would then be planned for the wrong symbol.
 * These hints close that gap: {@link #mismatch} proves the javac-resolved element matches the caller's named symbol
 * (simple name), its coarse kind, and — for executables — its parameter arity, refusing the operation otherwise.
 *
 * <p>All hints are optional (absent hints verify nothing — position-only callers keep working), but a PRESENT hint
 * that does not match is a hard refusal: identity that cannot be proven must not be guessed. {@code arityHint} uses
 * {@code -1} for "not provided". Unknown {@code kindHint} values are ignored rather than refused so newer Serena
 * clients can introduce finer categories without breaking older sidecars.</p>
 */
public record TargetHints(String nameHint, String kindHint, long arityHint) {

    /** No identity information: every target verifies successfully. */
    public static final TargetHints NONE = new TargetHints(null, null, -1L);

    /** Whether any verifiable hint is present. */
    public boolean isEmpty() {
        return (nameHint == null || nameHint.isBlank()) && (kindHint == null || kindHint.isBlank()) && arityHint < 0;
    }

    /**
     * Returns a human-readable mismatch description when the resolved target does not match these hints, else null.
     */
    public String mismatch(ResolvedTarget target) {
        if (isEmpty()) {
            return null;
        }
        Element element = target.element();
        ElementKind kind = element.getKind();
        String resolvedSimpleName = PlannerSupport.simpleName(target.key().name());
        if (nameHint != null && !nameHint.isBlank() && !nameMatches(element, kind, resolvedSimpleName)) {
            return "the position resolved to '" + resolvedSimpleName + "' (" + kind
                    + "), not the requested symbol '" + nameHint.strip()
                    + "'. The requested name path and the resolved source position identify different declarations.";
        }
        if (kindHint != null && !kindHint.isBlank() && !kindMatches(kind)) {
            return "the position resolved to '" + resolvedSimpleName + "' of kind " + kind
                    + ", which is not the requested symbol kind '" + kindHint.strip() + "'.";
        }
        if (arityHint >= 0) {
            if (!(element instanceof ExecutableElement executable)) {
                return "a method/constructor with " + arityHint + " parameter(s) was requested, but the position "
                        + "resolved to '" + resolvedSimpleName + "' of kind " + kind + ".";
            }
            int parameterCount = executable.getParameters().size();
            if (parameterCount != arityHint) {
                return "the position resolved to the " + parameterCount + "-parameter overload of '"
                        + resolvedSimpleName + "', not the requested " + arityHint + "-parameter one.";
            }
        }
        return null;
    }

    private boolean nameMatches(Element element, ElementKind kind, String resolvedSimpleName) {
        String expected = nameHint.strip();
        if (resolvedSimpleName.equals(expected)) {
            return true;
        }
        // A constructor is declared under the type's name in source (and in any caller-facing name path), but javac
        // keys it as <init>; compare against the enclosing type's simple name instead.
        return kind == ElementKind.CONSTRUCTOR
                && element.getEnclosingElement() instanceof TypeElement type
                && type.getSimpleName().contentEquals(expected);
    }

    private boolean kindMatches(ElementKind kind) {
        return switch (kindHint.strip()) {
            case "type" -> kind.isClass() || kind.isInterface();
            case "method" -> kind == ElementKind.METHOD;
            case "constructor" -> kind == ElementKind.CONSTRUCTOR;
            // ElementKind.isField() covers FIELD and ENUM_CONSTANT; record components surface as FIELD via their
            // backing field when resolved at the component position.
            case "field" -> kind.isField() || kind == ElementKind.RECORD_COMPONENT;
            case "variable" -> switch (kind) {
                case LOCAL_VARIABLE, RESOURCE_VARIABLE, EXCEPTION_PARAMETER -> true;
                // BINDING_VARIABLE by name for parity with SemanticKey's compatibility handling.
                default -> kind.name().equals("BINDING_VARIABLE");
            };
            case "parameter" -> kind == ElementKind.PARAMETER;
            case "type_parameter" -> kind == ElementKind.TYPE_PARAMETER;
            case "package" -> kind == ElementKind.PACKAGE;
            // Unknown categories from a newer client verify nothing rather than refusing valid requests.
            default -> true;
        };
    }
}
