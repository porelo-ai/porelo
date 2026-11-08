package org.fuin.porelo.core.project.file;

import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.SnippetFiles;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * A project file with the files of a repository added to it: the project's form, less what makes a
 * project the one it is, each value merged as {@link SettingsMerge} does, and the whole judged as
 * the project file is.
 */
public final class RepositorySettings {

    /** Where the project's own values come from, in every source list. */
    public static final String PROJECT = "the project";

    private RepositorySettings() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * A file that adds to the project's.
     *
     * @param source What it is called in a message and a source list, {@code porelo.yml of shop}.
     * @param text Its content.
     */
    public record Layer(String source, String text) {}

    /**
     * A value the added files set otherwise than the project, outside the image: what a machine is
     * asked to allow.
     *
     * @param key Its dotted path, a list's entry as {@code path[entry]}.
     * @param value What it is now, or {@code ""} when removed.
     * @param was What the project said, or {@code ""} when it is new.
     * @param removed Whether it was taken away.
     */
    public record Asked(String key, String value, String was, boolean removed) {}

    /**
     * A value an added file took away, and which file did.
     *
     * @param key Its dotted path, a list's entry as {@code path[entry]}.
     * @param by The file that took it away, as its layer's source names it.
     */
    public record Removal(String key, String by) {}

    /**
     * What the files make together.
     *
     * @param file The project file they make, judged.
     * @param asked What they set otherwise than the project, in the order written, removals last.
     * @param sources Each value's path to the file that set it.
     * @param removed What they took away and is not there again, in order.
     */
    public record Read(
            ProjectFile file,
            List<Asked> asked,
            Map<String, String> sources,
            List<Removal> removed) {}

    /**
     * Reads a project file and adds the given files to it.
     *
     * @param project The project file's content.
     * @param origin The project file, for messages.
     * @param snippets Where a {@code run_file} is read from, or {@code null} to check its name
     *     only.
     * @param layers The files that add to it, earliest first.
     * @return What they make.
     * @throws ProjectException If a file says what only the project may, or the merge refuses.
     * @throws ProjectFileException If what they make is no valid project file.
     */
    public static Read read(
            final Reader project,
            final String origin,
            final @Nullable SnippetFiles snippets,
            final List<Layer> layers) {
        final Node root = ProjectFileReader.root(project, origin);
        // Before the merge: it builds on the project's nodes.
        final Map<String, String> was = values(root);
        final List<SettingsMerge.Layer> all = new ArrayList<>();
        all.add(new SettingsMerge.Layer(PROJECT, root));
        final List<String> faults = new ArrayList<>();
        for (final Layer layer : layers) {
            final Node added = Binding.compose(new StringReader(layer.text()), layer.source());
            faults.addAll(theProjects(layer.source(), added));
            all.add(new SettingsMerge.Layer(layer.source(), added));
        }
        if (!faults.isEmpty()) {
            throw new ProjectException(String.join("; ", faults));
        }
        final SettingsMerge.Merged merged = SettingsMerge.merge(all);
        final Binding binding = new Binding(origin, Sections.PROJECT);
        final ProjectFile file = FileBinder.project(binding, merged.root());
        ProjectFileReader.refuse(binding);
        ProjectFileCheck.check(file, snippets).forEach(binding::fault);
        ProjectFileReader.refuse(binding);
        return new Read(
                file,
                asked(was, values(merged.root())),
                merged.sources(),
                merged.removed().stream()
                        .map(each -> new Removal(each.path(), each.by()))
                        .toList());
    }

    /** A repository adds to a project and never names, lists or becomes one. */
    private static List<String> theProjects(final String source, final @Nullable Node root) {
        final List<String> faults = new ArrayList<>();
        if (!(root instanceof MappingNode mapping)) {
            return faults;
        }
        for (final NodeTuple tuple : mapping.getValue()) {
            final String key = text(tuple.getKeyNode());
            // What a task may do through its MCP endpoint is signed with the project's file alone.
            if ("repositories".equals(key) || "mcp".equals(key)) {
                faults.add(theProjects(source, tuple.getKeyNode(), key));
            } else if ("project".equals(key)
                    && tuple.getValueNode() instanceof MappingNode section) {
                for (final NodeTuple inner : section.getValue()) {
                    if ("name".equals(text(inner.getKeyNode()))) {
                        faults.add(theProjects(source, inner.getKeyNode(), "project.name"));
                    }
                }
            }
        }
        return faults;
    }

    private static String theProjects(final String source, final Node at, final String key) {
        return source
                + ", line "
                + (at.getStartMark().getLine() + 1)
                + ": '"
                + key
                + "' is the project's to say, not a repository's";
    }

    /** The image is chosen and shown apart, so it is never asked here. */
    private static List<Asked> asked(final Map<String, String> was, final Map<String, String> now) {
        final List<Asked> asked = new ArrayList<>();
        now.forEach(
                (key, value) -> {
                    if (!image(key) && !Objects.equals(was.get(key), value)) {
                        asked.add(new Asked(key, value, was.getOrDefault(key, ""), false));
                    }
                });
        was.forEach(
                (key, value) -> {
                    if (!image(key) && !now.containsKey(key)) {
                        asked.add(new Asked(key, "", value, true));
                    }
                });
        return List.copyOf(asked);
    }

    private static boolean image(final String key) {
        return "image".equals(key) || key.startsWith("image.") || key.startsWith("image[");
    }

    /** Every scalar under the root by its path, named as the merge names it. */
    private static Map<String, String> values(final Node root) {
        final Map<String, String> values = new LinkedHashMap<>();
        values(root, "", values);
        return values;
    }

    private static void values(final Node node, final String path, final Map<String, String> into) {
        switch (node) {
            case MappingNode map -> {
                for (final NodeTuple tuple : map.getValue()) {
                    values(
                            tuple.getValueNode(),
                            Binding.join(path, text(tuple.getKeyNode())),
                            into);
                }
            }
            case SequenceNode list -> {
                for (int at = 0; at < list.getValue().size(); at++) {
                    final Node entry = list.getValue().get(at);
                    values(
                            entry,
                            path
                                    + "["
                                    + (entry instanceof ScalarNode scalar ? scalar.getValue() : at)
                                    + "]",
                            into);
                }
            }
            case ScalarNode scalar -> into.put(path, scalar.getValue());
            default -> {
                // Nothing else composes.
            }
        }
    }

    private static String text(final Node node) {
        return node instanceof ScalarNode scalar ? scalar.getValue() : "";
    }
}
