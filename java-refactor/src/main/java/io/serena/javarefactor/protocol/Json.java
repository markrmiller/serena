package io.serena.javarefactor.protocol;
import io.serena.javarefactor.project.*;
import io.serena.javarefactor.compiler.*;
import io.serena.javarefactor.ast.*;
import io.serena.javarefactor.edits.*;
import io.serena.javarefactor.rename.*;
import io.serena.javarefactor.safedelete.*;
import io.serena.javarefactor.move.*;
import io.serena.javarefactor.inline.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON parser for sidecar request lines.
 *
 * <p>The Python side always emits well-formed JSON, but requests can contain nested objects (e.g. the
 * {@code params} payload) and escaped strings, so a real recursive-descent parser is used instead of regexes.
 * Values are mapped to {@link Map} (objects), {@link List} (arrays), {@link String}, {@link Long}/{@link Double}
 * (numbers), {@link Boolean}, or {@code null}.</p>
 */
public final class Json {
    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Serializes a parsed JSON value (the object/list/string/number/boolean/null types produced by
     * {@link #parseObject}) back to a compact JSON string. Used to merge the structured {@code config} object with the
     * legacy {@code configuration} string and the top-level {@code encoding}/{@code ignoredPatterns} into a single
     * configuration string the discovery layer parses uniformly.
     */
    static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(JsonUtil.quote(String.valueOf(entry.getKey()))).append(':');
                writeValue(builder, entry.getValue());
            }
            builder.append('}');
        } else if (value instanceof List<?> list) {
            builder.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                writeValue(builder, list.get(i));
            }
            builder.append(']');
        } else if (value instanceof String string) {
            builder.append(JsonUtil.quote(string));
        } else if (value instanceof Boolean || value instanceof Number) {
            builder.append(value);
        } else {
            builder.append(JsonUtil.quote(String.valueOf(value)));
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Json parser = new Json(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.index != parser.text.length()) {
            throw new IllegalArgumentException("Trailing content after JSON value at offset " + parser.index);
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (index >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        }
        char c = text.charAt(index);
        return switch (c) {
            case '{' -> parseObjectValue();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObjectValue() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            result.put(key, value);
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return result;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected ',' or '}' in object at offset " + (index - 1));
            }
        }
    }

    private List<Object> parseArray() {
        List<Object> result = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return result;
        }
        while (true) {
            result.add(parseValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return result;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected ',' or ']' in array at offset " + (index - 1));
            }
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (true) {
            if (index >= text.length()) {
                throw new IllegalArgumentException("Unterminated JSON string");
            }
            char c = text.charAt(index++);
            if (c == '"') {
                return builder.toString();
            }
            if (c == '\\') {
                char escape = next();
                switch (escape) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) {
                            throw new IllegalArgumentException("Invalid unicode escape in JSON string");
                        }
                        builder.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid escape '\\" + escape + "' in JSON string");
                }
            } else {
                builder.append(c);
            }
        }
    }

    private Object parseNumber() {
        int start = index;
        if (peek() == '-') {
            index++;
        }
        boolean floating = false;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c >= '0' && c <= '9') {
                index++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                floating = c == '.' || c == 'e' || c == 'E' || floating;
                index++;
            } else {
                break;
            }
        }
        String token = text.substring(start, index);
        if (token.isEmpty() || token.equals("-")) {
            throw new IllegalArgumentException("Invalid JSON number at offset " + start);
        }
        if (floating) {
            return Double.parseDouble(token);
        }
        return Long.parseLong(token);
    }

    private Boolean parseBoolean() {
        if (text.startsWith("true", index)) {
            index += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", index)) {
            index += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Invalid JSON literal at offset " + index);
    }

    private Object parseNull() {
        if (text.startsWith("null", index)) {
            index += 4;
            return null;
        }
        throw new IllegalArgumentException("Invalid JSON literal at offset " + index);
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }

    private char peek() {
        if (index >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        }
        return text.charAt(index);
    }

    private char next() {
        if (index >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        }
        return text.charAt(index++);
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "' but found '" + c + "' at offset " + (index - 1));
        }
    }
}
