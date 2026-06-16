package io.serena.javarefactor.protocol;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.operations.move_member.*;
import io.serena.javarefactor.operations.inline_method.*;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public final class JsonUtil {
    private JsonUtil() {
    }

    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }

    public static String array(Collection<String> values) {
        return values.stream().map(JsonUtil::quote).collect(Collectors.joining(",", "[", "]"));
    }

    /** Builds a JSON array from already-serialized JSON values (objects, numbers, arrays) without re-quoting them. */
    public static String rawArray(Collection<String> serializedValues) {
        return serializedValues.stream().collect(Collectors.joining(",", "[", "]"));
    }

    public static String object(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> quote(entry.getKey()) + ":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
