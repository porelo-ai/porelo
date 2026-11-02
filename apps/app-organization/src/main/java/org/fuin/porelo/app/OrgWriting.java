package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.git.RegistryOrigin;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.GitTreeWrites;
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
     */
    record Session(OrganizationWrites writes, OrganizationWrites.Acting acting, AgentKey key) {}

    /** Opens the changes; replaced by a test, which has no agent or forge. */
    @FunctionalInterface
    interface Opener {
        Session open(PoreloContext machine) throws IOException;
    }

    private static final String AGENT = "SSH_AUTH_SOCK";

    @Option(names = "--base", description = "The revision it was made against.")
    private @Nullable String base;

    @Option(names = "--id", description = "Names the change: asked again, the same answer.")
    private @Nullable String id;

    Opener session = OrgWriting::real;

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
    final Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        final Session opened = session.open(machine);
        final String named = id == null ? "org-" + UUID.randomUUID() : id;
        final OrganizationWrites.Change change;
        try {
            change = new OrganizationWrites.Change(opened.acting(), base, named);
        } catch (IllegalArgumentException ex) {
            throw new Refused(Refused.USAGE, String.valueOf(ex.getMessage()), null);
        }
        final OrganizationWrites.Written written;
        try {
            written = write(machine, opened, change);
        } catch (GitTreeStore.Unreachable ex) {
            throw new Refused(
                    Refused.CANNOT_SAY,
                    "the organization cannot be reached, and the change may have been made: ask"
                            + " again with --id "
                            + named,
                    null);
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", written.revision());
        answer.put("id", written.id());
        answer.put("again", written.again());
        answer.put("of", "organization");
        return answer;
    }

    @Override
    final void text(final Map<String, @Nullable Object> answer) {
        line(
                (Boolean.TRUE.equals(answer.get("again")) ? "Already made" : "Made")
                        + " at "
                        + answer.get("revision")
                        + ", under the id "
                        + answer.get("id"));
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
        return new Session(
                new GitTreeWrites(
                        machine.runner(),
                        repository,
                        RegistryOrigin.of(repository).organisation(),
                        pushing(machine, String.valueOf(socket)),
                        opened.identities(),
                        CommitSigning.agent(String.valueOf(socket))),
                new OrganizationWrites.Acting(card.login(), own),
                key);
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

    private static PersonCard ownCard(final PoreloContext machine) throws IOException {
        try {
            return PersonCard.read(ownCardText(machine), "own card");
        } catch (CardException ex) {
            throw new Refused(Refused.NO_CARD, "this machine's card does not read", null);
        }
    }

    private static AgentKey served(final @Nullable String socket, final String fingerprint) {
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

    private static @Nullable String fingerprintOf(final AgentKey key) {
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

    /** What the person holds for the address, and their agent, which a push over ssh signs with. */
    private static FetchAccess pushing(final PoreloContext machine, final String socket) {
        final FetchAccess lent = OrgAccess.lent(machine);
        return address -> {
            final FetchAccess.Lease lease = lent.lend(address);
            return new FetchAccess.Lease() {
                @Override
                public Map<String, String> environment() {
                    final Map<String, String> all = new HashMap<>();
                    all.put(AGENT, socket);
                    all.putAll(lease.environment());
                    return all;
                }

                @Override
                public List<String> arguments() {
                    return lease.arguments();
                }

                @Override
                public void close() {
                    lease.close();
                }
            };
        };
    }
}
