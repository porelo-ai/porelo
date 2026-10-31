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
 * A second, offline key for the one owner of a registry or the one admin of a host: made once into
 * a file the person keeps away from their daily agent, and filed into {@code owners.yml} beside the
 * key it backs. With {@code --use} the file signs the one change it may make, the key it backs
 * replaced by a new card's.
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
                "Makes an offline backup key for a registry or host you alone hold, or uses one to"
                        + " replace the key it backs.")
public class IdentityBackupKeyCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";

    /** How far back an id is looked for. */
    private static final int SEARCHED = 1000;

    /** Where the backup key goes: exactly one. */
    static final class Scope {
        @Option(
                names = "--project",
                paramLabel = "PROJECT",
                description = "A project you are the one admin of.")
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
            if (scope.project != null) {
                throw new IdentityChange.Refused(
                        65,
                        "not-in-this-release",
                        "a project's backup key is not made here yet; a project admin's key is"
                                + " replaced by another admin with project access replace");
            }
            final PersonCard own = new IdentityChange(machine, forges, IdentityChange.FORGE).card();
            final IdentityFacts.Registry registry = registry(machine);
            final String login = own.login();
            final String head = head(machine, registry);
            final String[] earlier = id == null ? null : earlier(machine, registry, head);
            if (earlier != null) {
                return use == null
                        ? madeEarlier(machine, registry, login, earlier[0])
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
                    ? make(machine, registry, state, entries, login, head)
                    : replace(machine, registry, state, entries, login, head);
        } catch (IdentityChange.Refused ex) {
            return IdentityChange.refuse(spec, json, ex);
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

    /** Answers the backup key an earlier call filed under this id, as that call answered it. */
    private int madeEarlier(
            final PoreloContext machine,
            final IdentityFacts.Registry registry,
            final String login,
            final String commit)
            throws IdentityChange.Refused, IOException {
        final String text = ownersAt(machine, registry, commit);
        // Filed again under the same id with the same file, the registry answers that commit.
        final String revision = file(machine, registry, text, null, null);
        final RegistryState.Owner backup =
                filedEntries(text).stream()
                        .filter(entry -> entry.backup() && entry.login().equals(login))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IdentityChange.Refused(
                                                65,
                                                null,
                                                "--id "
                                                        + id
                                                        + " names "
                                                        + commit
                                                        + ", which files no backup key of "
                                                        + login));
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
        final String text = ownersAt(machine, registry, commit);
        final String revision =
                file(machine, registry, text, java.util.Objects.requireNonNull(use), null);
        final PersonCard next = next(login);
        final String old = ownKey(filedEntries(ownersAt(machine, registry, parent)), login);
        final String by = ownKey(filedEntries(text), login);
        if (old.isEmpty() || !by.equals(next.fingerprint())) {
            throw new IdentityChange.Refused(
                    65,
                    null,
                    "--id " + id + " names " + commit + ", which replaces no key by this card's");
        }
        return replaced(old, by, revision, false);
    }

    private static String ownKey(final List<RegistryState.Owner> entries, final String login) {
        return entries.stream()
                .filter(entry -> !entry.backup() && entry.login().equals(login))
                .map(RegistryState.Owner::fingerprint)
                .findFirst()
                .orElse("");
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
            final String login,
            final String head)
            throws IdentityChange.Refused, IOException {
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
        final String revision =
                file(machine, registry, with(state, entries, backup, null), null, head);
        kept = true;
        return made(backup.fingerprint(), revision, false);
    }

    /**
     * Makes the backup key's file on the person's terminal, and answers its public half; refused
     * over a file that exists and without a terminal.
     */
    private String keygen(final PoreloContext machine, final String login)
            throws IdentityChange.Refused, IOException {
        final Path file = java.util.Objects.requireNonNull(out).toAbsolutePath();
        if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IdentityChange.Refused(65, "file-exists", file + " exists already");
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
                file(machine, registry, with(state, entries, replacement, old), file, head);
        return replaced(old.fingerprint(), next.fingerprint(), revision, false);
    }

    /**
     * The owners file with the entry put in, in place of the one given, or after the backed one.
     */
    private String with(
            final RegistryState state,
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
            return RegistryState.ownersFile(changed, state.hostAdmins());
        }
        final Map<String, List<RegistryState.Owner>> hosts =
                new LinkedHashMap<>(state.hostAdmins());
        hosts.put(java.util.Objects.requireNonNull(scope.host), changed);
        return RegistryState.ownersFile(state.owners(), hosts);
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
        named.put("kind", scope.registry ? "registry" : "host");
        named.put("name", scope.registry ? null : scope.host);
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
