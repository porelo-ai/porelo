package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SshAgentClient;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What changing the person's own key needs of this machine, for {@code identity create}, {@code
 * rotate} and {@code revoke --lost}: the key the agent serves, whether a key is revoked, and the
 * operation kept while its card is on the way to the organization.
 */
final class IdentityChange {

    /** The agent's variable, read from the context's environment and nowhere else. */
    static final String AGENT = "SSH_AUTH_SOCK";

    /** How long a card is valid from the day it is made. */
    private static final int DAYS = 30;

    /** The longest public key file read. */
    private static final long PUBLIC_KEY_LIMIT = 16 * 1024;

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

        private final @Nullable IOException failure;

        private final boolean nothingPushed;

        Refused(final int code, final @Nullable String reason, final String words) {
            this(code, reason, words, Map.of());
        }

        /** What nobody expected: the change may have been made, and its trace is kept. */
        Refused(final IOException failure) {
            super(failure.getMessage(), failure);
            this.code = 70;
            this.reason = "failed";
            this.extra = Map.of();
            this.failure = failure;
            this.nothingPushed = false;
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
            this.failure = null;
            this.nothingPushed = false;
        }

        private Refused(
                final Refused of, final Map<String, @Nullable Object> extra, final boolean none) {
            super(of.getMessage(), of.failure);
            this.code = of.code;
            this.reason = of.reason;
            this.extra = extra;
            this.failure = of.failure;
            this.nothingPushed = none;
        }

        /** The same refusal, it being known that no push was tried or none went out. */
        Refused beforeAnyPush() {
            return new Refused(this, extra, true);
        }

        /** Whether this answer says nothing was pushed. */
        boolean nothingPushed() {
            return nothingPushed;
        }

        /** The same refusal, saying more. */
        Refused saying(final Map<String, @Nullable Object> more) {
            final Map<String, @Nullable Object> all = new LinkedHashMap<>(extra);
            all.putAll(more);
            return new Refused(this, all, nothingPushed);
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

        /** What nobody expected, to keep its trace, or {@code null}. */
        @Nullable IOException failure() {
            return failure;
        }

        /** Whether the change may stand: after this a person asks again with the same id. */
        boolean mayHaveBeenMade() {
            return code == 69 || code == 70;
        }

        /** The typed answer, or {@code null} for a refusal that carries no reason. */
        @Nullable Map<String, @Nullable Object> answer() {
            final String typed = reason == null && code == 64 ? USAGE : reason;
            if (typed == null) {
                return null;
            }
            final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
            answer.put("reason", typed);
            answer.put("words", words());
            answer.put("revision", null);
            answer.putAll(extra);
            return answer;
        }
    }

    private static final String USAGE = OrganizationQuestions.Refused.USAGE;

    private static final String NO_KEY = OrganizationQuestions.Refused.NO_KEY;

    private final PoreloContext context;

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    IdentityChange(final PoreloContext context) {
        this.context = context;
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
     * @param answer The answer.
     * @param text What a person reads.
     * @param made The key made into KeePassXC, or {@code null} for a key named with {@code --key}.
     */
    static void print(
            final picocli.CommandLine.Model.CommandSpec spec,
            final boolean json,
            final Map<String, @Nullable Object> answer,
            final List<String> text,
            final KeePassXcKey.@Nullable Made made) {
        final var out = spec.commandLine().getOut();
        if (json) {
            final Map<String, @Nullable Object> all = new LinkedHashMap<>(answer);
            if (made != null) {
                all.put("entry", made.entry());
                all.put("agent", "holds");
            }
            out.println(Json.write(IdentityFacts.printable(all)));
        } else {
            text.forEach(line -> out.println(RegistryVerifyCommand.printable(line)));
            if (made != null) {
                out.println(
                        "entry "
                                + RegistryVerifyCommand.printable(made.entry())
                                + ", served by the agent");
            }
        }
        out.flush();
    }

    /**
     * What a refusal says of a key already made into KeePassXC: the same key goes on with {@code
     * --key}, which a key made anew would never need.
     *
     * @param made The key.
     * @return Its entry and fingerprint.
     */
    static Map<String, @Nullable Object> madeSoFar(final KeePassXcKey.Made made) {
        final Map<String, @Nullable Object> more = new LinkedHashMap<>();
        more.put("entry", made.entry());
        more.put("fingerprint", made.fingerprint());
        return more;
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
     * @throws Refused 69 {@code no-key} when none is named.
     */
    String socket() throws Refused {
        final String socket = context.environment().apply(AGENT);
        if (socket == null || socket.isEmpty()) {
            throw new Refused(69, NO_KEY, "no ssh-agent is reachable (SSH_AUTH_SOCK is not set)");
        }
        return socket;
    }

    /** The keys the agent serves. */
    private static List<AgentKey> served(final String socket) throws Refused {
        try {
            return new SshAgentClient(Path.of(socket)).identities();
        } catch (RuntimeException ex) {
            throw new Refused(69, NO_KEY, "the ssh-agent does not answer");
        }
    }

    /**
     * The successor a {@code --key} names, as {@code identity create} takes a key: one the agent
     * serves, Ed25519, not revoked and on no other login's accepted card.
     *
     * @param named {@code SHA256:...} or a public key file.
     * @param login The person's login.
     * @param socket The agent.
     * @param organization What the organization says, or {@code null} when this machine has none.
     * @return The key, which signs through the agent.
     * @throws Refused As {@code create} refuses a key.
     */
    AgentKey successor(
            final String named,
            final String login,
            final String socket,
            final IdentityOrganization.@Nullable Reading organization)
            throws Refused {
        final String fingerprint = fingerprintNamed(named);
        final AgentKey key = servedKey(fingerprint, socket);
        if (!key.authorizedKeysLine().startsWith("ssh-ed25519 ")) {
            throw new Refused(
                    65, "key-not-ed25519", "the key " + fingerprint + " is not an Ed25519 key");
        }
        final String line = PersonCard.keyLine(key);
        if (revoked(line, organization)) {
            throw revokedRefusal("the key " + fingerprint + " is revoked", organization);
        }
        final Optional<String> another = IdentityFacts.cardOfAnother(organization, line, login);
        if (another.isPresent()) {
            throw new Refused(
                    65,
                    "key-on-another-card",
                    "the key " + fingerprint + " is on the card of another login",
                    Map.of("login", another.get()));
        }
        return key;
    }

    /** A revoked key's refusal, naming who may name another. */
    static Refused revokedRefusal(
            final String words, final IdentityOrganization.@Nullable Reading organization) {
        return new Refused(
                65, "revoked", words, Map.of("who_may", IdentityFacts.owners(organization)));
    }

    /**
     * The key of that fingerprint, as the agent serves it.
     *
     * @param fingerprint {@code SHA256:...}.
     * @param socket The agent.
     * @return The key.
     * @throws Refused 69 {@code no-key} naming the fingerprint.
     */
    static AgentKey servedKey(final String fingerprint, final String socket) throws Refused {
        for (final AgentKey key : served(socket)) {
            if (fingerprint(key.keyBlob()).equals(fingerprint)) {
                return key;
            }
        }
        throw new Refused(
                69,
                NO_KEY,
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
     * Whether a key is revoked: in this machine's revocation list or by the organization.
     *
     * @param key {@code ssh-ed25519 AAAA...}.
     * @param organization What the organization says, or {@code null} when this machine has none.
     * @return {@code true} when revoked.
     * @throws Refused 66 when the revocation list is there but cannot be read.
     */
    boolean revoked(final String key, final IdentityOrganization.@Nullable Reading organization)
            throws Refused {
        try {
            return IdentityFacts.revoked(context, organization, key);
        } catch (IOException ex) {
            throw new Refused(66, null, String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Refuses a new change while an earlier one of another id stands in the organization, or may: a
     * run that may have pushed its card is not disproved by a state that lacks it, since a forge
     * may serve an older state again. It holds only while its card hands on from the card kept
     * here.
     *
     * @param organization What the organization says.
     * @param login The person's login.
     * @param id The id of this change, or {@code null}.
     * @throws Refused 65 {@code exists} naming the id to ask again with.
     */
    void refuseWhatReached(
            final IdentityOrganization.Reading organization,
            final String login,
            final @Nullable String id)
            throws Refused, IOException {
        final String held = heldCard(context).map(PersonCard::fingerprint).orElse("");
        for (final IdentityOperation pending : IdentityOperation.pending(context)) {
            if (filing(pending)
                    && !pending.id().equals(id)
                    && (reached(organization, login, pending)
                            || pending.pushTried()
                                    && (pending.predecessor().isEmpty()
                                            || pending.predecessor().equals(held)))) {
                final Map<String, @Nullable Object> more = new HashMap<>();
                more.put("revision", organization.revision());
                more.put("id", pending.id());
                throw new Refused(
                        65,
                        OrganizationQuestions.Refused.EXISTS,
                        "the change "
                                + pending.id()
                                + " reached the organization or may have: go on with that id, or"
                                + " with porelo identity revoke --lost",
                        more);
            }
        }
    }

    /**
     * Lets go of each earlier change of another id that reached the organization nowhere; this
     * change replaces it.
     *
     * @param organization What the organization says.
     * @param login The person's login.
     * @param id The id of this change, or {@code null}.
     * @throws IOException When a record cannot be removed.
     */
    void replaceWhatReachedNowhere(
            final IdentityOrganization.Reading organization,
            final String login,
            final @Nullable String id)
            throws IOException {
        for (final IdentityOperation pending : IdentityOperation.pending(context)) {
            if (filing(pending)
                    && !pending.pushTried()
                    && !pending.id().equals(id)
                    && !reached(organization, login, pending)) {
                pending.forget(context);
            }
        }
    }

    /**
     * Retires each earlier change that hands on from a key now revoked: the card kept here no
     * longer rests on that key, so its row would stay pending for good.
     *
     * @param key The fingerprint of the key revoked.
     * @param id The id of this change.
     * @return The ids retired.
     * @throws IOException When a record cannot be removed.
     */
    List<String> retireWhatHandsOnFrom(final String key, final String id) throws IOException {
        final List<String> retired = new java.util.ArrayList<>();
        for (final IdentityOperation pending : IdentityOperation.pending(context)) {
            if (filing(pending) && !pending.id().equals(id) && pending.predecessor().equals(key)) {
                pending.forget(context);
                retired.add(pending.id());
            }
        }
        return retired;
    }

    /**
     * Ends each earlier change of another id that reached the organization: the card it filed is
     * the person's, and what comes next is made against it. A stale state can say neither way.
     *
     * @param organization What the organization says.
     * @param login The person's login.
     * @param id The id of this change, or {@code null}.
     * @throws IOException When the card cannot be kept.
     * @throws Refused When its record cannot be taken.
     */
    void finishWhatReached(
            final IdentityOrganization.Reading organization,
            final String login,
            final @Nullable String id)
            throws IOException, Refused {
        for (final IdentityOperation pending : IdentityOperation.pending(context)) {
            if (filing(pending)
                    && !pending.id().equals(id)
                    && reached(organization, login, pending)) {
                final IdentityOperation taken =
                        java.util.Objects.requireNonNull(
                                kept(context, pending.id(), null), "it was listed just now");
                finish(context, taken)
                        .filedAt(String.valueOf(organization.revision()))
                        .keep(context);
            }
        }
    }

    /** Whether an operation files a card of the person's own, which only they could. */
    private static boolean filing(final IdentityOperation operation) {
        return !operation.action().equals(IdentityOperation.REVOKE_LOST);
    }

    private static boolean reached(
            final IdentityOrganization.Reading organization,
            final String login,
            final IdentityOperation operation) {
        return organization.answers().getPersons().stream()
                .anyMatch(
                        person ->
                                person.login().equals(login)
                                        && person.fingerprint().equals(operation.fingerprint())
                                        && !org.fuin.porelo.organization.Organization.REFUSED
                                                .equals(person.state()));
    }

    /**
     * What filing an operation's card ended with.
     *
     * @param operation The operation, done.
     * @param again Whether its id had already made the change.
     */
    record Filed(IdentityOperation operation, boolean again) {}

    /**
     * Files an operation's card through the store, signed through the agent, and only then keeps it
     * as the person's own. The record says a push may be tried before one is. An answer that says
     * nothing was pushed takes that back, unless an earlier run may have pushed: then this answer
     * cannot say. Such a record stays, whatever the store says.
     *
     * @param organization What the organization says.
     * @param operation The operation.
     * @param base The revision asked for, or {@code null} for the one read.
     * @param socket The agent.
     * @return What it ended with.
     * @throws Refused With the contract's code and reason, naming the id.
     * @throws IOException When the record cannot be written before the push.
     */
    Filed file(
            final IdentityOrganization.Reading organization,
            final IdentityOperation operation,
            final @Nullable String base,
            final String socket)
            throws Refused, IOException {
        final PersonCard card = PersonCard.read(operation.card(), "the operation's card");
        final OrganizationWrites.Change change =
                new OrganizationWrites.Change(
                        new OrganizationWrites.Acting(card.login(), card.key()),
                        base != null ? base : organization.revision(),
                        operation.id());
        final OrganizationWrites writes =
                organization.opened().writes().signingWith(CommitSigning.agent(socket));
        final IdentityOperation trying = operation.pushTried(true);
        trying.keep(context);
        final Map<String, @Nullable Object> named = new HashMap<>();
        named.put("id", operation.id());
        final OrganizationWrites.Written written;
        try {
            written =
                    IdentityOrganization.filed(
                            () ->
                                    operation.action().equals(IdentityOperation.CREATE)
                                            ? writes.addCard(change, operation.card())
                                            : writes.changeCard(change, operation.card()));
        } catch (Refused ex) {
            if (ex.nothingPushed() && !operation.pushTried()) {
                if (ex.mayHaveBeenMade()) {
                    operation.keep(context);
                } else {
                    operation.forget(context);
                }
            }
            throw ex.saying(named);
        }
        final IdentityOperation done = finished(context, trying.filedAt(written.revision()));
        return new Filed(done, written.again());
    }

    /**
     * Keeps a filed operation's card and notes that it is done. A write failing here leaves a
     * change that stands: the same id finishes it.
     *
     * @param context The machine.
     * @param filed The operation, which the organization took.
     * @return It, its card kept.
     * @throws Refused 70 {@code failed} naming the id.
     */
    static IdentityOperation finished(final PoreloContext context, final IdentityOperation filed)
            throws Refused {
        try {
            final IdentityOperation kept = finish(context, filed);
            kept.keep(context);
            return kept;
        } catch (IOException ex) {
            final Map<String, @Nullable Object> more = new HashMap<>();
            more.put("revision", filed.revision());
            more.put("id", filed.id());
            throw new Refused(
                    70,
                    "failed",
                    "the organization took the change, but it could not be kept here: ask again"
                            + " with the id "
                            + filed.id(),
                    more);
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
     * Keeps a new operation, under the id given or the first free one made. Its card is kept as the
     * person's own by what files it, once filed, or at once when nothing files it.
     *
     * @param id The id given, or {@code null} to make one.
     * @param made The operation, its id aside.
     * @return What is kept under the id.
     * @throws IOException When the record cannot be written.
     * @throws Refused When a run at once kept another operation under the id: 64 for one of the
     *     other action, 66 {@code operation-damaged} for one that does not read.
     */
    Begun begin(final @Nullable String id, final IdentityOperation made)
            throws IOException, Refused {
        if (id != null) {
            final IdentityOperation operation = withId(made, id);
            if (operation.keepNew(context)) {
                return new Begun(operation, true);
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
                return new Begun(operation, true);
            }
        }
    }

    /**
     * Keeps the operation's card as the person's own, then notes in the record that it is.
     *
     * @param operation The operation.
     * @return It, its card kept.
     * @throws IOException When it cannot be written.
     */
    IdentityOperation keep(final IdentityOperation operation) throws IOException {
        new IdentityStore(context).keep(operation.card());
        final IdentityOperation kept = operation.keptHere();
        kept.keep(context);
        return kept;
    }

    /**
     * Keeps an operation's card, once filed or once a run stopped before it kept it: when the card
     * kept here is still the one the operation replaces, or none for a first card, the operation's
     * card is kept in its place. The card is the one the record holds, never one made again.
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
            if (!operation.action().equals(IdentityOperation.CREATE)) {
                return operation;
            }
            store.keep(operation.card());
        } else {
            final String fingerprint = held.get().fingerprint();
            if (fingerprint.equals(operation.predecessor())) {
                store.keep(operation.card());
            } else if (!fingerprint.equals(operation.fingerprint())) {
                // Another card was kept since; this operation's is not brought back over it.
                return operation;
            }
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
                made.revision(),
                false,
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
     *     another action, 65 {@code no-identity} when no card is kept here for a change of one.
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
        final boolean first = kept.action().equals(IdentityOperation.CREATE);
        final Optional<PersonCard> held = first ? heldCard(context) : Optional.empty();
        final String login =
                first
                        ? held.map(PersonCard::login).orElse(card.login())
                        : new IdentityChange(context).card().login();
        if (!card.login().equals(login)) {
            throw damaged(
                    "the card of the operation "
                            + id
                            + " is of another login than the card kept here");
        }
        return kept;
    }

    private static Optional<PersonCard> heldCard(final PoreloContext context) throws Refused {
        try {
            return new IdentityStore(context).card();
        } catch (IOException | org.fuin.porelo.vault.CardException ex) {
            throw new Refused(66, null, String.valueOf(ex.getMessage()));
        }
    }

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
     * @throws Refused 69 {@code no-key} when the agent did not sign as asked.
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
                    69,
                    NO_KEY,
                    "the ssh-agent did not sign the card (KeePassXC locked or the entry not"
                            + " loaded)");
        }
        if (!verifies(PersonCard.read(text, "the card made"))) {
            throw new Refused(
                    69,
                    NO_KEY,
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
     * Answers a refusal: typed on stdout with {@code --json} when it carries a reason, and its
     * words on stderr.
     *
     * @param spec The command's spec.
     * @param json Whether JSON was asked for.
     * @param refused The refusal.
     * @return Its exit code.
     * @throws IOException What nobody expected, for the handler that keeps its trace.
     */
    static int refuse(
            final picocli.CommandLine.Model.CommandSpec spec,
            final boolean json,
            final Refused refused)
            throws IOException {
        final Map<String, @Nullable Object> typed = refused.answer();
        if (json && typed != null) {
            final var out = spec.commandLine().getOut();
            out.println(Json.write(RegistryVerifyCommand.printable(typed)));
            out.flush();
        }
        final IOException failure = refused.failure();
        if (failure != null) {
            throw failure;
        }
        final var err = spec.commandLine().getErr();
        err.println("porelo: " + RegistryVerifyCommand.printable(refused.words()));
        err.flush();
        return refused.code();
    }
}
