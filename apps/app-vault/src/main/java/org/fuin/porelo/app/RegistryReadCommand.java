package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.core.credential.CredentialRegistry;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.git.GitOrganizationStore;
import org.fuin.porelo.organization.git.HttpForgeDirectory;
import org.fuin.porelo.organization.git.RegistryOrigin;
import org.fuin.porelo.vault.CardException;
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
        IdentitySource open(URI forge, @Nullable String token);
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
     * @param organization What it says.
     */
    record Read(RegistryOrigin origin, Organization organization) {}

    /**
     * A store opened for a read, and who it asks.
     *
     * @param store The store.
     * @param identities The identity source of its origin's forge.
     */
    record Opened(GitOrganizationStore store, IdentitySource identities) {}

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
        final Organization state;
        try {
            final Read read = read(machine, runner, checkout, forges, spec.commandLine().getErr());
            origin = read.origin();
            state = read.organization();
        } catch (IllegalArgumentException ex) {
            return refuse(64, ex.getMessage());
        } catch (IOException ex) {
            return refuse(66, ex.getMessage());
        } catch (OrganizationException | CardException | CommandException ex) {
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
            final Organization.Refusal refused = state.lastRefusal();
            if (refused != null) {
                out.println(
                        "Refused: "
                                + RegistryVerifyCommand.printable(
                                        refused.revision()
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
    static List<String> warnings(final Organization state) {
        final List<String> warnings = new java.util.ArrayList<>(state.warnings());
        for (Organization.Sole sole : state.soleWithoutBackup()) {
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
        final Opened opened = open(context, runner, checkout, forges, err);
        final Organization organization = opened.store().read(opened.identities());
        warn(organization, err);
        return new Read(opened.store().origin(), organization);
    }

    /**
     * Opens a checkout and the identity source of its origin's forge, nothing read yet.
     *
     * @param context The machine.
     * @param runner How git runs.
     * @param checkout The checkout.
     * @param forges How the forge is asked.
     * @param err Where a warning about the forge token goes.
     * @return The store and who it asks.
     * @throws IOException When the checkout cannot be read.
     * @throws IllegalArgumentException When the origin is none the reader can place.
     * @throws CardException When the trust record refuses the read.
     */
    static Opened open(
            final PoreloContext context,
            final CommandRunner runner,
            final Path checkout,
            final Forges forges,
            final java.io.PrintWriter err)
            throws IOException {
        final GitOrganizationStore store =
                GitOrganizationStore.open(runner, context.paths().xdg().state(), checkout);
        return new Opened(store, forge(context, store.origin(), forges, err));
    }

    /**
     * Says what a read warns of, whichever command read it: a warning, such as a record discarded,
     * is never lost.
     *
     * @param organization What was read.
     * @param err Where it goes.
     */
    static void warn(final Organization organization, final java.io.PrintWriter err) {
        for (String warning : warnings(organization)) {
            err.println("porelo: warning: " + RegistryVerifyCommand.printable(warning));
        }
        err.flush();
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
     * Returns the checkout's {@code origin} URL, the one setting of the checkout this reads.
     *
     * @throws IOException When the checkout is not a Git repository that can be read.
     * @throws IllegalArgumentException When it has no origin, more than one, or one git rewrites to
     *     another than it names.
     */
    static String origin(final CommandRunner runner, final Path checkout) throws IOException {
        return GitOrganizationStore.origin(runner, checkout);
    }

    /** Where the state the last read of this origin ended with is kept. */
    static Path trustRecord(final PoreloContext context, final String url) {
        return GitOrganizationStore.record(context.paths().xdg().state(), url);
    }

    private static IdentitySource forge(
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
     * The token this person signed in to the forge with: declared for the origin in a variable or a
     * file (a GitHub App's installation token, which lives an hour, is never put in the vault), or,
     * when the vault opens without asking, declared for the origin there or kept under the forge's
     * own name. Never the plain {@code git.token}, which a machine keeps for another forge, and
     * which a checkout's origin would otherwise send to whatever host it names.
     */
    static @Nullable String token(final PoreloContext context, final RegistryOrigin origin) {
        final String outside = outside(context, origin);
        if (outside != null) {
            return outside;
        }
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

    /** The most a token file may hold, in bytes; anything longer is no token. */
    private static final long TOKEN_FILE_LIMIT = 64 * 1024;

    /**
     * The token declared in a variable or a file for the registry at the forge's https address,
     * where it is sent, whether the checkout was cloned over https or over ssh (no token is
     * declared for an ssh address), with the same rule for a port as a token in the vault; {@code
     * null} when none is declared there or it is unset, unreadable or blank.
     */
    private static @Nullable String outside(
            final PoreloContext context, final RegistryOrigin origin) {
        final Credential declared =
                context.credentialRegistry().forUrl("git", httpsAddress(origin));
        if (declared == null
                || declared.kind() != Credential.Kind.TOKEN
                || (origin.forge().getPort() >= 0 && !namesPort(declared.match(), origin))) {
            return null;
        }
        final @Nullable String value =
                switch (declared.source()) {
                    case ENVIRONMENT -> context.environment().apply(declared.id());
                    case FILE -> file(Path.of(declared.id()));
                    case VAULT, AGENT -> null;
                };
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * The registry's address at the forge's https address, however the checkout was cloned, so a
     * token declared for the forge, the organisation or the repository itself is found.
     */
    private static String httpsAddress(final RegistryOrigin origin) {
        final String url = origin.url().replaceAll("/+$", "");
        final String repository = url.substring(url.lastIndexOf('/') + 1);
        return origin.forge() + "/" + origin.organisation() + "/" + repository;
    }

    private static @Nullable String file(final Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > TOKEN_FILE_LIMIT) {
                return null;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            return null;
        }
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
    static Map<String, @Nullable Object> answer(final Organization state) {
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", state.revision());
        final Organization.Refusal refused = state.lastRefusal();
        answer.put(
                "refused_commit",
                refused == null
                        ? null
                        : ordered(
                                "commit", refused.revision(),
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
                                    final Organization.Card card = host.card();
                                    return ordered(
                                            "host",
                                            host.host(),
                                            "card",
                                            card == null ? null : cardAnswer(host.host(), card),
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

    /**
     * A host's card as an answer names it.
     *
     * @param host The host's name.
     * @param card Its card.
     * @return {@code host}, {@code address}, {@code ssh_port}, {@code ssh_host_key}, {@code
     *     services} and {@code signed_by}.
     */
    static Map<String, Object> cardAnswer(final String host, final Organization.Card card) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("host", host);
        answer.put("address", card.address());
        answer.put("ssh_port", card.sshPort());
        answer.put("ssh_host_key", card.sshHostKey());
        answer.put(
                "services",
                Map.of(
                        "forge",
                        Map.of("web_port", card.forgeWebPort(), "git_port", card.forgeGitPort()),
                        "matrix",
                        Map.of("port", card.matrixPort())));
        answer.put("signed_by", card.signedBy());
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
