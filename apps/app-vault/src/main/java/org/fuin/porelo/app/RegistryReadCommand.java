package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.core.credential.CredentialRegistry;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.ForgeDirectory;
import org.fuin.porelo.vault.HttpForgeDirectory;
import org.fuin.porelo.vault.RegistryHistory;
import org.fuin.porelo.vault.RegistryState;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Reads a person's registry checkout path by path, as its signers were allowed to write it, and
 * keeps what it trusted for the next read.
 */
@Command(
        name = "read",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Reads a registry checkout as its signers were allowed to write it, asking the"
                        + " forge its origin names.")
public class RegistryReadCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** How a forge is asked: its address and the token ownership is asked with. */
    @FunctionalInterface
    interface Forges {
        ForgeDirectory open(URI forge, @Nullable String token);
    }

    @Parameters(index = "0", paramLabel = "CHECKOUT", description = "The registry checkout.")
    private Path checkout = Path.of("");

    @Option(names = "--json", description = "Answers people, owners, warnings and hosts as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    private CommandRunner runner = new ProcessCommandRunner();

    /** Replaced by a test, which has no forge to ask. */
    Forges forges =
            (forge, token) ->
                    new HttpForgeDirectory(
                            forge,
                            token,
                            http(
                                    java.util.Objects.requireNonNull(
                                            context, "picocli's factory sets the context")));

    /**
     * What one read found.
     *
     * @param origin The origin it asked the forge of.
     * @param state The state it ended with, kept as the trust record.
     */
    record Read(RegistryOrigin origin, RegistryState state) {}

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
        runner = context.runner();
    }

    @Override
    public Integer call() {
        final PoreloContext machine =
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context");
        final RegistryOrigin origin;
        final RegistryState state;
        try {
            final Read read = read(machine, runner, checkout, forges, spec.commandLine().getErr());
            origin = read.origin();
            state = read.state();
        } catch (IllegalArgumentException ex) {
            return refuse(64, ex.getMessage());
        } catch (IOException ex) {
            return refuse(66, ex.getMessage());
        } catch (CardException | CommandException ex) {
            return refuse(65, ex.getMessage());
        }
        final var out = spec.commandLine().getOut();
        if (json) {
            out.println(Json.write(RegistryVerifyCommand.printable(answer(state))));
        } else {
            final String revision = state.revision();
            out.println(
                    "Registry "
                            + (revision == null ? "with no commit accepted" : revision)
                            + " of "
                            + RegistryVerifyCommand.printable(origin.organisation()));
            out.println("People: " + state.people().size());
            out.println("Owners: " + state.owners().size());
            out.println("Hosts: " + state.hosts().size());
            final RegistryState.Refusal refused = state.lastInForce();
            if (refused != null) {
                out.println(
                        "Refused: "
                                + RegistryVerifyCommand.printable(
                                        refused.commit()
                                                + " "
                                                + refused.path()
                                                + ": "
                                                + refused.why()));
            }
            for (String warning : warnings(state)) {
                out.println("warning: " + RegistryVerifyCommand.printable(warning));
            }
        }
        out.flush();
        return 0;
    }

    /**
     * What the read warns of: what the walk warned of, then each registry or host whose one owner
     * or admin has no backup key.
     *
     * @param state The state read.
     * @return The warnings.
     */
    static List<String> warnings(final RegistryState state) {
        final List<String> warnings = new java.util.ArrayList<>(state.warnings());
        for (RegistryState.Sole sole : state.soleWithoutBackup()) {
            warnings.add(
                    "owners.yml names "
                            + sole.login()
                            + (sole.host() == null
                                    ? " as the registry's only owner"
                                    : " as the only admin of " + sole.host())
                            + ", without a backup key; make one with porelo identity backup-key "
                            + (sole.host() == null ? "--registry" : "--host " + sole.host()));
        }
        return List.copyOf(warnings);
    }

    /**
     * Reads a checkout as its origin's forge says, from the trust record kept for that origin, and
     * keeps the state it ends with.
     *
     * @param context The machine.
     * @param runner How git runs.
     * @param checkout The checkout.
     * @param forges How the forge is asked.
     * @param err Where a warning about the forge token goes.
     * @return The origin and the state.
     * @throws IOException When the checkout cannot be read or the record cannot be kept.
     * @throws IllegalArgumentException When the origin is none the reader can place.
     * @throws CardException When the trust record or the history refuses the read.
     */
    static Read read(
            final PoreloContext context,
            final CommandRunner runner,
            final Path checkout,
            final Forges forges,
            final java.io.PrintWriter err)
            throws IOException {
        final String url = origin(runner, checkout);
        final RegistryOrigin origin = RegistryOrigin.of(url);
        final Path record = trustRecord(context, url);
        final RegistryState state =
                new RegistryHistory(runner)
                        .read(
                                checkout,
                                trusted(record),
                                record.toString(),
                                forge(context, origin, forges, err),
                                origin.organisation());
        keep(record, state);
        if (state.commit() != null) {
            write(
                    known(record),
                    Json.write(
                            Map.of(
                                    "origin",
                                    url,
                                    "checkout",
                                    checkout.toAbsolutePath().normalize().toString())));
        }
        // Whichever command read it: a warning, such as a record discarded, is never lost.
        for (String warning : warnings(state)) {
            err.println("porelo: warning: " + RegistryVerifyCommand.printable(warning));
        }
        err.flush();
        return new Read(origin, state);
    }

    /**
     * A registry this machine has read: its origin exactly as the checkout names it, the checkout
     * it was read from last, and the trust record that read left.
     *
     * @param origin The checkout's {@code origin} URL, as {@code git remote get-url} answers it.
     * @param checkout The checkout read last for that origin.
     * @param record The trust record.
     */
    record Known(String origin, Path checkout, Path record) {}

    /**
     * The registries this machine has read, one per origin, the latest read of each.
     *
     * @param context The machine.
     * @return Each, in the order of their records' names; none when nothing was read.
     * @throws IOException When the registry directory cannot be listed.
     */
    static List<Known> known(final PoreloContext context) throws IOException {
        final Path directory = context.paths().xdg().state().resolve("registry");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        final List<Known> known = new java.util.ArrayList<>();
        try (var listed = Files.list(directory)) {
            for (final Path where :
                    listed.filter(path -> path.getFileName().toString().endsWith(".where.json"))
                            .sorted()
                            .toList()) {
                if (!Files.isRegularFile(where, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                final Object read;
                try {
                    read = Json.parse(Files.readString(where, StandardCharsets.UTF_8));
                } catch (RuntimeException ex) {
                    // Half a file or none of this shape is no registry this machine read.
                    continue;
                }
                // A record of another origin's name would answer for an address it is not.
                if (read instanceof Map<?, ?> map
                        && map.get("origin") instanceof String origin
                        && map.get("checkout") instanceof String checkout
                        && trustRecord(context, origin).equals(record(where))) {
                    known.add(new Known(origin, Path.of(checkout), record(where)));
                }
            }
        }
        return List.copyOf(known);
    }

    private static Path known(final Path record) {
        final String name = record.getFileName().toString();
        return record.resolveSibling(
                name.substring(0, name.length() - ".json".length()) + ".where.json");
    }

    private static Path record(final Path where) {
        final String name = where.getFileName().toString();
        return where.resolveSibling(
                name.substring(0, name.length() - ".where.json".length()) + ".json");
    }

    /**
     * Returns the checkout's {@code origin} URL, the one setting of the checkout this reads.
     *
     * @throws IOException When the checkout is not a Git repository that can be read.
     * @throws IllegalArgumentException When it has no origin, more than one, or one git rewrites to
     *     another than it names.
     */
    static String origin(final CommandRunner runner, final Path checkout) throws IOException {
        if (!Files.isDirectory(checkout, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "cannot read registry checkout '"
                            + RegistryVerifyCommand.printable(checkout.toString())
                            + "': expected a directory, not a symlink");
        }
        if (!git(runner, checkout, "rev-parse", "--git-dir").successful()) {
            throw new IOException(
                    "cannot read registry checkout '"
                            + RegistryVerifyCommand.printable(checkout.toString())
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
                            + RegistryVerifyCommand.printable(checkout.toString())
                            + "' has no origin to name its forge");
        }
        // Git fetches from the first URL, after its rewrites; the forge must be the one it names.
        final CommandResult fetched = git(runner, checkout, "remote", "get-url", "origin");
        if (urls.size() > 1
                || !fetched.successful()
                || !fetched.trimmedOutput().equals(urls.getFirst())) {
            throw new IllegalArgumentException(
                    "the registry checkout '"
                            + RegistryVerifyCommand.printable(checkout.toString())
                            + "' names its origin more than once or git rewrites it; give it"
                            + " exactly one url, as git fetches it");
        }
        return urls.getFirst();
    }

    private static CommandResult git(
            final CommandRunner runner, final Path checkout, final String... arguments) {
        return runner.run(RegistryGit.git(checkout, Map.of(), arguments));
    }

    /** Where the state the last read of this origin ended with is kept. */
    static Path trustRecord(final PoreloContext context, final String url) {
        try {
            final byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(url.getBytes(StandardCharsets.UTF_8));
            return context.paths()
                    .xdg()
                    .state()
                    .resolve("registry")
                    .resolve(HexFormat.of().formatHex(digest) + ".json");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
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
    private static void keep(final Path record, final RegistryState state) throws IOException {
        if (state.commit() == null) {
            // The record and the origin it was read from go together, so neither is left alone.
            Files.deleteIfExists(record);
            Files.deleteIfExists(known(record));
            return;
        }
        write(record, state.toJson());
    }

    /** Writes a file of the registry directory, owner-only and whole. */
    private static void write(final Path record, final String text) throws IOException {
        final Path directory =
                java.util.Objects.requireNonNull(
                        record.getParent(), "a trust record lies in the registry directory");
        Files.createDirectories(
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
                    record,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static ForgeDirectory forge(
            final PoreloContext context,
            final RegistryOrigin origin,
            final Forges forges,
            final java.io.PrintWriter err) {
        final String token = token(context, origin);
        try {
            return forges.open(origin.forge(), token);
        } catch (IllegalArgumentException ex) {
            // A held token a header cannot carry: the forge is still asked for keys, and a first
            // owners file stays undecided, as without a token.
            err.println(
                    "porelo: warning: the forge token held for "
                            + RegistryVerifyCommand.printable(origin.forge().getHost())
                            + " cannot be sent, so ownership is not asked");
            err.flush();
            return forges.open(origin.forge(), null);
        }
    }

    /**
     * The token this person signed in to the forge with, when the vault opens without asking:
     * declared for the origin, or kept under the forge's own name. Never the plain {@code
     * git.token}, which a machine keeps for another forge, and which a checkout's origin would
     * otherwise send to whatever host it names.
     */
    static @Nullable String token(final PoreloContext context, final RegistryOrigin origin) {
        final Map<String, VaultEntry> held = context.readableCredentials().orElseGet(Map::of);
        if (held.isEmpty()) {
            return null;
        }
        return token(held, context.credentialRegistry(), origin);
    }

    /**
     * The token of {@link #token(PoreloContext, RegistryOrigin)}, from what the vault holds. An
     * origin that names a port is another server than the one the forge's name stands for: only a
     * token declared for that host and port is sent there.
     *
     * @param held What the vault holds.
     * @param registry The credentials declared for destinations.
     * @param origin The checkout's origin.
     * @return The token, or {@code null}.
     */
    static @Nullable String token(
            final Map<String, VaultEntry> held,
            final CredentialRegistry registry,
            final RegistryOrigin origin) {
        final Credential declared = registry.forUrl("git", origin.url());
        final boolean vault =
                declared != null
                        && declared.kind() == Credential.Kind.TOKEN
                        && declared.source() == Credential.Source.VAULT;
        final @Nullable String name;
        if (declared != null && vault) {
            name = origin.port() < 0 || namesPort(declared.match(), origin) ? declared.id() : null;
        } else {
            name =
                    origin.port() < 0
                            ? GitCredentialNames.impliedName(
                                    GitCredentialNames.Kind.TOKEN, origin.forge().toString())
                            : null;
        }
        final VaultEntry entry = name == null ? null : held.get(name);
        return entry == null || entry.value().isBlank() ? null : entry.value();
    }

    /** Whether a declared match names the origin's host and port, not the host alone. */
    private static boolean namesPort(final String match, final RegistryOrigin origin) {
        final int scheme = match.indexOf("://");
        if (scheme < 0) {
            return false;
        }
        final String rest = match.substring(scheme + 3);
        final int slash = rest.indexOf('/');
        final String authority = slash < 0 ? rest : rest.substring(0, slash);
        return authority.equalsIgnoreCase(origin.forge().getHost() + ":" + origin.port());
    }

    /** How the forge is asked: what the account trusts, and no redirect. */
    static java.net.http.HttpClient http(final PoreloContext machine) {
        return org.fuin.porelo.core.net.TrustedCertificates.builder(
                        org.fuin.porelo.core.net.TrustedCertificates.file(machine.paths().xdg()))
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
    }

    /** The answer, in the order the contract names its fields. */
    static Map<String, @Nullable Object> answer(final RegistryState state) {
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", state.revision());
        final RegistryState.Refusal refused = state.lastInForce();
        answer.put(
                "refused_commit",
                refused == null
                        ? null
                        : ordered(
                                "commit", refused.commit(),
                                "path", refused.path(),
                                "why", refused.why()));
        answer.put(
                "people",
                state.people().stream()
                        .map(
                                person ->
                                        ordered(
                                                "login", person.login(),
                                                "name", person.name(),
                                                "email", person.email(),
                                                "key", person.key(),
                                                "fingerprint", person.fingerprint(),
                                                "state", person.state(),
                                                "why", person.why()))
                        .toList());
        answer.put(
                "owners",
                state.owners().stream()
                        .map(
                                owner ->
                                        ordered(
                                                "login", owner.login(),
                                                "fingerprint", owner.fingerprint(),
                                                "backup", owner.backup()))
                        .toList());
        // The host admins owners.yml names, who sign a host's files; not its access file's admins.
        final Map<String, Object> hostAdmins = new LinkedHashMap<>();
        state.hostAdmins()
                .forEach(
                        (host, admins) ->
                                hostAdmins.put(
                                        host,
                                        admins.stream()
                                                .map(
                                                        admin ->
                                                                ordered(
                                                                        "login", admin.login(),
                                                                        "fingerprint",
                                                                                admin.fingerprint(),
                                                                        "backup", admin.backup()))
                                                .toList()));
        answer.put("host_admins", hostAdmins);
        answer.put("warnings", warnings(state));
        answer.put(
                "hosts",
                state.hosts().stream()
                        .map(
                                host -> {
                                    final RegistryState.Card card = host.card();
                                    return ordered(
                                            "host",
                                            host.host(),
                                            "card",
                                            card == null ? null : card.answer(host.host()),
                                            "admins",
                                            host.admins().stream()
                                                    .map(
                                                            admin ->
                                                                    ordered(
                                                                            "login",
                                                                            admin.login(),
                                                                            "fingerprint",
                                                                            admin.fingerprint()))
                                                    .toList(),
                                            "projects",
                                            host.projects().stream()
                                                    .map(
                                                            project ->
                                                                    ordered(
                                                                            "name",
                                                                            project.name(),
                                                                            "admin",
                                                                            project.admin(),
                                                                            "access_repository",
                                                                            project
                                                                                    .accessRepository()))
                                                    .toList());
                                })
                        .toList());
        return answer;
    }

    /** A JSON object whose keys stay in the order given: name, value, name, value. */
    private static Map<String, @Nullable Object> ordered(final @Nullable Object... pairs) {
        final Map<String, @Nullable Object> map = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            map.put(String.valueOf(pairs[at]), pairs[at + 1]);
        }
        return map;
    }

    private int refuse(int code, @Nullable String message) {
        final var err = spec.commandLine().getErr();
        err.println("porelo: " + RegistryVerifyCommand.printable(String.valueOf(message)));
        err.flush();
        return code;
    }
}
