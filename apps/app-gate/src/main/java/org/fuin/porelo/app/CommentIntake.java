package org.fuin.porelo.app;

import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.CommentCheck;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.Producer;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewAnchors;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewComments;
import org.fuin.porelo.gate.review.ReviewCounts;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.ReviewSarif;
import org.fuin.porelo.gate.review.ReviewStore;
import org.fuin.porelo.gate.review.ReviewWriter;
import org.fuin.porelo.gate.review.Task;
import org.jspecify.annotations.Nullable;

/**
 * Takes one comment or reply from a producer: checks it, judges whether the producer may write
 * where it points, and keeps it stamped with the producer.
 *
 * <p>The one way in for every channel: the daemon's socket, the command line and a task's reply all
 * come through here, so a rule holds for each of them alike. It owns no rule of its own but the
 * producer's authority over a thread: the owner account writes in any thread of the push; a task
 * only replies, and only in a thread of the push it is given, which is its own.
 */
public final class CommentIntake {

    private final CommentCheck check;

    private final ReviewStore store;

    /**
     * Constructor with what checks and what keeps.
     *
     * @param check The check of an item against its push.
     * @param store Where items are kept.
     */
    public CommentIntake(final CommentCheck check, final ReviewStore store) {
        this.check = Objects.requireNonNull(check, "check");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Checks an item, judges the producer's authority over its thread and keeps it.
     *
     * @param producer Who gave it, by the channel it came through.
     * @param push The push it is given for.
     * @param item What was given.
     * @param id The caller's retry id, or null.
     * @return The item as kept.
     * @throws ReviewRefused By the check's rule, {@code NoSuchThread} for a thread the producer may
     *     not write in, or the store's refusal; nothing is kept then.
     */
    public Kept take(
            final Producer producer,
            final Push push,
            final ReviewItem item,
            final @Nullable String id)
            throws ReviewRefused {
        final Optional<ReviewRefusal> refused = check.check(push, item);
        if (refused.isPresent()) {
            throw new ReviewRefused(refused.get());
        }
        authority(producer, push, item);
        // The producer is stamped from the channel, never taken from what was given.
        return store.keep(push, item, Origin.of(producer), id);
    }

    /** Refuses an item the producer may not write where it points. */
    private void authority(final Producer producer, final Push push, final ReviewItem item)
            throws ReviewRefused {
        // Exhaustive, so a new kind of producer is judged here before it can write anything.
        switch (producer) {
            case OwnerAccount _ -> {
                // A new thread anywhere in the push, a reply in any of its threads.
            }
            case Task _ -> {
                if (item instanceof ReviewComment) {
                    // A task answers in a thread; it opens none.
                    throw noSuchThread();
                }
            }
            case ForgeAccount _ ->
                    // A forge's account writes at its pull request, never into a push's threads.
                    throw noSuchThread();
        }
        if (item instanceof ForgeReply) {
            throw noSuchThread();
        }
        if (item instanceof ReviewReply reply && !holds(push, reply.thread())) {
            throw noSuchThread();
        }
    }

    /** Whether a thread of that id is one of this push's. */
    private boolean holds(final Push push, final String thread) {
        return store.threads(push).stream().anyMatch(each -> each.id().equals(thread));
    }

    private static ReviewRefused noSuchThread() {
        return new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_THREAD, null));
    }

    /**
     * Finds the push a comment is given for, as it waits at the gate.
     *
     * <p>A stand-in answers a fixed push, or refuses with {@code NoSuchPush}.
     */
    @FunctionalInterface
    public interface Pushes {

        /**
         * Returns the push waiting under a name.
         *
         * @param project The project's name.
         * @param repository The repository's name, or null for the one the task worked on.
         * @param name The push's name at the gate.
         * @return The push, bound to the task that pushed it.
         * @throws ReviewRefused {@code NoSuchPush} when nothing of that name waits, or its task's
         *     container id is not recorded.
         */
        Push waiting(String project, @Nullable String repository, String name) throws ReviewRefused;
    }

    /**
     * What a channel's edge needs to take, read, resolve and count comments: the waiting pushes,
     * the check, the store, the writer and the counts. The daemon and the command line each get
     * one.
     *
     * @param pushes Finds the push a call names.
     * @param check The check of an item against its push.
     * @param store Where items and thread states are kept.
     * @param writer What writes a push's threads as SARIF pages.
     * @param counts What counts a push's open findings, open threads and runs.
     */
    public record Parts(
            Pushes pushes,
            CommentCheck check,
            ReviewStore store,
            ReviewWriter writer,
            ReviewCounts counts) {

        /** Refuses a missing part. */
        public Parts {
            Objects.requireNonNull(pushes, "pushes");
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(writer, "writer");
            Objects.requireNonNull(counts, "counts");
        }

        /**
         * Returns the intake over this check and store.
         *
         * @return The intake.
         */
        public CommentIntake intake() {
            return new CommentIntake(check, store);
        }

        /**
         * Returns a machine's parts: the waiting pushes as the gate holds them, each item checked
         * against its commit in the push's own mirror, the threads kept in the owner account's
         * journals, which also count them, and the pages written in Porelo's review profile.
         *
         * @param context The machine.
         * @return The parts.
         */
        public static Parts of(final PoreloContext context) {
            final ReviewComments journals =
                    new ReviewComments(context.paths().gate().reviewComments());
            return new Parts(
                    waitingAt(context),
                    new ReviewAnchors(
                            push -> GateSupport.mirror(context, push.project(), push.repository()),
                            context.limits()),
                    journals,
                    new ReviewSarif(),
                    journals);
        }
    }

    /**
     * Returns the waiting pushes of a machine, found as a review finds them: the commit waiting
     * under the name, the base the review compares with, and the container id recorded for the task
     * that pushed it, never its reusable name.
     *
     * @param context The machine.
     * @return The pushes.
     */
    public static Pushes waitingAt(final PoreloContext context) {
        return (projectName, repositoryName, name) -> {
            final Project project = GateSupport.byName(context, projectName);
            final Repository repository =
                    GateSupport.repository(context, project, repositoryName, name);
            final GitGate gate = GateSupport.gate(context, project, repository, null, null);
            final GitGate.Waiting head;
            try {
                GateSupport.held(gate);
                head = gate.pendingWork(name);
            } catch (GateException ex) {
                throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_PUSH, null));
            }
            final TaskIdentity identity = recorded(context, project.name(), name);
            final String base =
                    gate.reviewBase(TaskBase.reviewAgainst(context, project, name, gate));
            try {
                return new Push(
                        project.name(),
                        repository.name(),
                        name,
                        head.commit(),
                        base,
                        identity.recordedId());
            } catch (IllegalArgumentException ex) {
                throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_PUSH, null));
            }
        };
    }

    /**
     * Returns the task recorded for a push, the one its threads are bound to.
     *
     * @param context The machine.
     * @param project The project's name.
     * @param name The push's name at the gate.
     * @return The task's identity as recorded.
     * @throws ReviewRefused {@code NoSuchPush} when no task is recorded for it, or its records
     *     cannot be read or contradict each other.
     */
    static TaskIdentity recorded(
            final PoreloContext context, final String project, final String name)
            throws ReviewRefused {
        final @Nullable TaskIdentity identity;
        try {
            identity = TaskHistory.forPush(context, project, name);
        } catch (RuntimeException ex) {
            // Records that cannot be trusted bind the push to no task; their text names local
            // paths and stays out of the answer.
            throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_PUSH, null));
        }
        if (identity == null) {
            // A push no recorded container is known for could pass its threads to a later
            // task of the same name.
            throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_PUSH, null));
        }
        return identity;
    }
}
