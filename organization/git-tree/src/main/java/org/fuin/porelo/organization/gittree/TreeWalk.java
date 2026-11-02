package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.PersonCardChain;
import org.fuin.porelo.vault.RegistryText;
import org.jspecify.annotations.Nullable;

/**
 * Walks the first-parent line, judging every commit path by path against the state before it. Write
 * access to the repository is wide, so a commit's signer alone proves nothing: {@code owners.yml}
 * counts from an owner, a person's card from its own key, a project's card from an owner, and its
 * members from one of its admins, or from an owner for its admins alone. A refused path keeps the
 * state it had and the walk goes on; every other path is never read.
 *
 * <p>A card's key changes when the old key hands it on, or when an owner replaces it, which revokes
 * the old key from that commit on: nothing it signs counts again, and no card holding it stands.
 */
final class TreeWalk {

    private static final String OWNERS = "owners.yml";

    private static final Pattern PERSON = Pattern.compile("people/([^/]+)\\.card");

    private static final Pattern PROJECT_FILE =
            Pattern.compile("projects/([^/]+)/(card|members\\.yml)");

    /** No project of an organization may carry the name of the one each machine has by itself. */
    private static final String RESERVED = "default";

    /**
     * The most distinct keys a person's chain holds: what the identity source is asked about for
     * one person at most, so a run of changes of key cannot spend its limit.
     */
    private static final int CHAIN = 16;

    private static final String OWNER_LIMIT =
            "an owner may add and remove a project's admins and change a role to or from admin,"
                    + " nothing else there";

    private final TreeRepository repository;

    private final IdentitySource identities;

    private final String organisation;

    private Model model =
            new Model(
                    new ArrayList<>(),
                    new TreeMap<>(),
                    new TreeMap<>(),
                    new TreeSet<>(),
                    new TreeMap<>());

    private final List<Organization.Refusal> refusals = new ArrayList<>();

    /** What has been accepted so far; copied before each commit, which is judged against it. */
    private record Model(
            List<TreeFiles.Owner> owners,
            Map<String, Person> people,
            Map<String, ProjectState> projects,
            Set<String> revoked,
            Map<String, String> cards) {

        Model copy() {
            return new Model(
                    new ArrayList<>(owners),
                    new TreeMap<>(people),
                    new TreeMap<>(projects),
                    new TreeSet<>(revoked),
                    new TreeMap<>(cards));
        }

        boolean owner(final String fingerprint) {
            return owners.stream()
                    .anyMatch(owner -> owner.fingerprint().equals(fingerprint) && !owner.backup());
        }
    }

    /**
     * A person's card as it stands.
     *
     * @param fingerprint The fingerprint of its key.
     * @param state {@link Organization#TRUSTED}, {@link Organization#FIRST_SEEN} or {@link
     *     Organization#REFUSED}.
     * @param handedOn The keys it was handed on from, newest first, never its own.
     * @param predecessor The fingerprint of the key the card names as its predecessor, or "".
     */
    private record Person(
            String fingerprint, String state, List<String> handedOn, String predecessor) {

        Person with(final String newState) {
            return new Person(fingerprint, newState, handedOn, predecessor);
        }
    }

    /**
     * A project as it stands.
     *
     * @param admin The first admin an owner named.
     * @param members Its members, or {@code null} before the first {@code members.yml}.
     */
    private record ProjectState(String admin, @Nullable List<TreeFiles.Member> members) {}

    /** Why one path of one commit is refused. */
    private static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        Refused(final String why) {
            super(why, null, false, false);
        }
    }

    /** Why a commit could not be decided, so that the next read judges it again. */
    private static final class Undecided extends Exception {
        private static final long serialVersionUID = 1L;

        Undecided(final String why) {
            super(why, null, false, false);
        }
    }

    TreeWalk(
            final TreeRepository repository,
            final IdentitySource identities,
            final String organisation) {
        this.repository = repository;
        this.identities = identities;
        this.organisation = organisation;
    }

    /**
     * Walks the line, stopping before a commit that could not be decided.
     *
     * @return What was accepted, at the last commit reached.
     * @throws IOException When a signature cannot be checked.
     * @throws CardException When the history proves nothing.
     */
    GitTreeOrganization read() throws IOException {
        String reached = null;
        for (final Map.Entry<String, String> entry : repository.line()) {
            if (!commit(entry.getKey(), entry.getValue())) {
                break;
            }
            reached = entry.getKey();
        }
        return answers(reached);
    }

    private GitTreeOrganization answers(final @Nullable String reached) {
        final Set<String> owners = new LinkedHashSet<>();
        model.owners().stream()
                .filter(owner -> !owner.backup())
                .forEach(owner -> owners.add(owner.login()));
        final Map<String, List<TreeFiles.Member>> members = new TreeMap<>();
        model.projects()
                .forEach(
                        (name, project) -> {
                            final List<TreeFiles.Member> listed = project.members();
                            members.put(name, listed == null ? List.of() : listed);
                        });
        return new GitTreeOrganization(reached, owners, members, refusals);
    }

    private boolean commit(final String commit, final String parent) throws IOException {
        final List<String> paths =
                repository.changed(commit, parent).stream().filter(TreeWalk::judged).toList();
        if (paths.isEmpty()) {
            return true;
        }
        final String signer;
        try {
            signer = PersonCard.fingerprint(repository.signer(commit));
        } catch (CardException ex) {
            paths.forEach(path -> refuse(commit, path, String.valueOf(ex.getMessage())));
            return true;
        }
        if (model.revoked().contains(signer)) {
            paths.forEach(
                    path -> refuse(commit, path, "signed by " + signer + ", which is revoked"));
            return true;
        }
        final Model before = model.copy();
        final List<Organization.Refusal> refusedBefore = List.copyOf(refusals);
        for (final String path : paths) {
            try {
                if (judge(before, commit, path, signer)) {
                    refusals.removeIf(refusal -> refusal.path().equals(path));
                }
            } catch (Refused refusal) {
                refuse(commit, path, String.valueOf(refusal.getMessage()));
            } catch (Undecided undecided) {
                model = before;
                refusals.clear();
                refusals.addAll(refusedBefore);
                return false;
            }
        }
        return true;
    }

    /** Whether a path is one the rule judges; every other path is never read. */
    private static boolean judged(final String path) {
        final Matcher person = PERSON.matcher(path);
        final Matcher project = PROJECT_FILE.matcher(path);
        return path.equals(OWNERS)
                || person.matches() && TreeFiles.login(person.group(1))
                || project.matches() && project.group(1).matches(Project.NAME_PATTERN);
    }

    /** Judges one path; returns whether it was accepted. */
    private boolean judge(
            final Model before, final String commit, final String path, final String signer)
            throws Refused, Undecided, IOException {
        final Matcher person = PERSON.matcher(path);
        final Matcher project = PROJECT_FILE.matcher(path);
        if (path.equals(OWNERS)) {
            owners(before, commit, signer);
            return true;
        }
        if (person.matches()) {
            return person(before, commit, path, person.group(1), signer);
        }
        if (project.matches()) {
            if (project.group(1).equals(RESERVED)) {
                throw new Refused("no project of an organization may be named " + RESERVED);
            }
            if (project.group(2).equals("card")) {
                projectCard(before, commit, path, project.group(1), signer);
            } else {
                members(before, commit, path, project.group(1), signer);
            }
            return true;
        }
        return false;
    }

    private void owners(final Model before, final String commit, final String signer)
            throws Refused, Undecided {
        final Optional<String> text = text(commit, OWNERS);
        if (text.isEmpty()) {
            if (!before.owners().isEmpty()) {
                throw new Refused("owners.yml cannot be removed once accepted");
            }
            return;
        }
        final List<TreeFiles.Owner> file = parsed(() -> TreeFiles.owners(text.orElseThrow()));
        if (before.owners().isEmpty()) {
            founding(file, signer);
        } else if (!before.owner(signer)) {
            backedOnly(before, file, signer);
        }
        model.owners().clear();
        model.owners().addAll(file);
    }

    /**
     * Judges an owners file signed by a backup key: it may put one new key, not revoked, in place
     * of the key of the login it backs, and everything else must stay as it was.
     */
    private static void backedOnly(
            final Model before, final List<TreeFiles.Owner> file, final String signer)
            throws Refused {
        final String login =
                before.owners().stream()
                        .filter(owner -> owner.backup() && owner.fingerprint().equals(signer))
                        .map(TreeFiles.Owner::login)
                        .findFirst()
                        .orElseThrow(() -> new Refused("signed by " + signer + ", not an owner"));
        final List<TreeFiles.Owner> was =
                before.owners().stream().filter(owner -> owner.login().equals(login)).toList();
        final List<TreeFiles.Owner> is =
                file.stream().filter(owner -> owner.login().equals(login)).toList();
        final List<TreeFiles.Owner> keys = is.stream().filter(owner -> !owner.backup()).toList();
        final boolean othersKept =
                before.owners().stream()
                        .filter(owner -> !owner.login().equals(login))
                        .toList()
                        .equals(
                                file.stream()
                                        .filter(owner -> !owner.login().equals(login))
                                        .toList());
        final boolean backupKept =
                is.stream()
                        .filter(TreeFiles.Owner::backup)
                        .toList()
                        .equals(
                                was.stream()
                                        .filter(
                                                owner ->
                                                        owner.backup()
                                                                && owner.fingerprint()
                                                                        .equals(signer))
                                        .toList());
        if (!othersKept
                || !backupKept
                || keys.size() != 1
                || was.stream()
                        .anyMatch(
                                owner -> owner.fingerprint().equals(keys.getFirst().fingerprint()))
                || before.revoked().contains(keys.getFirst().fingerprint())) {
            throw new Refused(
                    "signed by "
                            + signer
                            + ", a backup key, which may only put one new key, not revoked, in"
                            + " place of the key it backs");
        }
    }

    /**
     * Judges the first owners file: its signer must be an owner it names, whom the identity source
     * plainly says owns the organisation, with this key.
     */
    private void founding(final List<TreeFiles.Owner> file, final String signer)
            throws Refused, Undecided {
        final TreeFiles.Owner founder =
                file.stream()
                        .filter(owner -> owner.fingerprint().equals(signer) && !owner.backup())
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                "the first owners.yml is signed by "
                                                        + signer
                                                        + ", not by an owner it names"));
        final Optional<Boolean> owns = identities.owns(organisation, founder.login());
        if (owns.isPresent() && !owns.orElseThrow()) {
            throw new Refused(founder.login() + " does not own " + organisation);
        }
        final Optional<IdentitySource.Keys> keys = identities.keys(founder.login(), signer);
        final boolean listed =
                keys.isPresent() && PersonCard.lists(keys.orElseThrow().keys(), signer);
        if (keys.isPresent() && keys.orElseThrow().complete() && !listed) {
            throw new Refused("no key " + signer + " is listed for " + founder.login());
        }
        if (owns.isEmpty() || !listed) {
            throw new Undecided("the identity source could not say who founded it");
        }
    }

    /** Judges a person's card; returns whether it was accepted. */
    private boolean person(
            final Model before,
            final String commit,
            final String path,
            final String login,
            final String signer)
            throws Refused, Undecided {
        final Person held = before.people().get(login);
        // A refused card is no card: the next card for the login is judged as a first one.
        final Person prior =
                held == null || held.state().equals(Organization.REFUSED) ? null : held;
        final Optional<String> object = object(commit, path);
        if (object.isEmpty()) {
            if (prior != null && !signer.equals(prior.fingerprint()) && !before.owner(signer)) {
                throw new Refused("removed by " + signer + ", neither the card's key nor an owner");
            }
            model.people().remove(login);
            model.cards().remove(path);
            return prior != null;
        }
        final PersonCard card = card(object.orElseThrow(), path, login, signer, before);
        final String state;
        List<String> handedOn = List.of();
        final String own = owned(before, path, prior, card, login, signer);
        if (own != null) {
            state = own;
        } else if (prior == null) {
            // An owner may put a person's own card in place of a refused one.
            if (!signer.equals(card.fingerprint()) && !(held != null && before.owner(signer))) {
                throw new Refused("signed by " + signer + ", not the card's own key");
            }
            state = trust(login, card.fingerprint());
        } else if (prior.fingerprint().equals(card.fingerprint())) {
            if (!signer.equals(card.fingerprint())) {
                throw new Refused("signed by " + signer + ", not the card's own key");
            }
            state = prior.state();
            handedOn = prior.handedOn();
        } else if (changeOfKey(before, path, card, prior, signer)) {
            handedOn = handedOn(card, prior);
            state =
                    prior.state().equals(Organization.FIRST_SEEN)
                            ? chain(login, card.fingerprint(), handedOn)
                            : prior.state();
        } else {
            // An owner's replacement vouches for the login, not the key: the source says.
            state = trust(login, card.fingerprint());
        }
        final Person read = new Person(card.fingerprint(), state, handedOn, named(card));
        final Person judged = revoked(read) ? read.with(Organization.REFUSED) : read;
        model.people().put(login, judged);
        if (judged.state().equals(Organization.REFUSED)) {
            // Answered, but not the path's accepted card; whatever it replaced is gone.
            model.cards().remove(path);
            refuse(commit, path, "the card's key is revoked, or not the one listed for the login");
            return false;
        }
        model.cards().put(path, object.orElseThrow());
        return true;
    }

    /** Reads a card as it must be held: the path's login, signed by its key and any predecessor. */
    private PersonCard card(
            final String object,
            final String path,
            final String login,
            final String signer,
            final Model before)
            throws Refused {
        final PersonCard card;
        try {
            card = PersonCard.read(repository.text(object), path);
        } catch (CardException ex) {
            // A card's own messages may quote what it holds.
            throw new Refused("not a readable person card");
        }
        if (!card.login().equals(login)) {
            throw new Refused("the card names another login than its path");
        }
        if (!card.signedAsItMustBe()) {
            throw new Refused("the card is not signed as it must be");
        }
        if (!signer.equals(card.fingerprint()) && !before.owner(signer)) {
            throw new Refused("signed by " + signer + ", neither the card's key nor an owner");
        }
        return card;
    }

    /**
     * The state of the login's own card in place of one nobody vouched for: a first-seen card holds
     * the login only until a card signed by a key the source lists for it comes, which is then
     * judged as a first card. A card the prior's key handed on is a change of key instead.
     *
     * @return The new card's state, or {@code null} when it is judged as a change of the prior.
     * @throws Undecided When the source cannot say: judged as a change of key the card would be
     *     refused, and the login's own card lost for good.
     */
    private @Nullable String owned(
            final Model before,
            final String path,
            final @Nullable Person prior,
            final PersonCard card,
            final String login,
            final String signer)
            throws Undecided {
        if (prior == null
                || !prior.state().equals(Organization.FIRST_SEEN)
                || prior.fingerprint().equals(card.fingerprint())
                || !signer.equals(card.fingerprint())
                || handsOn(before, path, card, prior)) {
            return null;
        }
        final String state = trust(login, card.fingerprint());
        if (state.equals(Organization.FIRST_SEEN)) {
            throw new Undecided("the identity source could not say whose card this is");
        }
        return state.equals(Organization.TRUSTED) ? state : null;
    }

    /** Whether the prior's key handed the card on, through no revoked key. */
    private boolean handsOn(
            final Model before, final String path, final PersonCard card, final Person prior) {
        try {
            PersonCardChain.check(
                    card, earlier(before, path), prior.fingerprint(), before.revoked()::contains);
            return true;
        } catch (CardException ex) {
            return false;
        }
    }

    /**
     * Judges a card with a new key: handed on by the old one, or a replacement an owner signs,
     * which revokes the old key from this commit on.
     *
     * @return Whether the old key handed it on; {@code false} for an owner's replacement.
     */
    private boolean changeOfKey(
            final Model before,
            final String path,
            final PersonCard card,
            final Person prior,
            final String signer)
            throws Refused {
        if (signer.equals(card.fingerprint()) && handsOn(before, path, card, prior)) {
            return true;
        }
        if (!before.owner(signer)) {
            throw new Refused("signed by " + signer + ", the old key did not hand the card on");
        }
        model.revoked().add(prior.fingerprint());
        for (final Map.Entry<String, Person> held : List.copyOf(model.people().entrySet())) {
            if (!held.getValue().state().equals(Organization.REFUSED) && revoked(held.getValue())) {
                model.people().put(held.getKey(), held.getValue().with(Organization.REFUSED));
                model.cards().remove("people/" + held.getKey() + ".card");
            }
        }
        return false;
    }

    /** The card last accepted for the path, the one a change of key must lead back to. */
    private List<PersonCard> earlier(final Model before, final String path) {
        final String object = before.cards().get(path);
        if (object == null) {
            return List.of();
        }
        try {
            return List.of(PersonCard.read(repository.text(object), path));
        } catch (CardException ex) {
            return List.of();
        }
    }

    /** The keys a card handed on from the prior's was handed on from, each once. */
    private static List<String> handedOn(final PersonCard card, final Person prior) throws Refused {
        final List<String> all = new ArrayList<>(List.of(prior.fingerprint()));
        all.addAll(prior.handedOn());
        final List<String> chain =
                all.stream().filter(key -> !key.equals(card.fingerprint())).distinct().toList();
        if (chain.size() + 1 > CHAIN) {
            throw new Refused("too many keys handed on");
        }
        return chain;
    }

    /** The fingerprint of the key a card names as its predecessor, or "" when it names none. */
    private static String named(final PersonCard card) {
        return card.predecessor().isEmpty() ? "" : PersonCard.fingerprint(card.predecessor());
    }

    /** Whether a card's key, a key it was handed on from, or its predecessor is revoked. */
    private boolean revoked(final Person person) {
        return model.revoked().contains(person.fingerprint())
                || model.revoked().contains(person.predecessor())
                || person.handedOn().stream().anyMatch(model.revoked()::contains);
    }

    /** Trusted when the source lists any of a chain's keys, refused when it plainly lists none. */
    private String chain(
            final String login, final String fingerprint, final List<String> handedOn) {
        final List<String> all = new ArrayList<>(List.of(fingerprint));
        all.addAll(handedOn);
        String current = null;
        boolean undecided = false;
        for (final String key : all.stream().distinct().limit(CHAIN).toList()) {
            final String state = trust(login, key);
            if (state.equals(Organization.TRUSTED)) {
                return state;
            }
            undecided |= state.equals(Organization.FIRST_SEEN);
            if (current == null) {
                current = state;
            }
        }
        return undecided || current == null ? Organization.FIRST_SEEN : current;
    }

    /** What the identity source says about a login's key. */
    private String trust(final String login, final String fingerprint) {
        final Optional<IdentitySource.Keys> keys = identities.keys(login, fingerprint);
        if (keys.isEmpty()) {
            return Organization.FIRST_SEEN;
        }
        if (PersonCard.lists(keys.orElseThrow().keys(), fingerprint)) {
            return Organization.TRUSTED;
        }
        // A list given only in part proves no key missing.
        return keys.orElseThrow().complete() ? Organization.REFUSED : Organization.FIRST_SEEN;
    }

    private void projectCard(
            final Model before,
            final String commit,
            final String path,
            final String project,
            final String signer)
            throws Refused {
        if (!before.owner(signer)) {
            throw new Refused("signed by " + signer + ", not an owner");
        }
        final Optional<String> text = text(commit, path);
        if (text.isEmpty()) {
            model.projects().remove(project);
            return;
        }
        final String admin = parsed(() -> TreeFiles.projectAdmin(text.orElseThrow(), project));
        final ProjectState prior = before.projects().get(project);
        model.projects()
                .put(project, new ProjectState(admin, prior == null ? null : prior.members()));
    }

    private void members(
            final Model before,
            final String commit,
            final String path,
            final String project,
            final String signer)
            throws Refused, Undecided {
        final ProjectState prior = before.projects().get(project);
        if (prior == null) {
            throw new Refused("no such project");
        }
        final Optional<String> text = text(commit, path);
        if (text.isEmpty()) {
            throw new Refused("members.yml cannot be removed while its project stands");
        }
        final List<TreeFiles.Member> file = parsed(() -> TreeFiles.members(text.orElseThrow()));
        final List<TreeFiles.Member> members = prior.members();
        if (!admin(before, members, signer)) {
            if (members == null && holds(before, prior.admin(), signer)) {
                firstMembers(file, prior.admin());
            } else if (before.owner(signer)) {
                adminsOnly(members == null ? List.of() : members, file);
            } else {
                throw new Refused("signed by " + signer + ", neither its admin nor an owner");
            }
        }
        model.projects().put(project, new ProjectState(prior.admin(), file));
    }

    /** Whether the key is that of an unlocked admin among these members. */
    private static boolean admin(
            final Model before, final @Nullable List<TreeFiles.Member> members, final String signer)
            throws Undecided {
        if (members == null) {
            return false;
        }
        for (final TreeFiles.Member member : members) {
            if (member.role().equals(TreeFiles.ADMIN)
                    && !member.locked()
                    && holds(before, member.login(), signer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the key is the login's on its card. A card the identity source has not vouched for
     * yet gives no right: anybody with write access could have filed it first, so the commit waits.
     */
    private static boolean holds(final Model before, final String login, final String signer)
            throws Undecided {
        final Person person = before.people().get(login);
        if (person == null || !person.fingerprint().equals(signer)) {
            return false;
        }
        if (person.state().equals(Organization.FIRST_SEEN)) {
            throw new Undecided("the identity source has not vouched for the signer's card");
        }
        return person.state().equals(Organization.TRUSTED);
    }

    /** The card's admin writes the first members once, naming themselves an unlocked admin. */
    private static void firstMembers(final List<TreeFiles.Member> file, final String admin)
            throws Refused {
        if (!file.contains(new TreeFiles.Member(admin, TreeFiles.ADMIN, false))) {
            throw new Refused("the first members.yml does not name its card's admin as admin");
        }
    }

    /**
     * An owner may add and remove a project's admins and change a role to or from admin. Anything
     * else on an entry, a lock above all, stays the project's own.
     */
    private static void adminsOnly(
            final List<TreeFiles.Member> before, final List<TreeFiles.Member> after)
            throws Refused {
        final Map<String, TreeFiles.Member> was = new TreeMap<>();
        before.forEach(member -> was.put(member.login(), member));
        final Map<String, TreeFiles.Member> is = new TreeMap<>();
        after.forEach(member -> is.put(member.login(), member));
        for (final TreeFiles.Member member : before) {
            final TreeFiles.Member now = is.get(member.login());
            final boolean kept =
                    now == null
                            ? member.role().equals(TreeFiles.ADMIN)
                            : now.locked() == member.locked();
            if (!kept) {
                throw new Refused(OWNER_LIMIT);
            }
        }
        for (final TreeFiles.Member member : after) {
            if (!was.containsKey(member.login()) && !unlockedAdmin(member)) {
                throw new Refused(OWNER_LIMIT);
            }
        }
    }

    private static boolean unlockedAdmin(final TreeFiles.Member member) {
        return member.role().equals(TreeFiles.ADMIN) && !member.locked();
    }

    private Optional<String> text(final String commit, final String path) throws Refused {
        try {
            return repository.object(commit, path).map(repository::text);
        } catch (CardException ex) {
            throw new Refused(String.valueOf(ex.getMessage()));
        }
    }

    private Optional<String> object(final String commit, final String path) throws Refused {
        try {
            return repository.object(commit, path);
        } catch (CardException ex) {
            throw new Refused(String.valueOf(ex.getMessage()));
        }
    }

    /** Reads a file, whose refusal then counts for its path alone. */
    private static <T> T parsed(final Parser<T> parser) throws Refused {
        try {
            return parser.parse();
        } catch (CardException ex) {
            throw new Refused(String.valueOf(ex.getMessage()));
        }
    }

    /** Reads one file's text. */
    private interface Parser<T> {
        T parse();
    }

    private void refuse(final String commit, final String path, final String why) {
        refusals.add(new Organization.Refusal(commit, path, RegistryText.printable(why)));
    }
}
