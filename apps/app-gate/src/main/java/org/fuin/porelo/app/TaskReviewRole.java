package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.ForgeInstances;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.Producer;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewRole;
import org.fuin.porelo.gate.review.Role;
import org.fuin.porelo.gate.review.Task;
import org.jspecify.annotations.Nullable;

/**
 * Decides what a task's role allows it, from the role its run recorded: the one class that tells an
 * author from a reviewer, so every other part asks it and names no role.
 *
 * <ul>
 *   <li>An author pushes, only replies, and is pinned only to a head its own gate took. Its items
 *       go out under the account that opened its pull request: the project's builds credential's
 *       account when the project names one, else on a host the task's own forge account, never the
 *       person's. At its gate push they are the task's own.
 *   <li>A reviewer pushes nothing, opens threads and replies, and may be pinned to any head. At a
 *       pull request its items go out under the owner account's forge account, with the credential
 *       of the forge the machine knows; at a gate push it has no posting account yet.
 * </ul>
 *
 * <p>Neither role approves. Answers name vault entries, never a credential.
 */
public final class TaskReviewRole implements ReviewRole {

    /** Why a reviewer at a gate push is refused an account, for the refusal and the exception. */
    private static final String NO_GATE_REVIEWER =
            "a reviewer at a gate push posts as no account yet";

    private final Function<String, Path> states;

    private final Entries entries;

    private final ForgeInstances forges;

    private final Accounts accounts;

    private final Ends ends;

    private final Forwarded forwarded;

    /**
     * The vault entries a task's items may go out under, by name; never a credential.
     *
     * <p>A stand-in answers fixed names and which of them the vault holds.
     */
    public interface Entries {

        /**
         * Whether this machine is a host, whose builds credential is the person's own.
         *
         * @return True on a host.
         */
        boolean onHost();

        /**
         * Returns the entry of the forge token a host made for the task's own account.
         *
         * @param task The task's name.
         * @return The entry, as {@link TaskReviewRole#taskForgeEntry} names it.
         */
        String taskAccount(String task);

        /**
         * Returns the builds credential the task's project names itself.
         *
         * @param task The task's name.
         * @return The entry, or empty when the project names no builds of its own.
         */
        Optional<String> projectBuilds(String task);

        /**
         * Whether the vault holds an entry.
         *
         * @param entry The entry's name.
         * @return False for one it does not hold, and when the vault cannot tell.
         */
        boolean held(String entry);
    }

    /** Names the forge account an entry's token belongs to, as the forge adapter answered it. */
    @FunctionalInterface
    public interface Accounts {

        /**
         * Returns the account an entry's token is at a pull request's forge.
         *
         * @param pullRequest The pull request the items go out at, whose forge is asked.
         * @param entry The entry's name.
         * @return The account.
         * @throws PlaceUnreadable When the forge cannot be asked or does not say.
         */
        ForgeAccount of(PullRequestRef pullRequest, String entry) throws PlaceUnreadable;
    }

    /** Reads where a task's last push stands at its own gate, as the gate kept it. */
    @FunctionalInterface
    public interface Ends {

        /**
         * Returns the task's kept push end.
         *
         * @param task The task's name.
         * @return It, or empty when its gate took no push of it.
         * @throws IOException When the record cannot be read.
         */
        Optional<PushEnd> of(String task) throws IOException;
    }

    /**
     * Reads what a task's gate last forwarded to one branch of an upstream, as the gate kept it.
     */
    @FunctionalInterface
    public interface Forwarded {

        /** Answers nothing: no run at a pull request is pinned. */
        Forwarded NONE = (task, upstream, branch) -> Optional.empty();

        /**
         * Returns the last commit the task's gate forwarded there.
         *
         * @param task The task's name.
         * @param upstream The upstream repository, as the project names it.
         * @param branch The upstream branch.
         * @return The commit in full, or empty when the gate forwarded nothing of the task there.
         * @throws IOException When the record cannot be read.
         */
        Optional<String> of(String task, String upstream, String branch) throws IOException;
    }

    /**
     * Constructor without the gates' forwarded record: an author is pinned at no pull request.
     *
     * @param states A task's state directory, by the task's name, where its run's role is kept.
     * @param entries The vault entries a task's items may go out under.
     * @param forges The forges this machine knows, whose credential is the owner account's there.
     * @param accounts The forge account an entry's token is.
     * @param ends Where a task's last push stands at its gate.
     */
    public TaskReviewRole(
            final Function<String, Path> states,
            final Entries entries,
            final ForgeInstances forges,
            final Accounts accounts,
            final Ends ends) {
        this(states, entries, forges, accounts, ends, Forwarded.NONE);
    }

    /**
     * Constructor.
     *
     * @param states A task's state directory, by the task's name, where its run's role is kept.
     * @param entries The vault entries a task's items may go out under.
     * @param forges The forges this machine knows, whose credential is the owner account's there.
     * @param accounts The forge account an entry's token is.
     * @param ends Where a task's last push stands at its gate.
     * @param forwarded What a task's gate last forwarded to an upstream branch.
     */
    public TaskReviewRole(
            final Function<String, Path> states,
            final Entries entries,
            final ForgeInstances forges,
            final Accounts accounts,
            final Ends ends,
            final Forwarded forwarded) {
        this.forwarded = Objects.requireNonNull(forwarded, "forwarded");
        this.states = Objects.requireNonNull(states, "states");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.forges = Objects.requireNonNull(forges, "forges");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.ends = Objects.requireNonNull(ends, "ends");
    }

    /**
     * Returns the vault entry of the forge token a host made for a task, as the task's secrets keep
     * it.
     *
     * @param container The task's container.
     * @return The entry's name.
     */
    public static String taskForgeEntry(final String container) {
        return TaskSecrets.PREFIX + container + TaskSecrets.FORGE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only a task whose state holds no recorded role is an author; one whose record exists but
     * does not read is refused every answer.
     *
     * @throws IllegalStateException When the task's recorded role does not read.
     */
    @Override
    public Role role(final String task) {
        final Path state = states.apply(task);
        final Path file = state.resolve(RunPlace.FILE);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Role.AUTHOR;
        }
        final RunPlace recorded =
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        ? RunPlace.readFrom(state)
                        : null;
        if (recorded == null) {
            // Never read as an author's: a record that does not read could have been a reviewer's.
            throw new IllegalStateException(
                    "the recorded role of task " + task + " does not read; it is given no right");
        }
        return Role.of(recorded.role());
    }

    @Override
    public boolean mayPush(final String task) {
        return role(task) == Role.AUTHOR;
    }

    @Override
    public Producer postingAccount(final String task, final ReviewPlace place) {
        final Posting posting;
        try {
            if (role(task) == Role.AUTHOR && pullRequest(place) == null) {
                // At its own gate push its items go into Porelo's review journal: no forge entry.
                return new Task(task);
            }
            posting = posting(task, place);
            final PullRequestRef ref =
                    Objects.requireNonNull(
                            posting.ref(),
                            "only an author at a gate push posts with no pull request");
            return accounts.of(ref, posting.entry());
        } catch (PlaceUnreadable ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    @Override
    public String postingEntry(final String task, final ReviewPlace place) throws PlaceUnreadable {
        return posting(task, place).entry();
    }

    @Override
    public boolean mayOpenThread(final String task, final ReviewPlace place) {
        return role(task) == Role.REVIEWER;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A reviewer may be pinned to any head. An author only to a head its own gate took, as the
     * gate kept its last push: waiting, approved or rejected alike, since a rejected author still
     * answers that head's threads. A record that cannot be read pins nothing.
     */
    @Override
    public boolean mayPin(final String task, final String head) {
        return switch (role(task)) {
            case REVIEWER -> true;
            case AUTHOR -> tookAtGate(task, head);
        };
    }

    /**
     * Whether a run at a pull request may be pinned to a head: a reviewer to any, an author only to
     * the commit its own gate last forwarded to that upstream branch, which is what the pull
     * request shows of its work. A record that cannot be read pins nothing.
     *
     * @param task The task's name.
     * @param upstream The upstream repository the pull request is at, as the project names it.
     * @param branch The upstream branch the pull request is open from.
     * @param head The pull request's head, to be pinned.
     * @return True when it may be pinned there.
     * @throws RunPlaceCheck.Refused As {@code NotTheTasksPush} with the head, when it may not.
     */
    public boolean mayPinAtPullRequest(
            final String task, final String upstream, final String branch, final String head) {
        if (role(task) == Role.REVIEWER) {
            return true;
        }
        Optional<String> passed;
        try {
            passed = forwarded.of(task, upstream, branch);
        } catch (IOException ex) {
            passed = Optional.empty();
        }
        if (passed.filter(head::equals).isPresent()) {
            return true;
        }
        throw new RunPlaceCheck.Refused(
                RunPlaceCheck.Refusal.unavailable(
                        "NotTheTasksPush",
                        Map.of("head", head),
                        "the pull request's head is not what this task's gate passed on to "
                                + branch));
    }

    /** Names no entry, credential or token. */
    @Override
    public String toString() {
        return "TaskReviewRole";
    }

    /**
     * The entry and place a task's items go out under, which both posting answers read, so they
     * cannot disagree.
     *
     * @param ref The pull request, or null for a gate push.
     * @param entry The vault entry, which the vault holds.
     */
    private record Posting(@Nullable PullRequestRef ref, String entry) {}

    private Posting posting(final String task, final ReviewPlace place) throws PlaceUnreadable {
        final Role role = role(task);
        final PullRequestRef ref = pullRequest(place);
        final String entry =
                switch (role) {
                    case AUTHOR -> authorEntry(task);
                    case REVIEWER -> reviewerEntry(ref);
                };
        if (!entries.held(entry)) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.MISSING_FORGE_TOKEN,
                    "the vault holds no '" + entry + "' to write this task's items with");
        }
        return new Posting(ref, entry);
    }

    /** The account that opened the task's pull request: the project's own builds, else a host's. */
    private String authorEntry(final String task) throws PlaceUnreadable {
        final Optional<String> own = entries.projectBuilds(task);
        final String entry;
        if (own.isPresent()) {
            entry = own.get();
        } else if (entries.onHost()) {
            // On a host the builds credential is the person's, whose approval the forge then
            // refuses.
            entry = entries.taskAccount(task);
        } else {
            throw new PlaceUnreadable(PlaceUnreadable.Reason.MISSING_FORGE_TOKEN, noAuthorEntry());
        }
        if (entry.startsWith(ForgeInstance.CREDENTIAL_PREFIX)) {
            // A forge's credential is the owner account's there; its entry is not repeated.
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.MISSING_FORGE_TOKEN,
                    "the entry named for this task's items is a forge's own credential, which an"
                            + " author never writes under");
        }
        return entry;
    }

    private String reviewerEntry(final @Nullable PullRequestRef ref) throws PlaceUnreadable {
        if (ref == null) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.FORGE_ADAPTER_MISSING, NO_GATE_REVIEWER);
        }
        final Optional<ForgeInstance> instance;
        try {
            instance = forges.of(ref.url());
        } catch (ForgeFileUnreadable ex) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.FORGE_FILE_UNREADABLE,
                    "the machine's forge file cannot be read: " + ex.getMessage());
        }
        if (instance.isEmpty()) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNKNOWN_FORGE,
                    "the machine knows no forge at " + ref.forge());
        }
        final String unreadable = instance.get().unreadable();
        if (unreadable != null) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNKNOWN_FORGE,
                    "the machine's entry of the forge at "
                            + ref.forge()
                            + " cannot be read: "
                            + unreadable);
        }
        return instance.get().credential();
    }

    private boolean tookAtGate(final String task, final String head) {
        try {
            return ends.of(task).map(PushEnd::commit).filter(head::equals).isPresent();
        } catch (IOException ex) {
            // A record that cannot be read shows no head the gate took; nothing is pinned.
            return false;
        }
    }

    /**
     * Returns the pull request a place is, or null for a gate push, by the place's name as {@link
     * ReviewPlace#name()} gives it: a pull request's https address, a gate push's repository and
     * name. A name in the https scheme, in any case, that is no pull request's address is refused.
     */
    private static @Nullable PullRequestRef pullRequest(final ReviewPlace place)
            throws PlaceUnreadable {
        final String name = place.name();
        if (!name.regionMatches(true, 0, "https:", 0, "https:".length())) {
            return null;
        }
        try {
            return PullRequestRef.of(new URI(name));
        } catch (URISyntaxException | IllegalArgumentException ex) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNKNOWN_FORGE,
                    "the place's name is no pull request's https address with a host");
        }
    }

    private static String noAuthorEntry() {
        return "the project names no builds credential, and off a host the task has no forge"
                + " account of its own to write its items as";
    }
}
