package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.Access;
import org.fuin.porelo.core.project.AccessFile;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.git.RegistryGit;
import org.fuin.porelo.organization.git.RegistryOrigin;
import org.fuin.porelo.vault.AccessPersons;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SshAgentClient;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/**
 * What changing the person's own key needs of this machine, for {@code identity rotate}, {@code
 * revoke --lost}, {@code publish} and {@code status}: the successor the agent serves, whether a key
 * is revoked, the places the new card goes to, and the registry filing and push.
 *
 * <p>The places are read from what this machine already trusts: each registry {@code registry read}
 * kept a record of, each followed project's access file in force, and the hosts those registries
 * name. Nothing a checkout's working tree holds decides a place.
 */
final class IdentityChange {

    /** The agent's variable, read from the context's environment and nowhere else. */
    static final String AGENT = "SSH_AUTH_SOCK";

    /** How long a card is valid from the day it is made. */
    private static final int DAYS = 30;

    /** The longest public key file read. */
    private static final long PUBLIC_KEY_LIMIT = 16 * 1024;

    /** The longest access file or card read from a followed clone. */
    private static final int READ_LIMIT = 1024 * 1024;

    /**
     * Why a verb stops, typed for a program when it carries a reason.
     *
     * <p>Its words name no file content, no secret and nothing the agent holds but fingerprints.
     */
    static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final int code;

        private final @Nullable String reason;

        private final transient Map<String, @Nullable Object> extra;

        Refused(final int code, final @Nullable String reason, final String words) {
            this(code, reason, words, Map.of());
        }

        Refused(
                final int code,
                final @Nullable String reason,
                final String words,
                final Map<String, @Nullable Object> extra) {
            super(words);
            this.code = code;
            this.reason = reason;
            this.extra = new LinkedHashMap<>(extra);
        }

        int code() {
            return code;
        }

        /** The fixed word a program reads, or {@code null} for a refusal that carries none. */
        @Nullable String reason() {
            return reason;
        }

        /** The words, never {@code null}, and never an address with its userinfo. */
        String words() {
            return org.fuin.porelo.vault.Userinfo.leftOutOf(String.valueOf(getMessage()));
        }

        /** The typed answer, or {@code null} for a refusal that carries no reason. */
        @Nullable Map<String, @Nullable Object> answer() {
            if (reason == null) {
                return null;
            }
            final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
            answer.put("reason", reason);
            answer.put("words", words());
            answer.putAll(extra);
            return answer;
        }
    }

    /**
     * A place's why when the checkout's origin is no longer the address the registry was read from.
     */
    static final String ORIGIN_MOVED = "origin-moved";

    /** A place's why when the checkout holds more than the registry commit read and this card. */
    static final String CHECKOUT_MOVED = "checkout-moved";

    /**
     * Where git pushes a registry read from an address, and over which of git's protocols.
     *
     * @param url What git is given to push to.
     * @param protocols The protocols git may use, no other: not even one a redirect names.
     */
    record Remote(String url, List<String> protocols) {

        /** Compact constructor: the protocols are copied and cannot be changed afterwards. */
        Remote {
            protocols = List.copyOf(protocols);
        }
    }

    /** How a registry's address becomes the remote git pushes to. */
    @FunctionalInterface
    interface Remotes {

        /**
         * The remote for an address.
         *
         * @param address The origin the registry was read from, as its trust record names it.
         * @return Where git pushes.
         */
        Remote of(String address);
    }

    /** The registry's own address, over https or ssh alone; a test replaces it with a path. */
    static final Remotes FORGE = address -> new Remote(address, List.of("https", "ssh"));

    private final PoreloContext context;

    private final CommandRunner runner;

    private final RegistryReadCommand.Forges forges;

    private final Remotes remotes;

    private @Nullable List<IdentityFacts.Registry> registries;

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param forges How a registry's forge is asked while a card is filed.
     */
    IdentityChange(final PoreloContext context, final RegistryReadCommand.Forges forges) {
        this(context, forges, FORGE);
    }

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param forges How a registry's forge is asked while a card is filed.
     * @param remotes Where a registry is pushed.
     */
    IdentityChange(
            final PoreloContext context,
            final RegistryReadCommand.Forges forges,
            final Remotes remotes) {
        this.context = context;
        this.runner = context.runner();
        this.forges = forges;
        this.remotes = remotes;
    }

    /** The UTC day a card made now starts on. */
    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** The day a card made today ends on. */
    static LocalDate expires(final LocalDate today) {
        return today.plusDays(DAYS);
    }

    /**
     * Refuses {@code --keepassxc} and {@code --key} together or neither, and {@code --entry}
     * without {@code --keepassxc}.
     *
     * @param key The {@code --key} given, or {@code null}.
     * @param keepassxc The {@code --keepassxc} given, or {@code null}.
     * @param entry The {@code --entry} given, or {@code null}.
     * @return The {@code --key}, or {@code null} when a new key is made into KeePassXC.
     * @throws Refused 64 for wrong use.
     */
    static @Nullable String keyChosen(
            final @Nullable String key,
            final @Nullable Path keepassxc,
            final @Nullable String entry)
            throws Refused {
        if ((key == null) == (keepassxc == null)) {
            throw new Refused(64, null, "give either --key FINGERPRINT|PUBFILE or --keepassxc");
        }
        if (entry != null && keepassxc == null) {
            throw new Refused(64, null, "--entry goes with --keepassxc");
        }
        return key;
    }

    /**
     * Prints an operation's answer with the KeePassXC entry its new key was made into.
     *
     * @param spec The command's spec.
     * @param json Whether JSON was asked for.
     * @param operation The operation.
     * @param made The key made into KeePassXC, or {@code null} for a key named with {@code --key}.
     */
    static void print(
            final picocli.CommandLine.Model.CommandSpec spec,
            final boolean json,
            final IdentityOperation operation,
            final KeePassXcKey.@Nullable Made made) {
        if (made == null || !json) {
            print(spec, json, operation);
            if (made != null) {
                spec.commandLine()
                        .getOut()
                        .println(
                                "entry "
                                        + RegistryVerifyCommand.printable(made.entry())
                                        + ", served by the agent");
                spec.commandLine().getOut().flush();
            }
            return;
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>(operation.answer());
        answer.put("entry", made.entry());
        answer.put("agent", "holds");
        final var out = spec.commandLine().getOut();
        out.println(Json.write(RegistryVerifyCommand.printable(answer)));
        out.flush();
    }

    /**
     * Refuses an id a caller may not choose.
     *
     * @param id The id given, or {@code null}.
     * @throws Refused 64 for an id outside {@code [A-Za-z0-9._-]{1,64}}.
     */
    static void checkId(final @Nullable String id) throws Refused {
        if (id != null && !IdentityOperation.ID.matcher(id).matches()) {
            throw new Refused(64, null, "an id is 1 to 64 of A-Z, a-z, 0-9, '.', '_' and '-'");
        }
    }

    /**
     * The card kept here.
     *
     * @return It.
     * @throws Refused 65 {@code no-identity} without one, 66 when it cannot be read.
     */
    PersonCard card() throws Refused {
        try {
            return new IdentityStore(context)
                    .card()
                    .orElseThrow(
                            () ->
                                    new Refused(
                                            65,
                                            "no-identity",
                                            "no card is kept here; make one with identity"
                                                    + " create"));
        } catch (IOException | org.fuin.porelo.vault.CardException ex) {
            throw new Refused(66, null, String.valueOf(ex.getMessage()));
        }
    }

    /**
     * The agent's socket, from the context's environment.
     *
     * @return The socket.
     * @throws Refused 65 {@code key-not-in-agent} when none is named.
     */
    String socket() throws Refused {
        final String socket = context.environment().apply(AGENT);
        if (socket == null || socket.isEmpty()) {
            throw new Refused(
                    65, "key-not-in-agent", "no ssh-agent is reachable (SSH_AUTH_SOCK is not set)");
        }
        return socket;
    }

    /** The keys the agent serves. */
    private static List<AgentKey> served(final String socket) throws Refused {
        try {
            return new SshAgentClient(Path.of(socket)).identities();
        } catch (RuntimeException ex) {
            throw new Refused(65, "key-not-in-agent", "the ssh-agent does not answer");
        }
    }

    /**
     * The successor a {@code --key} names, as {@code identity create} takes a key: one the agent
     * serves, Ed25519, not revoked and on no other login's accepted card.
     *
     * @param named {@code SHA256:...} or a public key file.
     * @param login The person's login.
     * @param socket The agent.
     * @return The key, which signs through the agent.
     * @throws Refused As {@code create} refuses a key.
     */
    AgentKey successor(final String named, final String login, final String socket) throws Refused {
        final String fingerprint = fingerprintNamed(named);
        final AgentKey key = servedKey(fingerprint, socket);
        if (!key.authorizedKeysLine().startsWith("ssh-ed25519 ")) {
            throw new Refused(
                    65, "key-not-ed25519", "the key " + fingerprint + " is not an Ed25519 key");
        }
        final String line = PersonCard.keyLine(key);
        if (revoked(line)) {
            throw new Refused(
                    65,
                    "revoked",
                    "the key " + fingerprint + " is revoked",
                    Map.of("who_may", answers(owners())));
        }
        final java.util.Optional<String> another =
                IdentityFacts.cardOfAnother(registries(), fingerprint, login);
        if (another.isPresent()) {
            throw new Refused(
                    65,
                    "key-on-another-card",
                    "the key " + fingerprint + " is on the card of another login",
                    Map.of("login", another.get()));
        }
        return key;
    }

    /**
     * The key of that fingerprint, as the agent serves it.
     *
     * @param fingerprint {@code SHA256:...}.
     * @param socket The agent.
     * @return The key.
     * @throws Refused 65 {@code key-not-in-agent} naming the fingerprint.
     */
    static AgentKey servedKey(final String fingerprint, final String socket) throws Refused {
        for (final AgentKey key : served(socket)) {
            if (fingerprint(key.keyBlob()).equals(fingerprint)) {
                return key;
            }
        }
        throw new Refused(
                65,
                "key-not-in-agent",
                "the ssh-agent does not serve "
                        + fingerprint
                        + " (KeePassXC locked or the entry not loaded)");
    }

    /** The fingerprint a {@code --key} names, by itself or by its public key file. */
    private static String fingerprintNamed(final String named) throws Refused {
        if (named.startsWith("SHA256:")) {
            if (!named.matches("SHA256:[A-Za-z0-9+/]{43}")) {
                throw new Refused(64, null, "a fingerprint is SHA256: and 43 base64 characters");
            }
            return named;
        }
        final Path file = Path.of(named);
        final String text;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > PUBLIC_KEY_LIMIT) {
                throw new Refused(
                        66,
                        null,
                        "cannot read '"
                                + RegistryVerifyCommand.printable(named)
                                + "': expected a public key file");
            }
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | java.io.UncheckedIOException ex) {
            throw new Refused(
                    66, null, "cannot read '" + RegistryVerifyCommand.printable(named) + "'");
        }
        final String[] words = text.strip().split("\\s+");
        final byte[] blob;
        try {
            blob = words.length >= 2 ? Base64.getDecoder().decode(words[1]) : new byte[0];
        } catch (IllegalArgumentException ex) {
            throw new Refused(
                    64,
                    null,
                    "'" + RegistryVerifyCommand.printable(named) + "' is not a public key file");
        }
        if (blob.length == 0) {
            throw new Refused(
                    64,
                    null,
                    "'" + RegistryVerifyCommand.printable(named) + "' is not a public key file");
        }
        if (!words[0].equals("ssh-ed25519")) {
            throw new Refused(
                    65,
                    "key-not-ed25519",
                    "'" + RegistryVerifyCommand.printable(named) + "' is not an Ed25519 key");
        }
        return fingerprint(blob);
    }

    /** A key blob's fingerprint, as {@code ssh-keygen -l} prints it, of any type. */
    private static String fingerprint(final byte[] blob) {
        try {
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * Whether a key is revoked: in this machine's revocation list or by any registry it reads.
     *
     * @param key {@code ssh-ed25519 AAAA...}.
     * @return {@code true} when revoked.
     * @throws Refused 66 when the revocation list or a registry's trust record is there but cannot
     *     be read.
     */
    boolean revoked(final String key) throws Refused {
        final List<IdentityFacts.Registry> read = registries();
        try {
            return IdentityFacts.revoked(context, read, key);
        } catch (IOException ex) {
            throw new Refused(66, null, String.valueOf(ex.getMessage()));
        }
    }

    /**
     * The registries this machine reads, each with the state its last read kept, as {@code identity
     * show} reads them.
     *
     * @throws Refused 66 when one's trust record cannot be read: leaving it out would hide what
     *     that registry revoked.
     */
    List<IdentityFacts.Registry> registries() throws Refused {
        if (registries == null) {
            try {
                registries = IdentityFacts.registries(context);
            } catch (IOException ex) {
                throw new Refused(66, null, String.valueOf(ex.getMessage()));
            }
        }
        return registries;
    }

    /** The owners of every registry read here, as contacts, each once, the backup keys left out. */
    List<IdentityOperation.Contact> owners() throws Refused {
        return contacts(IdentityFacts.owners(registries()));
    }

    private static List<IdentityOperation.Contact> contacts(
            final List<Map<String, @Nullable Object>> answers) {
        return answers.stream().map(IdentityOperation.Contact::of).toList();
    }

    /** Contacts as the answer writes them. */
    static List<Map<String, @Nullable Object>> answers(
            final List<IdentityOperation.Contact> contacts) {
        return contacts.stream().map(IdentityOperation.Contact::answer).toList();
    }

    /**
     * Every place a new card of this login goes to, as this machine knows them now.
     *
     * @param login The person's login.
     * @param action {@link IdentityOperation#ROTATE} or {@link IdentityOperation#REVOKE_LOST}.
     * @param status What each place starts as: {@code planned} for a dry run, {@code pending}.
     * @return The registries, then the projects, then the hosts.
     * @throws Refused When the registries cannot be listed.
     */
    List<IdentityOperation.Place> places(
            final String login, final String action, final String status) throws Refused {
        final boolean lost = action.equals(IdentityOperation.REVOKE_LOST);
        final List<IdentityOperation.Place> places = new ArrayList<>();
        final Set<String> addresses = new LinkedHashSet<>();
        for (final IdentityFacts.Registry registry : registries()) {
            if (addresses.add(registry.known().origin())) {
                final List<IdentityOperation.Contact> owners =
                        contacts(IdentityFacts.owners(registry.organization()));
                places.add(
                        new IdentityOperation.Place(
                                "registry",
                                registry.known().origin(),
                                status,
                                lost ? "a registry owner files it" : null,
                                lost ? owners : List.of()));
            }
        }
        final Set<String> projects = new LinkedHashSet<>();
        for (final Map.Entry<String, List<IdentityOperation.Contact>> project :
                projects(login).entrySet()) {
            projects.add(project.getKey());
            places.add(
                    new IdentityOperation.Place(
                            "project",
                            project.getKey(),
                            status,
                            "a project admin accepts it",
                            project.getValue()));
        }
        final Set<String> hosts = new LinkedHashSet<>();
        for (final IdentityFacts.Registry registry : registries()) {
            for (final Organization.Host host : registry.organization().hosts()) {
                final boolean names =
                        host.projects().stream().anyMatch(each -> projects.contains(each.name()))
                                || host.admins().stream()
                                        .anyMatch(admin -> admin.login().equals(login));
                if (names && hosts.add(host.host())) {
                    final List<IdentityOperation.Contact> admins =
                            lost
                                    ? contacts(
                                            IdentityFacts.hostAdmins(
                                                    registry.organization(), host.host()))
                                    : List.of();
                    places.add(
                            new IdentityOperation.Place(
                                    "host",
                                    host.host(),
                                    status,
                                    "the host takes it at its next sync",
                                    admins));
                }
            }
        }
        return List.copyOf(places);
    }

    /**
     * Each followed project whose access file in force names the login, with its admins: persons of
     * role admin, neither locked nor revoked, as contacts from their cards at that commit.
     */
    private Map<String, List<IdentityOperation.Contact>> projects(final String login)
            throws Refused {
        final List<FollowedProjects.Followed> followed;
        try {
            followed = new FollowedProjects(context.paths().projects().followed()).all();
        } catch (IOException ex) {
            throw new Refused(66, null, "cannot read what this machine follows");
        }
        final Map<String, List<IdentityOperation.Contact>> projects = new LinkedHashMap<>();
        for (final FollowedProjects.Followed project : followed) {
            final String commit = project.accessCommit();
            if (project.unverified() || commit.isBlank()) {
                continue;
            }
            final Path access = context.paths().projects().followedAccessClone(project.name());
            final Path clone =
                    Files.isDirectory(access, LinkOption.NOFOLLOW_LINKS)
                            ? access
                            : context.paths().projects().followedClone(project.name());
            final AccessFile file;
            try {
                final String text = blob(clone, commit, Access.FILE);
                if (text == null) {
                    continue;
                }
                file = AccessFile.read(new StringReader(text), Access.FILE + " at " + commit);
            } catch (RuntimeException ex) {
                // An access file in force that does not read names nobody this could tell of.
                continue;
            }
            if (!file.persons().containsKey(login)) {
                continue;
            }
            final List<IdentityOperation.Contact> admins = new ArrayList<>();
            for (final AccessPersons.Person person :
                    AccessPersons.read(RegistryGit.sealing(runner), clone, commit, file)) {
                if (person.role().equals("admin") && person.admitted()) {
                    admins.add(projectContact(clone, commit, person.login()));
                }
            }
            projects.put(project.name(), List.copyOf(admins));
        }
        return projects;
    }

    private IdentityOperation.Contact projectContact(
            final Path clone, final String commit, final String login) {
        try {
            final String text = blob(clone, commit, "cards/" + login + ".card");
            if (text != null) {
                final PersonCard card = PersonCard.read(text, login);
                return new IdentityOperation.Contact(
                        login, card.name(), card.email().isEmpty() ? null : card.email());
            }
        } catch (RuntimeException ex) {
            // The admin's card verified as the access file was read; a name is all that is lost.
        }
        return new IdentityOperation.Contact(login, login, null);
    }

    /** A file of that commit, as git holds it, or {@code null} when it holds none of that size. */
    private @Nullable String blob(final Path clone, final String commit, final String path) {
        final CommandResult shown =
                runner.run(RegistryGit.sealed(clone, "cat-file", "blob", commit + ":" + path));
        if (!shown.successful() || shown.standardOutput().length() > READ_LIMIT) {
            return null;
        }
        return shown.standardOutput();
    }

    /**
     * Sends the new card to each place not done that the person's own key can serve: a rotation's
     * registries. Every other place is answered as it stands, since only another person accepts it
     * there.
     *
     * @param operation The operation.
     * @param login The person's login.
     * @return The places, as tried now.
     * @throws Refused When the registries cannot be listed.
     */
    List<IdentityOperation.Place> publish(final IdentityOperation operation, final String login)
            throws Refused {
        final List<IdentityOperation.Place> places = new ArrayList<>();
        for (final IdentityOperation.Place place : operation.published()) {
            if (place.status().equals("done")
                    || !place.where().equals("registry")
                    || !operation.action().equals(IdentityOperation.ROTATE)) {
                places.add(place);
                continue;
            }
            final IdentityFacts.Registry registry =
                    registries().stream()
                            .filter(each -> each.known().origin().equals(place.name()))
                            .findFirst()
                            .orElse(null);
            if (registry == null) {
                places.add(place.with("refused", "this machine no longer reads that registry"));
                continue;
            }
            final String why = file(registry, operation.card(), login);
            places.add(why == null ? place.with("done", null) : place.with("refused", why));
        }
        return List.copyOf(places);
    }

    /**
     * Files the card into the checkout read last as {@code registry file card} does, unless the
     * registry commit read holds it already, and pushes that one commit to the address the registry
     * was read from.
     *
     * <p>The checkout is the person's, and anybody who can write to it can change its configuration
     * and its branch. So its origin must still be the address read, its {@code HEAD} the commit
     * read or that commit with this card filed on it and nothing else, and git pushes from a
     * repository of its own that borrows the checkout's objects alone: no hook, helper, rewrite or
     * push URL of the checkout's configuration takes part.
     *
     * @return Why it is not done, or {@code null} once pushed.
     */
    private @Nullable String file(
            final IdentityFacts.Registry registry, final String card, final String login) {
        final String socket = context.environment().apply(AGENT);
        if (socket == null || socket.isEmpty()) {
            return "no ssh-agent is reachable (SSH_AUTH_SOCK is not set)";
        }
        final Path checkout = registry.known().checkout();
        final String read = registry.organization().readTo();
        try {
            // Opened as registry read opens it: a directory, never through a link, whose one
            // origin git does not rewrite.
            if (!RegistryReadCommand.origin(runner, checkout).equals(registry.known().origin())) {
                return ORIGIN_MOVED
                        + ": the checkout's origin is no longer the address this registry was read"
                        + " from; read the registry again";
            }
        } catch (IOException ex) {
            return CHECKOUT_MOVED
                    + ": the checkout read last is no Git directory of its own any more (a link is"
                    + " not followed); read the registry again";
        } catch (IllegalArgumentException ex) {
            return ORIGIN_MOVED
                    + ": the checkout no longer names one origin git does not rewrite; read the"
                    + " registry again";
        }
        final String path = "people/" + login + ".card";
        String head = head(checkout);
        if (read != null && read.equals(head) && !card.equals(blob(checkout, read, path))) {
            final String refused = fileCommand(checkout, card, login);
            if (refused != null) {
                return refused;
            }
            head = head(checkout);
        }
        // A retry after a push that failed finds its own commit at HEAD: it is pushed again.
        final String branch = branch(checkout);
        if (read == null
                || head == null
                || branch == null
                || !head.equals(read) && !filedOnto(checkout, read, head, path, card)) {
            return CHECKOUT_MOVED
                    + ": the checkout's branch holds other than the registry commit read last and"
                    + " this card filed on it; read the registry again";
        }
        return push(registry.known().origin(), checkout, head, branch, socket);
    }

    /** The commit the checkout's {@code HEAD} names, or {@code null}. */
    private @Nullable String head(final Path checkout) {
        final CommandResult head =
                runner.run(
                        RegistryGit.sealed(
                                checkout, "rev-parse", "--verify", "-q", "HEAD^{commit}"));
        final String commit = head.trimmedOutput();
        return head.successful() && commit.matches("[0-9a-f]{40}|[0-9a-f]{64}") ? commit : null;
    }

    /** The branch the checkout is on, or {@code null} when it is on none or one of an odd name. */
    private @Nullable String branch(final Path checkout) {
        final CommandResult branch =
                runner.run(RegistryGit.sealed(checkout, "symbolic-ref", "-q", "--short", "HEAD"));
        final String name = branch.trimmedOutput();
        return branch.successful() && name.matches("[A-Za-z0-9_][A-Za-z0-9._/-]{0,199}")
                ? name
                : null;
    }

    /**
     * Whether the commit is the one filing of this card onto the commit read, and nothing else: its
     * one parent the commit read, its one change the card as a plain file, and its signature the
     * card's own key's, as {@code registry file card} signs it. Anything else would be pushed as
     * the person's and refused by every reader.
     */
    private boolean filedOnto(
            final Path checkout,
            final String read,
            final String head,
            final String path,
            final String card) {
        final CommandResult parents =
                runner.run(RegistryGit.sealed(checkout, "rev-list", "--parents", "-n", "1", head));
        if (!parents.successful() || !parents.trimmedOutput().equals(head + " " + read)) {
            return false;
        }
        final CommandResult changed =
                runner.run(
                        RegistryGit.sealed(
                                checkout, "diff-tree", "-r", "--no-renames", "-z", read, head));
        // One raw entry: ":old-mode new-mode old-blob new-blob status", then the path.
        final String[] entry = changed.standardOutput().split("\0", -1);
        if (!changed.successful()
                || entry.length != 3
                || !entry[1].equals(path)
                || !entry[2].isEmpty()
                || !entry[0].matches(":[0-7]{6} 100644 [0-9a-f]+ [0-9a-f]+ [AM]")
                || !card.equals(blob(checkout, head, path))) {
            return false;
        }
        final CommandResult commit =
                runner.run(RegistryGit.sealed(checkout, "cat-file", "commit", head));
        try {
            return commit.successful()
                    && signedBy(commit.standardOutput(), PersonCard.read(card, path).key());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Whether a commit object carries one SSH signature, in Git's namespace, by this key over the
     * commit without it: Git's own check, made here so no program a configuration names runs.
     *
     * @param raw The commit, as {@code git cat-file commit} prints it.
     * @param key {@code ssh-ed25519 AAAA...}.
     * @return Whether it does.
     */
    static boolean signedBy(final String raw, final String key) {
        final int end = raw.indexOf("\n\n");
        if (end < 0) {
            return false;
        }
        final StringBuilder payload = new StringBuilder();
        final StringBuilder signature = new StringBuilder();
        int signatures = 0;
        boolean inSignature = false;
        for (final String line : raw.substring(0, end + 1).split("(?<=\n)")) {
            if (line.startsWith("gpgsig ") || line.startsWith("gpgsig-sha256 ")) {
                signatures++;
                inSignature = true;
                signature.append(line.substring(line.indexOf(' ') + 1));
            } else if (inSignature && line.startsWith(" ")) {
                signature.append(line.substring(1));
            } else {
                inSignature = false;
                payload.append(line);
            }
        }
        payload.append(raw.substring(end + 1));
        return signatures == 1
                && org.fuin.porelo.vault.SshSignature.verify(
                        payload.toString().getBytes(StandardCharsets.UTF_8),
                        signature.toString(),
                        Base64.getDecoder().decode(key.substring(key.indexOf(' ') + 1)),
                        "git");
    }

    /**
     * Pushes one commit to the branch's name at the registry's address, from a scratch repository
     * whose only configuration is this one's and which borrows the checkout's objects.
     *
     * @return Why it is not done, or {@code null} once pushed.
     */
    private @Nullable String push(
            final String address,
            final Path checkout,
            final String commit,
            final String branch,
            final String socket) {
        final Remote remote = remotes.of(address);
        final CommandResult common =
                runner.run(
                        RegistryGit.sealed(
                                checkout,
                                "rev-parse",
                                "--path-format=absolute",
                                "--git-common-dir"));
        if (!common.successful()) {
            return "cannot find the checkout's objects to push";
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("porelo-push-");
            final Path template = Files.createDirectory(scratch.resolve("template"));
            final Path repository = scratch.resolve("push.git");
            final CommandResult made =
                    runner.run(
                            Command.of(
                                            "git",
                                            "init",
                                            "-q",
                                            "--bare",
                                            "--template=" + template,
                                            repository.toString())
                                    .withChosenEnvironment(RegistryGit.ENVIRONMENT));
            if (!made.successful()) {
                return "cannot make a scratch repository to push from";
            }
            final Path info = Directories.create(repository.resolve("objects").resolve("info"));
            Files.writeString(
                    info.resolve("alternates"),
                    Path.of(common.trimmedOutput()).resolve("objects") + "\n",
                    StandardCharsets.UTF_8);
            final List<String> push = new ArrayList<>(List.of("git", "--git-dir=" + repository));
            push.addAll(RegistryGit.SEALED);
            for (final String protocol : remote.protocols()) {
                push.add("-c");
                push.add("protocol." + protocol + ".allow=always");
            }
            push.addAll(
                    List.of(
                            "push",
                            "-q",
                            "--no-verify",
                            remote.url(),
                            commit + ":refs/heads/" + branch));
            final CommandResult pushed =
                    runner.run(
                            Command.of(push)
                                    .withEnvironment(Map.of(AGENT, socket))
                                    .withChosenEnvironment(RegistryGit.ENVIRONMENT));
            // Git's own words may carry the remote's URL, credentials and all; only its exit is
            // told.
            return pushed.successful()
                    ? null
                    : "the card is committed but git push to origin failed (exit "
                            + pushed.exitCode()
                            + "); retry with identity publish";
        } catch (IOException ex) {
            return "cannot make a scratch repository to push from";
        } finally {
            if (scratch != null) {
                deleteTree(scratch);
            }
        }
    }

    private static void deleteTree(final Path directory) {
        try (var files = Files.walk(directory)) {
            for (Path each : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(each);
            }
        } catch (IOException ex) {
            // A scratch repository left in the temporary directory holds public objects only.
        }
    }

    /** Runs {@code registry file card} on the checkout; why it refused, or {@code null}. */
    private @Nullable String fileCommand(
            final Path checkout, final String card, final String login) {
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("porelo-identity-");
            final Path file = Files.writeString(scratch.resolve(login + ".card"), card);
            final RegistryFileCommand command = new RegistryFileCommand();
            command.setContext(context);
            command.forges = forges;
            final StringWriter out = new StringWriter();
            final StringWriter err = new StringWriter();
            final CommandLine cli = new CommandLine(command);
            cli.setOut(new PrintWriter(out));
            cli.setErr(new PrintWriter(err));
            final int code =
                    cli.execute("card", checkout.toString(), "--card", file.toString(), "--json");
            if (code == 0) {
                return null;
            }
            final String said =
                    err.toString()
                            .lines()
                            .filter(line -> !line.startsWith("porelo: warning"))
                            .findFirst()
                            .orElse("registry file card ended with " + code);
            // The words are kept in the record and printed: no URL in them keeps its user part.
            return RegistryOrigin.withoutUserinfoIn(
                    said.startsWith("porelo: ") ? said.substring("porelo: ".length()) : said);
        } catch (IOException ex) {
            return "cannot write the card to a scratch file";
        } finally {
            if (scratch != null) {
                try (var files = Files.walk(scratch)) {
                    for (Path each : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(each);
                    }
                } catch (IOException ex) {
                    // A public card left in the temporary directory holds nothing secret.
                }
            }
        }
    }

    /**
     * An operation kept, and whether this run started it.
     *
     * @param operation The operation.
     * @param fresh {@code false} when a run at once kept one under the same id first.
     */
    record Begun(IdentityOperation operation, boolean fresh) {}

    /**
     * Keeps a new operation, under the id given or the first free one made, and only then the new
     * card: a run stopped in between leaves the record to answer, never a card nothing records.
     *
     * @param id The id given, or {@code null} to make one.
     * @param made The operation, its id aside.
     * @return What is kept under the id.
     * @throws IOException When the record or the card cannot be written.
     * @throws Refused When a run at once kept another operation under the id: 64 for one of the
     *     other action, 66 {@code operation-damaged} for one that does not read.
     */
    Begun begin(final @Nullable String id, final IdentityOperation made)
            throws IOException, Refused {
        if (id != null) {
            final IdentityOperation operation = withId(made, id);
            if (operation.keepNew(context)) {
                return new Begun(keep(operation), true);
            }
            final IdentityOperation kept = kept(context, id, made.action());
            if (kept == null) {
                throw new IOException("the operation " + id + " vanished as it was kept");
            }
            return new Begun(kept, false);
        }
        while (true) {
            final IdentityOperation operation =
                    withId(made, IdentityOperation.newId(context, made.action(), today()));
            if (operation.keepNew(context)) {
                return new Begun(keep(operation), true);
            }
        }
    }

    /** Keeps the operation's card as the person's own, then notes in the record that it is. */
    private IdentityOperation keep(final IdentityOperation operation) throws IOException {
        new IdentityStore(context).keep(operation.card());
        final IdentityOperation kept = operation.keptHere();
        kept.keep(context);
        return kept;
    }

    /**
     * Finishes what a run stopped before it kept the operation's card: when the card kept here is
     * still the one the operation replaces, the operation's card is kept in its place. The card is
     * the one the record holds, never one made again.
     *
     * @param context The machine.
     * @param operation The operation, as read back.
     * @return It, its card kept when it was the one to keep.
     * @throws IOException When the card kept here cannot be read or the new one written.
     */
    static IdentityOperation finish(final PoreloContext context, final IdentityOperation operation)
            throws IOException {
        if (operation.kept()) {
            return operation;
        }
        final IdentityStore store = new IdentityStore(context);
        final java.util.Optional<PersonCard> held = store.card();
        if (held.isEmpty()) {
            return operation;
        }
        final String fingerprint = held.get().fingerprint();
        if (fingerprint.equals(operation.predecessor())) {
            store.keep(operation.card());
        } else if (!fingerprint.equals(operation.fingerprint())) {
            // Another card was kept since; this operation's is not brought back over it.
            return operation;
        }
        final IdentityOperation kept = operation.keptHere();
        kept.keep(context);
        return kept;
    }

    private static IdentityOperation withId(final IdentityOperation made, final String id) {
        return new IdentityOperation(
                id,
                made.action(),
                made.fingerprint(),
                made.predecessor(),
                made.card(),
                made.done(),
                made.published(),
                false);
    }

    /**
     * The operation kept under an id the caller gave, refusing one of another action and one that
     * is not what this machine kept: its card must verify as a card must, be of its key and its
     * predecessor, and be of the login whose card is kept here.
     *
     * @param context The machine.
     * @param id The id, or {@code null}.
     * @param action The action the caller runs, or {@code null} for any.
     * @return The operation, or {@code null} when none is kept under it.
     * @throws Refused 66 {@code operation-damaged} when it cannot be taken, 64 when it is of
     *     another action, 65 {@code no-identity} when no card is kept here.
     */
    static @Nullable IdentityOperation kept(
            final PoreloContext context, final @Nullable String id, final @Nullable String action)
            throws Refused {
        if (id == null) {
            return null;
        }
        final IdentityOperation kept;
        try {
            kept = IdentityOperation.read(context, id);
        } catch (IOException ex) {
            throw damaged(String.valueOf(ex.getMessage()));
        }
        if (kept == null) {
            return null;
        }
        if (action != null && !kept.action().equals(action)) {
            throw new Refused(
                    64, null, "the id " + id + " names a " + kept.action() + " operation");
        }
        final PersonCard card;
        try {
            card = PersonCard.read(kept.card(), "the card of the operation " + id);
        } catch (org.fuin.porelo.vault.CardException ex) {
            throw damaged("the card of the operation " + id + " does not read as a card");
        }
        final boolean rotation = kept.action().equals(IdentityOperation.ROTATE);
        if (!verifies(card)
                || !card.fingerprint().equals(kept.fingerprint())
                || rotation == card.predecessor().isEmpty()
                || rotation
                        && !PersonCard.fingerprint(card.predecessor()).equals(kept.predecessor())) {
            throw damaged(
                    "the card of the operation "
                            + id
                            + " is not signed as it must be or not of the keys it names");
        }
        final String login = new IdentityChange(context, NO_FORGE).card().login();
        if (!card.login().equals(login)) {
            throw damaged(
                    "the card of the operation "
                            + id
                            + " is of another login than the card kept here");
        }
        return kept;
    }

    /** A forge for a change that files nothing. */
    static final RegistryReadCommand.Forges NO_FORGE =
            (forge, token) -> {
                throw new IllegalStateException("this files nothing");
            };

    private static Refused damaged(final String words) {
        return new Refused(66, "operation-damaged", words);
    }

    /** Whether a card's signatures verify, a malformed one answering no. */
    private static boolean verifies(final PersonCard card) {
        try {
            return card.signedAsItMustBe();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Writes a card through the agent and checks it as every reader will: an agent that refuses to
     * sign, or answers with another key's signature or bytes that are none, makes no card.
     *
     * @param login The person's login.
     * @param name What they are called.
     * @param email How to reach them, or "".
     * @param own The card's key.
     * @param created The day it is made.
     * @param predecessor The key it replaces, which signs second, or {@code null}.
     * @return The card's text.
     * @throws Refused 65 {@code key-not-in-agent} when the agent did not sign as asked.
     */
    static String signed(
            final String login,
            final String name,
            final String email,
            final AgentKey own,
            final LocalDate created,
            final @Nullable AgentKey predecessor)
            throws Refused {
        final String text;
        try {
            text =
                    PersonCard.write(
                            login, name, email, own, created, expires(created), predecessor);
        } catch (org.fuin.porelo.vault.VaultException ex) {
            throw new Refused(
                    65,
                    "key-not-in-agent",
                    "the ssh-agent did not sign the card (KeePassXC locked or the entry not"
                            + " loaded)");
        }
        if (!verifies(PersonCard.read(text, "the card made"))) {
            throw new Refused(
                    65,
                    "key-not-in-agent",
                    "the ssh-agent's signature on the card does not verify by "
                            + PersonCard.fingerprint(PersonCard.keyLine(own))
                            + (predecessor == null
                                    ? ""
                                    : " and "
                                            + PersonCard.fingerprint(
                                                    PersonCard.keyLine(predecessor)))
                            + "; nothing was kept");
        }
        return text;
    }

    /**
     * Prints an answer, every text in it printable.
     *
     * @param spec The command's spec.
     * @param json Whether JSON was asked for.
     * @param operation The operation.
     */
    static void print(
            final picocli.CommandLine.Model.CommandSpec spec,
            final boolean json,
            final IdentityOperation operation) {
        final var out = spec.commandLine().getOut();
        if (json) {
            out.println(Json.write(RegistryVerifyCommand.printable(operation.answer())));
        } else {
            out.println(
                    RegistryVerifyCommand.printable(
                            operation.action()
                                    + " "
                                    + operation.id()
                                    + ": "
                                    + operation.predecessor()
                                    + " -> "
                                    + operation.fingerprint()));
            for (final IdentityOperation.Place place : operation.published()) {
                out.println(
                        RegistryVerifyCommand.printable(
                                place.where()
                                        + " "
                                        + place.name()
                                        + " "
                                        + place.status()
                                        + (place.why() == null ? "" : ": " + place.why())));
            }
        }
        out.flush();
    }

    /**
     * Answers a refusal: typed on stdout with {@code --json} when it carries a reason, and its
     * words on stderr.
     *
     * @param spec The command's spec.
     * @param json Whether JSON was asked for.
     * @param refused The refusal.
     * @return Its exit code.
     */
    static int refuse(
            final picocli.CommandLine.Model.CommandSpec spec,
            final boolean json,
            final Refused refused) {
        final Map<String, @Nullable Object> typed = refused.answer();
        if (json && typed != null) {
            final var out = spec.commandLine().getOut();
            out.println(Json.write(RegistryVerifyCommand.printable(typed)));
            out.flush();
        }
        final var err = spec.commandLine().getErr();
        err.println("porelo: " + RegistryVerifyCommand.printable(refused.words()));
        err.flush();
        return refused.code();
    }
}
