package org.fuin.porelo.organization.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationStore;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.RegistryText;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * An organization kept in a Git repository, opened by a checkout: what a read trusted is kept under
 * Porelo's state directory for the checkout's origin, as a trust record beside where it was read.
 */
public final class GitOrganizationStore implements OrganizationStore {

    private final CommandRunner runner;

    private final Path checkout;

    private final String address;

    private final RegistryOrigin origin;

    private final Path record;

    private final @Nullable String trusted;

    private GitOrganizationStore(
            final CommandRunner runner,
            final Path checkout,
            final String address,
            final RegistryOrigin origin,
            final Path record,
            final @Nullable String trusted) {
        this.runner = runner;
        this.checkout = checkout;
        this.address = address;
        this.origin = origin;
        this.record = record;
        this.trusted = trusted;
    }

    /**
     * Opens a checkout, refusing one whose origin cannot be placed or whose trust record cannot be
     * taken, before any identity source is asked.
     *
     * @param runner How git runs.
     * @param state Porelo's directory in {@code $XDG_STATE_HOME}.
     * @param checkout The checkout.
     * @return The store.
     * @throws IOException When the checkout is not a Git repository that can be read.
     * @throws IllegalArgumentException When the origin is none the reader can place.
     * @throws CardException When the trust record cannot be taken.
     */
    public static GitOrganizationStore open(
            final CommandRunner runner, final Path state, final Path checkout) throws IOException {
        final String url = origin(runner, checkout);
        final RegistryOrigin origin = RegistryOrigin.of(url);
        final Path record = TrustRecords.file(state, url);
        return new GitOrganizationStore(runner, checkout, url, origin, record, trusted(record));
    }

    /**
     * Returns where the origin was placed: the forge an identity source is opened for.
     *
     * @return The origin.
     */
    public RegistryOrigin origin() {
        return origin;
    }

    @Override
    public String address() {
        return address;
    }

    @Override
    public Organization read(final IdentitySource identities) throws IOException {
        try {
            return organization(walk(identities));
        } catch (CardException | CommandException ex) {
            throw new OrganizationException(ex.getMessage(), ex);
        }
    }

    /**
     * Reads the history as {@link #read} does, keeping the state it ends with, and answers that
     * state as it is kept, for what writes to this repository.
     *
     * @param identities Who says whose keys are whose.
     * @return The state.
     * @throws IOException When it cannot be read or what was read cannot be kept.
     * @throws CardException When what was kept or what was read is refused.
     */
    public RegistryState walk(final IdentitySource identities) throws IOException {
        final RegistryState state =
                new RegistryHistory(runner)
                        .read(
                                checkout,
                                trusted,
                                record.toString(),
                                identities,
                                origin.organisation());
        keep(state);
        if (state.commit() != null) {
            write(
                    where(record),
                    Json.write(
                            Map.of(
                                    "origin",
                                    address,
                                    "checkout",
                                    checkout.toAbsolutePath().normalize().toString())));
        }
        return state;
    }

    /**
     * An organization this machine read.
     *
     * @param address Its address, exactly as the checkout named it.
     * @param checkout The checkout read last for that address.
     * @param record The trust record that read left.
     * @param organization What it said.
     */
    public record Known(String address, Path checkout, Path record, Organization organization) {}

    /**
     * The organizations this machine read, every one or none at all, as {@link TrustRecords#read}
     * takes them.
     *
     * @param state Porelo's directory in {@code $XDG_STATE_HOME}.
     * @return Each, in the order of their records' names.
     * @throws IOException When a directory on the way or a record cannot be taken.
     */
    public static List<Known> known(final Path state) throws IOException {
        final List<Known> known = new ArrayList<>();
        for (final TrustRecords.Read read : TrustRecords.read(state)) {
            known.add(
                    new Known(
                            read.origin(),
                            read.checkout(),
                            read.file(),
                            organization(read.state())));
        }
        return List.copyOf(known);
    }

    /**
     * Where the trust record of an address is kept.
     *
     * @param state Porelo's directory in {@code $XDG_STATE_HOME}.
     * @param address The address.
     * @return Its path, whether or not it exists.
     */
    public static Path record(final Path state, final String address) {
        return TrustRecords.file(state, address);
    }

    /**
     * Returns what a state says, as plain data.
     *
     * @param state The state.
     * @return What the organization says.
     */
    public static Organization organization(final RegistryState state) {
        return new Organization(
                state.commit(),
                state.revision(),
                state.owners().stream().map(GitOrganizationStore::owner).toList(),
                hostAdmins(state),
                state.people().stream()
                        .map(
                                person ->
                                        new Organization.Person(
                                                person.login(),
                                                person.name(),
                                                person.email(),
                                                person.key(),
                                                person.fingerprint(),
                                                person.state(),
                                                person.why()))
                        .toList(),
                state.hosts().stream().map(GitOrganizationStore::host).toList(),
                state.revoked(),
                state.warnings(),
                state.refusals().stream()
                        .map(
                                refusal ->
                                        new Organization.Refusal(
                                                refusal.commit(), refusal.path(), refusal.why()))
                        .toList(),
                state.soleWithoutBackup().stream()
                        .map(sole -> new Organization.Sole(sole.role(), sole.host(), sole.login()))
                        .toList());
    }

    private static Map<String, List<Organization.Owner>> hostAdmins(final RegistryState state) {
        final Map<String, List<Organization.Owner>> admins = new java.util.TreeMap<>();
        state.hostAdmins()
                .forEach(
                        (host, list) ->
                                admins.put(
                                        host,
                                        list.stream().map(GitOrganizationStore::owner).toList()));
        return admins;
    }

    private static Organization.Owner owner(final RegistryState.Owner owner) {
        return new Organization.Owner(
                owner.login(), owner.key(), owner.fingerprint(), owner.backup());
    }

    private static Organization.Host host(final RegistryState.Host host) {
        final RegistryState.@Nullable Card card = host.card();
        return new Organization.Host(
                host.host(),
                card == null
                        ? null
                        : new Organization.Card(
                                card.address(),
                                card.sshPort(),
                                card.sshHostKey(),
                                card.forgeWebPort(),
                                card.forgeGitPort(),
                                card.matrixPort(),
                                card.signedBy()),
                host.admins().stream()
                        .map(admin -> new Organization.Admin(admin.login(), admin.fingerprint()))
                        .toList(),
                host.projects().stream()
                        .map(
                                project ->
                                        new Organization.Project(
                                                project.name(),
                                                project.admin(),
                                                project.accessRepository()))
                        .toList());
    }

    /**
     * Returns the checkout's {@code origin} URL, the one setting of the checkout this reads.
     *
     * @param runner How git runs.
     * @param checkout The checkout.
     * @return The URL.
     * @throws IOException When the checkout is not a Git repository that can be read.
     * @throws IllegalArgumentException When it has no origin, more than one, or one git rewrites to
     *     another than it names.
     */
    public static String origin(final CommandRunner runner, final Path checkout)
            throws IOException {
        if (!Files.isDirectory(checkout, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "cannot read registry checkout '"
                            + RegistryText.printable(checkout.toString())
                            + "': expected a directory, not a symlink");
        }
        if (!git(runner, checkout, "rev-parse", "--git-dir").successful()) {
            throw new IOException(
                    "cannot read registry checkout '"
                            + RegistryText.printable(checkout.toString())
                            + "': not a Git repository");
        }
        final CommandResult origin =
                git(runner, checkout, "config", "--get-all", "remote.origin.url");
        final List<String> urls =
                origin.successful()
                        ? origin.standardOutput().lines().filter(url -> !url.isEmpty()).toList()
                        : List.of();
        if (urls.isEmpty()) {
            throw new IllegalArgumentException(
                    "the registry checkout '"
                            + RegistryText.printable(checkout.toString())
                            + "' has no origin to name its forge");
        }
        // Git fetches from the first URL, after its rewrites; the forge must be the one it names.
        final CommandResult fetched = git(runner, checkout, "remote", "get-url", "origin");
        if (urls.size() > 1
                || !fetched.successful()
                || !fetched.trimmedOutput().equals(urls.getFirst())) {
            throw new IllegalArgumentException(
                    "the registry checkout '"
                            + RegistryText.printable(checkout.toString())
                            + "' names its origin more than once or git rewrites it; give it"
                            + " exactly one url, as git fetches it");
        }
        return urls.getFirst();
    }

    private static CommandResult git(
            final CommandRunner runner, final Path checkout, final String... arguments) {
        return runner.run(RegistryGit.git(checkout, Map.of(), arguments));
    }

    private static Path where(final Path record) {
        final String name = record.getFileName().toString();
        return record.resolveSibling(
                name.substring(0, name.length() - ".json".length()) + ".where.json");
    }

    /**
     * Reads the trust record's text, or none for a first read. One that is no regular file, or
     * cannot be read, refuses the read; one whose text fails a check is discarded by the read,
     * which walks the registry afresh and says so.
     */
    private static @Nullable String trusted(final Path record) {
        if (!Files.exists(record, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) {
            throw new CardException(
                    "the registry trust record " + record + " is not a regular file");
        }
        try {
            return Files.readString(record, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new CardException("cannot read the registry trust record " + record);
        }
    }

    /**
     * Keeps a read that ended, owner-only and whole: a reader never finds half a record. A read
     * that stopped before its first commit leaves none, so the next one starts afresh; a record it
     * discarded is deleted, never left for the next read.
     */
    private void keep(final RegistryState state) throws IOException {
        if (state.commit() == null) {
            // The record and the origin it was read from go together, so neither is left alone.
            Files.deleteIfExists(record);
            Files.deleteIfExists(where(record));
            return;
        }
        write(record, state.toJson());
    }

    /** Writes a file of the registry directory, owner-only and whole. */
    private static void write(final Path file, final String text) throws IOException {
        final Path directory =
                java.util.Objects.requireNonNull(
                        file.getParent(), "a trust record lies in the registry directory");
        Directories.create(
                java.util.Objects.requireNonNull(
                        directory.getParent(), "the registry directory lies in the state one"));
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(
                    directory,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        final Path staged =
                Files.createTempFile(
                        directory,
                        ".",
                        ".json.staged",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(staged, text, StandardCharsets.UTF_8);
            Files.move(
                    staged,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
