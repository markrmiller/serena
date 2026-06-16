package io.serena.javarefactor.shared;

import java.util.Map;

/** A structured refusal emitted by conservative V2 semantic infrastructure. */
public record StructuredRefusal(String code, String message, Map<String, String> details) {
    public StructuredRefusal(String code, String message) {
        this(code, message, Map.of());
    }
}
