package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.RegistryText;
import org.jspecify.annotations.Nullable;

/**
 * Walks the first-parent line, judging every commit path by path against the state before it. Write
 * access to the repository is wide, so a commit's signer alone proves nothing: {@code owners.yml}
 * counts from an owner, a person's card from its own key, a project's card from an owner, and its
 * members from one of its admins, or from an owner for its admins alone. A refused path keeps the
 * state it had and the walk goes on; every other path is never read.
 *
 * <p>A change of a card's key, its handing on and an owner's replacement of it are refused, not
 * guessed at, until this reader takes them.
 */
final class TreeWalk {

    private static final String OWNERS = "owners.yml";

    private static final Pattern PERSON = Pattern.compile("people/([^/]+)\\.card");

    private static final Pattern PROJECT_FILE =
            Pattern.compile("projects/([^/]+)/(card|members\\.yml)");

    /** No project of an organization may carry the name of the one each machine has by itself. */
    private static final String RESERVED = "default";

    private final TreeRepository repository;

    private final IdentitySource identities;

    private final String organisation;

    private Model model = new Model(new ArrayList<>(), new TreeMap<>(), new TreeMap<>());

    private final List<Organization.Refusal> refusals = new ArrayList<>();

    /** What has been accepted so far; copied before each commit, which is judged against it. */
    private record Model(
            List<TreeFiles.Owner> owners,
            Map<String, Person> people,
            Map<String, ProjectState> projects) {

        Model copy() {
            return new Model(
                    new ArrayList<>(owners), new TreeMap<>(people), new TreeMap<>(projects));
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
     */
    private record Person(String fingerprint, String state) {}

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
        final Model before = model.copy();
        final List<Organization.Refusal> refusedBefore = List.copyOf(refusals);
        for (final String path : paths) {
            try {
                judge(before, commit, path, signer);
                refusals.removeIf(refusal -> refusal.path().equals(path));
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

    private void judge(
            final Model before, final String commit, final String path, final String signer)
            throws Refused, Undecided, IOException {
        final Matcher person = PERSON.matcher(path);
        final Matcher project = PROJECT_FILE.matcher(path);
        if (path.equals(OWNERS)) {
            owners(before, commit, signer);
        } else if (person.matches()) {
            person(before, commit, path, person.group(1), signer);
        } else if (project.matches()) {
            if (project.group(1).equals(RESERVED)) {
                throw new Refused("no project of an organization may be named " + RESERVED);
            }
            if (project.group(2).equals("card")) {
                projectCard(before, commit, path, project.group(1), signer);
            } else {
                members(before, commit, path, project.group(1), signer);
            }
        }
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
            throw new Refused("signed by " + signer + ", not an owner");
        }
        model.owners().clear();
        model.owners().addAll(file);
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

    private void person(
            final Model before,
            final String commit,
            final String path,
            final String login,
            final String signer)
            throws Refused {
        final Person held = before.people().get(login);
        // A refused card is no card: the next card for the login is judged as a first one.
        final Person prior =
                held == null || held.state().equals(Organization.REFUSED) ? null : held;
        final Optional<String> text = text(commit, path);
        if (text.isEmpty()) {
            if (prior != null && !signer.equals(prior.fingerprint()) && !before.owner(signer)) {
                throw new Refused("removed by " + signer + ", neither the card's key nor an owner");
            }
            model.people().remove(login);
            return;
        }
        final PersonCard card;
        try {
            card = PersonCard.read(text.orElseThrow(), path);
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
        if (!signer.equals(card.fingerprint())) {
            throw new Refused("signed by " + signer + ", not the card's own key");
        }
        if (!card.predecessor().isEmpty()
                || prior != null && !prior.fingerprint().equals(card.fingerprint())) {
            throw new Refused("a change of key is not read yet");
        }
        final String state = prior != null ? prior.state() : trust(login, card.fingerprint());
        model.people().put(login, new Person(card.fingerprint(), state));
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

    /** An owner may add and remove a project's admins, and nothing else there. */
    private static void adminsOnly(
            final List<TreeFiles.Member> before, final List<TreeFiles.Member> after)
            throws Refused {
        final Set<TreeFiles.Member> others = new LinkedHashSet<>(before);
        others.removeIf(TreeWalk::unlockedAdmin);
        final Set<TreeFiles.Member> kept = new LinkedHashSet<>(after);
        kept.removeIf(TreeWalk::unlockedAdmin);
        if (!others.equals(kept)) {
            throw new Refused("an owner may add and remove a project's admins, nothing else there");
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
