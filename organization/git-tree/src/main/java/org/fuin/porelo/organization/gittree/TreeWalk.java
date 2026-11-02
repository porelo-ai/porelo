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
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.PersonCardChain;
import org.fuin.porelo.vault.RegistryText;
import org.jspecify.annotations.Nullable;

/**
 * Walks the first-parent line, judging every commit path by path against the state before it. Write
 * access to the repository is wide, so a commit's signer alone proves nothing: {@code owners.yml}
 * counts from an owner, a person's card from its own key, a project's card from an owner, and its
 * members from one of its admins, or from an owner for its admins alone; a host from an owner when
 * it is made or removed, and from its admins between. A refused path keeps the state it had and the
 * walk goes on; every other path is never read.
 *
 * <p>A card's key changes when the old key hands it on, or when an owner replaces it, which revokes
 * the old key from that commit on: nothing it signs counts again, and no card holding it stands.
 */
final class TreeWalk {

    private static final String OWNERS = "owners.yml";

    private static final Pattern PERSON = Pattern.compile("people/([^/]+)\\.card");

    private static final Pattern PROJECT_FILE =
            Pattern.compile("projects/([^/]+)/(card|members\\.yml|repository\\.yml)");

    private static final Pattern HOST_FILE = Pattern.compile("hosts/([^/]+)/(card|host\\.yml)");

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

    private TreeModel model = TreeModel.empty();

    /** For each login, each key's successors: the keys it was handed on to, read to the tip. */
    private final Map<String, Map<String, Set<String>>> steps = new TreeMap<>();

    private final List<Organization.Refusal> refusals = new ArrayList<>();

    /** The judged paths of the commit being judged. */
    private List<String> touched = List.of();

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
     * Walks the line, stopping before a commit that could not be decided, on from a record whose
     * commit the line must hold. {@link #readChains} comes first.
     *
     * @param revision Where the line ends, or {@code null} for the branch's tip.
     * @param start The record to go on from, or {@code null} to walk from the founding commit.
     * @return What was accepted, at the last commit reached.
     * @throws IOException When a signature cannot be checked.
     * @throws CardException When the history proves nothing.
     */
    TreeRecord read(final @Nullable String revision, final @Nullable TreeRecord start)
            throws IOException {
        String reached = null;
        boolean past = start == null;
        if (start != null) {
            model = start.model().copy();
            refusals.addAll(start.refusals());
            reached = start.commit();
        }
        for (final Map.Entry<String, String> entry : repository.line(revision)) {
            if (!past) {
                past = entry.getKey().equals(reached);
                continue;
            }
            if (!commit(entry.getKey(), entry.getValue())) {
                break;
            }
            reached = entry.getKey();
        }
        if (revision != null && !revision.equals(reached)) {
            throw new CardException(
                    "cannot say: the identity source could not say enough to reach the revision"
                            + " asked for, and the walk stopped at "
                            + (reached == null ? "the first commit, accepting nothing" : reached));
        }
        return new TreeRecord(reached, model, refusals);
    }

    /**
     * Whether the identity source now says otherwise of a card a record could not settle. A commit
     * the old answer stopped or refused would then be judged otherwise, so the record's walk is not
     * the one a fresh read would make.
     *
     * @param record The record.
     * @return Whether a card's state would change.
     */
    boolean unsettled(final TreeRecord record) {
        model = record.model().copy();
        boolean changed = false;
        for (final Map.Entry<String, TreeModel.Person> each : model.people().entrySet()) {
            final TreeModel.Person person = each.getValue();
            if (!person.state().equals(Organization.TRUSTED)
                    && !revoked(person)
                    && !person.state()
                            .equals(
                                    chain(
                                            each.getKey(),
                                            person.fingerprint(),
                                            person.handedOn()))) {
                changed = true;
            }
        }
        model = TreeModel.empty();
        return changed;
    }

    /**
     * The first pass: every change of key on the whole line, to the tip, whatever revision is asked
     * for, so that a new machine and an old one judge a past commit alike. A step counts only where
     * the new key signed the commit and both keys signed the card, so vouching for a key reaches
     * back only from a key its holder handed on to.
     */
    void readChains() throws IOException {
        for (final Map.Entry<String, String> entry : repository.line(null)) {
            for (final String path : repository.changed(entry.getKey(), entry.getValue())) {
                final Matcher person = PERSON.matcher(path);
                if (person.matches() && TreeFiles.login(person.group(1))) {
                    readStep(entry.getKey(), path, person.group(1));
                }
            }
        }
    }

    private void readStep(final String commit, final String path, final String login)
            throws IOException {
        try {
            final Optional<String> object = repository.object(commit, path);
            if (object.isEmpty()) {
                return;
            }
            final PersonCard card = PersonCard.read(repository.text(object.orElseThrow()), path);
            if (card.predecessor().isEmpty()
                    || !card.login().equals(login)
                    || !card.signedAsItMustBe()
                    || !PersonCard.fingerprint(repository.signer(commit))
                            .equals(card.fingerprint())) {
                return;
            }
            steps.computeIfAbsent(login, each -> new TreeMap<>())
                    .computeIfAbsent(named(card), each -> new TreeSet<>())
                    .add(card.fingerprint());
        } catch (CardException ex) {
            // What the walk refuses vouches for nothing; the second pass says why.
            return;
        }
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
        touched = paths;
        final TreeModel before = model.copy();
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
        final Matcher host = HOST_FILE.matcher(path);
        return path.equals(OWNERS)
                || person.matches() && TreeFiles.login(person.group(1))
                || project.matches() && project.group(1).matches(Project.NAME_PATTERN)
                || host.matches() && TreeFiles.host(host.group(1));
    }

    /** Judges one path; returns whether it was accepted. */
    private boolean judge(
            final TreeModel before, final String commit, final String path, final String signer)
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
            switch (project.group(2)) {
                case "card" -> projectCard(before, commit, path, project.group(1), signer);
                case "members.yml" -> members(before, commit, path, project.group(1), signer);
                default -> address(before, commit, path, project.group(1), signer);
            }
            return true;
        }
        final Matcher host = HOST_FILE.matcher(path);
        if (host.matches()) {
            if (host.group(2).equals("card")) {
                hostCard(before, commit, path, host.group(1), signer);
            } else {
                hostFile(before, commit, path, host.group(1), signer);
            }
            return true;
        }
        return false;
    }

    private void owners(final TreeModel before, final String commit, final String signer)
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
            final TreeModel before, final List<TreeFiles.Owner> file, final String signer)
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
        final String state = trust(founder.login(), signer);
        if (state.equals(Organization.REFUSED)) {
            throw new Refused("no key " + signer + " is listed for " + founder.login());
        }
        if (owns.isEmpty() || state.equals(Organization.FIRST_SEEN)) {
            throw new Undecided("the identity source could not say who founded it");
        }
    }

    /** Judges a person's card; returns whether it was accepted. */
    private boolean person(
            final TreeModel before,
            final String commit,
            final String path,
            final String login,
            final String signer)
            throws Refused, Undecided {
        final TreeModel.Person held = before.people().get(login);
        // A refused card is no card: the next card for the login is judged as a first one.
        final TreeModel.Person prior =
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
        final TreeModel.Person read =
                new TreeModel.Person(card.fingerprint(), state, handedOn, named(card));
        final TreeModel.Person judged = revoked(read) ? read.with(Organization.REFUSED) : read;
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
            final TreeModel before)
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
            final TreeModel before,
            final String path,
            final TreeModel.@Nullable Person prior,
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
            final TreeModel before,
            final String path,
            final PersonCard card,
            final TreeModel.Person prior) {
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
            final TreeModel before,
            final String path,
            final PersonCard card,
            final TreeModel.Person prior,
            final String signer)
            throws Refused {
        if (signer.equals(card.fingerprint()) && handsOn(before, path, card, prior)) {
            return true;
        }
        if (!before.owner(signer)) {
            throw new Refused("signed by " + signer + ", the old key did not hand the card on");
        }
        model.revoked().add(prior.fingerprint());
        for (final Map.Entry<String, TreeModel.Person> held :
                List.copyOf(model.people().entrySet())) {
            if (!held.getValue().state().equals(Organization.REFUSED) && revoked(held.getValue())) {
                model.people().put(held.getKey(), held.getValue().with(Organization.REFUSED));
                model.cards().remove("people/" + held.getKey() + ".card");
            }
        }
        return false;
    }

    /** The card last accepted for the path, the one a change of key must lead back to. */
    private List<PersonCard> earlier(final TreeModel before, final String path) {
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
    private static List<String> handedOn(final PersonCard card, final TreeModel.Person prior)
            throws Refused {
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
    private boolean revoked(final TreeModel.Person person) {
        return model.revoked().contains(person.fingerprint())
                || model.revoked().contains(person.predecessor())
                || person.handedOn().stream().anyMatch(model.revoked()::contains);
    }

    /** Trusted when the source lists any key of the chain, refused when it plainly lists none. */
    private String chain(
            final String login, final String fingerprint, final List<String> handedOn) {
        final Set<String> all = new LinkedHashSet<>(List.of(fingerprint));
        all.addAll(handedOn);
        List.copyOf(all).forEach(key -> successors(login, key, all));
        String current = null;
        boolean undecided = false;
        for (final String key : all.stream().limit(CHAIN).toList()) {
            final String state = listed(login, key);
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

    /** Adds the keys this one was handed on to, and theirs, up to the limit of a chain. */
    private void successors(final String login, final String key, final Set<String> all) {
        for (final String next : steps.getOrDefault(login, Map.of()).getOrDefault(key, Set.of())) {
            if (all.size() < CHAIN && all.add(next)) {
                successors(login, next, all);
            }
        }
    }

    /** What the identity source says about a key, or any it was handed on to by the tip. */
    private String trust(final String login, final String fingerprint) {
        return chain(login, fingerprint, List.of());
    }

    /** What the identity source says about this key alone. */
    private String listed(final String login, final String fingerprint) {
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
            final TreeModel before,
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
            leaveHosts(project);
            return;
        }
        final String admin = parsed(() -> TreeFiles.projectAdmin(text.orElseThrow(), project));
        final TreeModel.ProjectState prior = before.projects().get(project);
        model.projects()
                .put(
                        project,
                        prior == null
                                ? new TreeModel.ProjectState(admin, null, null)
                                : new TreeModel.ProjectState(
                                        admin, prior.members(), prior.address()));
    }

    private void members(
            final TreeModel before,
            final String commit,
            final String path,
            final String project,
            final String signer)
            throws Refused, Undecided {
        final TreeModel.ProjectState prior = before.projects().get(project);
        if (prior == null) {
            throw new Refused("no such project");
        }
        final Optional<String> text = text(commit, path);
        if (text.isEmpty()) {
            if (model.projects().containsKey(project)) {
                throw new Refused("members.yml cannot be removed while its project stands");
            }
            return;
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
        model.projects()
                .put(project, new TreeModel.ProjectState(prior.admin(), file, prior.address()));
    }

    /** A project's address counts from its admins alone, and goes only with the project. */
    private void address(
            final TreeModel before,
            final String commit,
            final String path,
            final String project,
            final String signer)
            throws Refused, Undecided {
        final TreeModel.ProjectState prior = before.projects().get(project);
        if (prior == null) {
            throw new Refused("no such project");
        }
        final Optional<String> text = text(commit, path);
        if (text.isEmpty()) {
            if (model.projects().containsKey(project)) {
                throw new Refused("repository.yml cannot be removed while its project stands");
            }
            return;
        }
        if (!admin(before, prior.members(), signer)) {
            throw new Refused("signed by " + signer + ", not its admin");
        }
        final String address = parsed(() -> TreeFiles.repositoryAddress(text.orElseThrow()));
        final TreeModel.ProjectState now = model.projects().getOrDefault(project, prior);
        model.projects()
                .put(project, new TreeModel.ProjectState(now.admin(), now.members(), address));
    }

    /** A project that goes leaves its host, so that one made again under its name is not on it. */
    private void leaveHosts(final String project) {
        model.hosts()
                .replaceAll(
                        (name, host) ->
                                new TreeModel.HostState(
                                        new TreeFiles.HostFile(
                                                host.file().admins(),
                                                host.file().projects().stream()
                                                        .filter(each -> !each.equals(project))
                                                        .toList()),
                                        host.card()));
    }

    /**
     * An owner makes a host with its first admin and nothing on it, and alone removes it;
     * everything between is its admins'.
     */
    private void hostFile(
            final TreeModel before,
            final String commit,
            final String path,
            final String host,
            final String signer)
            throws Refused, Undecided {
        final TreeModel.HostState prior = before.hosts().get(host);
        final Optional<String> text = text(commit, path);
        if (text.isEmpty()) {
            if (prior == null) {
                return;
            }
            if (!before.owner(signer)) {
                throw new Refused("signed by " + signer + ", not an owner");
            }
            model.hosts().remove(host);
            return;
        }
        final TreeFiles.HostFile file = parsed(() -> TreeFiles.hostFile(text.orElseThrow()));
        if (prior == null) {
            if (!before.owner(signer)) {
                throw new Refused("signed by " + signer + ", not an owner");
            }
            if (file.admins().size() != 1 || !file.projects().isEmpty()) {
                throw new Refused("a new host holds its first admin and nothing else");
            }
            model.hosts().put(host, new TreeModel.HostState(file, null));
            return;
        }
        if (!hostAdmin(before, prior, signer)) {
            throw new Refused("signed by " + signer + ", not its admin");
        }
        for (final String project : file.projects()) {
            carriable(before, host, prior, project);
        }
        model.hosts().put(host, new TreeModel.HostState(file, prior.card()));
    }

    /** A project newly on a host must stand, and be on no other host. */
    private static void carriable(
            final TreeModel before,
            final String host,
            final TreeModel.HostState prior,
            final String project)
            throws Refused {
        if (prior.file().projects().contains(project)) {
            return;
        }
        if (!before.projects().containsKey(project)) {
            throw new Refused("no such project");
        }
        final boolean elsewhere =
                before.hosts().entrySet().stream()
                        .anyMatch(
                                other ->
                                        !other.getKey().equals(host)
                                                && other.getValue()
                                                        .file()
                                                        .projects()
                                                        .contains(project));
        if (elsewhere) {
            throw new Refused("the project is on another host");
        }
    }

    /** A host's card counts from its admins; an owner removes it only with the host. */
    private void hostCard(
            final TreeModel before,
            final String commit,
            final String path,
            final String host,
            final String signer)
            throws Refused, Undecided {
        final TreeModel.HostState prior = before.hosts().get(host);
        if (prior == null) {
            throw new Refused("no such host");
        }
        final Optional<String> object = object(commit, path);
        if (object.isEmpty()
                && before.owner(signer)
                && touched.contains("hosts/" + host + "/host.yml")) {
            return;
        }
        if (!hostAdmin(before, prior, signer)) {
            throw new Refused("signed by " + signer + ", not its admin");
        }
        if (object.isEmpty()) {
            model.hosts().put(host, new TreeModel.HostState(prior.file(), null));
            return;
        }
        model.hosts()
                .put(
                        host,
                        new TreeModel.HostState(
                                prior.file(),
                                readHostCard(object.orElseThrow(), path, host, signer)));
    }

    private HostCard readHostCard(
            final String object, final String path, final String host, final String signer)
            throws Refused {
        final HostCard card;
        try {
            card = HostCard.read(repository.text(object), path);
        } catch (CardException ex) {
            // A card's own messages may quote what it holds.
            throw new Refused("not a readable host card");
        }
        if (!card.host().equals(host)) {
            throw new Refused("the card names another host than its path");
        }
        try {
            card.checkSignedBy(signer);
        } catch (CardException ex) {
            throw new Refused("the card is not signed by the commit's signer");
        }
        return card;
    }

    /** Whether the key is that of an admin of the host on their card; a backup key never is. */
    private static boolean hostAdmin(
            final TreeModel before, final TreeModel.HostState host, final String signer)
            throws Undecided {
        for (final TreeFiles.HostAdmin admin : host.file().admins()) {
            if (!admin.backup() && holds(before, admin.login(), signer)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the key is that of an unlocked admin among these members. */
    private static boolean admin(
            final TreeModel before,
            final @Nullable List<TreeFiles.Member> members,
            final String signer)
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
    private static boolean holds(final TreeModel before, final String login, final String signer)
            throws Undecided {
        final TreeModel.Person person = before.people().get(login);
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
