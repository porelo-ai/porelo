package org.fuin.porelo.core.project.file;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.fuin.porelo.core.project.ProjectException;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.reader.ReaderException;

/**
 * One file's reading: its node tree, the faults of its shape, each with its line, and the line of
 * every key, so a fault the check finds later is named at its line too.
 *
 * <p>Read from SnakeYAML's node tree and bound by hand, type by type: nothing is constructed from a
 * tag, so a file can name no class, and nothing needs reflection in a native image.
 */
final class Binding {

    private final String origin;
    private final Sections sections;
    private final List<String> faults = new ArrayList<>();
    private final Map<String, Integer> lines = new HashMap<>();

    /** Faults kept: past these a file is wrong enough, and the rest only grows the message. */
    static final int MOST = 50;

    private int more;

    Binding(final String origin, final Sections sections) {
        this.origin = origin;
        this.sections = sections;
    }

    /**
     * Composes a file's node tree, refusing what is not YAML; a refusal names the line, never an
     * excerpt, since the file may not have been judged yet.
     *
     * @param reader The file's content.
     * @param origin The file, for messages.
     * @return The root, or {@code null} for an empty file.
     */
    static @Nullable Node compose(final Reader reader, final String origin) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final String text = text(reader, origin, options.getCodePointLimit() * 2);
        try {
            return new Yaml(new SafeConstructor(options)).compose(new java.io.StringReader(text));
        } catch (final MarkedYAMLException ex) {
            final Mark mark = ex.getProblemMark();
            throw new ProjectException(
                    "Cannot parse "
                            + origin
                            + (mark == null
                                    ? ""
                                    : " at line "
                                            + (mark.getLine() + 1)
                                            + ", column "
                                            + (mark.getColumn() + 1)),
                    ex);
        } catch (final ReaderException ex) {
            throw new ProjectException(
                    "Cannot parse " + origin + " at " + position(text, ex.getPosition()), ex);
        } catch (final RuntimeException ex) {
            throw new ProjectException("Cannot parse " + origin, ex);
        }
    }

    private static String position(final String text, final int position) {
        int line = 1;
        int column = 1;
        int offset = 0;
        for (int index = 0; index < position && offset < text.length(); index++) {
            final int point = text.codePointAt(offset);
            offset += Character.charCount(point);
            if (point == '\n'
                    || point == 0x85
                    || point == 0x2028
                    || point == 0x2029
                    || point == '\r' && (offset == text.length() || text.charAt(offset) != '\n')) {
                line++;
                column = 1;
            } else if (point != '\r') {
                column++;
            }
        }
        return "line " + line + ", column " + column;
    }

    private static String text(final Reader reader, final String origin, final int limit) {
        final StringBuilder content = new StringBuilder();
        final char[] buffer = new char[4096];
        try {
            int count;
            while ((count =
                            reader.read(
                                    buffer,
                                    0,
                                    Math.min(buffer.length, limit + 1 - content.length())))
                    != -1) {
                content.append(buffer, 0, count);
                if (content.length() > limit) {
                    throw new ProjectException("Cannot parse " + origin + ": YAML is too large");
                }
            }
        } catch (final IOException ex) {
            throw new ProjectException("Cannot read " + origin, ex);
        }
        return content.toString();
    }

    /**
     * Records a fault at a node's line.
     *
     * @param node Where it is.
     * @param path The key's full path.
     * @param message What is wrong.
     */
    void fault(final Node node, final String path, final String message) {
        add(render(line(node), path, message));
    }

    /** Records a fault of a value's shape at its key's line, where a person looks for it. */
    private void faultAtKey(final Node node, final String path, final String message) {
        add(render(lines.getOrDefault(path, line(node)), path, message));
    }

    /**
     * Records a fault the check found, at the line of its key or of the nearest key above it.
     *
     * @param fault The fault.
     */
    void fault(final Fault fault) {
        add(render(lineOf(fault.path()), fault.path(), fault.message()));
    }

    private void add(final String fault) {
        if (faults.size() < MOST) {
            faults.add(fault);
        } else {
            more++;
        }
    }

    private String render(final int line, final String path, final String message) {
        return origin
                + ":"
                + line
                + ": "
                + (path.isEmpty() || message.contains("'" + path + "'")
                        ? message
                        : "'" + path + "': " + message);
    }

    private int lineOf(final String path) {
        String each = path;
        while (!each.isEmpty()) {
            final Integer line = lines.get(each);
            if (line != null) {
                return line;
            }
            each = parent(each);
        }
        return 1;
    }

    /**
     * Returns every fault recorded, each naming the file, its line and its key.
     *
     * @return The faults, in the order found.
     */
    List<String> faults() {
        if (more == 0) {
            return List.copyOf(faults);
        }
        final List<String> all = new ArrayList<>(faults);
        all.add(origin + ": and " + more + " more");
        return List.copyOf(all);
    }

    /**
     * Returns the mapping of one of the file's fixed sections, refusing every key it does not hold.
     *
     * @param node The section's node, or {@code null} when the file leaves it out.
     * @param path Its full path; {@code ""} for the top level.
     * @param section Which section it is, as {@link Sections} names it.
     * @return Key to value, or {@code null} when absent or not a mapping.
     */
    @Nullable Map<String, Node> section(
            final @Nullable Node node, final String path, final String section) {
        final Map<String, Node> entries = mapping(node, path);
        if (entries == null) {
            return null;
        }
        final List<String> known = sections.keys(section);
        entries.entrySet()
                .removeIf(
                        entry -> {
                            if (known.contains(entry.getKey())) {
                                return false;
                            }
                            final String where = join(path, entry.getKey());
                            add(
                                    render(
                                            lines.getOrDefault(where, 1),
                                            where,
                                            sections.refusal(section, entry.getKey(), where)));
                            return true;
                        });
        return entries;
    }

    /**
     * Returns a mapping whose keys are names the file chooses: repositories, parameters, peers.
     *
     * @param node Its node, or {@code null} when absent.
     * @param path Its full path.
     * @return Name to value, in the order written; empty when absent or not a mapping.
     */
    Map<String, Node> named(final @Nullable Node node, final String path) {
        final Map<String, Node> entries = mapping(node, path);
        return entries == null ? Map.of() : entries;
    }

    /**
     * Returns the mapping of a named entry, where an entry with nothing under it is an empty one.
     *
     * @param node The entry's value.
     * @param path Its full path.
     * @param section Which section it is.
     * @return Key to value; empty when the entry is empty, {@code null} when it is not a mapping.
     */
    @Nullable Map<String, Node> entry(final Node node, final String path, final String section) {
        return isNull(node) ? new LinkedHashMap<>() : section(node, path, section);
    }

    private @Nullable Map<String, Node> mapping(final @Nullable Node node, final String path) {
        if (node == null || isNull(node)) {
            return null;
        }
        if (!(node instanceof MappingNode mapping)) {
            faultAtKey(node, path, shape(path, "a mapping", node));
            return null;
        }
        final Map<String, Node> entries = new LinkedHashMap<>();
        for (final NodeTuple tuple : mapping.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key) || isNull(key)) {
                fault(tuple.getKeyNode(), path, "'" + path + "' has a key that is not a name");
                continue;
            }
            final String where = join(path, key.getValue());
            if (entries.containsKey(key.getValue())) {
                fault(key, where, "'" + where + "' is written twice");
                continue;
            }
            lines.put(where, line(key));
            entries.put(key.getValue(), tuple.getValueNode());
        }
        return entries;
    }

    /**
     * Returns a single value.
     *
     * @param node Its node, or {@code null} when absent.
     * @param path Its full path.
     * @return The value as written, or {@code null} when absent or not a single value.
     */
    @Nullable String text(final @Nullable Node node, final String path) {
        final ScalarNode scalar = scalar(node, path);
        return scalar == null ? null : scalar.getValue();
    }

    /**
     * Returns a value that must be written as text, so that {@code 123} or {@code yes} is refused
     * rather than read as the word it looks like.
     *
     * @param node Its node, or {@code null} when absent.
     * @param path Its full path.
     * @return The value, or {@code null} when absent or not text.
     */
    @Nullable String word(final @Nullable Node node, final String path) {
        final ScalarNode scalar = scalar(node, path);
        if (scalar == null) {
            return null;
        }
        if (!Tag.STR.equals(scalar.getTag())) {
            fault(
                    scalar,
                    path,
                    "'" + path + "' is one word, a string, not '" + shown(scalar) + "'");
            return null;
        }
        return scalar.getValue();
    }

    /**
     * Returns a whole number.
     *
     * @param node Its node, or {@code null} when absent.
     * @param path Its full path.
     * @return The number, or {@code null} when absent or not a whole number.
     */
    @Nullable Integer number(final @Nullable Node node, final String path) {
        final ScalarNode scalar = scalar(node, path);
        if (scalar == null) {
            return null;
        }
        if (Tag.INT.equals(scalar.getTag())) {
            // As YAML reads it: 010 is eight and 0x10 sixteen, as they were before.
            final Object value =
                    new Yaml(new SafeConstructor(new LoaderOptions())).load(scalar.getValue());
            if (value instanceof Integer number) {
                return number;
            }
            if (value instanceof Number) {
                fault(scalar, path, "'" + path + "' is too large: '" + shown(scalar) + "'");
                return null;
            }
        }
        fault(scalar, path, "'" + path + "' must be a whole number, not '" + shown(scalar) + "'");
        return null;
    }

    /**
     * Returns a yes or no.
     *
     * @param node Its node, or {@code null} when absent.
     * @param path Its full path.
     * @return The value, or {@code null} when absent or not {@code true} or {@code false}.
     */
    @Nullable Boolean bool(final @Nullable Node node, final String path) {
        final ScalarNode scalar = scalar(node, path);
        if (scalar == null) {
            return null;
        }
        if (!Tag.BOOL.equals(scalar.getTag())) {
            fault(scalar, path, "'" + path + "' is true or false, not '" + shown(scalar) + "'");
            return null;
        }
        return List.of("true", "yes", "on").contains(scalar.getValue().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns a list of single values.
     *
     * <p>A single value where a list belongs is refused, not wrapped: {@code sets: maven} is a
     * plausible slip for {@code sets: [maven]}, and the file's meaning must not depend on it.
     *
     * @param node Its node, or {@code null} when absent.
     * @param path Its full path.
     * @return The values, or {@code null} when absent or not such a list.
     */
    @Nullable List<String> list(final @Nullable Node node, final String path) {
        return list(node, path, false);
    }

    /**
     * Returns a list of names, each written as text: {@code [on]} is a yes, not a name.
     *
     * @param node Its node, or {@code null} when absent.
     * @param path Its full path.
     * @return The names, or {@code null} when absent or not such a list.
     */
    @Nullable List<String> names(final @Nullable Node node, final String path) {
        return list(node, path, true);
    }

    private @Nullable List<String> list(
            final @Nullable Node node, final String path, final boolean names) {
        if (node == null || isNull(node)) {
            return null;
        }
        if (!(node instanceof SequenceNode sequence)) {
            faultAtKey(node, path, shape(path, "a list", node));
            return null;
        }
        final List<String> values = new ArrayList<>();
        for (final Node each : sequence.getValue()) {
            if (each instanceof ScalarNode scalar && !isNull(scalar)) {
                if (names && !Tag.STR.equals(scalar.getTag())) {
                    fault(
                            scalar,
                            path,
                            "'"
                                    + path
                                    + "' has an entry that is not a name: '"
                                    + shown(scalar)
                                    + "'");
                }
                values.add(scalar.getValue());
            } else {
                fault(each, path, "'" + path + "' has an entry that is not a single value");
            }
        }
        return values;
    }

    private @Nullable ScalarNode scalar(final @Nullable Node node, final String path) {
        if (node == null || isNull(node)) {
            return null;
        }
        if (!(node instanceof ScalarNode scalar)) {
            faultAtKey(node, path, shape(path, "a single value", node));
            return null;
        }
        return scalar;
    }

    private static String shape(final String path, final String wanted, final Node found) {
        return "'"
                + (path.isEmpty() ? "the file" : path)
                + "' must be "
                + wanted
                + ", not "
                + (found instanceof MappingNode
                        ? "a mapping"
                        : found instanceof SequenceNode ? "a list" : "a single value");
    }

    private static String shown(final ScalarNode scalar) {
        return ProjectFileCheck.shown(scalar.getValue());
    }

    private static boolean isNull(final Node node) {
        return node instanceof ScalarNode && Tag.NULL.equals(node.getTag());
    }

    private static int line(final Node node) {
        return node.getStartMark() == null ? 1 : node.getStartMark().getLine() + 1;
    }

    /**
     * Returns a key's full path.
     *
     * @param path The path of the mapping it is in; {@code ""} for the top level.
     * @param key The key.
     * @return The path.
     */
    static String join(final String path, final String key) {
        // A name with a dot in it is quoted, so it cannot be read as a path of two keys.
        final String written =
                key.indexOf('.') < 0 && key.indexOf('"') < 0
                        ? key
                        : '"' + key.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        return path.isEmpty() ? written : path + "." + written;
    }

    /**
     * Returns the path of the mapping a path's last key is in.
     *
     * @param path A full path, as {@link #join} builds it.
     * @return Its parent; {@code ""} for a key at the top level.
     */
    static String parent(final String path) {
        int dot = -1;
        boolean quoted = false;
        for (int index = 0; index < path.length(); index++) {
            final char each = path.charAt(index);
            if (quoted && each == '\\') {
                index++;
            } else if (each == '"') {
                quoted = !quoted;
            } else if (each == '.' && !quoted) {
                dot = index;
            }
        }
        return dot < 0 ? "" : path.substring(0, dot);
    }
}
