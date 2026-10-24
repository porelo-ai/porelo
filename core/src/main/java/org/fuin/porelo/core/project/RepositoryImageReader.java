package org.fuin.porelo.core.project;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads a repository's {@code porelo.yml} or {@code .porelo.yml}: what the repository needs to be
 * built in, and nothing else.
 *
 * <p><strong>Only {@code image}.</strong> Egress, limits, the class and every other rule a task
 * runs under stay the project's: a repository that could name them would widen what its own tasks
 * may do. Each such key is refused by its name, never ignored, so a file that tried is not mistaken
 * for one that was applied.
 */
public final class RepositoryImageReader {

    /** The keys {@code image} may hold here. */
    static final Set<String> IMAGE_KEYS = Set.of("base_image", "snippet", "snippet_file", "env");

    private RepositoryImageReader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads one file.
     *
     * @param text The file's content.
     * @param origin Which file it is, as the start names it.
     * @param files Where its {@code snippet_file} is read from.
     * @return What it says.
     * @throws ProjectException If it is not YAML, names anything but {@code image}, or names a
     *     variable Porelo sets.
     */
    public static ImageSource read(String text, String origin, SnippetFiles files) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(text);
        } catch (RuntimeException ex) {
            throw new ProjectException("Cannot parse " + origin + ": " + ex.getMessage(), ex);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new ProjectException(origin + " is empty or is not a YAML mapping");
        }
        for (final Object key : root.keySet()) {
            if (!"image".equals(key)) {
                throw new ProjectException(
                        origin + ": '" + key + "' cannot be set here; only image may be set here");
            }
        }
        if (!(root.get("image") instanceof Map<?, ?> image)) {
            throw new ProjectException(origin + ": 'image' must be a mapping");
        }
        return image(image, origin, files);
    }

    /**
     * Reads an {@code image} mapping, as a repository's file and a project file's {@code
     * repositories.<name>.image} both hold it.
     *
     * @param image The mapping.
     * @param origin Where it is, as the start names it.
     * @param files Where its {@code snippet_file} is read from.
     * @return What it says.
     * @throws ProjectException If it names a key outside the image or a variable Porelo sets.
     */
    public static ImageSource image(Map<?, ?> image, String origin, SnippetFiles files) {
        for (final Object key : image.keySet()) {
            if (!IMAGE_KEYS.contains(String.valueOf(key))) {
                throw new ProjectException(
                        origin
                                + ": 'image."
                                + key
                                + "' cannot be set here; only "
                                + new java.util.TreeSet<>(IMAGE_KEYS)
                                + " may be set under image");
            }
        }
        final Object inline = image.get("snippet");
        final Object file = image.get("snippet_file");
        if (inline != null && file != null) {
            throw new ProjectException(
                    origin + ": 'image.snippet' and 'image.snippet_file' are mutually exclusive");
        }
        final String snippet =
                inline != null
                        ? String.valueOf(inline)
                        : file == null ? null : files.read(String.valueOf(file), origin);
        final Object base = image.get("base_image");
        if (base != null) {
            Repository.refuseAsOption(String.valueOf(base), "base_image");
        }
        return new ImageSource(
                base == null ? null : String.valueOf(base), snippet, env(image, origin), origin);
    }

    private static Map<String, String> env(Map<?, ?> image, String origin) {
        final Object declared = image.get("env");
        if (declared == null) {
            return Map.of();
        }
        if (!(declared instanceof Map<?, ?> variables)) {
            throw new ProjectException(origin + ": 'image.env' must be a mapping");
        }
        final Map<String, String> env = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> variable : variables.entrySet()) {
            final String name = String.valueOf(variable.getKey());
            final String value = text(variable.getValue());
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                throw new ProjectException(
                        origin + ": 'image.env." + name + "' is no variable name");
            }
            if (TaskVariables.reserved(name)) {
                // Porelo sets these in every task; a repository replacing one would decide where
                // the task's tools and its own token are found.
                final String fixed = TaskVariables.value(name);
                throw new ProjectException(
                        origin
                                + ": 'image.env."
                                + name
                                + "' is set by Porelo"
                                + (fixed == null
                                        ? " for each task; remove the line, and give a value the repository needs"
                                                + " a name of its own"
                                        : " to "
                                                + fixed
                                                + " in every task; the file is the repository's, so whoever commits"
                                                + " there must remove the line - the task has that value without it"));
            }
            if (value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
                // One variable is one ENV line; a line break would write a Containerfile line of
                // its own.
                throw new ProjectException(
                        origin + ": 'image.env." + name + "' holds a control character");
            }
            env.put(name, value);
        }
        return env;
    }

    private static String text(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
