package io.serena.javarefactor.operations.change_signature;

/**
 * A single desired or existing parameter in a change-signature / introduce-parameter plan: its declared {@code type},
 * its {@code name}, an optional call-site {@code defaultValue} (for an added parameter, re-emitted at every caller), an
 * optional {@code oldIndex} mapping it back to a current parameter (for retained/reordered parameters), the leading
 * annotation/{@code final} {@code prefix} that must travel with the rendered parameter, and {@code astVerifiedDefault}
 * marking that the {@code defaultValue} originates from a real {@link com.sun.source.util.TreePath} selection that javac
 * proved reorder-safe (introduce-parameter) — which supersedes the detached-text compile-time-constant gate that
 * applies to change-signature defaults.
 */
public record ParameterSpec(
        String type, String name, String defaultValue, Integer oldIndex, String prefix, boolean astVerifiedDefault) {
    public ParameterSpec(String type, String name, String defaultValue) {
        this(type, name, defaultValue, null, "", false);
    }

    public ParameterSpec(String type, String name, String defaultValue, Integer oldIndex) {
        this(type, name, defaultValue, oldIndex, "", false);
    }

    public ParameterSpec(String type, String name, String defaultValue, Integer oldIndex, String prefix) {
        this(type, name, defaultValue, oldIndex, prefix, false);
    }
}
