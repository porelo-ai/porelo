package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.GitTreeWrites;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SshAgentClient;
import org.fuin.porelo.vault.VaultException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Option;

/**
 * A command that changes the organization. The acting person is never an argument: it is whoever's
 * own card this machine holds, signing through their agent.
 */
public abstract class OrgWriting extends OrgReading {

    /**
     * Who acts, and what they act on.
     *
     * @param writes The changes.
     * @param acting The person.
     * @param key Their key as the agent serves it, which signs what a change carries.
     * @param files A project's own file, changed by that key.
     */
    record Session(
            OrganizationWrites writes,
            OrganizationWrites.Acting acting,
            AgentKey key,
            ProjectFile files) {}

    /** Opens the changes; replaced by a test, which has no agent or forge. */
    @FunctionalInterface
    interface Opener {
        Session open(PoreloContext machine) throws IOException;
    }

    static final String AGENT = "SSH_AUTH_SOCK";

    @Option(names = "--base", description = "The revision it was made against.")
    private @Nullable String base;

    @Option(names = "--id", description = "Names the change: asked again, the same answer.")
    private @Nullable String id;

    Opener session = OrgWriting::real;

    /** Reads what a change stands on; replaced by a test, which has no organization. */
    @FunctionalInterface
    interface Ground {
        @Nullable String of(PoreloContext machine, OrgAccess.Opener opener) throws IOException;
    }

    Ground ground = OrgWriting::current;

    /**
     * Makes the change.
     *
     * @param machine The machine.
     * @param opened Who acts.
     * @param change Against which state, under which id.
     * @return What it made.
     * @throws IOException When something unexpected happened; the change may have been made.
     */
    abstract OrganizationWrites.Written write(
            PoreloContext machine, Session opened, OrganizationWrites.Change change)
            throws IOException;

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        final Session opened = session.open(machine);
        final String named = id == null ? "org-" + UUID.randomUUID() : id;
        // A project's own file has its own revisions, and its command reads the organization.
        final @Nullable String standing =
                "organization".equals(of()) ? ground.of(machine, opener) : null;
        final OrganizationWrites.Change change;
        try {
            change =
                    new OrganizationWrites.Change(
                            opened.acting(), base == null ? standing : base, named);
        } catch (IllegalArgumentException ex) {
            throw new Refused(Refused.USAGE, String.valueOf(ex.getMessage()), null);
        }
        final OrganizationWrites.Written written;
        try {
            written = write(machine, opened, change);
        } catch (GitTreeStore.Unreachable ex) {
            throw new Refused(
                    Refused.CANNOT_SAY,
                    (ex instanceof GitTreeStore.NotTaken
                                    ? ex.getMessage() + ": ask again with --id "
                                    : "the organization cannot be reached, and the change may have"
                                            + " been made: ask again with --id ")
                            + named,
                    null);
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", written.revision());
        answer.put("id", written.id());
        answer.put("again", written.again());
        answer.put("of", of());
        more(answer);
        return answer;
    }

    /**
     * Returns the revision the person named.
     *
     * @return It, or {@code null} when none was named.
     */
    final @Nullable String base() {
        return base;
    }

    /**
     * Returns the id the person named.
     *
     * @return It, or {@code null} when none was named.
     */
    final @Nullable String id() {
        return id;
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        line(
                (Boolean.TRUE.equals(answer.get("again")) ? "Already made" : "Made")
                        + " at "
                        + answer.get("revision")
                        + ", under the id "
                        + answer.get("id"));
    }

    /**
     * Reads the revision a write stands on, through the trust record: a forge serving an older
     * state must not get a change signed on it.
     *
     * @param machine The machine.
     * @param opener How the store is opened.
     * @return The revision, or {@code null} when nothing is accepted yet.
     * @throws IOException When something unexpected happened.
     * @throws Refused {@code refused-state} when the organization went back, {@code cannot-say}
     *     when it cannot be reached.
     */
    static @Nullable String current(final PoreloContext machine, final OrgAccess.Opener opener)
            throws IOException {
        final OrgAccess.Reading reading = OrgAccess.read(machine, opener);
        if (reading.stale()) {
            throw new Refused(
                    Refused.CANNOT_SAY,
                    "the organization cannot be reached to say who may, or what this stands on",
                    null);
        }
        return reading.answers().revision();
    }

    /**
     * Adds what this command's answer holds beside what every write's holds.
     *
     * @param answer The answer.
     */
    void more(final Map<String, @Nullable Object> answer) {
        // Most hold nothing more.
    }

    /**
     * Returns whose revision the answer's is.
     *
     * @return {@code organization}, unless a command changes something else.
     */
    String of() {
        return "organization";
    }

    /** Reads a file a person names, saying nothing of what it holds. */
    static String file(final Path file, final String option) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > 1024 * 1024) {
            throw new Refused(Refused.USAGE, option + " names no card's file", null);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException ex) {
            throw new Refused(Refused.USAGE, option + " names no card's file", null);
        }
    }

    private static Session real(final PoreloContext machine) throws IOException {
        final String repository =
                OrgAccess.repository(machine)
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                Refused.NO_ORGANIZATION,
                                                "this machine has no organization: run porelo setup"
                                                        + " first",
                                                null));
        return at(machine, repository);
    }

    /**
     * Opens the changes of the organization at an address, for this machine's person.
     *
     * @param machine The machine.
     * @param repository The address.
     * @return Who acts, and what they act on.
     * @throws IOException When the person's card cannot be read.
     */
    static Session at(final PoreloContext machine, final String repository) throws IOException {
        final PersonCard card = ownCard(machine);
        final String socket = machine.environment().apply(AGENT);
        final AgentKey key = served(socket, card.fingerprint());
        final String own = PersonCard.keyLine(key);
        final OrgAccess.Opened opened = OrgAccess.real(machine, repository);
        final FetchAccess pushing = OrganizationAccess.lent(machine, String.valueOf(socket));
        final CommitSigning signing = CommitSigning.agent(String.valueOf(socket));
        return new Session(
                new GitTreeWrites(
                        machine.runner(),
                        repository,
                        ForgeAddress.of(repository).organization(),
                        pushing,
                        opened.identities(),
                        signing),
                new OrganizationWrites.Acting(card.login(), own),
                key,
                new ProjectFile(
                        machine.runner(), pushing, signing, OrgAccess.projectFiles(machine)));
    }

    /**
     * Returns the text of this machine's person's card.
     *
     * @param machine The machine.
     * @return The card, as filed.
     * @throws IOException When it cannot be read.
     */
    static String ownCardText(final PoreloContext machine) throws IOException {
        final Path file = machine.paths().xdg().config().resolve("identity/card");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new Refused(Refused.NO_CARD, "this machine holds no card of yours", null);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    static PersonCard ownCard(final PoreloContext machine) throws IOException {
        try {
            return PersonCard.read(ownCardText(machine), "own card");
        } catch (CardException ex) {
            throw new Refused(Refused.NO_CARD, "this machine's card does not read", null);
        }
    }

    static AgentKey served(final @Nullable String socket, final String fingerprint) {
        if (socket == null || socket.isBlank()) {
            throw noKey(fingerprint);
        }
        final List<AgentKey> keys;
        try {
            keys = new SshAgentClient(Path.of(socket)).identities();
        } catch (VaultException | java.nio.file.InvalidPathException ex) {
            throw noKey(fingerprint);
        }
        for (final AgentKey key : keys) {
            if (fingerprint.equals(fingerprintOf(key))) {
                return key;
            }
        }
        throw noKey(fingerprint);
    }

    static @Nullable String fingerprintOf(final AgentKey key) {
        try {
            return PersonCard.fingerprint(PersonCard.keyLine(key));
        } catch (CardException | IllegalArgumentException ex) {
            // An agent holds other kinds of key too; none of those is a card's.
            return null;
        }
    }

    private static Refused noKey(final String fingerprint) {
        return new Refused(
                Refused.NO_KEY,
                "the ssh-agent does not serve " + fingerprint + ", your card's key",
                null);
    }
}
