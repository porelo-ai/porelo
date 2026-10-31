package org.fuin.porelo.vault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What a registry says once its history is read path by path: who owns it, who administers each
 * host, the people's cards and the hosts' files as last accepted, and what was refused on the way.
 *
 * <p>It is also the trust record kept between reads: the next read walks only the commits after
 * {@link #commit}, judging them against this state.
 *
 * @param commit The commit the state was read up to, a full lower-case object name; {@code null}
 *     when a first read stopped at the first commit, so that none was read.
 * @param owners The registry's owners, as {@code owners.yml} last accepted names them.
 * @param hostAdmins The admins of each host, by host label, as {@code owners.yml} names them.
 * @param people The people whose cards were accepted, by login, and those refused because the forge
 *     lists other keys, which are answered but hold no blob.
 * @param hosts The hosts with an accepted card or access file, by label.
 * @param revoked The fingerprints of keys an owner replaced; nothing they sign later is accepted.
 * @param blobs The blob last accepted for each registry path, by path; only what was accepted.
 * @param changes The commit each accepted file was accepted from, and its signer, by path: exactly
 *     the paths of {@code blobs}.
 * @param accepted Whether any commit had a path accepted, in this read or one before it: exactly
 *     whether it is firm, holds a first-seen card, or removed one; see {@link #accepts}.
 * @param firm Whether a path was accepted that rests on no first-seen card, so that no later answer
 *     of the forge takes it back.
 * @param warnings What this read refused or could not confirm, {@code "<commit> <path>: <why>"}.
 * @param refusals The refusals still in force, in walk order: a refusal leaves the list once a
 *     later commit has its path accepted, so it is kept across reads until then.
 * @param removed The first-seen cards whose removal was accepted while nothing was firm: the
 *     forge's later answer about each decides whether that removal stays accepted. None once firm.
 */
public record RegistryState(
        @Nullable String commit,
        boolean accepted,
        boolean firm,
        List<Owner> owners,
        Map<String, List<Owner>> hostAdmins,
        List<Person> people,
        List<Host> hosts,
        List<String> revoked,
        Map<String, String> blobs,
        Map<String, Change> changes,
        List<String> warnings,
        List<Refusal> refusals,
        List<Removed> removed) {

    /** A person's trust: the forge lists their key. */
    public static final String TRUSTED = "trusted";

    /** Accepted before the forge could say; asked again at the next read. */
    public static final String FIRST_SEEN = "first-seen";

    /**
     * The forge lists all its keys for the login, and not this one. A refused card is answered, but
     * is no card: the next card for the login is judged as a first one.
     */
    public static final String REFUSED = "refused";

    private static final String OWNERS = "owners.yml";

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");

    /** A predecessor a card names, or none. */
    private static final Pattern NAMED = Pattern.compile("|SHA256:[A-Za-z0-9+/]{43}");

    /** A card's text field, as a card holds it: no control character. */
    private static final Pattern TEXT =
            Pattern.compile("\\P{Cntrl}*", Pattern.UNICODE_CHARACTER_CLASS);

    /** Compact constructor: every collection is copied and cannot be changed afterwards. */
    public RegistryState {
        owners = List.copyOf(owners);
        final Map<String, List<Owner>> admins = new TreeMap<>();
        hostAdmins.forEach((host, list) -> admins.put(host, List.copyOf(list)));
        hostAdmins = Collections.unmodifiableMap(admins);
        people = List.copyOf(people);
        hosts = List.copyOf(hosts);
        revoked = List.copyOf(revoked);
        blobs = Collections.unmodifiableMap(new TreeMap<>(blobs));
        changes = Collections.unmodifiableMap(new TreeMap<>(changes));
        warnings = List.copyOf(warnings);
        refusals = List.copyOf(refusals);
        removed = List.copyOf(removed);
    }

    /**
     * A path of a commit the rule refused.
     *
     * @param commit The commit.
     * @param path The path.
     * @param why Why, printable and quoting nothing the file holds.
     */
    public record Refusal(String commit, String path, String why) {}

    /**
     * The commit a path last took its state from.
     *
     * @param commit The commit.
     * @param signer The fingerprint of the key the walk took as its signer, {@code SHA256:...}.
     */
    public record Change(String commit, String signer) {}

    /**
     * A project admin a host's access file names.
     *
     * @param login Their login.
     * @param fingerprint Their key's fingerprint, {@code SHA256:...}.
     */
    public record Admin(String login, String fingerprint) {}

    /**
     * Returns the last refusal whose path no later commit has had accepted.
     *
     * @return The refusal, or {@code null} when none is in force.
     */
    public @Nullable Refusal lastInForce() {
        return refusals.isEmpty() ? null : refusals.getLast();
    }

    /**
     * Returns the commit the read reached, if any commit was accepted on the way.
     *
     * @return {@link #commit}, or {@code null} when no commit was accepted.
     */
    public @Nullable String revision() {
        return accepted ? commit : null;
    }

    /**
     * An owner or a host admin.
     *
     * @param login The forge login.
     * @param key Their key, {@code ssh-ed25519 AAAA...}.
     * @param fingerprint The key's fingerprint, {@code SHA256:...}.
     * @param backup Whether this is an offline backup key.
     */
    public record Owner(String login, String key, String fingerprint, boolean backup) {}

    /**
     * A registry or host with one owner or admin and no backup key for them.
     *
     * @param role {@code registry-owner} or {@code host-admin}.
     * @param host The host for a host admin; {@code null} for the registry.
     * @param login Who it is.
     */
    public record Sole(String role, @Nullable String host, String login) {}

    /**
     * Writes an owners file as {@code owners.yml} is read: the owners, then each host's admins,
     * each entry with its login and key and, for a backup key, {@code backup: true}.
     *
     * @param owners The owners, in order.
     * @param hostAdmins The admins of each host.
     * @return The file's text.
     */
    public static String ownersFile(List<Owner> owners, Map<String, List<Owner>> hostAdmins) {
        final StringBuilder text = new StringBuilder("owners:\n");
        owners.forEach(owner -> entry(text, "  ", owner));
        if (!hostAdmins.isEmpty()) {
            text.append("host_admins:\n");
            hostAdmins.forEach(
                    (host, admins) -> {
                        text.append("  ").append(host).append(":\n");
                        admins.forEach(admin -> entry(text, "    ", admin));
                    });
        }
        return text.toString();
    }

    private static void entry(StringBuilder text, String indent, Owner owner) {
        text.append(indent)
                .append("- login: ")
                .append(owner.login())
                .append('\n')
                .append(indent)
                .append("  key: \"")
                .append(owner.key())
                .append("\"\n");
        if (owner.backup()) {
            text.append(indent).append("  backup: true\n");
        }
    }

    /**
     * The registry and each host whose one owner or admin has no backup key: losing that key leaves
     * nobody who may change it.
     *
     * @return Them, the registry first, then the hosts by name.
     */
    public List<Sole> soleWithoutBackup() {
        final List<Sole> sole = new ArrayList<>();
        sole(owners).ifPresent(login -> sole.add(new Sole("registry-owner", null, login)));
        new java.util.TreeMap<>(hostAdmins)
                .forEach(
                        (host, admins) ->
                                sole(admins)
                                        .ifPresent(
                                                login ->
                                                        sole.add(
                                                                new Sole(
                                                                        "host-admin",
                                                                        host,
                                                                        login))));
        return List.copyOf(sole);
    }

    private static java.util.Optional<String> sole(List<Owner> entries) {
        final List<Owner> own = entries.stream().filter(entry -> !entry.backup()).toList();
        return own.size() == 1
                        && entries.stream()
                                .noneMatch(
                                        entry ->
                                                entry.backup()
                                                        && entry.login()
                                                                .equals(own.getFirst().login()))
                ? java.util.Optional.of(own.getFirst().login())
                : java.util.Optional.empty();
    }

    /**
     * A person, as their card last accepted says.
     *
     * @param login The login, the identity.
     * @param name What they are called.
     * @param email How to reach them, or "".
     * @param key Their key, {@code ssh-ed25519 AAAA...}.
     * @param fingerprint The key's fingerprint, {@code SHA256:...}.
     * @param state {@link #TRUSTED}, {@link #FIRST_SEEN} or {@link #REFUSED}.
     * @param why Why it is refused, or "".
     * @param predecessors The fingerprints of the keys this key was handed on from, as the walk
     *     accepted each change, newest first; none for a first card or an owner's replacement. Only
     *     keys the person really held: what a card names alone is never one.
     * @param predecessor The fingerprint of the key the card names as its predecessor, or "" when
     *     it names none; a card is refused once that key is revoked, whichever way it was accepted.
     * @param blob The blob the card was read from: the path's accepted blob unless refused.
     * @param change The commit the card was read from, and its signer.
     */
    public record Person(
            String login,
            String name,
            String email,
            String key,
            String fingerprint,
            String state,
            String why,
            List<String> predecessors,
            String predecessor,
            String blob,
            Change change) {
        /** Compact constructor: the predecessors are copied and cannot be changed afterwards. */
        public Person {
            predecessors = List.copyOf(predecessors);
        }
    }

    /**
     * A host, as its card and access file last accepted say.
     *
     * @param host The host label.
     * @param card Its card, or {@code null} when none was accepted.
     * @param admins Its project admins, in the access file's order; none without one.
     * @param projects Its projects; none without an access file.
     */
    public record Host(
            String host, @Nullable Card card, List<Admin> admins, List<Project> projects) {
        /** Compact constructor: the collections are copied and cannot be changed afterwards. */
        public Host {
            admins = List.copyOf(admins);
            projects = List.copyOf(projects);
        }
    }

    /**
     * A host's card, as the registry verifier answers it.
     *
     * @param address Where the host is reached.
     * @param sshPort The port its ssh listens on.
     * @param sshHostKey The key its ssh answers with.
     * @param forgeWebPort The port of the forge's web interface.
     * @param forgeGitPort The port of the forge's git over ssh.
     * @param matrixPort The port of its Matrix server.
     * @param signedBy The fingerprint of the host admin who signed it.
     */
    public record Card(
            String address,
            int sshPort,
            String sshHostKey,
            int forgeWebPort,
            int forgeGitPort,
            int matrixPort,
            String signedBy) {

        static Card of(HostCard card, String signedBy) {
            return new Card(
                    card.address(),
                    card.sshPort(),
                    card.sshHostKey(),
                    card.forgeWebPort(),
                    card.forgeGitPort(),
                    card.matrixPort(),
                    signedBy);
        }

        /**
         * Returns the card as the registry verifier answers a host card.
         *
         * @param host The host label.
         * @return {@code host}, {@code address}, {@code ssh_port}, {@code ssh_host_key}, {@code
         *     services} and {@code signed_by}.
         */
        public Map<String, Object> answer(String host) {
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("host", host);
            answer.put("address", address);
            answer.put("ssh_port", sshPort);
            answer.put("ssh_host_key", sshHostKey);
            answer.put(
                    "services",
                    Map.of(
                            "forge",
                            Map.of("web_port", forgeWebPort, "git_port", forgeGitPort),
                            "matrix",
                            Map.of("port", matrixPort)));
            answer.put("signed_by", signedBy);
            return answer;
        }
    }

    /**
     * A project a host's access file names.
     *
     * @param name The project's name.
     * @param admin The login of its admin.
     * @param accessRepository Where its access repository is.
     */
    public record Project(String name, String admin, String accessRepository) {}

    /**
     * A first-seen card whose removal was accepted: the key the forge is asked about, and the keys
     * it was handed on from, as the card's own chain.
     *
     * @param login The login.
     * @param fingerprint The removed card's key's fingerprint.
     * @param predecessors The keys that card was handed on from, newest first.
     */
    public record Removed(String login, String fingerprint, List<String> predecessors) {
        /** Compact constructor: the predecessors are copied and cannot be changed afterwards. */
        public Removed {
            predecessors = List.copyOf(predecessors);
        }
    }

    /**
     * Whether a registry counts as accepted, the one rule every walk, settling and record share: a
     * path was accepted firmly, or a first-seen card is held, or one was removed, each of which the
     * forge's answer about that card may still take back.
     */
    static boolean accepts(boolean firm, List<Person> people, List<Removed> removed) {
        return firm
                || people.stream().anyMatch(person -> person.state().equals(FIRST_SEEN))
                || !removed.isEmpty();
    }

    /** The state before any commit: nothing accepted yet. */
    static RegistryState empty(@Nullable String commit) {
        return new RegistryState(
                commit, false, false, List.of(), Map.of(), List.of(), List.of(), List.of(),
                Map.of(), Map.of(), List.of(), List.of(), List.of());
    }

    /**
     * Writes the state as the trust record keeps it.
     *
     * @return The JSON document.
     * @throws IllegalStateException When no commit was read, so there is nothing to continue from.
     */
    public String toJson() {
        if (commit == null) {
            throw new IllegalStateException("a read that reached no commit leaves no trust record");
        }
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("commit", commit);
        record.put("accepted", accepted);
        record.put("firm", firm);
        record.put("owners", owners.stream().map(RegistryState::owner).toList());
        final Map<String, Object> admins = new LinkedHashMap<>();
        hostAdmins.forEach(
                (host, list) -> admins.put(host, list.stream().map(RegistryState::owner).toList()));
        record.put("host_admins", admins);
        record.put("people", people.stream().map(RegistryState::person).toList());
        record.put("hosts", hosts.stream().map(RegistryState::host).toList());
        record.put("revoked", revoked);
        record.put("blobs", blobs);
        final Map<String, Object> changed = new LinkedHashMap<>();
        changes.forEach((path, change) -> changed.put(path, change(change)));
        record.put("changes", changed);
        record.put("warnings", warnings);
        record.put(
                "refusals",
                refusals.stream()
                        .map(
                                refusal ->
                                        Map.of(
                                                "commit",
                                                refusal.commit(),
                                                "path",
                                                refusal.path(),
                                                "why",
                                                refusal.why()))
                        .toList());
        record.put(
                "removed",
                removed.stream()
                        .map(
                                each -> {
                                    final Map<String, Object> entry = new LinkedHashMap<>();
                                    entry.put("login", each.login());
                                    entry.put("fingerprint", each.fingerprint());
                                    entry.put("predecessors", each.predecessors());
                                    return entry;
                                })
                        .toList());
        return Json.write(record);
    }

    /**
     * Reads a trust record {@link #toJson} wrote. Anything malformed refuses the whole record, so a
     * damaged one is never half-used.
     *
     * @param text The JSON document.
     * @return The state.
     * @throws CardException Naming the field that is wrong, never quoting its value.
     */
    public static RegistryState fromJson(String text) {
        final Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException ex) {
            throw new CardException("the registry trust record is not well-formed JSON");
        }
        final Fields record = Fields.of(parsed, "the record");
        record.only(
                "commit",
                "accepted",
                "firm",
                "owners",
                "host_admins",
                "people",
                "hosts",
                "revoked",
                "blobs",
                "changes",
                "warnings",
                "refusals",
                "removed");
        final String commit = record.text("commit", COMMIT);
        final List<Owner> owners = new ArrayList<>();
        for (Object entry : record.list("owners")) {
            owners.add(owner(Fields.of(entry, "owners")));
        }
        final Map<String, List<Owner>> hostAdmins = new TreeMap<>();
        for (Map.Entry<String, Object> host : record.map("host_admins").entrySet()) {
            final List<Owner> admins = new ArrayList<>();
            for (Object entry : Fields.list(host.getValue(), "host_admins")) {
                admins.add(owner(Fields.of(entry, "host_admins")));
            }
            hostAdmins.put(host.getKey(), admins);
        }
        final List<Person> people = new ArrayList<>();
        for (Object entry : record.list("people")) {
            people.add(person(Fields.of(entry, "people")));
        }
        final List<Host> hosts = new ArrayList<>();
        for (Object entry : record.list("hosts")) {
            hosts.add(host(Fields.of(entry, "hosts")));
        }
        final List<String> revoked = new ArrayList<>();
        for (Object entry : record.list("revoked")) {
            revoked.add(Fields.text(entry, "revoked", FINGERPRINT));
        }
        final Map<String, String> blobs = new TreeMap<>();
        for (Map.Entry<String, Object> blob : record.map("blobs").entrySet()) {
            blobs.put(blob.getKey(), Fields.text(blob.getValue(), "blobs", COMMIT));
        }
        final Map<String, Change> changes = new TreeMap<>();
        for (Map.Entry<String, Object> change : record.map("changes").entrySet()) {
            changes.put(change.getKey(), change(Fields.of(change.getValue(), "changes")));
        }
        final List<String> warnings = new ArrayList<>();
        for (Object entry : record.list("warnings")) {
            warnings.add(Fields.text(entry, "warnings", null));
        }
        final List<Refusal> refusals = new ArrayList<>();
        for (Object entry : record.list("refusals")) {
            final Fields refusal = Fields.of(entry, "refusals");
            refusal.only("commit", "path", "why");
            refusals.add(
                    new Refusal(
                            refusal.text("commit", COMMIT),
                            refusal.text("path", null),
                            refusal.text("why", null)));
        }
        final List<Removed> removed = new ArrayList<>();
        for (Object entry : record.list("removed")) {
            final Fields each = Fields.of(entry, "removed");
            each.only("login", "fingerprint", "predecessors");
            final List<String> predecessors = new ArrayList<>();
            for (Object key : each.list("predecessors")) {
                predecessors.add(Fields.text(key, "removed", FINGERPRINT));
            }
            removed.add(
                    new Removed(each.login(), each.text("fingerprint", FINGERPRINT), predecessors));
        }
        final RegistryState state =
                new RegistryState(
                        commit,
                        record.bool("accepted"),
                        record.bool("firm"),
                        owners,
                        hostAdmins,
                        people,
                        hosts,
                        revoked,
                        blobs,
                        changes,
                        warnings,
                        refusals,
                        removed);
        state.validate();
        return state;
    }

    /**
     * Refuses a state no walk could have left, as a trust record or as the state a read starts
     * from: every name as the walk writes it, each blob the file the state was read from, each
     * change the commit it was accepted from, and nothing trusted that a revoked key bears.
     *
     * @throws CardException Naming the field that is wrong, never quoting its value.
     */
    void validate() {
        Fields.require(commit != null && COMMIT.matcher(commit).matches(), "commit");
        Fields.require(owners.stream().allMatch(RegistryState::valid), "owners");
        Fields.require(listed(owners), "owners");
        hostAdmins.forEach(
                (host, admins) ->
                        Fields.require(
                                RegistryOwners.HOST.matcher(host).matches()
                                        && admins.stream().allMatch(RegistryState::valid)
                                        && listed(admins)
                                        && !admins.isEmpty(),
                                "host_admins"));
        for (Person person : people) {
            Fields.require(valid(person), "people");
            // As the walk keeps them: each once, never the card's own key, the chain bounded.
            Fields.require(
                    person.predecessors().stream()
                                    .allMatch(each -> FINGERPRINT.matcher(each).matches())
                            && distinct(person.predecessors())
                            && !person.predecessors().contains(person.fingerprint())
                            && person.predecessors().size() < RegistryHistory.CHAIN,
                    "predecessors");
        }
        Fields.require(distinct(people.stream().map(Person::login).toList()), "people");
        Fields.require(
                hosts.stream().allMatch(host -> RegistryOwners.HOST.matcher(host.host()).matches()),
                "hosts");
        Fields.require(distinct(hosts.stream().map(Host::host).toList()), "hosts");
        Fields.require(
                revoked.stream().allMatch(each -> FINGERPRINT.matcher(each).matches()), "revoked");
        // A card of a revoked key, handed on from one, or naming one as its predecessor, is
        // refused, as the walk leaves it.
        Fields.require(
                people.stream()
                        .filter(person -> !person.state().equals(REFUSED))
                        .noneMatch(
                                person ->
                                        revoked.contains(person.fingerprint())
                                                || revoked.contains(person.predecessor())
                                                || person.predecessors().stream()
                                                        .anyMatch(revoked::contains)),
                "revoked");
        Fields.require(
                blobs.entrySet().stream()
                        .allMatch(
                                blob ->
                                        RegistryHistory.judged(blob.getKey())
                                                && COMMIT.matcher(blob.getValue()).matches()),
                "blobs");
        Fields.require(heldBy(blobs, owners, hostAdmins, people, hosts), "blobs");
        Fields.require(changes.values().stream().allMatch(RegistryState::valid), "changes");
        Fields.require(changed(changes, blobs, people, hosts), "changes");
        Fields.require(
                refusals.stream()
                        .allMatch(
                                refusal ->
                                        COMMIT.matcher(refusal.commit()).matches()
                                                && RegistryHistory.judged(refusal.path())),
                "path");
        // As a walk leaves them: owners, hosts and trusted cards are accepted firmly, a first-seen
        // card is accepted, and nothing is firm that is not accepted.
        Fields.require(
                (accepted || !firm)
                        && (firm
                                || owners.isEmpty()
                                        && hosts.isEmpty()
                                        && people.stream()
                                                .noneMatch(
                                                        person -> person.state().equals(TRUSTED)))
                        && (accepted
                                || people.stream()
                                        .noneMatch(person -> person.state().equals(FIRST_SEEN))),
                "firm");
        // Owners and hosts come only from accepted commits, and a host only once owners name its
        // admins; a refused card is all a record may hold before anything was accepted.
        Fields.require(
                accepted
                        || owners.isEmpty()
                                && hosts.isEmpty()
                                && people.stream()
                                        .allMatch(person -> person.state().equals(REFUSED)),
                "accepted");
        Fields.require(!owners.isEmpty() || hosts.isEmpty() && hostAdmins.isEmpty(), "owners");
        // Each removal once, its chain as a card's; none kept once firm, and accepted exactly as
        // the one rule says.
        Fields.require(
                removed.stream()
                                .allMatch(
                                        each ->
                                                RegistryOwners.login(each.login())
                                                        && FINGERPRINT
                                                                .matcher(each.fingerprint())
                                                                .matches()
                                                        && each.predecessors().stream()
                                                                .allMatch(
                                                                        key ->
                                                                                FINGERPRINT
                                                                                        .matcher(
                                                                                                key)
                                                                                        .matches())
                                                        && distinct(each.predecessors())
                                                        && !each.predecessors()
                                                                .contains(each.fingerprint())
                                                        && each.predecessors().size()
                                                                < RegistryHistory.CHAIN)
                        && new HashSet<>(removed).size() == removed.size()
                        && (!firm || removed.isEmpty()),
                "removed");
        Fields.require(accepted == accepts(firm, people, removed), "accepted");
    }

    /** Whether an owner's key is one, and its fingerprint the key's. */
    private static boolean valid(Owner owner) {
        return RegistryOwners.login(owner.login())
                && fingerprints(owner.key(), owner.fingerprint());
    }

    /** Whether a person is held as the walk holds one: every name and id as it writes them. */
    private static boolean valid(Person person) {
        return RegistryOwners.login(person.login())
                && TEXT.matcher(person.name()).matches()
                && TEXT.matcher(person.email()).matches()
                && fingerprints(person.key(), person.fingerprint())
                && Set.of(TRUSTED, FIRST_SEEN, REFUSED).contains(person.state())
                && NAMED.matcher(person.predecessor()).matches()
                && COMMIT.matcher(person.blob()).matches()
                && valid(person.change());
    }

    private static boolean valid(Change change) {
        return COMMIT.matcher(change.commit()).matches()
                && FINGERPRINT.matcher(change.signer()).matches();
    }

    private static boolean fingerprints(String key, String fingerprint) {
        try {
            return fingerprint.equals(PersonCard.fingerprint(key));
        } catch (CardException ex) {
            return false;
        }
    }

    private static Map<String, Object> change(Change change) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("commit", change.commit());
        entry.put("signer", change.signer());
        return entry;
    }

    private static Change change(Fields change) {
        change.only("commit", "signer");
        return new Change(change.text("commit", COMMIT), change.text("signer", FINGERPRINT));
    }

    /**
     * Whether the changes are exactly the accepted files', a card's the one its person was read
     * from, a host card's signed by the admin who signed the card.
     */
    private static boolean changed(
            Map<String, Change> changes,
            Map<String, String> blobs,
            List<Person> people,
            List<Host> hosts) {
        if (!changes.keySet().equals(blobs.keySet())) {
            return false;
        }
        for (Person person : people) {
            final Change change = changes.get("people/" + person.login() + ".card");
            if (!person.state().equals(REFUSED) && !person.change().equals(change)) {
                return false;
            }
        }
        for (Host host : hosts) {
            final Card card = host.card();
            final Change change = changes.get("hosts/" + host.host() + "/card");
            if (card != null && (change == null || !change.signer().equals(card.signedBy()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a list is as {@code owners.yml} lists it: each login once with its key and at most
     * once more with a backup key that backs it, each key once.
     */
    private static boolean listed(List<Owner> entries) {
        final List<String> logins =
                entries.stream().filter(entry -> !entry.backup()).map(Owner::login).toList();
        final List<String> backups =
                entries.stream().filter(Owner::backup).map(Owner::login).toList();
        return distinct(logins)
                && distinct(backups)
                && logins.containsAll(backups)
                && distinct(entries.stream().map(Owner::fingerprint).toList());
    }

    private static boolean distinct(List<String> names) {
        return new HashSet<>(names).size() == names.size();
    }

    /**
     * Whether the blobs are exactly the files the state was read from: the owners file when it
     * names owners, a card for each person not refused (the one that person was read from), and a
     * host's card and access file when it has them. A record that pairs them otherwise would judge
     * the next commit against the wrong file.
     */
    private static boolean heldBy(
            Map<String, String> blobs,
            List<Owner> owners,
            Map<String, List<Owner>> hostAdmins,
            List<Person> people,
            List<Host> hosts) {
        final Set<String> expected = new HashSet<>();
        if (!owners.isEmpty()) {
            expected.add(OWNERS);
        } else if (!hostAdmins.isEmpty()) {
            return false;
        }
        for (Person person : people) {
            final String path = "people/" + person.login() + ".card";
            if (!person.state().equals(REFUSED)) {
                if (!person.blob().equals(blobs.get(path))) {
                    return false;
                }
                expected.add(path);
            }
        }
        for (Host host : hosts) {
            final String card = "hosts/" + host.host() + "/card";
            final String access = "hosts/" + host.host() + "/access.yml";
            if (host.card() != null) {
                expected.add(card);
            } else if (!blobs.containsKey(access)) {
                return false;
            }
            if (blobs.containsKey(access)) {
                expected.add(access);
            } else if (!host.admins().isEmpty() || !host.projects().isEmpty()) {
                return false;
            }
        }
        return expected.equals(blobs.keySet());
    }

    private static Map<String, Object> owner(Owner owner) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("login", owner.login());
        entry.put("key", owner.key());
        entry.put("fingerprint", owner.fingerprint());
        entry.put("backup", owner.backup());
        return entry;
    }

    private static Owner owner(Fields entry) {
        entry.only("login", "key", "fingerprint", "backup");
        final String key = entry.key();
        return new Owner(entry.login(), key, entry.fingerprintOf(key), entry.bool("backup"));
    }

    private static Map<String, Object> person(Person person) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("login", person.login());
        entry.put("name", person.name());
        entry.put("email", person.email());
        entry.put("key", person.key());
        entry.put("fingerprint", person.fingerprint());
        entry.put("state", person.state());
        entry.put("why", person.why());
        entry.put("predecessors", person.predecessors());
        entry.put("predecessor", person.predecessor());
        entry.put("blob", person.blob());
        entry.put("change", change(person.change()));
        return entry;
    }

    private static Person person(Fields entry) {
        entry.only(
                "login",
                "name",
                "email",
                "key",
                "fingerprint",
                "state",
                "why",
                "predecessors",
                "predecessor",
                "blob",
                "change");
        final String key = entry.key();
        final List<String> predecessors = new ArrayList<>();
        for (Object each : entry.list("predecessors")) {
            predecessors.add(Fields.text(each, "predecessors", FINGERPRINT));
        }
        final String fingerprint = entry.fingerprintOf(key);
        return new Person(
                entry.login(),
                entry.text("name", TEXT),
                entry.text("email", TEXT),
                key,
                fingerprint,
                entry.oneOf("state", Set.of(TRUSTED, FIRST_SEEN, REFUSED)),
                entry.text("why", null),
                predecessors,
                entry.text("predecessor", NAMED),
                entry.text("blob", COMMIT),
                change(Fields.of(entry.get("change"), "change")));
    }

    private static Map<String, Object> host(Host host) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("host", host.host());
        final Card card = host.card();
        entry.put("card", card == null ? null : card.answer(host.host()));
        entry.put(
                "admins",
                host.admins().stream()
                        .map(
                                admin ->
                                        Map.of(
                                                "login",
                                                admin.login(),
                                                "fingerprint",
                                                admin.fingerprint()))
                        .toList());
        entry.put(
                "projects",
                host.projects().stream()
                        .map(
                                project ->
                                        Map.of(
                                                "name",
                                                project.name(),
                                                "admin",
                                                project.admin(),
                                                "access_repository",
                                                project.accessRepository()))
                        .toList());
        return entry;
    }

    private static Host host(Fields entry) {
        entry.only("host", "card", "admins", "projects");
        final String host = entry.text("host", RegistryOwners.HOST);
        final Card card =
                entry.has("card") ? card(Fields.of(entry.get("card"), "card"), host) : null;
        // As the access file is read: each admin once, each project once, by one of its admins.
        final List<Admin> admins = new ArrayList<>();
        final Set<String> logins = new HashSet<>();
        for (Object item : entry.list("admins")) {
            final Fields admin = Fields.of(item, "admins");
            admin.only("login", "fingerprint");
            final String login = admin.login();
            Fields.require(logins.add(login), "admins");
            admins.add(new Admin(login, admin.text("fingerprint", RegistryAccess.FINGERPRINT)));
        }
        final List<Project> projects = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        for (Object item : entry.list("projects")) {
            final Fields project = Fields.of(item, "projects");
            project.only("name", "admin", "access_repository");
            final String name = project.text("name", RegistryAccess.NAME);
            Fields.require(names.add(name), "projects");
            final String admin = project.text("admin", null);
            Fields.require(logins.contains(admin), "admin");
            final String repository = project.text("access_repository", null);
            Fields.require(RegistryAccess.accessRepository(repository), "access_repository");
            projects.add(new Project(name, admin, repository));
        }
        return new Host(host, card, admins, projects);
    }

    private static Card card(Fields card, String host) {
        card.only("host", "address", "ssh_port", "ssh_host_key", "services", "signed_by");
        Fields.require(card.text("host", null).equals(host), "card");
        final Fields services = Fields.of(card.get("services"), "services");
        services.only("forge", "matrix");
        final Fields forge = Fields.of(services.get("forge"), "forge");
        forge.only("web_port", "git_port");
        final Fields matrix = Fields.of(services.get("matrix"), "matrix");
        matrix.only("port");
        // As a host card is read: a person's machine writes these into its ssh configuration.
        final String address = card.text("address", null);
        Fields.require(HostCard.address(address), "address");
        final String hostKey = card.text("ssh_host_key", null);
        try {
            HostCard.hostKey(hostKey, "ssh_host_key");
        } catch (CardException ex) {
            throw Fields.invalid("ssh_host_key");
        }
        return new Card(
                address,
                card.port("ssh_port"),
                hostKey,
                forge.port("web_port"),
                forge.port("git_port"),
                matrix.port("port"),
                card.text("signed_by", FINGERPRINT));
    }

    /** One JSON object of the record, read strictly: every field there, and no other. */
    private record Fields(Map<String, Object> values, String where) {

        static Fields of(@Nullable Object value, String where) {
            if (!(value instanceof Map<?, ?> map)) {
                throw invalid(where);
            }
            final Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw invalid(where);
                }
                values.put(key, entry.getValue());
            }
            return new Fields(values, where);
        }

        static List<?> list(@Nullable Object value, String where) {
            if (!(value instanceof List<?> list)) {
                throw invalid(where);
            }
            return list;
        }

        static String text(@Nullable Object value, String where, @Nullable Pattern pattern) {
            if (!(value instanceof String text)
                    || pattern != null && !pattern.matcher(text).matches()) {
                throw invalid(where);
            }
            return text;
        }

        static void require(boolean valid, String where) {
            if (!valid) {
                throw invalid(where);
            }
        }

        void only(String... names) {
            require(values.keySet().equals(Set.of(names)), where);
        }

        boolean has(String name) {
            return values.get(name) != null;
        }

        @Nullable Object get(String name) {
            return values.get(name);
        }

        String text(String name, @Nullable Pattern pattern) {
            return text(values.get(name), name, pattern);
        }

        List<?> list(String name) {
            return list(values.get(name), name);
        }

        Map<String, Object> map(String name) {
            return of(values.get(name), name).values();
        }

        boolean bool(String name) {
            if (!(values.get(name) instanceof Boolean value)) {
                throw invalid(name);
            }
            return value;
        }

        String oneOf(String name, Set<String> allowed) {
            final String value = text(name, null);
            require(allowed.contains(value), name);
            return value;
        }

        String login() {
            final String login = text("login", null);
            require(RegistryOwners.login(login), "login");
            return login;
        }

        String key() {
            final String key = text("key", null);
            try {
                PersonCard.fingerprint(key);
            } catch (CardException ex) {
                throw invalid("key");
            }
            return key;
        }

        /** The fingerprint field, which must be the key's: a record cannot pair them otherwise. */
        String fingerprintOf(String key) {
            final String fingerprint = text("fingerprint", FINGERPRINT);
            require(fingerprint.equals(PersonCard.fingerprint(key)), "fingerprint");
            return fingerprint;
        }

        int port(String name) {
            if (!(values.get(name) instanceof Double number)
                    || number != Math.rint(number)
                    || number < 1
                    || number > 65_535) {
                throw invalid(name);
            }
            return number.intValue();
        }

        static CardException invalid(String where) {
            return new CardException(
                    "the registry trust record is not valid: " + where + " is malformed");
        }
    }
}
