package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.HttpForgeDirectory;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.RegistryState;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * A second, offline key for the one owner of a registry, the one admin of a host or the one signer
 * of a followed project: made once into a file the person keeps away from their daily agent, and
 * filed into {@code owners.yml}, or among the project's signers, beside the key it backs. With
 * {@code --use} the file signs the one change it may make, the key it backs replaced by a new
 * card's.
 *
 * <p>Neither the key's secret half nor its passphrase passes through Porelo: {@code ssh-keygen}
 * makes the file on the person's terminal, and git signs with it through {@code ssh-keygen}, which
 * asks the passphrase there too.
 */
@Command(
        name = "backup-key",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Makes an offline backup key for a registry, host or project you alone hold, or"
                        + " uses one to replace the key it backs.")
public class IdentityBackupKeyCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";

    /** How far back an id is looked for. */
    private static final int SEARCHED = 1000;

    /**
     * A project's side of the verb: the project's signers are its area's, which this area does not
     * depend on, so it is set where the command line is assembled.
     */
    interface Projects {

        /**
         * Files a backup key for the person's key among the project's signers, signed through the
         * agent by that key, and pushes it; the key is made only once nothing refuses.
         *
         * @param machine The machine.
         * @param project The followed project.
         * @param own The person's card.
         * @param keys Makes the backup key and answers its public half.
         * @param base The commit the change is made against, or {@code null} for the one in force.
         * @param id Names the change, or {@code null}.
         * @return What was filed.
         * @throws IdentityChange.Refused With the contract's code and reason.
         * @throws IOException If a scratch file cannot be written.
         */
        Filed make(
                PoreloContext machine,
                String project,
                PersonCard own,
                KeyMaker keys,
                @Nullable String base,
                @Nullable String id)
                throws IdentityChange.Refused, IOException;

        /**
         * Replaces the key a backup key backs among the project's signers by the new card's, signed
         * with the backup key's file, and pushes it.
         *
         * @param machine The machine.
         * @param project The followed project.
         * @param file The backup key's file.
         * @param backupKey Its public half.
         * @param next The card whose key replaces the one backed.
         * @param base The commit the change is made against, or {@code null} for the one in force.
         * @param id Names the change.
         * @return What was replaced.
         * @throws IdentityChange.Refused With the contract's code and reason.
         * @throws IOException If a scratch file cannot be written.
         */
        Replaced replace(
                PoreloContext machine,
                String project,
                Path file,
                String backupKey,
                PersonCard next,
                @Nullable String base,
                String id)
                throws IdentityChange.Refused, IOException;
    }

    /** Makes the backup key's file and answers its public half. */
    @FunctionalInterface
    interface KeyMaker {
        String make() throws IdentityChange.Refused, IOException;
    }

    /**
     * A backup key filed.
     *
     * @param fingerprint Its fingerprint.
     * @param revision The commit that filed it.
     */
    record Filed(String fingerprint, String revision) {}

    /**
     * A key replaced by the backup key.
     *
     * @param replaced The key it backed.
     * @param by The new card's key.
     * @param revision The commit that replaced it.
     */
    record Replaced(String replaced, String by, String revision) {}

    /** Where the backup key goes: exactly one. */
    static final class Scope {
        @Option(
                names = "--project",
                paramLabel = "PROJECT",
                description = "A followed project whose one signer you are.")
        @Nullable String project;

        @Option(
                names = "--host",
                paramLabel = "HOST",
                description = "A host you are the one admin of.")
        @Nullable String host;

        @Option(names = "--registry", description = "The registry you are the one owner of.")
        boolean registry;
    }

    @ArgGroup(multiplicity = "1")
    private Scope scope = new Scope();

    @Option(
            names = "--out",
            paramLabel = "FILE",
            description = "Where the new backup key's file is written; it must not exist.")
    private @Nullable Path out;

    @Option(
            names = "--use",
            paramLabel = "FILE",
            description = "The backup key's file, to replace the key it backs.")
    private @Nullable Path use;

    @Option(
            names = "--card",
            paramLabel = "NEW_CARD",
            description = "With --use: the card whose key replaces the one the backup key backs.")
    private @Nullable Path card;

    @Option(
            names = "--base",
            paramLabel = "COMMIT",
            description = "The registry commit the change is made against.")
    private @Nullable String base;

    @Option(
            names = "--id",
            paramLabel = "ID",
            description = "Names the change: asked again, it answers the same commit.")
    private @Nullable String id;

    @Option(names = "--json", description = "Answers as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    /** Whether this call's ssh-keygen wrote the file at {@code --out}. */
    private boolean written;

    /** Whether the key written there was filed, so the file is the person's to keep. */
    private boolean kept;

    /** Replaced by a test, which has no forge to ask. */
    RegistryReadCommand.Forges forges =
            (forge, token) ->
                    new HttpForgeDirectory(
                            forge,
                            token,
                            RegistryReadCommand.http(
                                    java.util.Objects.requireNonNull(
                                            context, "picocli's factory sets the context")));

    /** Whether a terminal is there to ask the passphrase on; replaced by a test. */
    BooleanSupplier terminal = () -> System.console() != null;

    /** The project's side, set where the command line is assembled. */
    @Nullable Projects projects;

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PoreloContext machine =
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context");
        try {
            IdentityChange.checkId(id);
            if (base != null && !base.matches(COMMIT)) {
                throw new IdentityChange.Refused(
                        64,
                        null,
                        "--base '"
                                + RegistryVerifyCommand.printable(base)
                                + "' is not a commit's full hash");
            }
            if ((out == null) == (use == null) || (use != null) != (card != null)) {
                throw new IdentityChange.Refused(
                        64,
                        null,
                        "give --out FILE to make a backup key, or --use FILE --card CARD");
            }
            if (use != null && id == null) {
                throw new IdentityChange.Refused(64, null, "--use needs --id");
            }
            final PersonCard own = new IdentityChange(machine, forges, IdentityChange.FORGE).card();
            if (scope.project != null) {
                return use == null ? makeForProject(machine, own) : replaceForProject(machine, own);
            }
            final IdentityFacts.Registry registry = registry(machine);
            requireOrigin(machine, registry);
            final String login = own.login();
            final String head = head(machine, registry);
            final String[] earlier = id == null ? null : earlier(machine, registry, head);
            if (earlier != null) {
                return use == null
                        ? madeEarlier(machine, registry, login, earlier[0], earlier[1])
                        : replacedEarlier(machine, registry, login, earlier[0], earlier[1]);
            }
            final String against = base != null ? base : String.valueOf(registry.state().commit());
            if (!head.equals(against)) {
                throw new IdentityChange.Refused(
                        65,
                        "base-moved",
                        "the registry checkout's HEAD is "
                                + head
                                + ", and the change was made against "
                                + (base != null ? "--base " : "the commit read last, ")
                                + against
                                + "; read it again and ask anew");
            }
            // The checkout as it is now, never the trust record of an earlier read.
            final RegistryState state =
                    RegistryReadCommand.read(
                                    machine,
                                    machine.runner(),
                                    registry.known().checkout(),
                                    forges,
                                    spec.commandLine().getErr())
                            .state();
            final List<RegistryState.Owner> entries = inScope(state);
            return use == null
                    ? make(machine, registry, state, entries, own, head)
                    : replace(machine, registry, state, entries, login, head);
        } catch (IdentityChange.Refused ex) {
            return IdentityChange.refuse(spec, json, ex);
        } catch (CardException | IllegalArgumentException ex) {
            return IdentityChange.refuse(
                    spec,
                    json,
                    new IdentityChange.Refused(65, null, String.valueOf(ex.getMessage())));
        } catch (IOException ex) {
            return IdentityChange.refuse(
                    spec,
                    json,
                    new IdentityChange.Refused(66, null, String.valueOf(ex.getMessage())));
        } finally {
            if (written && !kept) {
                forgetUnfiled();
            }
        }
    }

    /**
     * Refuses a checkout whose origin is no longer the address the registry was read from: read
     * there, it would be the first read of another registry, trusted as this one.
     */
    private static void requireOrigin(
            final PoreloContext machine, final IdentityFacts.Registry registry)
            throws IdentityChange.Refused {
        final String origin;
        try {
            origin = RegistryReadCommand.origin(machine.runner(), registry.known().checkout());
        } catch (IOException ex) {
            throw new IdentityChange.Refused(
                    65,
                    IdentityChange.CHECKOUT_MOVED,
                    "the registry checkout read last cannot be read as it was: "
                            + ex.getMessage()
                            + "; read the registry again");
        } catch (IllegalArgumentException ex) {
            throw new IdentityChange.Refused(
                    65,
                    IdentityChange.ORIGIN_MOVED,
                    "the registry checkout no longer names one origin git does not rewrite: "
                            + ex.getMessage()
                            + "; read the registry again");
        }
        if (!origin.equals(registry.known().origin())) {
            throw new IdentityChange.Refused(
                    65,
                    IdentityChange.ORIGIN_MOVED,
                    "the registry checkout's origin is no longer the address this registry was"
                            + " read from; read the registry again");
        }
    }

    /** A key made and never filed is no backup of anything: the file goes, so none is kept. */
    private void forgetUnfiled() {
        final Path file = java.util.Objects.requireNonNull(out).toAbsolutePath();
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + ".pub"));
        } catch (IOException ex) {
            spec.commandLine()
                    .getErr()
                    .println("porelo: warning: the unfiled key " + file + " could not be removed");
        }
    }

    private List<RegistryState.Owner> inScope(final RegistryState state) {
        return scope.registry
                ? state.owners()
                : state.hostAdmins().getOrDefault(scope.host, List.of());
    }

    /** The commit the registry checkout's HEAD names. */
    private static String head(final PoreloContext machine, final IdentityFacts.Registry registry)
            throws IdentityChange.Refused {
        final String head =
                git(machine, registry, "rev-parse", "--verify", "HEAD^{commit}").strip();
        if (!head.matches(COMMIT)) {
            throw new IdentityChange.Refused(
                    65, null, "the registry checkout names a HEAD that is no commit");
        }
        return head;
    }

    /**
     * The commit an earlier call filed under this id on HEAD's first parents, and its first parent;
     * {@code null} when there is none.
     */
    private String @Nullable [] earlier(
            final PoreloContext machine, final IdentityFacts.Registry registry, final String head)
            throws IdentityChange.Refused {
        final String log =
                git(
                        machine,
                        registry,
                        "log",
                        "-z",
                        "--first-parent",
                        "--max-count=" + SEARCHED,
                        "--format=%H%x1F%P%x1F"
                                + "%(trailers:key=Porelo-Change-Id,valueonly,unfold,"
                                + "separator=%x1E)",
                        head,
                        "--");
        for (final String entry : log.split("\0")) {
            final String[] fields = entry.strip().split("\u001F", -1);
            if (fields.length == 3
                    && fields[0].matches(COMMIT)
                    && List.of(fields[2].split("\u001E")).contains(id)) {
                final String parent = fields[1].isEmpty() ? "" : fields[1].split(" ")[0];
                if (!parent.matches(COMMIT)) {
                    throw new IdentityChange.Refused(
                            65, null, "--id " + id + " names another change: " + fields[0]);
                }
                return new String[] {fields[0], parent};
            }
        }
        return null;
    }

    /**
     * Answers the backup key an earlier call filed under this id, as that call answered it: only
     * when the commit is its parent's owners file with this login's one backup key added in this
     * scope, and nothing else changed.
     */
    private int madeEarlier(
            final PoreloContext machine,
            final IdentityFacts.Registry registry,
            final String login,
            final String commit,
            final String parent)
            throws IdentityChange.Refused, IOException {
        final String text = ownersAt(machine, registry, commit);
        final String before = ownersAt(machine, registry, parent);
        final List<RegistryState.Owner> was = filedEntries(before);
        final RegistryState.Owner backup =
                filedEntries(text).stream()
                        .filter(entry -> entry.backup() && entry.login().equals(login))
                        .findFirst()
                        .orElse(null);
        if (backup == null
                || was.stream().anyMatch(entry -> entry.backup() && entry.login().equals(login))
                || !canonical(text).equals(with(before, was, backup, null))) {
            throw new IdentityChange.Refused(
                    65,
                    null,
                    "--id "
                            + id
                            + " names "
                            + commit
                            + ", which files no backup key of "
                            + login
                            + " for "
                            + where()
                            + " alone");
        }
        // Filed again under the same id with the same file, the registry answers that commit.
        final String revision = file(machine, registry, text, null, base);
        kept = true;
        return made(backup.fingerprint(), revision, false);
    }

    /** Answers the key an earlier call replaced under this id, as that call answered it. */
    private int replacedEarlier(
            final PoreloContext machine,
            final IdentityFacts.Registry registry,
            final String login,
            final String commit,
            final String parent)
            throws IdentityChange.Refused, IOException {
        final Path file = java.util.Objects.requireNonNull(use);
        final String text = ownersAt(machine, registry, commit);
        final String before = ownersAt(machine, registry, parent);
        final List<RegistryState.Owner> was = filedEntries(before);
        final PersonCard next = next(login);
        final String backup = PersonCard.fingerprint(backupKey(file));
        final RegistryState.Owner old =
                was.stream()
                        .filter(entry -> !entry.backup() && entry.login().equals(login))
                        .findFirst()
                        .orElse(null);
        if (old == null
                || old.fingerprint().equals(next.fingerprint())
                || was.stream()
                        .noneMatch(
                                entry ->
                                        entry.backup()
                                                && entry.login().equals(login)
                                                && entry.fingerprint().equals(backup))
                || !canonical(text)
                        .equals(
                                with(
                                        before,
                                        was,
                                        new RegistryState.Owner(
                                                login, next.key(), next.fingerprint(), false),
                                        old))) {
            throw new IdentityChange.Refused(
                    65,
                    null,
                    "--id "
                            + id
                            + " names "
                            + commit
                            + ", which replaces no key of "
                            + login
                            + " for "
                            + where()
                            + " by this card's with this backup key");
        }
        final String revision = file(machine, registry, text, file, base);
        return replaced(old.fingerprint(), next.fingerprint(), revision, false);
    }

    /** The owners file as it is written, so two that name the same entries read alike. */
    private static String canonical(final String text) throws IdentityChange.Refused {
        try {
            return RegistryState.ownersFile(
                    org.fuin.porelo.vault.RegistryFiles.entries(text, "owners.yml", null),
                    org.fuin.porelo.vault.RegistryFiles.hostAdmins(text, "owners.yml"));
        } catch (CardException ex) {
            throw new IdentityChange.Refused(65, null, String.valueOf(ex.getMessage()));
        }
    }

    private List<RegistryState.Owner> filedEntries(final String text)
            throws IdentityChange.Refused {
        try {
            return org.fuin.porelo.vault.RegistryFiles.entries(text, "owners.yml", scope.host);
        } catch (CardException ex) {
            throw new IdentityChange.Refused(65, null, String.valueOf(ex.getMessage()));
        }
    }

    private static String ownersAt(
            final PoreloContext machine, final IdentityFacts.Registry registry, final String commit)
            throws IdentityChange.Refused {
        return git(machine, registry, "show", commit + ":owners.yml");
    }

    private static String git(
            final PoreloContext machine,
            final IdentityFacts.Registry registry,
            final String... arguments)
            throws IdentityChange.Refused {
        final org.fuin.porelo.core.process.CommandResult result =
                machine.runner().run(RegistryGit.sealed(registry.known().checkout(), arguments));
        if (!result.successful()) {
            throw new IdentityChange.Refused(
                    65,
                    null,
                    "git "
                            + arguments[0]
                            + " failed in the registry checkout: "
                            + result.standardError().strip());
        }
        return result.standardOutput();
    }

    private int make(
            final PoreloContext machine,
            final IdentityFacts.Registry registry,
            final RegistryState state,
            final List<RegistryState.Owner> entries,
            final PersonCard own,
            final String head)
            throws IdentityChange.Refused, IOException {
        final String login = own.login();
        if (state.revoked().contains(own.fingerprint())
                || IdentityFacts.revoked(machine, List.of(registry), own.key())) {
            throw new IdentityChange.Refused(
                    65, "revoked", "your card's key " + own.fingerprint() + " is revoked");
        }
        if (entries.stream().anyMatch(entry -> entry.backup() && entry.login().equals(login))) {
            throw new IdentityChange.Refused(
                    65, "backup-exists", login + " has a backup key for " + where() + " already");
        }
        final boolean sole =
                state.soleWithoutBackup().stream()
                        .anyMatch(
                                one ->
                                        one.login().equals(login)
                                                && java.util.Objects.equals(
                                                        one.host(), scope.host));
        if (!sole) {
            throw new IdentityChange.Refused(
                    65,
                    "not-sole",
                    login
                            + " is not the one "
                            + (scope.registry ? "owner of the registry" : "admin of " + scope.host)
                            + "; another may replace the key");
        }
        final String key = keygen(machine, login);
        final RegistryState.Owner backup =
                new RegistryState.Owner(login, key, PersonCard.fingerprint(key), true);
        final String revision;
        try {
            revision =
                    file(
                            machine,
                            registry,
                            with(state.owners(), state.hostAdmins(), entries, backup, null),
                            null,
                            head);
        } finally {
            // The filing may fail after its commit, which files the key: then the file is kept.
            kept = kept || filedAtHead(machine, registry, key);
        }
        kept = true;
        return made(backup.fingerprint(), revision, false);
    }

    /** Files a backup key among a followed project's signers. */
    private int makeForProject(final PoreloContext machine, final PersonCard own)
            throws IdentityChange.Refused, IOException {
        if (IdentityFacts.revoked(machine, IdentityFacts.registries(machine), own.key())) {
            throw new IdentityChange.Refused(
                    65, "revoked", "your card's key " + own.fingerprint() + " is revoked");
        }
        final Filed filed =
                projects()
                        .make(
                                machine,
                                java.util.Objects.requireNonNull(scope.project),
                                own,
                                () -> keygen(machine, own.login()),
                                base,
                                id);
        kept = true;
        return made(filed.fingerprint(), filed.revision(), true);
    }

    /** Replaces the key a project's backup key backs. */
    private int replaceForProject(final PoreloContext machine, final PersonCard own)
            throws IdentityChange.Refused, IOException {
        final Path file = java.util.Objects.requireNonNull(use);
        final String key = backupKey(file);
        final PersonCard next = next(own.login());
        final List<IdentityFacts.Registry> registries = IdentityFacts.registries(machine);
        for (final String each : List.of(key, next.key())) {
            if (IdentityFacts.revoked(machine, registries, each)) {
                throw new IdentityChange.Refused(
                        65, "revoked", "the key " + PersonCard.fingerprint(each) + " is revoked");
            }
        }
        final Replaced replaced =
                projects()
                        .replace(
                                machine,
                                java.util.Objects.requireNonNull(scope.project),
                                file,
                                key,
                                next,
                                base,
                                java.util.Objects.requireNonNull(id));
        return replaced(replaced.replaced(), replaced.by(), replaced.revision(), true);
    }

    private Projects projects() {
        return java.util.Objects.requireNonNull(
                projects, "the command line is assembled with the project area");
    }

    /**
     * Makes the backup key's file on the person's terminal, and answers its public half; refused
     * over a file that exists and without a terminal.
     */
    private String keygen(final PoreloContext machine, final String login)
            throws IdentityChange.Refused, IOException {
        final Path file = java.util.Objects.requireNonNull(out).toAbsolutePath();
        // ssh-keygen writes both halves, and the public one is removed once read.
        for (final Path half : List.of(file, Path.of(file + ".pub"))) {
            if (Files.exists(half, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IdentityChange.Refused(65, "file-exists", half + " exists already");
            }
        }
        if (!terminal.getAsBoolean()) {
            throw new IdentityChange.Refused(
                    65, "no-terminal", "the backup key's passphrase is asked on a terminal");
        }
        // ssh-keygen asks the passphrase itself, twice, and writes the file with mode 0600.
        final int made =
                machine.exec()
                        .applyAsInt(
                                List.of(
                                        "ssh-keygen",
                                        "-q",
                                        "-t",
                                        "ed25519",
                                        "-C",
                                        "porelo backup key of " + login,
                                        "-f",
                                        file.toString()));
        final Path pub = Path.of(file + ".pub");
        written = Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (made != 0 || !Files.isRegularFile(file)) {
            Files.deleteIfExists(pub);
            throw new IdentityChange.Refused(
                    65, null, "ssh-keygen made no key (exit " + made + ")");
        }
        try {
            return OpenSshPrivateKey.publicKey(Files.readString(file));
        } catch (VaultException ex) {
            throw new IdentityChange.Refused(65, null, file + ": " + ex.getMessage());
        } finally {
            // Only the file is the person's to keep; its public half is filed.
            Files.deleteIfExists(pub);
        }
    }

    private int made(final String fingerprint, final String revision, final boolean pushed) {
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("id", id);
        answer.put("scope", scope());
        answer.put("fingerprint", fingerprint);
        answer.put("file", java.util.Objects.requireNonNull(out).toAbsolutePath().toString());
        answer.put("revision", revision);
        answer.put("pushed", pushed);
        return answer(answer, "filed backup key " + fingerprint + " in " + revision);
    }

    private int replaced(
            final String old, final String by, final String revision, final boolean pushed) {
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("id", id);
        answer.put("scope", scope());
        answer.put("revision", revision);
        answer.put("pushed", pushed);
        answer.put("replaced", old);
        answer.put("by", by);
        return answer(answer, "replaced " + old + " by " + by + " in " + revision);
    }

    /** The public half of the backup key's file; its secret part stays in the file. */
    private static String backupKey(final Path file) throws IdentityChange.Refused, IOException {
        try {
            return OpenSshPrivateKey.publicKey(Files.readString(file));
        } catch (VaultException ex) {
            throw new IdentityChange.Refused(65, "not-the-backup", file + ": " + ex.getMessage());
        }
    }

    /** The new card, of the person's own login and signed as a card must be. */
    private PersonCard next(final String login) throws IdentityChange.Refused, IOException {
        final PersonCard next;
        try {
            next =
                    PersonCard.read(
                            Files.readString(java.util.Objects.requireNonNull(card)), "--card");
        } catch (CardException ex) {
            throw new IdentityChange.Refused(65, "card-login", String.valueOf(ex.getMessage()));
        }
        if (!next.login().equals(login) || !next.signedAsItMustBe()) {
            throw new IdentityChange.Refused(
                    65,
                    "card-login",
                    "the new card is not a card of " + login + " signed as it must be");
        }
        return next;
    }

    private int replace(
            final PoreloContext machine,
            final IdentityFacts.Registry registry,
            final RegistryState state,
            final List<RegistryState.Owner> entries,
            final String login,
            final String head)
            throws IdentityChange.Refused, IOException {
        final Path file = java.util.Objects.requireNonNull(use);
        final String key = backupKey(file);
        final String backup = PersonCard.fingerprint(key);
        if (entries.stream()
                .noneMatch(
                        entry ->
                                entry.backup()
                                        && entry.login().equals(login)
                                        && entry.fingerprint().equals(backup))) {
            throw new IdentityChange.Refused(
                    65,
                    "not-the-backup",
                    "the key in " + file + " is not " + login + "'s backup key for " + where());
        }
        if (state.revoked().contains(backup)
                || IdentityFacts.revoked(machine, List.of(registry), key)) {
            throw new IdentityChange.Refused(
                    65, "revoked", "the backup key " + backup + " is revoked");
        }
        final PersonCard next = next(login);
        if (state.revoked().contains(next.fingerprint())
                || IdentityFacts.revoked(machine, List.of(registry), next.key())) {
            throw new IdentityChange.Refused(
                    65, "revoked", "the new card's key " + next.fingerprint() + " is revoked");
        }
        final RegistryState.Owner old =
                entries.stream()
                        .filter(entry -> !entry.backup() && entry.login().equals(login))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IdentityChange.Refused(
                                                65,
                                                "not-the-backup",
                                                login + " holds no key for " + where()));
        final RegistryState.Owner replacement =
                new RegistryState.Owner(login, next.key(), next.fingerprint(), false);
        final String revision =
                file(
                        machine,
                        registry,
                        with(state.owners(), state.hostAdmins(), entries, replacement, old),
                        file,
                        head);
        return replaced(old.fingerprint(), next.fingerprint(), revision, false);
    }

    /**
     * Whether the owners file at the checkout's {@code HEAD} names the key, or cannot be read, so
     * that the key may be filed and its file is not removed.
     */
    private static boolean filedAtHead(
            final PoreloContext machine, final IdentityFacts.Registry registry, final String key) {
        try {
            return ownersAt(machine, registry, head(machine, registry)).contains(key);
        } catch (IdentityChange.Refused ex) {
            return true;
        }
    }

    /**
     * That owners file with the entry put in, in place of the one given, or after the backed one.
     */
    private String with(
            final String text,
            final List<RegistryState.Owner> entries,
            final RegistryState.Owner entry,
            final RegistryState.@Nullable Owner instead)
            throws IdentityChange.Refused {
        try {
            return with(
                    org.fuin.porelo.vault.RegistryFiles.entries(text, "owners.yml", null),
                    org.fuin.porelo.vault.RegistryFiles.hostAdmins(text, "owners.yml"),
                    entries,
                    entry,
                    instead);
        } catch (CardException ex) {
            throw new IdentityChange.Refused(65, null, String.valueOf(ex.getMessage()));
        }
    }

    /**
     * The owners file with the entry put in, in place of the one given, or after the backed one.
     */
    private String with(
            final List<RegistryState.Owner> owners,
            final Map<String, List<RegistryState.Owner>> hostAdmins,
            final List<RegistryState.Owner> entries,
            final RegistryState.Owner entry,
            final RegistryState.@Nullable Owner instead) {
        final List<RegistryState.Owner> changed = new ArrayList<>();
        for (RegistryState.Owner each : entries) {
            changed.add(each.equals(instead) ? entry : each);
            if (instead == null && !each.backup() && each.login().equals(entry.login())) {
                changed.add(entry);
            }
        }
        if (scope.registry) {
            return RegistryState.ownersFile(changed, hostAdmins);
        }
        final Map<String, List<RegistryState.Owner>> hosts = new LinkedHashMap<>(hostAdmins);
        hosts.put(java.util.Objects.requireNonNull(scope.host), changed);
        return RegistryState.ownersFile(owners, hosts);
    }

    /** Files the owners file as {@code registry file owners} does, and answers its commit. */
    private String file(
            final PoreloContext machine,
            final IdentityFacts.Registry registry,
            final String text,
            final @Nullable Path signingKey,
            final @Nullable String against)
            throws IdentityChange.Refused, IOException {
        final Path scratch = Files.createTempDirectory("porelo-backup-key-");
        try {
            final Path owners = Files.writeString(scratch.resolve("owners.yml"), text);
            final RegistryFileCommand command = new RegistryFileCommand();
            command.setContext(machine);
            command.forges = forges;
            final StringWriter said = new StringWriter();
            final StringWriter err = new StringWriter();
            final CommandLine cli = new CommandLine(command);
            cli.setOut(new PrintWriter(said));
            cli.setErr(new PrintWriter(err));
            final List<String> args =
                    new ArrayList<>(
                            List.of(
                                    "owners",
                                    registry.known().checkout().toString(),
                                    "--file",
                                    owners.toString(),
                                    "--json"));
            if (against != null) {
                args.addAll(List.of("--base", against));
            }
            if (id != null) {
                args.addAll(List.of("--id", id));
            }
            if (signingKey != null) {
                args.addAll(List.of("--signing-key-file", signingKey.toAbsolutePath().toString()));
            }
            final int code = cli.execute(args.toArray(String[]::new));
            if (code != 0) {
                final String words =
                        err.toString()
                                .lines()
                                .filter(line -> !line.startsWith("porelo: warning"))
                                .findFirst()
                                .orElse("registry file owners ended with " + code);
                final String why = words.startsWith("porelo: ") ? words.substring(8) : words;
                throw new IdentityChange.Refused(
                        code,
                        code == 65 && why.contains("HEAD is")
                                ? "base-moved"
                                : code == 65 && signingKey != null && why.contains("commit-tree")
                                        ? "wrong-passphrase"
                                        : code == 65
                                                        && signingKey == null
                                                        && why.contains("ssh-agent")
                                                ? "not-signer"
                                                : null,
                        why);
            }
            return Json.parse(said.toString().strip()) instanceof Map<?, ?> filed
                    ? String.valueOf(filed.get("commit"))
                    : "";
        } finally {
            try (var files = Files.walk(scratch)) {
                for (Path each : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(each);
                }
            }
        }
    }

    /** The one registry this machine reads; with none or several, which is meant is not known. */
    private static IdentityFacts.Registry registry(final PoreloContext machine)
            throws IdentityChange.Refused, IOException {
        final List<IdentityFacts.Registry> registries = IdentityFacts.registries(machine);
        if (registries.size() != 1) {
            throw new IdentityChange.Refused(
                    66,
                    null,
                    registries.isEmpty()
                            ? "no registry is read here; read it with registry read"
                            : "several registries are read here; a backup key is made for one");
        }
        return registries.getFirst();
    }

    private Map<String, @Nullable Object> scope() {
        final Map<String, @Nullable Object> named = new LinkedHashMap<>();
        named.put("kind", scope.registry ? "registry" : scope.project != null ? "project" : "host");
        named.put(
                "name", scope.registry ? null : scope.project != null ? scope.project : scope.host);
        return named;
    }

    private String where() {
        return scope.registry ? "the registry" : "host " + scope.host;
    }

    private int answer(final Map<String, @Nullable Object> answer, final String text) {
        final PrintWriter said = spec.commandLine().getOut();
        said.println(json ? Json.write(RegistryVerifyCommand.printable(answer)) : text);
        said.flush();
        return 0;
    }
}
