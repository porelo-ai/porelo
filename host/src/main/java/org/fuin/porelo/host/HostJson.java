package org.fuin.porelo.host;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/** Ordered JSON objects and checked access to the host's persisted JSON. */
final class HostJson {
    private HostJson() {}

    static String write(@Nullable Object value) {
        final String json = Json.write(value);
        final StringBuilder ascii = new StringBuilder();
        for (int index = 0; index < json.length(); index++) {
            final char unit = json.charAt(index);
            if (unit == '\\') {
                if (json.startsWith("\\u0008", index) || json.startsWith("\\u000c", index)) {
                    ascii.append(json.charAt(index + 5) == '8' ? "\\b" : "\\f");
                    index += 5;
                } else {
                    // Consume the escape together so a literal backslash-u stays literal.
                    ascii.append(unit).append(json.charAt(++index));
                }
            } else if (unit > 127) {
                ascii.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) unit));
            } else {
                ascii.append(unit);
            }
        }
        return ascii.toString();
    }

    static Map<String, @Nullable Object> fields(@Nullable Object... pairs) {
        final Map<String, @Nullable Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(
                    (String)
                            java.util.Objects.requireNonNull(
                                    pairs[index], "Field names are non-null"),
                    pairs[index + 1]);
        }
        return result;
    }

    static Map<String, @Nullable Object> object(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        final Map<String, @Nullable Object> result = new LinkedHashMap<>();
        map.forEach((key, entry) -> result.put((String) key, entry));
        return result;
    }

    static Map<String, @Nullable Object> optionalObject(@Nullable Object value) {
        return value == null ? new LinkedHashMap<>() : object(value);
    }

    static List<?> array(@Nullable Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IllegalArgumentException("Expected a JSON array");
    }

    static String string(@Nullable Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("Expected a JSON string");
    }

    static @Nullable Object parse(String text) {
        try {
            return Json.parseNumberTypes(text);
        } catch (JsonException ex) {
            throw new IllegalArgumentException("Invalid JSON", ex);
        }
    }
}
