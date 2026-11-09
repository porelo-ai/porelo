package org.fuin.porelo.wire.planning;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Strict decoding prevents malformed records from becoming invented board data. */
final class Fields {
    private Fields() {}

    static String text(Map<?, ?> fields, String key) {
        if (fields.get(key) instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("Expected string " + key);
    }

    static int integer(Map<?, ?> fields, String key) {
        if (fields.get(key) instanceof Number value
                && Double.isFinite(value.doubleValue())
                && value.doubleValue() == value.intValue()) {
            return value.intValue();
        }
        throw new IllegalArgumentException("Expected int " + key);
    }

    static boolean bool(Map<?, ?> fields, String key) {
        if (fields.get(key) instanceof Boolean value) {
            return value;
        }
        throw new IllegalArgumentException("Expected bool " + key);
    }

    static Map<?, ?> object(Map<?, ?> fields, String key) {
        if (fields.get(key) instanceof Map<?, ?> value) {
            return value;
        }
        throw new IllegalArgumentException("Expected object " + key);
    }

    static List<String> strings(Map<?, ?> fields, String key) {
        if (fields.get(key) instanceof List<?> values) {
            return values.stream()
                    .map(
                            value -> {
                                if (value instanceof String text) {
                                    return text;
                                }
                                throw new IllegalArgumentException("Expected strings " + key);
                            })
                    .toList();
        }
        throw new IllegalArgumentException("Expected array " + key);
    }

    static <T> List<T> list(Map<?, ?> fields, String key, Function<Map<?, ?>, T> read) {
        if (fields.get(key) instanceof List<?> values) {
            return values.stream()
                    .map(
                            value -> {
                                if (value instanceof Map<?, ?> object) {
                                    return read.apply(object);
                                }
                                throw new IllegalArgumentException("Expected objects " + key);
                            })
                    .toList();
        }
        throw new IllegalArgumentException("Expected array " + key);
    }
}
