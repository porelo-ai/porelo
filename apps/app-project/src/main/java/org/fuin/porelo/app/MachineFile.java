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
 * Porelo's part of a person's machine file: the project a host brings up and the keys to put into
 * its vault.
 *
 * <p>The file is the person's, beside the provider, offer and zone a host package reads from it for
 * the first play; Porelo reads only {@code project}, {@code keys} and {@code access}, and ignores
 * the rest. A key is named by its path on the person's drive and never read here: what leaves this
 * class is a name and a path, never a value.
 *
 * @param project The project's name.
 * @param url Its repository.
 * @param signedBy The key or fingerprint its project file must be signed with.
 * @param keys The keys to put into the vault, each with its absolute path.
 * @param accessBundle The bundle of the project's access repository, absolute, or {@code null} when
 *     the file names none.
 */
public record MachineFile(
        String project,
        String url,
        String signedBy,
        List<Key> keys,
        @org.jspecify.annotations.Nullable Path accessBundle) {

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
        if (root.containsKey("tasks") || root.containsKey("agents")) {
            throw new IllegalArgumentException(
                    origin
                            + ": the machine installs every agent Porelo's repository offers; "
                            + "tasks are started by the person with 'porelo task start', "
                            + "so the machine file names neither tasks nor agents");
        }
        if (!(root.get("project") instanceof Map<?, ?> project)) {
            throw new IllegalArgumentException(origin + " names no 'project'");
        }
        return new MachineFile(
                text(project, "name", origin + ": project"),
                text(project, "url", origin + ": project"),
                text(project, "signed_by", origin + ": project"),
                keys(root.get("keys"), base, origin),
                access(root.get("access"), base, origin));
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

    /**
     * The access repository's bundle, under the same rule as a key: on the person's drive or not at
     * all, since the inventory checks whatever it names.
     */
    private static @org.jspecify.annotations.Nullable Path access(
            final @org.jspecify.annotations.Nullable Object section,
            final Path base,
            final String origin) {
        if (section == null) {
            return null;
        }
        if (!(section instanceof Map<?, ?> access)) {
            throw new IllegalArgumentException(origin + ": 'access' is not a mapping");
        }
        final Path root = base.toAbsolutePath().normalize();
        final Path path = root.resolve(text(access, "bundle", origin + ": access")).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException(
                    origin + ": access: the bundle is outside " + root + ": " + path);
        }
        return path;
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

    private static String text(final Map<?, ?> section, final String key, final String where) {
        final Object value = section.get(key);
        if (!(value instanceof String said) || said.isBlank()) {
            throw new IllegalArgumentException(where + " names no '" + key + "'");
        }
        return said.strip();
    }
}
