package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.project.Access;
import org.fuin.porelo.core.project.AccessFile;
import org.fuin.porelo.core.project.BranchScheme;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.vault.AccessPersons;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.TrustRecords;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Writes one change to a followed project's access file, in its access repository, as a commit
 * signed through the caller's own ssh agent by a key the project's signers hold.
 *
 * <p>An interface asks for a change and never writes {@code access.yml} or {@code cards/} itself:
 * this clones the repository into a scratch directory, checks its history since the access commit
 * in force here as the follow checks it, makes the change there, checks that the result reads and
 * verifies as the follow takes it, commits, and pushes only onto the head it read. Nothing of the
 * clone is kept. The private key never leaves the agent; git asks the agent for the signature. No
 * git it runs reads the caller's git configuration, hooks or {@code GIT_*} environment.
 *
 * <p>The change is named by the caller's {@code --id}, carried in the commit as a trailer, so a
 * caller that lost the answer asks again and is answered the commit already made.
 */
@picocli.CommandLine.Command(
        name = "access",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Writes a person's access to a followed project into its access repository, signed"
                        + " through your ssh agent by a project signer's key.")
public class ProjectAccessCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** The id's shape, so it is one trailer value and one word in every message. */
    private static final String ID = "[A-Za-z0-9._-]{1,64}";

    /** A login, as the access file's reader takes one. */
    private static final String LOGIN = "[a-z][a-z0-9-]{0,31}";

    /** A host name, as the access file's reader takes one. */
    private static final String HOST = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?";

    /** A commit's full hash. */
    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";

    /** How far back an id is looked for: a bound, so a long history costs no more than this. */
    private static final int SEARCHED = 1000;

    /** A card is a few lines and two signatures; anything larger is not one. */
    private static final int CARD_LIMIT = 64 * 1024;

    /** An access file of thousands of persons is still far under this. */
    private static final int ACCESS_LIMIT = 1024 * 1024;

    /** How many versions of a card are read back: more changes of key than any person makes. */
    private static final int VERSIONS = 64;

    /**
     * The branch a clone of an empty repository is left on when the remote does not say which one
     * its HEAD names: a name no remote is asked for.
     */
    private static final String UNANSWERED = "porelo-head-unanswered";

    /**
     * What every git that reaches the access repository is told about the way there: no redirect
     * followed, so the commit goes only to the address the project names, and no transport but
     * those a follow reaches a repository by.
     */
    private static final List<String> TRANSPORT =
            List.of(
                    "-c",
                    "http.followRedirects=false",
                    "-c",
                    "protocol.allow=never",
                    "-c",
                    "protocol.file.allow=always",
                    "-c",
                    "protocol.git.allow=always",
                    "-c",
                    "protocol.http.allow=always",
                    "-c",
                    "protocol.https.allow=always",
                    "-c",
                    "protocol.ssh.allow=always");

    @Parameters(
            index = "0",
            paramLabel = "ACTION",
            description = "init, add, remove, lock, unlock or replace.")
    private String action = "";

    @Option(
            names = "--project",
            required = true,
            paramLabel = "NAME",
            description = "A project this machine follows, whose access file is in a repository.")
    private String projectName = "";

    @Option(
            names = "--person",
            required = true,
            paramLabel = "LOGIN",
            description = "The person's login, as their card names it.")
    private String person = "";

    @Option(
            names = "--host",
            paramLabel = "HOST",
            description =
                    "The host the person is given or taken off; required by init and add, and"
                            + " without it remove takes them off every host.")
    private @Nullable String host;

    @Option(
            names = "--card",
            paramLabel = "FILE",
            description =
                    "The person's card; required by init, and by add unless the person is in and"
                            + " the repository's card of theirs carries the key access.yml pins;"
                            + " required by replace, as the person's card of their new key.")
    private @Nullable Path card;

    @Option(
            names = "--role",
            paramLabel = "ROLE",
            description = "admin or developer, for a person add puts in; default developer.")
    private @Nullable String role;

    @Option(
            names = "--base",
            paramLabel = "COMMIT",
            description =
                    "The access commit the change was made against; refused when the repository's"
                            + " head is another. Required by every action but init.")
    private @Nullable String base;

    @Option(
            names = "--id",
            required = true,
            paramLabel = "ID",
            description =
                    "Names the change: asked again with the same change, it answers the same commit.")
    private String id = "";

    @Option(names = "--json", description = "Answers in JSON, for a program.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    /** The caller's environment, which every program run here starts from before it is chosen. */
    private Supplier<Map<String, String>> callerEnvironment = System::getenv;

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    /**
     * Sets the environment the caller is taken to have, so a test can hand it what a shell would.
     *
     * @param environment The caller's environment.
     */
    void setCallerEnvironment(final Supplier<Map<String, String>> environment) {
        this.callerEnvironment = environment;
    }

    /** A refusal and its exit code, said as one line. */
    private static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final int code;

        Refused(final int code, final String message) {
            super(message);
            this.code = code;
        }
    }

    /** A card given or found, and the digest that names it in the change. */
    private record Card(String text, PersonCard parsed, String digest) {}

    /** Where the access repository's head was, and the branch it is pushed to. */
    private record Remote(String head, String branch) {}

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        try {
            final String revision = printable(change());
            out.println(
                    json
                            ? org.fuin.porelo.wire.Json.write(Map.of("revision", revision))
                            : "revision " + revision);
            out.flush();
            return 0;
        } catch (final Refused refused) {
            err.println(said("porelo: " + refused.getMessage()));
            err.flush();
            return refused.code;
        } catch (final CommandException ex) {
            // A program that could not be run, or was stopped, says what it had read: that may be
            // anything the remote sent.
            err.println(said("porelo: " + ex.getMessage()));
            err.flush();
            return 70;
        }
    }

    private String change() throws Refused {
        checkUse();
        final @Nullable Card given = card == null ? null : card(card);
        final FollowedProjects.Followed followed = followed();
        final String url = accessRepository(followed.commit());
        final String digest = digest(given);
        final Path scratch;
        try {
            scratch = Files.createTempDirectory("porelo-access-");
        } catch (final IOException ex) {
            throw new Refused(70, "cannot make a scratch directory: " + ex.getMessage());
        }
        try {
            return change(scratch, url, followed.accessCommit(), given, digest);
        } finally {
            deleteTree(scratch);
        }
    }

    private String change(
            final Path scratch,
            final String url,
            final String inForce,
            final @Nullable Card given,
            final String digest)
            throws Refused {
        final Path repository = scratch.resolve("access");
        final SignerHandover handover =
                new SignerHandover(
                        this::run,
                        context.paths().projects().configurationSigners(),
                        krl(),
                        ignored -> {},
                        registryRevocations()::contains);
        final Remote remote = fetch(scratch, repository, url);
        followable(handover, repository, inForce, remote.head());
        final String made = madeBefore(handover, repository, remote.head(), digest);
        if (made != null) {
            return made;
        }
        if (action.equals("init")) {
            if (!remote.head().isEmpty() && present(repository, remote.head(), Access.FILE)) {
                throw new Refused(
                        65,
                        url
                                + " already holds "
                                + Access.FILE
                                + " at "
                                + remote.head()
                                + "; change it with add, remove, lock or unlock");
            }
            if (!remote.head().isEmpty() && inForce.isEmpty()) {
                // Nothing here vouches for what is there already, and init would sign on top of it.
                throw new Refused(
                        65,
                        url
                                + " holds "
                                + remote.head()
                                + ", and no access commit of "
                                + projectName
                                + " is in force here; init writes only into an empty access"
                                + " repository");
            }
        } else if (!remote.head().equals(base)) {
            throw new Refused(
                    65,
                    "the access repository is at "
                            + (remote.head().isEmpty() ? "no commit" : remote.head())
                            + ", and the change was made against --base "
                            + base
                            + "; read it again and ask anew");
        }
        final String key = signingKey(signers(handover, scratch));
        final AccessFile before =
                action.equals("init")
                        ? new AccessFile(null, Map.of(), List.of(), Map.of())
                        : current(repository, remote.head());
        final String cardPath = "cards/" + person + ".card";
        final @Nullable String cardBefore =
                remote.head().isEmpty()
                        ? null
                        : blob(repository, remote.head(), cardPath, CARD_LIMIT);
        final @Nullable Card cardAfter = cardAfter(before, given, cardBefore, cardPath);
        if (cardAfter != null) {
            unrevoked(before, cardAfter.parsed());
        }
        final Set<String> chain =
                action.equals("replace")
                        ? replacing(
                                handover,
                                scratch,
                                repository,
                                remote.head(),
                                before,
                                required(cardAfter))
                        : Set.of();
        final Map<String, AccessFile.Person> persons = changed(before, cardAfter);
        final List<String> revoked = revokedAfter(before, cardAfter, chain);
        final String text = write(before, persons, revoked);
        final AccessFile written;
        try {
            written = AccessFile.read(new StringReader(text), Access.FILE);
        } catch (final ProjectException ex) {
            throw new Refused(65, "the changed access file would not read: " + ex.getMessage());
        }
        // Compared as read, not as written: a file written by hand reads the same and is not
        // worth a commit that only reformats it.
        final @Nullable String cardWritten =
                given == null || given.text().equals(cardBefore) ? null : given.text();
        if (persons.equals(new TreeMap<>(before.persons()))
                && revoked.equals(before.revoked())
                && cardWritten == null) {
            throw new Refused(65, "nothing to change: " + said());
        }
        final String commit =
                commit(repository, remote.head(), key, text, cardWritten, cardPath, digest);
        // The commit made, checked as a follow will check it: one that follows for the first time
        // takes the tip only when it is signed by a key in force, and this machine's follow walks
        // from the access commit in force. A commit either refuses is never pushed.
        verifiable(handover, repository, "", commit);
        if (!inForce.isEmpty()) {
            verifiable(handover, repository, inForce, commit);
        }
        if (action.equals("replace")) {
            admitted(repository, commit, written);
        }
        push(repository, url, remote, commit);
        return commit;
    }

    private void verifiable(
            final SignerHandover handover,
            final Path repository,
            final String from,
            final String commit)
            throws Refused {
        final SignerHandover.Handed checked =
                handover.access(repository, projectName, from, commit);
        if (checked.refused() != null) {
            throw new Refused(
                    65,
                    "the follow would refuse the access commit made, so nothing was pushed: "
                            + checked.refused().detail());
        }
    }

    /**
     * Refuses a replaced key the follow would not take for the person at the commit made, checked
     * as the follow checks each person's card, so a replacement that leaves them revoked is never
     * pushed.
     */
    private void admitted(final Path repository, final String commit, final AccessFile written)
            throws Refused {
        for (final AccessPersons.Person checked :
                AccessPersons.read(this::run, repository, commit, written)) {
            if (checked.login().equals(person) && checked.revoked()) {
                throw new Refused(
                        65,
                        "the follow would answer "
                                + person
                                + " revoked at the access commit made, so nothing was pushed: "
                                + checked.refusal());
            }
        }
    }

    /** Refuses a use the action has no meaning for, before anything is read or fetched. */
    private void checkUse() throws Refused {
        if (!Set.of("init", "add", "remove", "lock", "unlock", "replace").contains(action)) {
            throw new Refused(
                    64,
                    "'"
                            + action
                            + "' is not an action; expected init, add, remove, lock, unlock or"
                            + " replace");
        }
        if (!id.matches(ID)) {
            throw new Refused(64, "--id '" + id + "' does not match " + ID);
        }
        if (!person.matches(LOGIN) || person.equals("porelo") || person.startsWith("porelo-")) {
            throw new Refused(
                    64,
                    "--person '"
                            + person
                            + "' is not a card login; expected "
                            + LOGIN
                            + ", excluding porelo and porelo-*");
        }
        if (host != null && !host.matches(HOST)) {
            throw new Refused(64, "--host '" + host + "' is not a host name; expected " + HOST);
        }
        if (role != null && !Set.of("admin", "developer").contains(role)) {
            throw new Refused(
                    64, "--role '" + role + "' is not a role; expected admin or developer");
        }
        if (base != null && !base.matches(COMMIT)) {
            throw new Refused(64, "--base '" + base + "' is not a commit's full hash");
        }
        final boolean init = action.equals("init");
        if (init && base != null) {
            throw new Refused(64, "init takes no --base: it makes the first access file");
        }
        if (!init && base == null) {
            throw new Refused(64, action + " needs --base, the access commit it was made against");
        }
        if ((init || action.equals("add")) && host == null) {
            throw new Refused(64, action + " needs --host");
        }
        if (init && card == null) {
            throw new Refused(64, "init needs --card, the first person's card");
        }
        if (init && role != null) {
            throw new Refused(64, "init takes no --role: its person is the project's admin");
        }
        final boolean replace = action.equals("replace");
        if (replace && card == null) {
            throw new Refused(64, "replace needs --card, the person's card of their new key");
        }
        if (replace && (host != null || role != null)) {
            throw new Refused(
                    64, "replace takes neither --host nor --role: the person keeps theirs");
        }
        if (!init && !action.equals("add") && !replace && (card != null || role != null)) {
            throw new Refused(64, action + " takes neither --card nor --role");
        }
        if ((action.equals("lock") || action.equals("unlock")) && host != null) {
            throw new Refused(64, action + " takes no --host: a lock holds on every host");
        }
    }

    /**
     * Reads a card as the follow will check it, so a card it would revoke is never written: one
     * open that follows no link, a bound on what is read, and the same file before and after.
     */
    private Card card(final Path file) throws Refused {
        final byte[] bytes;
        try {
            final BasicFileAttributes seen =
                    Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!seen.isRegularFile()) {
                throw new Refused(66, "there is no card file at " + file);
            }
            try (SeekableByteChannel channel =
                            Files.newByteChannel(
                                    file,
                                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    InputStream in = Channels.newInputStream(channel)) {
                bytes = in.readNBytes(CARD_LIMIT + 1);
            }
            // Java cannot ask an open channel what it is; the file the path names after the read
            // is asked instead, and must be the one seen before it.
            final BasicFileAttributes read =
                    Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!read.isRegularFile()
                    || seen.fileKey() == null
                    || !seen.fileKey().equals(read.fileKey())) {
                throw new Refused(66, file + " changed while it was read");
            }
        } catch (final NoSuchFileException ex) {
            throw new Refused(66, "there is no card file at " + file);
        } catch (final IOException ex) {
            throw new Refused(66, "cannot read " + file);
        }
        if (bytes.length > CARD_LIMIT) {
            throw new Refused(65, file + " is larger than a card");
        }
        final String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
        } catch (final CharacterCodingException ex) {
            throw new Refused(65, file + " is not a card: it is not UTF-8");
        }
        return parsed(text, file.toString());
    }

    private Card parsed(final String text, final String origin) throws Refused {
        final PersonCard parsed;
        try {
            parsed = PersonCard.read(text, origin);
        } catch (final CardException ex) {
            throw new Refused(65, String.valueOf(ex.getMessage()));
        }
        if (!parsed.login().equals(person)) {
            throw new Refused(
                    65, origin + " is the card of " + parsed.login() + ", not of " + person);
        }
        if (!parsed.signedAsItMustBe()) {
            throw new Refused(65, origin + " is not signed as a card must be");
        }
        return new Card(text, parsed, sha256(text));
    }

    /** Refuses a card the access file revokes, by its key or the key it replaces. */
    private static void unrevoked(final AccessFile before, final PersonCard card) throws Refused {
        final String fingerprint = card.fingerprint();
        final String replaced =
                card.predecessor().isEmpty() ? "" : PersonCard.fingerprint(card.predecessor());
        for (final String revoked : List.of(fingerprint, replaced)) {
            if (!revoked.isEmpty() && before.revoked().contains(revoked)) {
                throw new Refused(
                        65,
                        "the card of "
                                + card.login()
                                + " carries "
                                + revoked
                                + ", which "
                                + Access.FILE
                                + " lists under revoked");
            }
        }
    }

    /**
     * Refuses a replacement the follow would take but should not: of a person the access file
     * already answers revoked, to a key another person holds or held, or to or from a key revoked
     * here or by a registry this machine reads.
     *
     * @return The keys of the person's chain at the base: the key pinned, and each key and
     *     predecessor of each version of their card, which a lost key's replacement revokes.
     */
    private Set<String> replacing(
            final SignerHandover handover,
            final Path scratch,
            final Path repository,
            final String base,
            final AccessFile before,
            final Card card)
            throws Refused {
        final PersonCard next = card.parsed();
        final AccessFile.@Nullable Person was = before.persons().get(person);
        if (was == null) {
            throw new Refused(65, person + " is not a person of " + projectName);
        }
        // A rotation from a key in a revoked chain would pin its successor, and the follow then
        // looks no further back than the key pinned.
        for (final AccessPersons.Person checked :
                AccessPersons.read(this::run, repository, base, before)) {
            if (checked.login().equals(person) && checked.revoked()) {
                throw new Refused(
                        65,
                        person
                                + " is revoked at --base "
                                + base
                                + " ("
                                + checked.refusal()
                                + "); a replacement does not take them back in");
            }
        }
        final List<String> keys = new ArrayList<>(List.of(next.key()));
        if (!next.predecessor().isEmpty()) {
            keys.add(next.predecessor());
        }
        for (final String key : keys) {
            final String fingerprint = PersonCard.fingerprint(key);
            if (revoked(scratch, key) || revokedByRegistry(fingerprint)) {
                throw new Refused(
                        65,
                        "the card of "
                                + person
                                + " carries "
                                + fingerprint
                                + ", which is revoked here or by a registry this machine reads");
            }
        }
        final Map<String, Set<String>> pins = pins(handover, repository, base);
        for (final AccessFile.Person other : before.persons().values()) {
            if (!other.login().equals(person)
                    && chain(repository, base, other, pins, false).contains(next.fingerprint())) {
                // One key under two logins lets its holder work as either.
                throw new Refused(
                        65,
                        next.fingerprint()
                                + " is a key of "
                                + other.login()
                                + ", pinned, on their card or in its history; a key is one"
                                + " person's");
            }
        }
        return chain(repository, base, was, pins, true);
    }

    /**
     * Each key the access file has pinned for each login along the first-parent line to the commit,
     * the last {@value #SEARCHED} versions of it. A version pins only when its commit is signed as
     * the follow checks a signature: anybody who can push may have left an unsigned one naming
     * another person's key under this login. One that does not read pins nothing.
     */
    private Map<String, Set<String>> pins(
            final SignerHandover handover, final Path repository, final String commit)
            throws Refused {
        final Map<String, Set<String>> pins = new TreeMap<>();
        for (final String version : versions(repository, commit, Access.FILE, SEARCHED, true)) {
            if (!signed(handover, repository, version)) {
                continue;
            }
            final String text = historical(repository, version, Access.FILE, ACCESS_LIMIT);
            if (text == null) {
                continue;
            }
            final AccessFile file;
            try {
                file = AccessFile.read(new StringReader(text), Access.FILE);
            } catch (final ProjectException ex) {
                continue;
            }
            for (final AccessFile.Person pinned : file.persons().values()) {
                pins.computeIfAbsent(pinned.login(), login -> new java.util.LinkedHashSet<>())
                        .add(pinned.fingerprint());
            }
        }
        return pins;
    }

    /**
     * The keys of a person's chain at the commit: each key the access file has pinned for them, and
     * each key a version of their card hands on to from one. A version counts only when it reads,
     * is signed as a card must be, is the person's, and its predecessor is a key of the chain
     * already: anybody who can push may have put a card there, and one that is not the person's own
     * change of key names no key of theirs. A version that is no regular file, too large or
     * unreadable names none either.
     *
     * @param strict Whether more versions than are read back refuse, as for the person replaced,
     *     whose chain a lost key's replacement revokes whole; another person's chain is read from
     *     their latest versions.
     */
    private Set<String> chain(
            final Path repository,
            final String commit,
            final AccessFile.Person who,
            final Map<String, Set<String>> pins,
            final boolean strict)
            throws Refused {
        final Set<String> keys = new java.util.LinkedHashSet<>();
        keys.add(who.fingerprint());
        keys.addAll(pins.getOrDefault(who.login(), Set.of()));
        final List<PersonCard> changes = new ArrayList<>();
        final String path = "cards/" + who.login() + ".card";
        List<String> versions = versions(repository, commit, path, VERSIONS + 1, false);
        if (versions.size() > VERSIONS) {
            if (strict) {
                throw new Refused(
                        65,
                        path
                                + " has more than "
                                + VERSIONS
                                + " versions at "
                                + commit
                                + ", the most a replacement of "
                                + who.login()
                                + " reads back");
            }
            versions = versions.subList(0, VERSIONS);
        }
        for (final String version : versions) {
            final String text = historical(repository, version, path, CARD_LIMIT);
            if (text == null) {
                continue;
            }
            try {
                final PersonCard card = PersonCard.read(text, path);
                if (card.login().equals(who.login())
                        && !card.predecessor().isEmpty()
                        && card.signedAsItMustBe()) {
                    changes.add(card);
                }
            } catch (final CardException ex) {
                // A version that does not read as a card names no key.
            }
        }
        // Walked forward from the pins until no change of key adds one; each pass adds a key or
        // ends, so it ends within as many passes as there are versions.
        boolean grew = true;
        while (grew) {
            grew = false;
            for (final PersonCard change : changes) {
                if (keys.contains(PersonCard.fingerprint(change.predecessor()))
                        && keys.add(change.fingerprint())) {
                    grew = true;
                }
            }
        }
        return keys;
    }

    /** The commits, newest first and at most so many, that changed a path up to the commit. */
    private List<String> versions(
            final Path repository,
            final String commit,
            final String path,
            final int most,
            final boolean firstParent)
            throws Refused {
        final List<String> arguments =
                new ArrayList<>(List.of("log", "--max-count=" + most, "--format=%H"));
        arguments.add(firstParent ? "--first-parent" : "--full-history");
        arguments.addAll(List.of(commit, "--", ":(literal)" + path));
        final List<String> versions =
                local(repository, arguments.toArray(String[]::new))
                        .lines()
                        .filter(line -> !line.isEmpty())
                        .toList();
        for (final String version : versions) {
            if (!version.matches(COMMIT)) {
                throw new Refused(70, "git log named no commit in the scratch clone");
            }
        }
        return versions;
    }

    /**
     * A file at a past commit when it is a regular file there, no larger than the limit, and reads;
     * otherwise none, since a past version refuses nothing now.
     */
    private @Nullable String historical(
            final Path repository, final String commit, final String path, final int limit) {
        final CommandResult entry =
                run(
                        Command.of(
                                "git",
                                "-C",
                                repository.toString(),
                                "ls-tree",
                                "-z",
                                "--long",
                                commit,
                                "--",
                                ":(literal)" + path));
        final String[] fields = entry.standardOutput().split("[ \\t]+", 5);
        if (!entry.successful()
                || fields.length < 5
                || !(fields[0].equals("100644") || fields[0].equals("100755"))
                || !fields[1].equals("blob")
                || !fields[3].matches("[0-9]{1,18}")
                || Long.parseLong(fields[3]) > limit) {
            return null;
        }
        final CommandResult shown =
                run(Command.of("git", "-C", repository.toString(), "cat-file", "blob", fields[2]));
        return shown.successful() ? shown.standardOutput() : null;
    }

    /**
     * Whether a registry this machine read names the key revoked. Trust records that do not read
     * refuse, as one of them may be the one that does.
     */
    private boolean revokedByRegistry(final String fingerprint) throws Refused {
        return registryRevocations().contains(fingerprint);
    }

    /** The keys the registries this machine read revoke, by fingerprint. */
    private Set<String> registryRevocations() throws Refused {
        final Set<String> revoked = new java.util.HashSet<>();
        try {
            for (final TrustRecords.Read read : TrustRecords.read(context.paths().xdg().state())) {
                revoked.addAll(read.state().revoked());
            }
        } catch (final IOException ex) {
            throw new Refused(66, "cannot read the registries read here: " + ex.getMessage());
        }
        return revoked;
    }

    /**
     * The follow this writes for: one that verifies what it takes and has a project commit in
     * force, as enroll asks of it.
     */
    private FollowedProjects.Followed followed() throws Refused {
        final FollowedProjects.@Nullable Followed followed;
        try {
            followed =
                    new FollowedProjects(context.paths().projects().followed()).find(projectName);
        } catch (final IOException ex) {
            throw new Refused(66, "cannot read what this machine follows: " + ex.getMessage());
        }
        if (followed == null) {
            throw new Refused(66, "this machine does not follow a project '" + projectName + "'");
        }
        if (followed.unverified()) {
            throw new Refused(
                    64,
                    "'"
                            + projectName
                            + "' is followed unverified, so nothing here says where its access"
                            + " repository is; follow it with --signed-by first");
        }
        if (followed.commit().isBlank()) {
            throw new Refused(66, "nothing of " + projectName + " is in force here yet");
        }
        return followed;
    }

    /**
     * The access repository the project commit in force names; the only place this writes. Read
     * from that commit as the follow reads it, a snippet file it names included, never from the
     * clone's working tree or the directory this runs in.
     */
    private String accessRepository(final String commit) throws Refused {
        final Path clone = context.paths().projects().followedClone(projectName);
        final Project project;
        try {
            project = Reconcile.readAt(this::run, clone, commit, clone.resolve("project.yml"));
        } catch (final ProjectException ex) {
            throw new Refused(65, String.valueOf(ex.getMessage()));
        }
        return switch (project.access()) {
            case Access.InRepository repository -> repository.url();
            case Access.BesideProject beside ->
                    throw new Refused(
                            65,
                            "the access file of "
                                    + projectName
                                    + " lies beside project.yml; this writes only an access"
                                    + " repository, so change it with a signed project commit");
            case null ->
                    throw new Refused(
                            65, "the project file of " + projectName + " names no access file");
        };
    }

    /** The revocation list every signature check here uses, as the follow uses it. */
    private @Nullable Path krl() {
        final Path file = context.paths().projects().revocations();
        return Files.notExists(file, LinkOption.NOFOLLOW_LINKS) ? null : file;
    }

    /**
     * The keys an access commit may be signed by, as the follow takes them from the pinned file:
     * those of this project's principal alone, not retired, not in the revocation list and revoked
     * by no registry read here. What a line's options allow is not judged here; the commit made is
     * checked by the follow's own check, which judges them as the follow does, before it is pushed.
     */
    private List<String> signers(final SignerHandover handover, final Path scratch) throws Refused {
        final List<String> keys = new ArrayList<>();
        try {
            ConfigurationGate.requireRevocations(this::run, krl());
            for (final String key : handover.pinned(projectName)) {
                final String fingerprint = SignedBy.fingerprintOf(key);
                if (!keys.contains(key)
                        && !revoked(scratch, key)
                        && (fingerprint == null || !revokedByRegistry(fingerprint))) {
                    keys.add(key);
                }
            }
        } catch (final IOException | ProjectException ex) {
            throw new Refused(65, "cannot read the pinned signers: " + ex.getMessage());
        }
        if (keys.isEmpty()) {
            throw new Refused(
                    65,
                    "this machine pins no signer for "
                            + projectName
                            + ", and an access commit must be signed by one");
        }
        return keys;
    }

    private boolean revoked(final Path scratch, final String key) throws Refused {
        final Path krl = krl();
        if (krl == null) {
            return false;
        }
        final Path file = scratch.resolve("signer.pub");
        try {
            Files.writeString(file, key + "\n");
        } catch (final IOException ex) {
            throw new Refused(70, "cannot write a scratch file: " + ex.getMessage());
        }
        final CommandResult checked =
                run(
                        Command.of(
                                "ssh-keygen",
                                "-Q",
                                "-f",
                                krl.toAbsolutePath().toString(),
                                file.toString()));
        // ssh-keygen says 0 for a key not revoked and 1 for one that is.
        if (checked.exitCode() != 0 && checked.exitCode() != 1) {
            throw new Refused(65, "cannot read the revocation list " + krl);
        }
        return checked.exitCode() == 1;
    }

    /** The change as one canonical text, so the same change asked twice has the same digest. */
    private String digest(final @Nullable Card given) {
        return "sha256:"
                + sha256(
                        "action="
                                + action
                                + "\nperson="
                                + person
                                + "\nhost="
                                + (host == null ? "" : host)
                                + "\nrole="
                                + (role == null ? "" : role)
                                + "\ncard="
                                + (given == null ? "" : given.digest())
                                + "\n");
    }

    /**
     * Makes an empty repository, no template and so no hooks, and fetches the access repository's
     * head into it as a follow fetches it.
     */
    private Remote fetch(final Path scratch, final Path repository, final String url)
            throws Refused {
        final Path template;
        try {
            template = Files.createDirectory(scratch.resolve("template"));
        } catch (final IOException ex) {
            throw new Refused(70, "cannot make a scratch directory: " + ex.getMessage());
        }
        final CommandResult made =
                run(
                        Command.of(
                                "git",
                                "init",
                                "-q",
                                "--template=" + template,
                                repository.toString()));
        if (!made.successful()) {
            throw new Refused(70, "cannot make a scratch clone: " + made.standardError().strip());
        }
        final CommandResult listed =
                remote(repository, url, List.of("ls-remote", "--symref", "--end-of-options", url));
        if (!listed.successful()) {
            throw new Refused(66, "cannot reach " + url + ": " + listed.standardError().strip());
        }
        @Nullable String branch = null;
        boolean born = false;
        boolean refs = false;
        for (final String line : listed.standardOutput().lines().toList()) {
            if (line.startsWith("ref: ") && line.endsWith("\tHEAD")) {
                final String target = line.substring("ref: ".length(), line.length() - 5);
                if (!target.startsWith("refs/heads/")) {
                    throw new Refused(
                            65, "the HEAD of " + url + " names " + target + ", no branch");
                }
                branch = target.substring("refs/heads/".length());
            } else if (line.endsWith("\tHEAD")) {
                born = true;
            } else if (!line.isBlank()) {
                refs = true;
            }
        }
        if (!born && refs) {
            // Its HEAD names no commit, yet it holds some: whatever the commits are, no follow
            // fetches them, and the branch HEAD names would be begun beside them.
            throw new Refused(
                    65,
                    "the HEAD of "
                            + url
                            + " names no commit, and the repository holds other refs; point its"
                            + " HEAD at the branch the access file is on");
        }
        if (branch == null && !born) {
            // Nothing is listed at all, so the clone has nothing to transfer.
            branch = unborn(scratch, template, url);
        }
        if (branch == null) {
            throw new Refused(
                    65,
                    "the HEAD of "
                            + url
                            + " is detached; this writes only onto the branch HEAD names");
        }
        if (!BranchScheme.refFormat(branch)) {
            throw new Refused(
                    65, "the HEAD of " + url + " names " + branch + ", which is no branch name");
        }
        if (!born) {
            return new Remote("", branch);
        }
        final CommandResult fetched =
                remote(
                        repository,
                        url,
                        List.of("fetch", "--quiet", "--end-of-options", url, "HEAD"));
        if (!fetched.successful()) {
            throw new Refused(66, "cannot fetch " + url + ": " + fetched.standardError().strip());
        }
        return new Remote(
                local(repository, "rev-parse", "--verify", "FETCH_HEAD^{commit}"), branch);
    }

    /**
     * The branch an empty repository's HEAD names. ls-remote does not say it for a HEAD with no
     * commit; clone asks for it, and is left on the name the remote gave, or on one no remote names
     * when it gave none.
     */
    private String unborn(final Path scratch, final Path template, final String url)
            throws Refused {
        final Path probe = scratch.resolve("probe");
        final CommandResult cloned =
                remote(
                        scratch,
                        url,
                        List.of(
                                "-c",
                                "init.defaultBranch=" + UNANSWERED,
                                "clone",
                                "-q",
                                "--bare",
                                "--template=" + template,
                                "--",
                                url,
                                probe.toString()));
        if (!cloned.successful()) {
            throw new Refused(66, "cannot reach " + url + ": " + cloned.standardError().strip());
        }
        final String head = local(probe, "symbolic-ref", "HEAD");
        if (!head.startsWith("refs/heads/") || head.equals("refs/heads/" + UNANSWERED)) {
            throw new Refused(
                    65,
                    url
                            + " holds no commit and does not say which branch its HEAD names;"
                            + " give it one commit on its default branch first");
        }
        return head.substring("refs/heads/".length());
    }

    /**
     * Refuses a head the follow would not take: one that does not descend from the access commit in
     * force here, or one whose commits since it the follow's own check refuses. Nothing is read
     * from the head before this.
     */
    private void followable(
            final SignerHandover handover,
            final Path repository,
            final String inForce,
            final String head)
            throws Refused {
        if (head.isEmpty()) {
            if (!inForce.isEmpty()) {
                throw new Refused(
                        65,
                        "the access repository holds no commit, and access commit "
                                + inForce
                                + " is in force here; nothing was written");
            }
            return;
        }
        if (!inForce.isEmpty()
                && !run(Command.of(
                                "git",
                                "-C",
                                repository.toString(),
                                "merge-base",
                                "--is-ancestor",
                                inForce,
                                head))
                        .successful()) {
            throw new Refused(
                    65,
                    "the access repository's head "
                            + head
                            + " does not descend from access commit "
                            + inForce
                            + ", which is in force here; nothing was written");
        }
        final SignerHandover.Handed checked =
                handover.access(repository, projectName, inForce, head);
        if (checked.refused() != null) {
            throw new Refused(
                    65,
                    "the follow refuses the access repository at "
                            + head
                            + ": "
                            + checked.refused().detail()
                            + "; nothing was written");
        }
    }

    /**
     * The commit an earlier call made for this id, when it was this same change; a change under an
     * id already used for another is refused. A commit counts only when it is on the head's first
     * parents, signed as the follow checks a signature, carries the id and a digest once each, and
     * was made on the same {@code --base}.
     */
    private @Nullable String madeBefore(
            final SignerHandover handover,
            final Path repository,
            final String head,
            final String digest)
            throws Refused {
        if (head.isEmpty()) {
            return null;
        }
        final CommandResult log =
                run(
                        Command.of(
                                "git",
                                "-C",
                                repository.toString(),
                                "log",
                                "-z",
                                "--first-parent",
                                "--max-count=" + SEARCHED,
                                "--format=%H%x1F%P%x1F"
                                        + "%(trailers:key=Porelo-Change-Id,valueonly,unfold,"
                                        + "separator=%x1E)%x1F"
                                        + "%(trailers:key=Porelo-Change-Digest,valueonly,unfold,"
                                        + "separator=%x1E)",
                                head,
                                "--"));
        if (!log.successful()) {
            throw new Refused(
                    70, "git log failed in the scratch clone: " + log.standardError().strip());
        }
        final String place = base == null ? "" : base;
        for (final String entry : log.standardOutput().split("\0")) {
            final String[] fields = entry.split("\u001F", -1);
            if (fields.length != 4 || !fields[0].matches(COMMIT)) {
                continue;
            }
            final List<String> ids = values(fields[2]);
            final List<String> digests = values(fields[3]);
            final String parent = fields[1].isEmpty() ? "" : fields[1].split(" ")[0];
            if (!ids.equals(List.of(id))
                    || digests.size() != 1
                    || !parent.equals(place)
                    || !signed(handover, repository, fields[0])) {
                continue;
            }
            if (digests.getFirst().equals(digest)) {
                return fields[0];
            }
            throw new Refused(65, "--id " + id + " names another change: " + fields[0]);
        }
        return null;
    }

    private static List<String> values(final String field) {
        return field.isEmpty() ? List.of() : List.of(field.split("\u001E", -1));
    }

    /**
     * Whether a commit is signed by a key pinned for the project that no registry read here
     * revokes, as the follow checks it.
     */
    private boolean signed(
            final SignerHandover handover, final Path repository, final String commit)
            throws Refused {
        final Set<String> revoked = registryRevocations();
        final List<String> keys =
                handover.pinned(projectName).stream()
                        .filter(
                                key -> {
                                    final String fingerprint = SignedBy.fingerprintOf(key);
                                    return fingerprint == null || !revoked.contains(fingerprint);
                                })
                        .toList();
        try {
            return handover.verify(repository, projectName, keys, commit).mayApply();
        } catch (final ProjectException ex) {
            throw new Refused(65, String.valueOf(ex.getMessage()));
        }
    }

    /** The key the caller's agent holds that is one of the project's signers. */
    private String signingKey(final List<String> signers) throws Refused {
        final String socket = context.environment().apply("SSH_AUTH_SOCK");
        if (socket == null || socket.isBlank()) {
            throw new Refused(
                    65,
                    "no ssh agent: SSH_AUTH_SOCK is not set, and the access commit is signed"
                            + " through one by a signer of "
                            + projectName);
        }
        final CommandResult listed = run(Command.of("ssh-add", "-L"));
        if (listed.successful()) {
            for (final String line : listed.standardOutput().lines().toList()) {
                final String[] fields = line.strip().split("\\s+");
                if (fields.length >= 2 && signers.contains(fields[0] + " " + fields[1])) {
                    return fields[0] + " " + fields[1];
                }
            }
        } else if (listed.exitCode() != 1) {
            // ssh-add says 1 for an agent holding no key, and 2 for an agent it cannot reach.
            throw new Refused(65, "cannot reach the ssh agent at " + socket);
        }
        final List<String> fingerprints = new ArrayList<>();
        for (final String signer : signers) {
            final String fingerprint = SignedBy.fingerprintOf(signer);
            fingerprints.add(fingerprint == null ? signer : fingerprint);
        }
        throw new Refused(
                65,
                "the ssh agent holds no signer of "
                        + projectName
                        + "; add one of "
                        + String.join(", ", fingerprints));
    }

    private AccessFile current(final Path repository, final String head) throws Refused {
        final String text = blob(repository, head, Access.FILE, ACCESS_LIMIT);
        if (text == null) {
            throw new Refused(
                    65, "the access repository holds no " + Access.FILE + " yet; begin with init");
        }
        try {
            return AccessFile.read(new StringReader(text), Access.FILE);
        } catch (final ProjectException ex) {
            throw new Refused(65, String.valueOf(ex.getMessage()));
        }
    }

    /**
     * The card that names the person after the change: the one given, or for a person already in
     * the one the repository holds, when it carries the key the access file pins for them.
     */
    private @Nullable Card cardAfter(
            final AccessFile before,
            final @Nullable Card given,
            final @Nullable String held,
            final String path)
            throws Refused {
        if (given != null) {
            return given;
        }
        if (!action.equals("add")) {
            return null;
        }
        final AccessFile.Person was = before.persons().get(person);
        if (was == null) {
            // A card nobody pinned is only what somebody pushed; the admin vouches for it by
            // giving it.
            throw new Refused(
                    64, person + " is not a person of " + projectName + "; give their --card");
        }
        if (held == null) {
            throw new Refused(
                    64, "the repository holds no card of " + person + "; give it with --card");
        }
        final Card found = parsed(held, path);
        if (!found.parsed().fingerprint().equals(was.fingerprint())) {
            throw new Refused(
                    64,
                    path
                            + " carries "
                            + found.parsed().fingerprint()
                            + ", and "
                            + Access.FILE
                            + " pins "
                            + was.fingerprint()
                            + " for "
                            + person
                            + "; give their --card");
        }
        return found;
    }

    /** The persons after the change, sorted by login. */
    private Map<String, AccessFile.Person> changed(
            final AccessFile before, final @Nullable Card card) throws Refused {
        final Map<String, AccessFile.Person> persons = new TreeMap<>(before.persons());
        final AccessFile.Person was = persons.get(person);
        final String named = String.valueOf(host);
        switch (action) {
            case "init" ->
                    persons.put(
                            person,
                            new AccessFile.Person(
                                    person,
                                    required(card).parsed().fingerprint(),
                                    "admin",
                                    List.of(named),
                                    false));
            case "add" -> {
                final PersonCard pinned = required(card).parsed();
                if (was == null) {
                    persons.put(
                            person,
                            new AccessFile.Person(
                                    person,
                                    pinned.fingerprint(),
                                    role == null ? "developer" : role,
                                    List.of(named),
                                    false));
                } else {
                    if (role != null && !role.equals(was.role())) {
                        throw new Refused(
                                65,
                                person
                                        + " is already a person of "
                                        + projectName
                                        + " as "
                                        + was.role()
                                        + "; --role is for a person add puts in");
                    }
                    if (!pinned.fingerprint().equals(was.fingerprint())) {
                        throw new Refused(
                                65,
                                person
                                        + " is pinned to "
                                        + was.fingerprint()
                                        + ", and the card carries "
                                        + pinned.fingerprint()
                                        + "; add does not change a person's key");
                    }
                    final List<String> hosts = new ArrayList<>(was.hosts());
                    if (!hosts.contains(named)) {
                        hosts.add(named);
                    }
                    persons.put(
                            person,
                            new AccessFile.Person(
                                    person, was.fingerprint(), was.role(), hosts, was.locked()));
                }
            }
            case "remove" -> {
                final AccessFile.Person present = present(was);
                final List<String> hosts = new ArrayList<>(present.hosts());
                if (host != null && !hosts.remove(host)) {
                    throw new Refused(65, person + " is not on " + host);
                }
                if (host == null || hosts.isEmpty()) {
                    // Their card stays: its history is the chain a later key change is checked
                    // along.
                    persons.remove(person);
                } else {
                    persons.put(
                            person,
                            new AccessFile.Person(
                                    person,
                                    present.fingerprint(),
                                    present.role(),
                                    hosts,
                                    present.locked()));
                }
            }
            case "replace" -> {
                final AccessFile.Person present = present(was);
                final PersonCard next = required(card).parsed();
                replaceable(persons, present, next);
                persons.put(
                        person,
                        new AccessFile.Person(
                                person,
                                next.fingerprint(),
                                present.role(),
                                present.hosts(),
                                present.locked()));
            }
            default -> {
                final AccessFile.Person present = present(was);
                persons.put(
                        person,
                        new AccessFile.Person(
                                person,
                                present.fingerprint(),
                                present.role(),
                                present.hosts(),
                                action.equals("lock")));
            }
        }
        return persons;
    }

    /**
     * Refuses a card that is no replacement of the key pinned: the same key, a change of key from
     * another one, or a key another person is pinned to.
     */
    private void replaceable(
            final Map<String, AccessFile.Person> persons,
            final AccessFile.Person present,
            final PersonCard next)
            throws Refused {
        if (next.fingerprint().equals(present.fingerprint())) {
            throw new Refused(
                    65,
                    person
                            + " is pinned to "
                            + present.fingerprint()
                            + " already; replace takes a card of a new key, add a renewed card of"
                            + " the same one");
        }
        if (!next.predecessor().isEmpty()
                && !PersonCard.fingerprint(next.predecessor()).equals(present.fingerprint())) {
            throw new Refused(
                    65,
                    "the card of "
                            + person
                            + " replaces "
                            + PersonCard.fingerprint(next.predecessor())
                            + ", and "
                            + Access.FILE
                            + " pins "
                            + present.fingerprint()
                            + " for them");
        }
        for (final AccessFile.Person other : persons.values()) {
            if (!other.login().equals(person) && other.fingerprint().equals(next.fingerprint())) {
                // One key under two logins lets its holder work as either.
                throw new Refused(
                        65,
                        Access.FILE
                                + " pins "
                                + next.fingerprint()
                                + " for "
                                + other.login()
                                + "; a key is one person's");
            }
        }
    }

    /**
     * The revoked keys after the change. A lost key's replacement, a card with no predecessor, puts
     * every key of the person's chain under them: the key pinned, and the key of the card held and
     * those it replaced, any of which the finder of the lost one may hold. A change of key leaves
     * the old one out: the new card names it as its predecessor, and the follow revokes a person
     * whose card's predecessor is revoked.
     */
    private List<String> revokedAfter(
            final AccessFile before, final @Nullable Card card, final Set<String> chain) {
        final List<String> revoked = new ArrayList<>(before.revoked());
        if (action.equals("replace") && card != null && card.parsed().predecessor().isEmpty()) {
            for (final String key : chain) {
                if (!revoked.contains(key)) {
                    revoked.add(key);
                }
            }
        }
        return revoked;
    }

    private AccessFile.Person present(final AccessFile.@Nullable Person was) throws Refused {
        if (was == null) {
            throw new Refused(65, person + " is not a person of " + projectName);
        }
        return was;
    }

    private static Card required(final @Nullable Card card) {
        return java.util.Objects.requireNonNull(
                card, "init, add and replace are always given a card");
    }

    /**
     * The file as this writes it: the blocks in the documented order, persons by login, each
     * person's keys in the documented order; the host and transports as they read, the revoked keys
     * as the change leaves them.
     */
    private static String write(
            final AccessFile before,
            final Map<String, AccessFile.Person> persons,
            final List<String> revoked) {
        final Map<String, Object> root = new LinkedHashMap<>();
        final AccessFile.Host was = before.host();
        if (was != null) {
            final Map<String, Object> written = new LinkedHashMap<>();
            written.put("address", was.address());
            written.put("ssh_host_key", was.sshHostKey());
            root.put("host", written);
        }
        if (before.source() != null) {
            root.put("source", before.source());
        }
        final Map<String, Object> named = new LinkedHashMap<>();
        for (final AccessFile.Person person : persons.values()) {
            final Map<String, Object> written = new LinkedHashMap<>();
            written.put("fingerprint", person.fingerprint());
            written.put("role", person.role());
            written.put("hosts", person.hosts());
            if (person.locked()) {
                written.put("locked", true);
            }
            named.put(person.login(), written);
        }
        root.put("persons", named);
        if (!revoked.isEmpty()) {
            root.put("revoked", revoked);
        }
        if (!before.transports().isEmpty()) {
            root.put("transports", before.transports());
        }
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(root);
    }

    private String commit(
            final Path repository,
            final String head,
            final String key,
            final String text,
            final @Nullable String cardText,
            final String cardPath,
            final String digest)
            throws Refused {
        if (head.isEmpty()) {
            local(repository, "read-tree", "--empty");
        } else {
            local(repository, "update-ref", "HEAD", head);
            local(repository, "read-tree", head);
        }
        index(repository, Access.FILE, text);
        if (cardText != null) {
            index(repository, cardPath, cardText);
        }
        // No configuration of the caller's is read, so the name is always this one: the signature
        // is what the follow checks, and a name is only for whoever reads the log.
        final List<String> arguments =
                List.of(
                        "git",
                        "-c",
                        "gpg.format=ssh",
                        "-c",
                        "gpg.ssh.program=ssh-keygen",
                        "-c",
                        "user.signingkey=key::" + key,
                        "-c",
                        "commit.gpgsign=true",
                        "-c",
                        "user.name=Porelo",
                        "-c",
                        "user.email=porelo@localhost",
                        "-C",
                        repository.toString(),
                        "commit",
                        "-q",
                        "--no-verify",
                        "--cleanup=verbatim",
                        "-F",
                        "-");
        final String message =
                "Access: "
                        + said()
                        + "\n\nPorelo-Change-Id: "
                        + id
                        + "\nPorelo-Change-Digest: "
                        + digest
                        + "\n";
        final CommandResult committed = run(Command.of(arguments).withInput(message));
        if (!committed.successful()) {
            throw new Refused(
                    65,
                    "cannot sign the access commit through the ssh agent: "
                            + committed.standardError().strip());
        }
        return local(repository, "rev-parse", "--verify", "HEAD^{commit}");
    }

    private void push(
            final Path repository, final String url, final Remote remote, final String commit)
            throws Refused {
        final String ref = "refs/heads/" + remote.branch();
        // Leased on the head read: a branch that moved, forward or back, since is not written.
        final CommandResult pushed =
                remote(
                        repository,
                        url,
                        List.of(
                                "push",
                                "--porcelain",
                                "--force-with-lease=" + ref + ":" + remote.head(),
                                "--end-of-options",
                                url,
                                commit + ":" + ref));
        if (pushed.successful()) {
            return;
        }
        final boolean rejected =
                pushed.standardOutput()
                        .lines()
                        .anyMatch(line -> line.startsWith("!") && line.contains("[rejected]"));
        if (!rejected) {
            throw new Refused(69, "cannot push to " + url + ": " + pushed.standardError().strip());
        }
        final CommandResult now =
                remote(repository, url, List.of("ls-remote", "--end-of-options", url, ref));
        final String moved =
                now.successful() && !now.standardOutput().isBlank()
                        ? now.standardOutput().strip().split("\\s+")[0]
                        : "an unknown commit";
        throw new Refused(
                65,
                "the access repository moved to "
                        + moved
                        + " while the change was made against "
                        + (base == null
                                ? remote.head().isEmpty() ? "no commit" : remote.head()
                                : "--base " + base)
                        + "; read it again and ask anew");
    }

    /** What the change does, in words, for the commit's subject and a refusal. */
    private String said() {
        return switch (action) {
            case "init" -> "init with " + person + " as admin on " + host;
            case "add" -> "add " + person + " on " + host;
            case "remove" ->
                    "remove " + person + (host == null ? " from every host" : " from " + host);
            case "replace" -> "replace the key of " + person;
            default -> action + " " + person;
        };
    }

    private void index(final Path repository, final String path, final String content)
            throws Refused {
        final CommandResult stored =
                run(
                        Command.of(
                                        "git",
                                        "-C",
                                        repository.toString(),
                                        "hash-object",
                                        "-w",
                                        "--stdin")
                                .withInput(content));
        if (!stored.successful()) {
            throw new Refused(70, "cannot write " + path + ": " + stored.standardError().strip());
        }
        local(
                repository,
                "update-index",
                "--add",
                "--cacheinfo",
                "100644," + stored.standardOutput().strip() + "," + path);
    }

    /**
     * Whether a path is a regular file at a commit, in directories that are directories; anything
     * else there - a link, a submodule, a tree - is refused, since git would write through it or
     * the follow would not read it.
     */
    private boolean present(final Path repository, final String commit, final String path)
            throws Refused {
        final int slash = path.lastIndexOf('/');
        if (slash > 0) {
            final String parent = path.substring(0, slash);
            final String entry = entry(repository, commit, parent);
            if (entry.isEmpty()) {
                return false;
            }
            if (!entry.startsWith("040000 tree ")) {
                throw new Refused(65, parent + " at " + commit + " is not a directory");
            }
        }
        final String entry = entry(repository, commit, path);
        if (entry.isEmpty()) {
            return false;
        }
        if (!entry.startsWith("100644 blob ") && !entry.startsWith("100755 blob ")) {
            throw new Refused(65, path + " at " + commit + " is not a regular file");
        }
        return true;
    }

    private String entry(final Path repository, final String commit, final String path)
            throws Refused {
        return local(repository, "ls-tree", "-z", commit, "--", ":(literal)" + path);
    }

    /** A file at a commit, byte for byte, when it is there and no larger than the limit. */
    private @Nullable String blob(
            final Path repository, final String commit, final String path, final int limit)
            throws Refused {
        if (!present(repository, commit, path)) {
            return null;
        }
        final String size = local(repository, "cat-file", "-s", commit + ":" + path);
        if (!size.matches("[0-9]{1,18}") || Long.parseLong(size) > limit) {
            throw new Refused(65, path + " at " + commit + " is larger than " + limit + " bytes");
        }
        final CommandResult shown =
                run(
                        Command.of(
                                "git",
                                "-C",
                                repository.toString(),
                                "cat-file",
                                "blob",
                                commit + ":" + path));
        if (!shown.successful()) {
            throw new Refused(
                    70,
                    "git cat-file failed in the scratch clone: " + shown.standardError().strip());
        }
        return shown.standardOutput();
    }

    /** A git command on the scratch clone alone, whose output is wanted. */
    private String local(final Path repository, final String... arguments) throws Refused {
        final List<String> all = new ArrayList<>(List.of("git", "-C", repository.toString()));
        all.addAll(List.of(arguments));
        final CommandResult result = run(Command.of(all));
        if (!result.successful()) {
            throw new Refused(
                    70,
                    "git "
                            + arguments[0]
                            + " failed in the scratch clone: "
                            + result.standardError().strip());
        }
        return result.standardOutput().strip();
    }

    /** A git command that reaches the access repository, with what a follow lends to reach it. */
    private CommandResult remote(
            final Path repository, final String url, final List<String> arguments) {
        try (FollowCredential credential = FollowCredential.open(context, url, projectName)) {
            final List<String> all = new ArrayList<>(List.of("git"));
            all.addAll(TRANSPORT);
            all.addAll(credential.arguments());
            all.addAll(List.of("-C", repository.toString()));
            all.addAll(arguments);
            return run(Command.of(all).withEnvironment(credential.environmentFor(context, url)));
        }
    }

    /**
     * Runs a program apart from the caller's git: no system or global configuration, none in the
     * environment, no repository, index or object store named there, no replaced objects, no hooks.
     * No transport git counts as given by the user is taken. The ssh agent is the one the context
     * names, for fetch, push and signing alike; what a command sets itself, such as a lent agent,
     * goes on top. What is not git's, a proxy included, is the person's and passes.
     */
    private CommandResult run(final Command command) {
        final Map<String, String> chosen = new LinkedHashMap<>();
        callerEnvironment
                .get()
                .forEach(
                        (name, value) -> {
                            if (!name.startsWith("GIT_") && !name.equals("SSH_AUTH_SOCK")) {
                                chosen.put(name, value);
                            }
                        });
        chosen.put("GIT_CONFIG_NOSYSTEM", "1");
        chosen.put("GIT_CONFIG_GLOBAL", "/dev/null");
        chosen.put("GIT_NO_REPLACE_OBJECTS", "1");
        chosen.put("GIT_PROTOCOL_FROM_USER", "0");
        final String socket = context.environment().apply("SSH_AUTH_SOCK");
        if (socket != null && !socket.isBlank()) {
            chosen.put("SSH_AUTH_SOCK", socket);
        }
        final List<String> arguments = new ArrayList<>(command.arguments());
        if (command.program().equals("git")) {
            arguments.addAll(1, List.of("-c", "core.hooksPath=/dev/null"));
        }
        return context.runner()
                .run(
                        new Command(
                                        arguments,
                                        command.workingDirectory(),
                                        command.environment(),
                                        command.input(),
                                        true)
                                .withChosenEnvironment(chosen));
    }

    private static String sha256(final String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void deleteTree(final Path root) {
        try (java.util.stream.Stream<Path> entries = Files.walk(root)) {
            entries.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (final IOException ex) {
                                    // A scratch directory that will not go is not worth failing.
                                }
                            });
        } catch (final IOException ex) {
            // As above.
        }
    }

    /** A value as a message may show it: one line, whatever it held. */
    /**
     * A refusal as it is printed: a URL in it, or in what git said, without its user part, and
     * printable.
     */
    private static String said(final String message) {
        return printable(org.fuin.porelo.vault.Userinfo.leftOutOf(message));
    }

    private static String printable(final String value) {
        return value.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?");
    }
}
