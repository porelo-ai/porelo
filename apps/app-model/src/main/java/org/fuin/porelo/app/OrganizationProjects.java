package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The organization's projects as the last start of each placed them on this machine, and what a
 * host says of them.
 *
 * <p><strong>A start writes here and nothing else does.</strong> A start fetches the project's
 * repository, judges its file with the project's signing keys, and only then records the keys it
 * judged with. Between starts nothing refreshes this, so off a host a key revoked in the
 * organization stays a believed certifier until the project's next start.
 *
 * <p><strong>On a host, the host's file answers at once.</strong> Root writes it at each follow of
 * the organization; a person who cannot read it has no host of that project. Root also writes one
 * file per person naming their projects, since the projects' directory cannot be listed.
 */
public final class OrganizationProjects {

    /** Where root writes a project's file on a host; a fixed path, as no setting names it. */
    public static final Path HOST_FILES = Path.of("/etc/porelo-host/organization/projects");

    /**
     * A key of a project's member.
     *
     * @param login Whose it is.
     * @param role Their role in the project.
     * @param key The key, {@code ssh-ed25519 AAAA...}.
     * @param validFrom From when it holds.
     * @param validUntil Until when it holds.
     * @param revokedSince Since when it no longer holds, or {@code null}.
     */
    public record Key(
            String login,
            String role,
            String key,
            LocalDate validFrom,
            LocalDate validUntil,
            @Nullable String revokedSince) {

        /**
         * Tells whether this key holds on a day.
         *
         * @param day The day.
         * @return {@code true} when it is valid then and not revoked.
         */
        public boolean holds(final LocalDate day) {
            return revokedSince == null && !day.isBefore(validFrom) && day.isBefore(validUntil);
        }
    }

    /**
     * A project of the organization.
     *
     * @param project Its name.
     * @param revision The project repository's revision placed; the organization's in a host file.
     * @param address The project repository.
     * @param fetchFrom The host's forge for it, or {@code null}.
     * @param keys Its members' keys.
     * @param homeserver The host's homeserver, or {@code null}.
     * @param room The room on it, or {@code null}.
     * @param host The host carrying it, named in a host's file, or {@code null}.
     */
    public record Placed(
            String project,
            String revision,
            String address,
            @Nullable String fetchFrom,
            List<Key> keys,
            @Nullable String homeserver,
            @Nullable String room,
            @Nullable String host) {

        /**
         * Returns the keys that hold on a day.
         *
         * @param day The day.
         * @return Those of them.
         */
        public List<Key> holding(final LocalDate day) {
            return keys.stream().filter(key -> key.holds(day)).toList();
        }
    }

    private final Path directory;

    private final Path hostFiles;

    /**
     * Constructor.
     *
     * @param directory Where this account's records are.
     * @param hostFiles Where a host's files are.
     */
    public OrganizationProjects(final Path directory, final Path hostFiles) {
        this.directory = directory;
        this.hostFiles = hostFiles;
    }

    /**
     * Returns them for this machine.
     *
     * @param context The machine.
     * @return Them.
     */
    public static OrganizationProjects of(final PoreloContext context) {
        return new OrganizationProjects(
                context.paths().projects().organizationProjects(), HOST_FILES);
    }

    /**
     * Returns what a host says of a project.
     *
     * @param name The project's name.
     * @return It, or {@code null} when this machine is no host of it for this person.
     * @throws IOException When a file that is there does not read.
     */
    public @Nullable Placed host(final String name) throws IOException {
        final Path file = hostFiles.resolve(name + ".json");
        // Root takes a locked member's read off: that is absence, not a failure.
        return Files.isReadable(file) ? read(file) : null;
    }

    /**
     * Returns what a host says of the projects this account knows the names of, and of those the
     * host names for this person. A host does not list its projects, so that a person learns no
     * name of a project they are not in: each file is opened by its name.
     *
     * @param account This person's account on the machine.
     * @return Those whose file this person may read, by name.
     * @throws IOException When a file that is there does not read.
     */
    public List<Placed> hosted(final String account) throws IOException {
        final java.util.Set<String> names = new java.util.TreeSet<>(names());
        names.addAll(named(account));
        final List<Placed> hosted = new ArrayList<>();
        for (final String name : names) {
            final Placed placed = host(name);
            if (placed != null) {
                hosted.add(placed);
            }
        }
        return List.copyOf(hosted);
    }

    /**
     * Returns the projects the host names for a person, from the file only that person may read.
     *
     * @param account The person's account, which on a host is their login.
     * @return The names; none where no such file can be read.
     * @throws IOException When the file is there and does not read, or names what no project can be
     *     called: each name goes on to be a path.
     */
    private List<String> named(final String account) throws IOException {
        final Path file = hostFiles.resolveSibling("persons").resolve(account + ".json");
        if (!Files.isReadable(file)) {
            return List.of();
        }
        final Object document;
        try {
            document = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final IllegalArgumentException ex) {
            throw new IOException(file + " does not read", ex);
        }
        if (!(document instanceof Map<?, ?> map)
                || !(map.get("projects") instanceof List<?> rows)) {
            throw new IOException(file + " does not read");
        }
        final List<String> names = new ArrayList<>();
        for (final Object row : rows) {
            if (!(row instanceof String name) || !name.matches(Project.NAME_PATTERN)) {
                throw new IOException(file + " names something no project can be called");
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    /**
     * Returns a project as the last start placed it, with what a host says of it now.
     *
     * @param name The project's name.
     * @return It, or {@code null} when no start placed it.
     * @throws IOException When its record does not read.
     */
    public @Nullable Placed find(final String name) throws IOException {
        final Path file = directory.resolve(name + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        final Placed placed = read(file);
        final Placed host = host(name);
        return host == null
                ? placed
                : new Placed(
                        placed.project(),
                        placed.revision(),
                        placed.address(),
                        host.fetchFrom(),
                        host.keys(),
                        host.homeserver(),
                        host.room(),
                        host.host());
    }

    /**
     * Tells whether a project is the organization's here, placed by a start or carried by the host
     * before one: it goes with the organization, and nobody removes it on this machine.
     *
     * @param name The project's name.
     * @return Whether it is.
     * @throws IOException When a file that is there does not read.
     */
    public boolean organizations(final String name) throws IOException {
        return find(name) != null || host(name) != null;
    }

    /**
     * Returns the names of the projects placed here.
     *
     * @return Their names, sorted.
     * @throws IOException When the directory cannot be listed.
     */
    public List<String> names() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Records a project as placed.
     *
     * @param placed What the start judged and placed.
     * @throws IOException When it cannot be written.
     */
    public void write(final Placed placed) throws IOException {
        Directories.create(directory);
        final Path file = directory.resolve(placed.project() + ".json");
        final Path staged = Files.createTempFile(directory, placed.project(), ".staged");
        try {
            Files.writeString(staged, Json.write(document(placed)), StandardCharsets.UTF_8);
            try {
                Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException ex) {
                Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /**
     * Forgets a project placed here. Its clone is the caller's.
     *
     * @param name The project's name.
     * @throws IOException When its record cannot be removed.
     */
    public void forget(final String name) throws IOException {
        Files.deleteIfExists(directory.resolve(name + ".json"));
    }

    private static Map<String, @Nullable Object> document(final Placed placed) {
        final List<Map<String, @Nullable Object>> keys = new ArrayList<>();
        for (final Key key : placed.keys()) {
            final Map<String, @Nullable Object> row = new LinkedHashMap<>();
            row.put("login", key.login());
            row.put("role", key.role());
            row.put("key", key.key());
            row.put("valid_from", key.validFrom().toString());
            row.put("valid_until", key.validUntil().toString());
            row.put("revoked_since", key.revokedSince());
            keys.add(row);
        }
        final Map<String, @Nullable Object> document = new LinkedHashMap<>();
        document.put("project", placed.project());
        document.put("revision", placed.revision());
        document.put("address", placed.address());
        document.put("fetch_from", placed.fetchFrom());
        document.put("signing_keys", keys);
        document.put("homeserver", placed.homeserver());
        document.put("room", placed.room());
        return document;
    }

    private static Placed read(final Path file) throws IOException {
        try {
            if (!(Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                            instanceof Map<?, ?> document)
                    || !(document.get("signing_keys") instanceof List<?> rows)) {
                throw new IOException(file + " does not read");
            }
            final List<Key> keys = new ArrayList<>();
            for (final Object each : rows) {
                if (!(each instanceof Map<?, ?> row)) {
                    throw new IOException(file + " does not read");
                }
                keys.add(
                        new Key(
                                text(row, "login", file),
                                text(row, "role", file),
                                text(row, "key", file),
                                LocalDate.parse(text(row, "valid_from", file)),
                                LocalDate.parse(text(row, "valid_until", file)),
                                optional(row, "revoked_since")));
            }
            return new Placed(
                    text(document, "project", file),
                    text(document, "revision", file),
                    text(document, "address", file),
                    optional(document, "fetch_from"),
                    List.copyOf(keys),
                    optional(document, "homeserver"),
                    optional(document, "room"),
                    optional(document, "host"));
        } catch (final DateTimeParseException | IllegalArgumentException ex) {
            // What the parser says quotes the file.
            throw new IOException(file + " does not read", ex);
        }
    }

    private static String text(final Map<?, ?> map, final String name, final Path file)
            throws IOException {
        if (map.get(name) instanceof String value) {
            return value;
        }
        throw new IOException(file + " names no " + name);
    }

    private static @Nullable String optional(final Map<?, ?> map, final String name) {
        return map.get(name) instanceof String value ? value : null;
    }
}
