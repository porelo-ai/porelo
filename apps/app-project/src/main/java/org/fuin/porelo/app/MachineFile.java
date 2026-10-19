package org.fuin.porelo.app;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Porelo's part of a person's machine file: the project a host brings up, the tasks to start there
 * and the keys to put into its vault.
 *
 * <p>The file is the person's, beside the provider, offer and zone a host package reads from it for
 * the first play; Porelo reads only {@code project}, {@code tasks} and {@code keys}, and ignores
 * the rest. A key is named by its path on the person's drive and never read here: what leaves this
 * class is a name and a path, never a value.
 *
 * @param project The project's name.
 * @param url Its repository.
 * @param signedBy The key or fingerprint its project file must be signed with.
 * @param tasks The tasks to start, in the file's order.
 * @param keys The keys to put into the vault, each with its absolute path.
 */
public record MachineFile(
        String project, String url, String signedBy, List<Task> tasks, List<Key> keys) {

    /** An agent's name, as {@code task start --agent} and {@code vault login} take it. */
    private static final java.util.regex.Pattern AGENT =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** A package's name, as both Debian and RPM allow it. */
    private static final java.util.regex.Pattern PACKAGE =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9+.-]*");

    /**
     * A task to start.
     *
     * @param name Its name, as {@code porelo task start NAME} takes it.
     * @param repository The project's repository it works on, as {@code -r} takes it.
     * @param agent The agent it runs, as {@code --agent} takes it, or {@code null} for the
     *     project's default.
     * @param agentPackage The package that installs that agent, or {@code null} for the default
     *     name; always {@code null} without an agent.
     */
    public record Task(
            String name,
            String repository,
            @org.jspecify.annotations.Nullable String agent,
            @org.jspecify.annotations.Nullable String agentPackage) {

        /**
         * A task that runs the project's default agent.
         *
         * @param name Its name.
         * @param repository The project's repository it works on.
         */
        public Task(final String name, final String repository) {
            this(name, repository, null, null);
        }
    }

    /**
     * A key to put into the vault.
     *
     * @param name The vault entry's name.
     * @param path Where it is on the person's drive, absolute.
     */
    public record Key(String name, Path path) {}

    /**
     * Reads a machine file.
     *
     * @param file The file.
     * @return Porelo's part of it.
     * @throws IOException If it cannot be read.
     * @throws IllegalArgumentException If Porelo's part is missing or wrong, saying what.
     */
    public static MachineFile read(final Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            final Path base = file.toAbsolutePath().normalize().getParent();
            return read(reader, base == null ? Path.of("/") : base, file.toString());
        }
    }

    /**
     * Reads a machine file's text.
     *
     * @param reader The YAML.
     * @param base The directory a key's path is relative to, and which it must stay in.
     * @param origin Where it came from, for messages.
     * @return Porelo's part of it.
     * @throws IllegalArgumentException If Porelo's part is missing or wrong, saying what.
     */
    public static MachineFile read(final Reader reader, final Path base, final String origin) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "cannot parse " + origin + ": " + ex.getMessage(), ex);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException(origin + " is not a mapping");
        }
        if (!(root.get("project") instanceof Map<?, ?> project)) {
            throw new IllegalArgumentException(origin + " names no 'project'");
        }
        return new MachineFile(
                text(project, "name", origin + ": project"),
                text(project, "url", origin + ": project"),
                text(project, "signed_by", origin + ": project"),
                tasks(root.get("tasks"), origin),
                keys(root.get("keys"), base, origin));
    }

    private static List<Task> tasks(
            final @org.jspecify.annotations.Nullable Object section, final String origin) {
        final List<Task> tasks = new ArrayList<>();
        for (final Map<?, ?> each : entries(section, "tasks", origin)) {
            final String where = origin + ": tasks";
            final String name = text(each, "name", where);
            final String task = where + ": '" + name + "'";
            final String agent = optional(each, "agent", AGENT, task);
            final String agentPackage = optional(each, "package", PACKAGE, task);
            // A package without the agent it installs would be installed and never started.
            if (agentPackage != null && agent == null) {
                throw new IllegalArgumentException(
                        task + " names a 'package' and no 'agent' it installs");
            }
            tasks.add(new Task(name, text(each, "repository", where), agent, agentPackage));
        }
        return List.copyOf(tasks);
    }

    private static List<Key> keys(
            final @org.jspecify.annotations.Nullable Object section,
            final Path base,
            final String origin) {
        final List<Key> keys = new ArrayList<>();
        final Path root = base.toAbsolutePath().normalize();
        for (final Map<?, ?> each : entries(section, "keys", origin)) {
            final String where = origin + ": keys";
            final String name = text(each, "name", where);
            final Path path = root.resolve(text(each, "path", where)).normalize();
            // A key outside the person's drive is not one this file can vouch for, and a path that
            // climbs out with '..' would let the file point the vault at anything on the host.
            if (!path.startsWith(root)) {
                throw new IllegalArgumentException(
                        where + ": the key '" + name + "' is outside " + root + ": " + path);
            }
            keys.add(new Key(name, path));
        }
        return List.copyOf(keys);
    }

    private static List<Map<?, ?>> entries(
            final @org.jspecify.annotations.Nullable Object section,
            final String name,
            final String origin) {
        if (section == null) {
            return List.of();
        }
        if (!(section instanceof List<?> list)) {
            throw new IllegalArgumentException(origin + ": '" + name + "' is not a list");
        }
        final List<Map<?, ?>> entries = new ArrayList<>();
        for (final Object each : list) {
            if (!(each instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException(
                        origin + ": an entry of '" + name + "' is not a mapping");
            }
            entries.add(entry);
        }
        return entries;
    }

    private static @org.jspecify.annotations.Nullable String optional(
            final Map<?, ?> section,
            final String key,
            final java.util.regex.Pattern form,
            final String where) {
        if (!section.containsKey(key)) {
            return null;
        }
        final String said = text(section, key, where);
        // It reaches a command line on the host, so only a name passes, never words or options.
        if (!form.matcher(said).matches()) {
            throw new IllegalArgumentException(
                    where + ": '" + key + "' is no " + key + "'s name: " + said);
        }
        return said;
    }

    private static String text(final Map<?, ?> section, final String key, final String where) {
        final Object value = section.get(key);
        if (!(value instanceof String said) || said.isBlank()) {
            throw new IllegalArgumentException(where + " names no '" + key + "'");
        }
        return said.strip();
    }
}
