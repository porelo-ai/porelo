package org.fuin.porelo.vault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/**
 * Reads a registry path by path, as its signers were allowed to write it.
 *
 * <p>Write access to a registry is wide, so a commit's signer alone proves nothing. Every commit
 * along the first parent is judged path by path against the state before it: {@code owners.yml} by
 * the owners it named, a person's card by its own key (and a change of key by its predecessor, or a
 * registry owner when the old key is lost), a host's card and access file by a host admin {@code
 * owners.yml} names for it. A refused path keeps the state it had, and the walk goes on. Every
 * other path is ignored and never read.
 *
 * <p>A card whose key the forge plainly does not list is answered as refused, but accepts nothing;
 * a card the forge has not vouched for yet holds its login only until the login's own card comes,
 * signed by a key the forge lists.
 *
 * <p>The first {@code owners.yml} is taken only when the forge plainly says its signer owns the
 * organisation with that key. When the forge cannot say, the walk stops before that commit, so the
 * state, and the trust record kept from it, end at the commit before and the next read judges it
 * again.
 */
public final class RegistryHistory {

    private static final String OWNERS = "owners.yml";

    private static final Pattern PERSON = Pattern.compile("people/([^/]+)\\.card");

    private static final Pattern HOST_FILE = Pattern.compile("hosts/([^/]+)/(card|access\\.yml)");

    /**
     * The most distinct keys a person's chain holds, the card's own key among them: what the forge
     * is asked about for one person at most, so a run of changes of key cannot spend its limit.
     */
    static final int CHAIN = 16;

    private static final String REVOKED_KEY = "revoked key";

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private final CommandRunner runner;

    /** Creates a reader whose Git commands use this runner. */
    public RegistryHistory(CommandRunner runner) {
        this.runner = runner;
    }

    /**
     * Reads a person's checkout of the registry at the commit it has checked out.
     *
     * <p>The checkout is cloned into a scratch directory first, so its own configuration and hooks
     * never take effect, and the clone is removed on every exit.
     *
     * <p>A trust record that fails any check is discarded, said so in a warning, and the registry
     * read afresh from its first commit: slower, never weaker.
     *
     * @param checkout The checkout's directory, never a symlink.
     * @param trusted The state the last read ended with, or {@code null} for a first read.
     * @param forge What the central forge says about its accounts.
     * @param organisation The forge organisation the registry belongs to.
     * @return The state at the checked-out commit, or at the commit before one the forge could not
     *     decide; its warnings are this read's.
     * @throws IOException If the checkout cannot be read or the clone cannot be removed.
     * @throws CardException If the history is shallow, or does not continue the trusted commit.
     */
    public RegistryState read(
            Path checkout,
            @Nullable RegistryState trusted,
            ForgeDirectory forge,
            String organisation)
            throws IOException {
        return readClone(checkout, trusted, null, forge, organisation);
    }

    /**
     * Reads a person's checkout of the registry from the trust record kept as text: one that does
     * not read whole is discarded, as one that fails a check is.
     *
     * @param checkout The checkout's directory, never a symlink.
     * @param record The trust record's text, or {@code null} for a first read.
     * @param where What names the record in a warning, such as its file.
     * @param forge What the central forge says about its accounts.
     * @param organisation The forge organisation the registry belongs to.
     * @return The state, as {@link #read(Path, RegistryState, ForgeDirectory, String)} answers it.
     * @throws IOException If the checkout cannot be read or the clone cannot be removed.
     * @throws CardException If the history is shallow, or does not continue the trusted commit.
     */
    public RegistryState read(
            Path checkout,
            @Nullable String record,
            String where,
            ForgeDirectory forge,
            String organisation)
            throws IOException {
        RegistryState trusted = null;
        String discarded = null;
        if (record != null) {
            try {
                trusted = RegistryState.fromJson(record);
            } catch (CardException ex) {
                discarded = where + ": " + ex.getMessage();
            }
        }
        return readClone(checkout, trusted, discarded, forge, organisation);
    }

    private RegistryState readClone(
            Path checkout,
            @Nullable RegistryState trusted,
            @Nullable String discarded,
            ForgeDirectory forge,
            String organisation)
            throws IOException {
        final Path scratch = Files.createTempDirectory("porelo-registry-");
        try (RegistryRepository repository = new RegistryRepository(scratch, runner)) {
            final String head = repository.cloneCheckout(checkout);
            return read(repository, head, trusted, discarded, forge, organisation);
        }
    }

    /**
     * Reads the owned clone up to a commit.
     *
     * @param repository The owned clone.
     * @param head The commit to read up to.
     * @param trusted The state the last read ended with, or {@code null} for a first read.
     * @param discarded Why a trust record was discarded before, or {@code null}.
     * @param forge What the central forge says about its accounts.
     * @param organisation The forge organisation the registry belongs to.
     * @return The state at the head, or at the commit before one the forge could not decide; its
     *     warnings are this read's.
     * @throws IOException If the allowed-signers file cannot be written.
     * @throws CardException If the history is shallow, or does not continue the trusted commit.
     */
    RegistryState read(
            RegistryRepository repository,
            String head,
            @Nullable RegistryState trusted,
            @Nullable String discarded,
            ForgeDirectory forge,
            String organisation)
            throws IOException {
        if (!repository.require("rev-parse", "--is-shallow-repository").strip().equals("false")) {
            throw new CardException(head + ": a shallow registry cannot prove its history");
        }
        final List<String> history =
                new ArrayList<>(
                        List.of(
                                "rev-list",
                                "--parents",
                                "--first-parent",
                                "--reverse",
                                "--end-of-options",
                                head));
        @Nullable String reached = null;
        @Nullable RegistryState start = trusted;
        @Nullable String why = discarded;
        if (start != null) {
            try {
                reached = checkRecord(repository, head, start);
                history.add("^" + reached);
            } catch (Discarded ex) {
                // Walked again from the founding commit: slower, never weaker.
                start = null;
                why = ex.why();
            }
        }
        final Walk walk =
                new Walk(
                        repository,
                        forge,
                        organisation,
                        start == null ? RegistryState.empty(null) : start);
        if (why != null) {
            walk.warnings.add(
                    RegistryText.printable(
                            "the registry trust record was discarded and the registry read afresh"
                                    + " from its first commit: "
                                    + why));
        }
        walk.settle(reached);
        for (String entry : repository.require(history.toArray(String[]::new)).lines().toList()) {
            final String[] ancestry = entry.split(" ");
            if (!walk.commit(ancestry[0], ancestry.length > 1 ? ancestry[1] : "")) {
                break;
            }
            reached = ancestry[0];
        }
        // A removal is judged after the walk, as settling judges it, whichever way the read began.
        walk.removals();
        return walk.state(reached);
    }

    /** Whether a path is one the rule judges; every other path is never read. */
    static boolean judged(String path) {
        if (path.equals(OWNERS)) {
            return true;
        }
        final Matcher person = PERSON.matcher(path);
        if (person.matches()) {
            return RegistryOwners.login(person.group(1));
        }
        final Matcher host = HOST_FILE.matcher(path);
        return host.matches() && RegistryOwners.HOST.matcher(host.group(1)).matches();
    }

    private static void checkContinues(RegistryRepository repository, String head, String trusted) {
        // Checked before git sees it: an option in its place would be read as one.
        final boolean named = COMMIT.matcher(trusted).matches();
        final var resolved =
                named ? repository.git("cat-file", "-t", "--end-of-options", trusted) : null;
        if (resolved == null
                || !resolved.successful()
                || !resolved.trimmedOutput().equals("commit")
                || !repository
                        .require("rev-list", "--first-parent", "--end-of-options", head)
                        .lines()
                        .anyMatch(trusted::equals)) {
            throw new CardException(
                    "registry head "
                            + head
                            + " does not continue the first-parent line of the trusted commit "
                            + trusted
                            + " (or that commit is missing)");
        }
    }

    /**
     * Refuses a trust record whose changes, cards or refusals the history it ends at does not bear
     * out: the record whole as a trust record must be, the head continuing its commit, each named
     * commit on that commit's first-parent line, each change and card signed by the signer named
     * and holding the blob named at its path, and each file read and judged as the walk judges it.
     *
     * @return The commit the record ended at.
     * @throws Discarded When the record fails a check, so that the registry is read afresh.
     * @throws CardException When the head does not continue the record's commit: the history, not
     *     the record, is then what is wrong.
     */
    private static String checkRecord(
            RegistryRepository repository, String head, RegistryState trusted)
            throws IOException, Discarded {
        // Whether read from a file or handed over in memory: no state skips what a record must be.
        try {
            trusted.validate();
        } catch (CardException ex) {
            throw new Discarded(String.valueOf(ex.getMessage()));
        }
        final String commit =
                java.util.Objects.requireNonNull(trusted.commit(), "a valid record names it");
        checkContinues(repository, head, commit);
        final Set<String> line =
                new java.util.HashSet<>(
                        repository
                                .require("rev-list", "--first-parent", "--end-of-options", commit)
                                .lines()
                                .toList());
        // Each change with the path it changed and the blob it left there.
        final List<Map.Entry<RegistryState.Change, Map.Entry<String, String>>> named =
                new ArrayList<>();
        trusted.changes()
                .forEach(
                        (path, change) ->
                                named.add(
                                        Map.entry(
                                                change,
                                                Map.entry(
                                                        path,
                                                        String.valueOf(
                                                                trusted.blobs().get(path))))));
        for (RegistryState.Person person : trusted.people()) {
            named.add(
                    Map.entry(
                            person.change(),
                            Map.entry("people/" + person.login() + ".card", person.blob())));
        }
        boolean borne =
                trusted.refusals().stream().allMatch(refusal -> line.contains(refusal.commit()));
        for (var each : named) {
            if (!borne) {
                break;
            }
            final RegistryState.Change change = each.getKey();
            final String path = each.getValue().getKey();
            // Only a commit on the line, so an object name, ever reaches git.
            try {
                borne =
                        line.contains(change.commit())
                                && touched(repository, change.commit(), path)
                                && signedBy(repository, change)
                                && repository
                                        .object(change.commit(), path)
                                        .orElse("")
                                        .equals(each.getValue().getValue());
            } catch (CardException ex) {
                borne = false;
            }
        }
        if (!borne || !given(repository, trusted)) {
            throw new Discarded("the registry trust record does not match the registry's history");
        }
        return commit;
    }

    /** Whether the commit's first-parent diff touched the path. */
    private static boolean touched(RegistryRepository repository, String commit, String path) {
        final String[] ancestry =
                repository
                        .require("rev-list", "--parents", "-n", "1", "--end-of-options", commit)
                        .strip()
                        .split(" ");
        final List<String> diff =
                new ArrayList<>(
                        List.of(
                                "diff-tree",
                                "--no-commit-id",
                                "--no-renames",
                                "--name-only",
                                "-r",
                                "-z"));
        if (ancestry.length < 2) {
            diff.add("--root");
        }
        diff.add("--end-of-options");
        if (ancestry.length >= 2) {
            diff.add(ancestry[1]);
        }
        diff.addAll(List.of(commit, "--", path));
        return !repository.require(diff.toArray(String[]::new)).isEmpty();
    }

    /**
     * Whether the owners, host admins, people and hosts the record names are exactly what the blobs
     * it names say, each blob read as the walk read it; the record is never taken at its word for
     * what a file holds.
     */
    private static boolean given(RegistryRepository repository, RegistryState trusted) {
        try {
            final String owners = trusted.blobs().get(OWNERS);
            final List<RegistryState.Owner> named = new ArrayList<>();
            final Map<String, List<RegistryState.Owner>> admins = new TreeMap<>();
            if (owners != null) {
                final RegistryOwners file = RegistryOwners.read(text(repository, owners), OWNERS);
                file.owners().forEach(entry -> named.add(Walk.owner(entry)));
                file.hostAdmins()
                        .forEach(
                                (host, list) ->
                                        admins.put(host, list.stream().map(Walk::owner).toList()));
            }
            if (!named.equals(trusted.owners()) || !admins.equals(trusted.hostAdmins())) {
                return false;
            }
            final Set<String> owning = new java.util.HashSet<>();
            // A backup key is no owner here either, as the walk's owner() has it.
            named.stream()
                    .filter(owner -> !owner.backup())
                    .forEach(owner -> owning.add(owner.fingerprint()));
            for (RegistryState.Person person : trusted.people()) {
                final String path = "people/" + person.login() + ".card";
                final PersonCard card =
                        personCard(
                                text(repository, person.blob()),
                                path,
                                person.login(),
                                person.change().signer(),
                                owning::contains);
                if (!card.name().equals(person.name())
                        || !card.email().equals(person.email())
                        || !card.key().equals(person.key())
                        || !named(card).equals(person.predecessor())) {
                    return false;
                }
            }
            for (RegistryState.Host host : trusted.hosts()) {
                final String path = "hosts/" + host.host() + "/";
                final String card = trusted.blobs().get(path + "card");
                final RegistryState.Change change = trusted.changes().get(path + "card");
                final RegistryState.Card read =
                        card == null || change == null
                                ? null
                                : hostCard(
                                        text(repository, card),
                                        path + "card",
                                        host.host(),
                                        change.signer());
                if (!java.util.Objects.equals(read, host.card())) {
                    return false;
                }
                final String access = trusted.blobs().get(path + "access.yml");
                final List<RegistryState.Admin> projectAdmins = new ArrayList<>();
                final List<RegistryState.Project> projects = new ArrayList<>();
                if (access != null) {
                    final RegistryAccess file =
                            RegistryAccess.read(text(repository, access), path + "access.yml");
                    file.admins()
                            .forEach(
                                    (login, fingerprint) ->
                                            projectAdmins.add(
                                                    new RegistryState.Admin(login, fingerprint)));
                    file.projects()
                            .forEach(
                                    project ->
                                            projects.add(
                                                    new RegistryState.Project(
                                                            project.name(),
                                                            project.admin(),
                                                            project.accessRepository())));
                }
                if (!projectAdmins.equals(host.admins()) || !projects.equals(host.projects())) {
                    return false;
                }
            }
            return true;
        } catch (CardException | Refused ex) {
            return false;
        }
    }

    /**
     * Reads a person's card as the walk takes one at its path, and as a trust record must hold it:
     * readable, the path's login, signed by its own key and any predecessor it names, and placed by
     * its own key or a registry owner. Which of the two the commit needed, the walk decides.
     *
     * @param owner Whether a key is a registry owner's.
     * @throws Refused Naming why, never quoting what the card holds.
     */
    private static PersonCard personCard(
            String text,
            String path,
            String login,
            String signer,
            java.util.function.Predicate<String> owner)
            throws Refused {
        final PersonCard card;
        try {
            card = PersonCard.read(text, path);
        } catch (CardException ex) {
            // A card's own messages may quote what it holds; only the path is named.
            throw new Refused("not a readable person card");
        }
        if (!card.login().equals(login)) {
            throw new Refused("the card is " + card.login() + "'s, not " + login + "'s");
        }
        if (!card.signedAsItMustBe()) {
            throw new Refused(
                    "the card is not signed by its own key"
                            + (card.predecessor().isEmpty() ? "" : " and its predecessor"));
        }
        if (!signer.equals(card.fingerprint()) && !owner.test(signer)) {
            throw new Refused(
                    "signed by "
                            + signer
                            + ", neither the card's own key "
                            + card.fingerprint()
                            + " nor a registry owner");
        }
        return card;
    }

    /** The fingerprint of the key a card names as its predecessor, or "" when it names none. */
    private static String named(PersonCard card) {
        return card.predecessor().isEmpty() ? "" : PersonCard.fingerprint(card.predecessor());
    }

    /**
     * Reads a host's card as the walk takes one at its path, and as a trust record must hold it:
     * readable, naming the path's host, and signed by the commit's signer.
     *
     * @throws Refused Naming why, never quoting what the card holds.
     */
    private static RegistryState.Card hostCard(String text, String path, String host, String signer)
            throws Refused {
        final HostCard card;
        try {
            card = HostCard.read(text, path);
        } catch (CardException ex) {
            // A card's own messages may quote what it holds; only the path is named.
            throw new Refused("not a readable host card");
        }
        if (!card.host().equals(host)) {
            throw new Refused("the host card names " + card.host() + ", expected " + host);
        }
        try {
            card.checkSignedBy(signer);
        } catch (CardException ex) {
            throw new Refused(String.valueOf(ex.getMessage()));
        }
        return RegistryState.Card.of(card, signer);
    }

    private static String text(RegistryRepository repository, String object) {
        if (!COMMIT.matcher(object).matches()) {
            throw new CardException("not an object name");
        }
        return repository.blob(object, "the trust record's blob");
    }

    /** Whether the change's commit is signed, verifiably, by the signer it names. */
    private static boolean signedBy(RegistryRepository repository, RegistryState.Change change)
            throws IOException {
        try {
            final String key =
                    CommitSigner.claimedKey(
                            repository.require(
                                    "cat-file", "commit", "--end-of-options", change.commit()));
            return PersonCard.fingerprint(key).equals(change.signer())
                    && CommitSigner.verifies(repository, change.commit(), key);
        } catch (CardException ex) {
            return false;
        }
    }

    /** Why a trust record is discarded, and the registry read afresh. */
    private static final class Discarded extends Exception {
        private static final long serialVersionUID = 1L;

        private final String why;

        Discarded(String why) {
            super(why, null, false, false);
            this.why = why;
        }

        String why() {
            return why;
        }
    }

    /** Why one path of one commit is refused. */
    private static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        private final String why;

        Refused(String why) {
            super(why, null, false, false);
            this.why = why;
        }

        String why() {
            return why;
        }
    }

    /** Why the forge could not decide a commit, which is then judged again at the next read. */
    private static final class Undecided extends Exception {
        private static final long serialVersionUID = 1L;

        private final String why;

        Undecided(String why) {
            super(why, null, false, false);
            this.why = why;
        }

        String why() {
            return why;
        }
    }

    /** What the forge's key list says about a person's key. */
    private record Trust(String state, String why) {}

    /** What has been accepted so far; copied before each commit, which is judged against it. */
    private record Model(
            Map<String, RegistryState.Owner> owners,
            Map<String, List<RegistryState.Owner>> hostAdmins,
            Map<String, RegistryState.Person> people,
            Map<String, RegistryState.Host> hosts,
            Set<String> revoked,
            Map<String, String> blobs,
            Map<String, RegistryState.Change> changes,
            Set<RegistryState.Removed> removed) {

        static Model of(RegistryState state) {
            final Map<String, RegistryState.Owner> owners = new LinkedHashMap<>();
            state.owners().forEach(owner -> owners.put(owner.fingerprint(), owner));
            final Map<String, RegistryState.Person> people = new TreeMap<>();
            state.people().forEach(person -> people.put(person.login(), person));
            final Map<String, RegistryState.Host> hosts = new TreeMap<>();
            state.hosts().forEach(host -> hosts.put(host.host(), host));
            return new Model(
                    owners,
                    new TreeMap<>(state.hostAdmins()),
                    people,
                    hosts,
                    new TreeSet<>(state.revoked()),
                    new TreeMap<>(state.blobs()),
                    new TreeMap<>(state.changes()),
                    new java.util.LinkedHashSet<>(state.removed()));
        }

        Model copy() {
            return new Model(
                    new LinkedHashMap<>(owners),
                    new TreeMap<>(hostAdmins),
                    new TreeMap<>(people),
                    new TreeMap<>(hosts),
                    new TreeSet<>(revoked),
                    new TreeMap<>(blobs),
                    new TreeMap<>(changes),
                    new java.util.LinkedHashSet<>(removed));
        }

        /** Whether the key is an owner's own, never a backup key. */
        boolean owner(String fingerprint) {
            final RegistryState.Owner owner = owners.get(fingerprint);
            return owner != null && !owner.backup();
        }

        /** Whether the key is one of the host's admins' own, never a backup key. */
        boolean hostAdmin(String host, String fingerprint) {
            return hostAdmins.getOrDefault(host, List.of()).stream()
                    .anyMatch(admin -> admin.fingerprint().equals(fingerprint) && !admin.backup());
        }

        /** Whether the registry counts as accepted, by the one rule records share. */
        boolean accepted(boolean firm) {
            return RegistryState.accepts(firm, List.copyOf(people.values()), List.copyOf(removed));
        }

        RegistryState state(
                @Nullable String commit,
                boolean firm,
                List<String> warnings,
                List<RegistryState.Refusal> refusals) {
            return new RegistryState(
                    commit,
                    accepted(firm),
                    firm,
                    List.copyOf(owners.values()),
                    hostAdmins,
                    List.copyOf(people.values()),
                    List.copyOf(hosts.values()),
                    List.copyOf(revoked),
                    blobs,
                    changes,
                    warnings,
                    refusals,
                    // Once firm, no forge answer about a removal can take acceptance back.
                    firm ? List.of() : List.copyOf(removed));
        }
    }

    /** One walk along the first parent, accepting path by path. */
    private static final class Walk {
        private final RegistryRepository repository;
        private final ForgeDirectory forge;
        private final String organisation;
        private Model model;
        private final List<String> warnings = new ArrayList<>();

        /** The refusals whose path no later commit has had accepted, in walk order. */
        private final List<RegistryState.Refusal> refusals;

        /** Whether a path was accepted that rests on no first-seen card. */
        private boolean firm;

        Walk(
                RegistryRepository repository,
                ForgeDirectory forge,
                String organisation,
                RegistryState start) {
            this.repository = repository;
            this.forge = forge;
            this.organisation = organisation;
            this.model = Model.of(start);
            this.refusals = new ArrayList<>(start.refusals());
            this.firm = start.firm();
        }

        RegistryState state(@Nullable String reached) {
            return model.state(reached, firm, warnings, refusals);
        }

        /**
         * Asks the forge again about every card it could not settle at an earlier read, and every
         * card it refused: a plain answer about any key of the card's accepted chain replaces the
         * state of a first-seen card, and a listing makes a refused card trusted, its blob
         * accepted. Either change is warned about, named by the commit the trust record ended at.
         *
         * @param start The commit the trust record ended at, or {@code null} without one.
         */
        void settle(@Nullable String start) {
            if (start == null) {
                return;
            }
            for (RegistryState.Person person : List.copyOf(model.people().values())) {
                final boolean refused = person.state().equals(RegistryState.REFUSED);
                final String path = "people/" + person.login() + ".card";
                if (!refused && !person.state().equals(RegistryState.FIRST_SEEN)) {
                    // Trusted, and borne by no revoked key: a record holding one is refused whole.
                    continue;
                }
                // A revoked key is never vouched for, whatever the forge lists.
                final Trust trust =
                        revoked(person)
                                ? new Trust(RegistryState.REFUSED, REVOKED_KEY)
                                : chain(
                                        person.login(),
                                        person.fingerprint(),
                                        person.predecessors());
                if (trust.state().equals(RegistryState.TRUSTED)) {
                    model.people().put(person.login(), with(person, trust));
                    firm = true;
                    if (refused) {
                        model.blobs().put(path, person.blob());
                        final RegistryState.Change change = person.change();
                        model.changes().put(path, change);
                        // A later commit's refusal of the path stays in force, as a walk keeps it.
                        refusals.removeIf(
                                refusal ->
                                        refusal.path().equals(path)
                                                && earlierOrSame(
                                                        refusal.commit(), change.commit()));
                        warn(start, path, "the forge lists the card's key now; it is trusted");
                    }
                } else if (!refused && trust.state().equals(RegistryState.REFUSED)) {
                    model.people().put(person.login(), with(person, trust));
                    model.blobs().remove(path);
                    warn(start, path, trust.why());
                }
            }
            // A change is kept for every accepted file, and for no other.
            model.changes().keySet().retainAll(model.blobs().keySet());
        }

        /**
         * Judges each first-seen card whose removal keeps the registry accepted by what the forge
         * says of that card now: listed makes the registry firm, others listed take the removal
         * back. Run at the end of every read, fresh or from a record, so that both answer alike.
         */
        void removals() {
            for (RegistryState.Removed each : List.copyOf(model.removed())) {
                if (firm) {
                    break;
                }
                final Trust trust =
                        each.predecessors().stream().anyMatch(model.revoked()::contains)
                                        || model.revoked().contains(each.fingerprint())
                                ? new Trust(RegistryState.REFUSED, REVOKED_KEY)
                                : chain(each.login(), each.fingerprint(), each.predecessors());
                if (trust.state().equals(RegistryState.TRUSTED)) {
                    firm = true;
                } else if (trust.state().equals(RegistryState.REFUSED)) {
                    model.removed().remove(each);
                }
            }
        }

        /** Whether a commit is the other or comes before it on the first-parent line walked. */
        private boolean earlierOrSame(String commit, String other) {
            return commit.equals(other)
                    || COMMIT.matcher(commit).matches()
                            && COMMIT.matcher(other).matches()
                            && repository
                                    .git(
                                            "merge-base",
                                            "--is-ancestor",
                                            "--end-of-options",
                                            commit,
                                            other)
                                    .successful();
        }

        /**
         * Whether a card's key, any key it was handed on from, or the predecessor it names is
         * revoked: the reason a card stays refused, at this read and every later one.
         */
        private boolean revoked(RegistryState.Person person) {
            return model.revoked().contains(person.fingerprint())
                    || model.revoked().contains(person.predecessor())
                    || person.predecessors().stream().anyMatch(model.revoked()::contains);
        }

        /**
         * What the forge says about a card's chain: trusted when it lists any of its keys, refused
         * when it plainly lists none, first-seen otherwise.
         */
        private Trust chain(String login, String fingerprint, List<String> predecessors) {
            final List<String> all = new ArrayList<>(List.of(fingerprint));
            all.addAll(predecessors);
            // Each distinct key once, and no more of them than a chain may hold.
            final List<String> keys = all.stream().distinct().limit(CHAIN).toList();
            Trust current = null;
            boolean undecided = false;
            for (String key : keys) {
                final Trust trust = trust(login, key);
                if (trust.state().equals(RegistryState.TRUSTED)) {
                    return trust;
                }
                undecided |= trust.state().equals(RegistryState.FIRST_SEEN);
                if (current == null) {
                    current = trust;
                }
            }
            return undecided || current == null ? new Trust(RegistryState.FIRST_SEEN, "") : current;
        }

        /**
         * Judges one commit path by path.
         *
         * @return Whether the walk goes on: {@code false} when the forge could not decide the
         *     commit, which then changes nothing.
         */
        boolean commit(String commit, String parent) throws IOException {
            final List<String> diff =
                    new ArrayList<>(
                            List.of(
                                    "diff-tree",
                                    "--no-commit-id",
                                    "--no-renames",
                                    "--name-only",
                                    "-r",
                                    "-z"));
            if (parent.isEmpty()) {
                diff.add("--root");
            }
            diff.add("--end-of-options");
            if (!parent.isEmpty()) {
                diff.add(parent);
            }
            diff.add(commit);
            final List<String> paths =
                    List.of(repository.require(diff.toArray(String[]::new)).split("\u0000"))
                            .stream()
                            .filter(RegistryHistory::judged)
                            .toList();
            if (paths.isEmpty()) {
                return true;
            }
            final String signer;
            try {
                signer = signer(commit);
            } catch (Refused refusal) {
                paths.forEach(path -> refuse(commit, path, refusal.why()));
                return true;
            }
            final Model before = model.copy();
            final boolean firmBefore = firm;
            final List<RegistryState.Refusal> refusedBefore = List.copyOf(refusals);
            final int warnedBefore = warnings.size();
            for (String path : paths) {
                try {
                    // Removing what was never accepted changes nothing, and accepts nothing.
                    if (judge(before, commit, path, signer)) {
                        firm |= !firstSeen(before, path);
                        model.changes().put(path, new RegistryState.Change(commit, signer));
                        refusals.removeIf(refusal -> refusal.path().equals(path));
                    }
                    // A change is kept for every accepted file, and for no other.
                    model.changes().keySet().retainAll(model.blobs().keySet());
                } catch (Refused refusal) {
                    refuse(commit, path, refusal.why());
                } catch (Undecided undecided) {
                    model = before;
                    firm = firmBefore;
                    refusals.clear();
                    refusals.addAll(refusedBefore);
                    warnings.subList(warnedBefore, warnings.size()).clear();
                    warn(commit, path, undecided.why());
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether a path just accepted rests on a first-seen card: the card now held is one, or it
         * removed one, either of which a forge that refuses the card takes back.
         */
        private boolean firstSeen(Model before, String path) {
            final Matcher person = PERSON.matcher(path);
            if (!person.matches()) {
                return false;
            }
            final RegistryState.Person now = model.people().get(person.group(1));
            final RegistryState.Person was = before.people().get(person.group(1));
            return now != null
                    ? now.state().equals(RegistryState.FIRST_SEEN)
                    : was != null && was.state().equals(RegistryState.FIRST_SEEN);
        }

        /** The fingerprint of the key that verifiably signed the commit, if it is not revoked. */
        private String signer(String commit) throws Refused, IOException {
            final String key;
            try {
                key =
                        CommitSigner.claimedKey(
                                repository.require(
                                        "cat-file", "commit", "--end-of-options", commit));
            } catch (CardException ex) {
                throw new Refused(String.valueOf(ex.getMessage()));
            }
            final String signer = PersonCard.fingerprint(key);
            if (!CommitSigner.verifies(repository, commit, key)) {
                throw new Refused("commit's SSH signature does not verify against " + signer);
            }
            if (model.revoked().contains(signer)) {
                throw new Refused("commit signed by " + signer + ", which is revoked");
            }
            return signer;
        }

        /** Judges one path; returns whether it changed the state. */
        private boolean judge(Model before, String commit, String path, String signer)
                throws Refused, Undecided {
            final Matcher person = PERSON.matcher(path);
            final Matcher host = HOST_FILE.matcher(path);
            if (path.equals(OWNERS)) {
                return owners(before, commit, signer);
            } else if (person.matches()) {
                return person(before, commit, path, person.group(1), signer);
            } else if (host.matches()) {
                return host(
                        before, commit, path, host.group(1), host.group(2).equals("card"), signer);
            }
            return false;
        }

        private boolean owners(Model before, String commit, String signer)
                throws Refused, Undecided {
            final Optional<String> object = object(commit, OWNERS);
            if (object.isEmpty()) {
                if (before.blobs().containsKey(OWNERS)) {
                    // Without it nobody owns the registry, and the next one would found it anew.
                    throw new Refused("owners.yml cannot be removed once accepted");
                }
                return false;
            }
            final RegistryOwners file;
            try {
                file = RegistryOwners.read(text(object.orElseThrow(), commit, OWNERS), OWNERS);
            } catch (CardException ex) {
                throw new Refused(String.valueOf(ex.getMessage()));
            }
            if (!before.blobs().containsKey(OWNERS)) {
                founding(file, signer);
            } else if (!before.owner(signer)) {
                backedOnly(before, file, signer);
            }
            model.owners().clear();
            file.owners().forEach(entry -> model.owners().put(entry.fingerprint(), owner(entry)));
            model.hostAdmins().clear();
            file.hostAdmins()
                    .forEach(
                            (host, admins) ->
                                    model.hostAdmins()
                                            .put(host, admins.stream().map(Walk::owner).toList()));
            model.blobs().put(OWNERS, object.orElseThrow());
            return true;
        }

        /**
         * Judges an owners file signed by a backup key: it may change only the entries of the login
         * it backs, among the owners where it backs an owner, under a host where it backs that
         * host's admin; everything else must stay as it was.
         */
        private void backedOnly(Model before, RegistryOwners file, String signer) throws Refused {
            final Optional<String> owner = backs(List.copyOf(before.owners().values()), signer);
            final Map<String, String> admins = new TreeMap<>();
            before.hostAdmins()
                    .forEach(
                            (host, entries) ->
                                    backs(entries, signer)
                                            .ifPresent(login -> admins.put(host, login)));
            if (owner.isEmpty() && admins.isEmpty()) {
                throw new Refused("signed by " + signer + ", not a registry owner");
            }
            final List<RegistryState.Owner> owners =
                    file.owners().stream().map(Walk::owner).toList();
            boolean same =
                    without(owners, owner)
                            .equals(without(List.copyOf(before.owners().values()), owner));
            final Set<String> hosts = new TreeSet<>(before.hostAdmins().keySet());
            hosts.addAll(file.hostAdmins().keySet());
            for (String host : hosts) {
                final Optional<String> login = Optional.ofNullable(admins.get(host));
                same &=
                        without(
                                                file
                                                        .hostAdmins()
                                                        .getOrDefault(host, List.of())
                                                        .stream()
                                                        .map(Walk::owner)
                                                        .toList(),
                                                login)
                                        .equals(
                                                without(
                                                        before.hostAdmins()
                                                                .getOrDefault(host, List.of()),
                                                        login))
                                && file.hostAdmins().containsKey(host)
                                        == before.hostAdmins().containsKey(host);
            }
            if (!same) {
                throw new Refused(
                        "signed by "
                                + signer
                                + ", a backup key, which may change only the entries it backs");
            }
            if (owner.isPresent()) {
                replacesOnly(
                        List.copyOf(before.owners().values()),
                        owners,
                        owner.orElseThrow(),
                        signer,
                        before.revoked());
            }
            for (Map.Entry<String, String> admin : admins.entrySet()) {
                replacesOnly(
                        before.hostAdmins().getOrDefault(admin.getKey(), List.of()),
                        file.hostAdmins().getOrDefault(admin.getKey(), List.of()).stream()
                                .map(Walk::owner)
                                .toList(),
                        admin.getValue(),
                        signer,
                        before.revoked());
            }
        }

        /**
         * Refuses unless the backed login's entries after the change are its backup key's entry, as
         * it was, and one new key of its own in place of the old one, which is not revoked.
         */
        private static void replacesOnly(
                List<RegistryState.Owner> before,
                List<RegistryState.Owner> after,
                String login,
                String signer,
                Set<String> revoked)
                throws Refused {
            final List<RegistryState.Owner> was =
                    before.stream().filter(entry -> entry.login().equals(login)).toList();
            final List<RegistryState.Owner> is =
                    after.stream().filter(entry -> entry.login().equals(login)).toList();
            final List<RegistryState.Owner> kept =
                    was.stream()
                            .filter(entry -> entry.backup() && entry.fingerprint().equals(signer))
                            .toList();
            final List<RegistryState.Owner> keys =
                    is.stream().filter(entry -> !entry.backup()).toList();
            if (!is.stream().filter(RegistryState.Owner::backup).toList().equals(kept)
                    || keys.size() != 1
                    || was.stream()
                            .anyMatch(
                                    entry ->
                                            entry.fingerprint()
                                                    .equals(keys.getFirst().fingerprint()))
                    || revoked.contains(keys.getFirst().fingerprint())) {
                throw new Refused(
                        "signed by "
                                + signer
                                + ", a backup key, which may only put one new key, not revoked,"
                                + " in place of the key of "
                                + login
                                + " it backs");
            }
        }

        /** The login whose backup key this is in the list, if it is one. */
        private static Optional<String> backs(List<RegistryState.Owner> entries, String signer) {
            return entries.stream()
                    .filter(entry -> entry.backup() && entry.fingerprint().equals(signer))
                    .map(RegistryState.Owner::login)
                    .findFirst();
        }

        private static List<RegistryState.Owner> without(
                List<RegistryState.Owner> entries, Optional<String> login) {
            return entries.stream()
                    .filter(entry -> login.isEmpty() || !entry.login().equals(login.orElseThrow()))
                    .toList();
        }

        /**
         * Judges the first owners file: its signer must be an owner it names, whom the forge
         * plainly says owns the organisation, with this key.
         *
         * @throws Refused When the forge says no.
         * @throws Undecided When it cannot say.
         */
        private void founding(RegistryOwners file, String signer) throws Refused, Undecided {
            final RegistryOwners.Entry founder =
                    file.owners().stream()
                            .filter(entry -> entry.fingerprint().equals(signer) && !entry.backup())
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new Refused(
                                                    "the first owners.yml is signed by "
                                                            + signer
                                                            + ", not by an owner it names"));
            final Optional<Boolean> owns = forge.owns(organisation, founder.login());
            if (owns.isPresent() && !owns.orElseThrow()) {
                throw new Refused(
                        founder.login()
                                + " is not an owner of the forge organisation "
                                + organisation);
            }
            final Optional<ForgeDirectory.Keys> keys = forge.keys(founder.login(), signer);
            final boolean listed = keys.isPresent() && keys.orElseThrow().lists(signer);
            if (keys.isPresent() && keys.orElseThrow().complete() && !listed) {
                throw new Refused("the forge lists no key " + signer + " for " + founder.login());
            }
            if (owns.isEmpty() || !listed) {
                throw new Undecided(
                        "undecided: the forge could not say whether "
                                + founder.login()
                                + " owns "
                                + organisation
                                + " with "
                                + signer
                                + "; read again with a forge token");
            }
        }

        /**
         * Judges a person's card.
         *
         * @return Whether it changed the state: {@code false} for a card the forge refuses, which
         *     is answered but accepts nothing.
         */
        private boolean person(
                Model before, String commit, String path, String login, String signer)
                throws Refused, Undecided {
            final RegistryState.Person held = before.people().get(login);
            // A refused card is no card: the next card for the login is judged as a first one.
            final RegistryState.Person prior =
                    held == null || held.state().equals(RegistryState.REFUSED) ? null : held;
            final Optional<String> object = object(commit, path);
            if (object.isEmpty()) {
                if (prior == null) {
                    // Nothing accepted to remove: a refused card's answer goes, and nothing is
                    // accepted.
                    model.people().remove(login);
                    return false;
                }
                if (!signer.equals(prior.fingerprint()) && !before.owner(signer)) {
                    throw new Refused(
                            "removed by "
                                    + signer
                                    + ", neither the card's key nor a registry owner");
                }
                model.people().remove(login);
                model.blobs().remove(path);
                if (prior.state().equals(RegistryState.FIRST_SEEN)) {
                    // Accepted only as far as the card was: kept, so that settling asks again.
                    model.removed()
                            .add(
                                    new RegistryState.Removed(
                                            login, prior.fingerprint(), prior.predecessors()));
                }
                return true;
            }
            final PersonCard card =
                    personCard(
                            text(object.orElseThrow(), commit, path),
                            path,
                            login,
                            signer,
                            before::owner);
            final Trust trust;
            List<String> predecessors = List.of();
            final Trust own = owned(before, path, prior, card, login, signer);
            if (own != null) {
                trust = own;
            } else if (prior == null) {
                // An owner may put a person's own card in place of a refused one.
                if (!signer.equals(card.fingerprint()) && !(held != null && before.owner(signer))) {
                    throw new Refused(
                            "signed by "
                                    + signer
                                    + ", not the card's own key "
                                    + card.fingerprint());
                }
                trust = trust(login, card.fingerprint());
            } else if (prior.fingerprint().equals(card.fingerprint())) {
                if (!signer.equals(card.fingerprint())) {
                    throw new Refused(
                            "signed by "
                                    + signer
                                    + ", not the card's own key "
                                    + card.fingerprint());
                }
                trust = new Trust(prior.state(), prior.why());
                predecessors = prior.predecessors();
            } else if (changeOfKey(before, commit, path, card, prior, signer)) {
                predecessors = handedOn(card, prior);
                // A first-seen chain is asked as settling asks it, the new key first, so that a
                // fresh read and a read from its own record answer the same.
                trust =
                        prior.state().equals(RegistryState.FIRST_SEEN)
                                ? chain(login, card.fingerprint(), predecessors)
                                : new Trust(prior.state(), prior.why());
            } else {
                // An owner's replacement vouches for the login, not the key: the forge says.
                trust = trust(login, card.fingerprint());
            }
            final RegistryState.Person read =
                    new RegistryState.Person(
                            login,
                            card.name(),
                            card.email(),
                            card.key(),
                            card.fingerprint(),
                            trust.state(),
                            trust.why(),
                            predecessors,
                            named(card),
                            object.orElseThrow(),
                            new RegistryState.Change(commit, signer));
            // The key the card names as its predecessor counts in every case, not only the chain.
            final RegistryState.Person judged =
                    revoked(read)
                            ? with(read, new Trust(RegistryState.REFUSED, REVOKED_KEY))
                            : read;
            model.people().put(login, judged);
            if (judged.state().equals(RegistryState.REFUSED)) {
                // Answered, but not the path's accepted blob; whatever it replaced is gone.
                model.blobs().remove(path);
                warn(commit, path, judged.why());
                return false;
            }
            model.blobs().put(path, object.orElseThrow());
            return true;
        }

        /**
         * The trust of the login's own card in place of one the forge never vouched for: a
         * first-seen card holds the login only until a card signed by a key the forge lists for it
         * comes, which is then judged as a first card. A card the prior's key handed on is a change
         * of key instead.
         *
         * @return The new card's trust, or {@code null} when it is judged as a change of the prior.
         * @throws Undecided When the forge cannot say whether it lists the new key: judged as a
         *     change of key the card would be refused, and the login's own card lost for good.
         */
        private @Nullable Trust owned(
                Model before,
                String path,
                RegistryState.@Nullable Person prior,
                PersonCard card,
                String login,
                String signer)
                throws Undecided {
            if (prior == null
                    || !prior.state().equals(RegistryState.FIRST_SEEN)
                    || prior.fingerprint().equals(card.fingerprint())
                    || !signer.equals(card.fingerprint())
                    || handsOn(before, path, card, prior)) {
                return null;
            }
            final Trust trust = trust(login, card.fingerprint());
            if (trust.state().equals(RegistryState.FIRST_SEEN)) {
                throw new Undecided(
                        "undecided: the forge could not say whether it lists "
                                + card.fingerprint()
                                + " for "
                                + login
                                + ", whose card a first-seen one holds; read again when the forge"
                                + " can be asked");
            }
            return trust.state().equals(RegistryState.TRUSTED) ? trust : null;
        }

        /** Whether the prior's key handed the card on, through no revoked key. */
        private boolean handsOn(
                Model before, String path, PersonCard card, RegistryState.Person prior) {
            try {
                PersonCardChain.check(
                        card,
                        earlier(before, path),
                        prior.fingerprint(),
                        before.revoked()::contains);
                return true;
            } catch (CardException ex) {
                return false;
            }
        }

        /**
         * The keys a card handed on from the prior's was handed on from, newest first, each once
         * and never the card's own.
         *
         * @throws Refused When the chain would hold more than {@link #CHAIN} distinct keys.
         */
        private static List<String> handedOn(PersonCard card, RegistryState.Person prior)
                throws Refused {
            final List<String> all = new ArrayList<>(List.of(prior.fingerprint()));
            all.addAll(prior.predecessors());
            final List<String> chain =
                    all.stream().filter(key -> !key.equals(card.fingerprint())).distinct().toList();
            if (chain.size() + 1 > CHAIN) {
                throw new Refused("too many keys handed on");
            }
            return chain;
        }

        /** What the forge's key list says about a login's key. */
        private Trust trust(String login, String fingerprint) {
            final Optional<ForgeDirectory.Keys> keys = forge.keys(login, fingerprint);
            if (keys.isEmpty()) {
                return new Trust(RegistryState.FIRST_SEEN, "");
            }
            if (keys.orElseThrow().lists(fingerprint)) {
                return new Trust(RegistryState.TRUSTED, "");
            }
            if (!keys.orElseThrow().complete()) {
                // A list the forge gave only in part proves no key missing.
                return new Trust(RegistryState.FIRST_SEEN, "");
            }
            return new Trust(
                    RegistryState.REFUSED,
                    "the forge lists other keys for " + login + ", not " + fingerprint);
        }

        private static RegistryState.Person with(RegistryState.Person person, Trust trust) {
            return new RegistryState.Person(
                    person.login(),
                    person.name(),
                    person.email(),
                    person.key(),
                    person.fingerprint(),
                    trust.state(),
                    trust.why(),
                    person.predecessors(),
                    person.predecessor(),
                    person.blob(),
                    person.change());
        }

        /**
         * Judges a card with a new key: signed by the new key and handed on by the old one, or a
         * replacement a registry owner signs, which revokes the old key from this commit on: every
         * card held with that key, or handed on from it, under any login, is refused from here.
         *
         * @return Whether the old key handed it on; {@code false} for an owner's replacement.
         */
        private boolean changeOfKey(
                Model before,
                String commit,
                String path,
                PersonCard card,
                RegistryState.Person prior,
                String signer)
                throws Refused {
            String refused = "signed by " + signer + ", neither the new key nor a registry owner";
            if (signer.equals(card.fingerprint())) {
                try {
                    PersonCardChain.check(
                            card,
                            earlier(before, path),
                            prior.fingerprint(),
                            before.revoked()::contains);
                    return true;
                } catch (CardException ex) {
                    refused = String.valueOf(ex.getMessage());
                }
            }
            if (!before.owner(signer)) {
                throw new Refused(refused);
            }
            model.revoked().add(prior.fingerprint());
            for (RegistryState.Person held : List.copyOf(model.people().values())) {
                if (!held.state().equals(RegistryState.REFUSED) && revoked(held)) {
                    final String heldPath = "people/" + held.login() + ".card";
                    model.people()
                            .put(
                                    held.login(),
                                    with(held, new Trust(RegistryState.REFUSED, REVOKED_KEY)));
                    model.blobs().remove(heldPath);
                    if (!heldPath.equals(path)) {
                        warn(commit, heldPath, REVOKED_KEY);
                    }
                }
            }
            return false;
        }

        /** The card last accepted for the path, the one a change of key must lead back to. */
        private List<PersonCard> earlier(Model before, String path) {
            final String object = before.blobs().get(path);
            if (object == null) {
                return List.of();
            }
            try {
                return List.of(PersonCard.read(repository.blob(object, path), path));
            } catch (CardException ex) {
                return List.of();
            }
        }

        private boolean host(
                Model before,
                String commit,
                String path,
                String host,
                boolean isCard,
                String signer)
                throws Refused {
            final Optional<String> object = object(commit, path);
            if (object.isEmpty() && !before.blobs().containsKey(path)) {
                return false;
            }
            if (!before.hostAdmin(host, signer)) {
                throw new Refused(
                        "signed by " + signer + ", not a host admin owners.yml names for " + host);
            }
            final RegistryState.Host prior =
                    model.hosts()
                            .getOrDefault(
                                    host, new RegistryState.Host(host, null, List.of(), List.of()));
            final RegistryState.Host next;
            if (object.isEmpty()) {
                next =
                        isCard
                                ? new RegistryState.Host(
                                        host, null, prior.admins(), prior.projects())
                                : new RegistryState.Host(host, prior.card(), List.of(), List.of());
            } else if (isCard) {
                next =
                        new RegistryState.Host(
                                host,
                                hostCard(
                                        text(object.orElseThrow(), commit, path),
                                        path,
                                        host,
                                        signer),
                                prior.admins(),
                                prior.projects());
            } else {
                final RegistryAccess access;
                try {
                    access = RegistryAccess.read(text(object.orElseThrow(), commit, path), path);
                } catch (CardException ex) {
                    // The access file's own messages may quote what it holds.
                    throw new Refused("not a readable access file");
                }
                next =
                        new RegistryState.Host(
                                host,
                                prior.card(),
                                access.admins().entrySet().stream()
                                        .map(
                                                admin ->
                                                        new RegistryState.Admin(
                                                                admin.getKey(), admin.getValue()))
                                        .toList(),
                                access.projects().stream()
                                        .map(
                                                project ->
                                                        new RegistryState.Project(
                                                                project.name(),
                                                                project.admin(),
                                                                project.accessRepository()))
                                        .toList());
            }
            if (object.isEmpty()) {
                model.blobs().remove(path);
            } else {
                model.blobs().put(path, object.orElseThrow());
            }
            final boolean held =
                    model.blobs().containsKey("hosts/" + host + "/card")
                            || model.blobs().containsKey("hosts/" + host + "/access.yml");
            if (held) {
                model.hosts().put(host, next);
            } else {
                model.hosts().remove(host);
            }
            return true;
        }

        private Optional<String> object(String commit, String path) throws Refused {
            try {
                return repository.object(commit, path);
            } catch (CardException ex) {
                throw new Refused("expected a regular file");
            }
        }

        private String text(String object, String commit, String path) throws Refused {
            final String origin = commit + " " + path + ": ";
            try {
                return repository.blob(object, commit + " " + path);
            } catch (CardException ex) {
                final String message = String.valueOf(ex.getMessage());
                throw new Refused(
                        message.startsWith(origin)
                                ? message.substring(origin.length())
                                : "the file cannot be read");
            }
        }

        private void warn(String commit, String path, String why) {
            warnings.add(RegistryText.printable(commit + " " + path + ": " + why));
        }

        private void refuse(String commit, String path, String why) {
            warn(commit, path, why);
            refusals.add(new RegistryState.Refusal(commit, path, RegistryText.printable(why)));
        }

        private static RegistryState.Owner owner(RegistryOwners.Entry entry) {
            return new RegistryState.Owner(
                    entry.login(), entry.key(), entry.fingerprint(), entry.backup());
        }
    }
}
