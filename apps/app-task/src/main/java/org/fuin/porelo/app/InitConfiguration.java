package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The init settings read in rootless container configuration order, or no answer when uncertain.
 */
record InitConfiguration(String containersInit, String engineInit, @Nullable List<String> helpers) {

    private record Source(Path path, boolean optional) {}

    static Optional<InitConfiguration> read(
            Path root, Path configHome, Function<String, String> environment) {
        try {
            final List<Source> sources = new ArrayList<>();
            final String selected = environment.apply("CONTAINERS_CONF");
            if (selected != null && !selected.isEmpty()) {
                sources.add(new Source(Path.of(selected), false));
            } else {
                sources.add(new Source(root.resolve("usr/share/containers/containers.conf"), true));
                layer(sources, root.resolve("etc/containers"));
                layer(sources, configHome.resolve("containers"));
            }
            final String override = environment.apply("CONTAINERS_CONF_OVERRIDE");
            if (override != null && !override.isEmpty()) {
                sources.add(new Source(Path.of(override), false));
            }
            InitConfiguration found = new InitConfiguration("", "", null);
            for (final Source source : sources) {
                final String text;
                try {
                    text = Files.readString(source.path());
                } catch (NoSuchFileException ex) {
                    if (source.optional()) {
                        continue;
                    }
                    throw ex;
                }
                found = found.with(new Parser(text).read());
            }
            return Optional.of(found);
        } catch (IOException | IllegalArgumentException | SecurityException ex) {
            // An incomplete view must never turn a usable Podman configuration into a refusal.
            return Optional.empty();
        }
    }

    private static void layer(List<Source> sources, Path directory) throws IOException {
        sources.add(new Source(directory.resolve("containers.conf"), true));
        try (var files = Files.list(directory.resolve("containers.conf.d"))) {
            for (final Path path :
                    files.filter(file -> file.getFileName().toString().endsWith(".conf"))
                            .sorted(
                                    (left, right) ->
                                            Arrays.compareUnsigned(
                                                    left.getFileName()
                                                            .toString()
                                                            .getBytes(StandardCharsets.UTF_8),
                                                    right.getFileName()
                                                            .toString()
                                                            .getBytes(StandardCharsets.UTF_8)))
                            .toList()) {
                sources.add(new Source(path, false));
            }
        } catch (NoSuchFileException ex) {
            // An absent optional directory supplies no settings.
        }
    }

    private InitConfiguration with(Map<String, Value> values) {
        final Value environment = values.get("engine.env");
        if (environment != null
                && !(environment instanceof Array array && array.values().isEmpty())) {
            throw uncertain();
        }
        List<String> directories = helpers;
        final Value helperValue = values.get("engine.helper_binaries_dir");
        if (helperValue != null) {
            if (!(helperValue instanceof Array array)) {
                throw uncertain();
            }
            final List<String> strings = new ArrayList<>();
            for (final Value entry : array.values()) {
                if (!(entry instanceof Text string)) {
                    throw uncertain();
                }
                strings.add(string.value());
            }
            directories = List.copyOf(strings);
        }
        return new InitConfiguration(
                text(values, "containers.init_path", containersInit),
                text(values, "engine.init_path", engineInit),
                directories);
    }

    private static String text(Map<String, Value> values, String key, String fallback) {
        final Value value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Text string) {
            return string.value();
        }
        throw uncertain();
    }

    private sealed interface Value permits Text, Array, Literal {}

    private record Text(String value) implements Value {}

    private record Array(List<Value> values) implements Value {}

    private record Literal(String value) implements Value {}

    private static IllegalArgumentException uncertain() {
        return new IllegalArgumentException(
                "Cannot interpret the container init configuration with certainty");
    }

    /** Recognizes a conservative TOML subset; unsupported syntax leaves the decision to Podman. */
    private static final class Parser {
        private final String input;
        private int offset;

        private Parser(String input) {
            this.input = input;
        }

        private Map<String, Value> read() {
            final Map<String, Value> values = new HashMap<>();
            final var tables = new HashSet<String>();
            String table = "";
            space();
            while (offset < input.length()) {
                if (input.charAt(offset) == '[') {
                    offset++;
                    horizontal();
                    table = key();
                    while (offset < input.length() && input.charAt(offset) == '.') {
                        offset++;
                        table += "." + key();
                    }
                    horizontal();
                    expect(']');
                    if (!tables.add(table)) {
                        throw uncertain();
                    }
                } else {
                    final String name = table + "." + key();
                    horizontal();
                    expect('=');
                    horizontal();
                    if (values.putIfAbsent(name, value()) != null) {
                        throw uncertain();
                    }
                }
                horizontal();
                comment();
                if (offset < input.length()) {
                    expect('\n');
                }
                space();
            }
            return values;
        }

        private String key() {
            final int start = offset;
            while (offset < input.length()
                    && ((input.charAt(offset) >= 'a' && input.charAt(offset) <= 'z')
                            || (input.charAt(offset) >= 'A' && input.charAt(offset) <= 'Z')
                            || (input.charAt(offset) >= '0' && input.charAt(offset) <= '9')
                            || input.charAt(offset) == '_'
                            || input.charAt(offset) == '-')) {
                offset++;
            }
            if (start == offset) {
                throw uncertain();
            }
            return input.substring(start, offset);
        }

        private Value value() {
            if (offset == input.length()) {
                throw uncertain();
            }
            final char first = input.charAt(offset);
            if (first == '\'' || first == '"') {
                return new Text(string());
            }
            if (first == '[') {
                offset++;
                final List<Value> entries = new ArrayList<>();
                space();
                while (offset < input.length() && input.charAt(offset) != ']') {
                    entries.add(value());
                    space();
                    if (offset < input.length() && input.charAt(offset) == ']') {
                        break;
                    }
                    expect(',');
                    space();
                }
                expect(']');
                return new Array(List.copyOf(entries));
            }
            final int start = offset;
            while (offset < input.length() && " \t\r\n#,]".indexOf(input.charAt(offset)) < 0) {
                offset++;
            }
            final String literal = input.substring(start, offset);
            if (!literal.matches(
                    "true|false|[+-]?(0|[1-9](_?[0-9])*)(\\.[0-9](_?[0-9])*)?([eE][+-]?[0-9](_?[0-9])*)?")) {
                throw uncertain();
            }
            return new Literal(literal);
        }

        private String string() {
            final char quote = input.charAt(offset++);
            final StringBuilder text = new StringBuilder();
            while (offset < input.length()) {
                final char current = input.charAt(offset++);
                if (current == quote) {
                    return text.toString();
                }
                if ((current < ' ' && current != '\t') || current == 127) {
                    throw uncertain();
                }
                if (current == '\\' && quote == '"') {
                    if (offset == input.length()) {
                        throw uncertain();
                    }
                    text.append(
                            switch (input.charAt(offset++)) {
                                case 'b' -> "\b";
                                case 't' -> "\t";
                                case 'n' -> "\n";
                                case 'f' -> "\f";
                                case 'r' -> "\r";
                                case '"' -> "\"";
                                case '\\' -> "\\";
                                case 'u' -> unicode(4);
                                case 'U' -> unicode(8);
                                default -> throw uncertain();
                            });
                } else {
                    text.append(current);
                }
            }
            throw uncertain();
        }

        private String unicode(int digits) {
            if (input.length() - offset < digits) {
                throw uncertain();
            }
            final int code = Integer.parseInt(input.substring(offset, offset + digits), 16);
            offset += digits;
            if (!Character.isValidCodePoint(code) || code >= 0xd800 && code <= 0xdfff) {
                throw uncertain();
            }
            return new String(Character.toChars(code));
        }

        private void horizontal() {
            while (offset < input.length() && " \t\r".indexOf(input.charAt(offset)) >= 0) {
                offset++;
            }
        }

        private void comment() {
            if (offset < input.length() && input.charAt(offset) == '#') {
                while (offset < input.length() && input.charAt(offset) != '\n') {
                    offset++;
                }
            }
        }

        private void space() {
            while (offset < input.length() && " \t\r\n#".indexOf(input.charAt(offset)) >= 0) {
                if (input.charAt(offset) == '#') {
                    comment();
                } else {
                    offset++;
                }
            }
        }

        private void expect(char expected) {
            if (offset == input.length() || input.charAt(offset++) != expected) {
                throw uncertain();
            }
        }
    }
}
