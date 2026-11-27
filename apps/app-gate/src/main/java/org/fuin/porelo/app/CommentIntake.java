package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.List;
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
import org.fuin.porelo.gate.review.ThreadResolve;
import org.fuin.porelo.gate.review.ThreadState;
import org.jspecify.annotations.Nullable;

/**
 * Takes one comment or reply from a producer: checks it, judges whether the producer may write
 * where it points, and keeps it stamped with the producer.
 *
 * <p>The one way in for every channel: the daemon's socket, the command line, a task's reply and a
 * task's run at a waiting push all come through here, so a rule holds for each of them alike. It
 * owns no rule of its own but the producer's authority over a thread: the owner account writes in
 * any thread of the push; a task writes only as its recorded rights allow and only in the push its
 * recorded place names, both looked up by its container name ({@link TaskWrites}).
 */
public final class CommentIntake {

    private final CommentCheck check;

    private final ReviewStore store;

    private final TaskWrites tasks;

    /**
     * Constructor with what checks, what keeps and what a task may write.
     *
     * @param check The check of an item against its push.
     * @param store Where items are kept.
     * @param tasks What a task may write into a push's threads.
     */
    public CommentIntake(
            final CommentCheck check, final ReviewStore store, final TaskWrites tasks) {
        this.check = Objects.requireNonNull(check, "check");
        this.store = Objects.requireNonNull(store, "store");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    /**
     * Checks an item of the owner account, judges its authority over its thread and keeps it.
     *
     * @param producer Who gave it, by the channel it came through.
     * @param push The push it is given for.
     * @param item What was given.
     * @param id The caller's retry id, or null.
     * @return The item as kept.
     * @throws ReviewRefused By the check's rule, {@code NoSuchThread} for a thread the producer may
     *     not write in, and for every task, which writes only through {@link #takeAsTask}, or the
     *     store's refusal; nothing is kept then.
     */
    public Kept take(
            final Producer producer,
            final Push push,
            final ReviewItem item,
            final @Nullable String id)
            throws ReviewRefused {
        if (item instanceof ThreadResolve) {
            // The owner account sets a thread's state through the store, never as an item.
            throw new IllegalArgumentException("a resolve is a task's item alone");
        }
        final Optional<ReviewRefusal> refused = check.check(push, item);
        if (refused.isPresent()) {
            throw new ReviewRefused(refused.get());
        }
        authority(producer, null, push, item);
        // The producer is stamped from the channel, never taken from what was given.
        return store.keep(push, item, Origin.of(producer), id);
    }

    /**
     * Takes a task's items for a waiting push, judged by the task's recorded rights and place:
     * every item is checked and judged before any is kept, so a refusal keeps nothing of them. The
     * one way a task's items reach a push's threads: its run's items written at once, and its held
     * items once released. A reply named by the thread id its conversation showed, as the profile
     * carries it, answers in that thread of Porelo's own: a push has no forge thread.
     *
     * @param container The task's container name, by which its rights and place are looked up.
     * @param producer The task as its channel stamps it.
     * @param push The push they are for.
     * @param items What it gave, in order.
     * @return The comments and replies as kept, in order; a resolve marks its thread resolved,
     *     stamped with the task, and is kept as no item.
     * @throws ReviewRefused By the check's rule, {@code NoSuchThread} for an item the task may not
     *     write there or a thread that is not the push's, or the store's refusal.
     */
    public List<Kept> takeAsTask(
            final String container,
            final Task producer,
            final Push push,
            final List<ReviewItem> items)
            throws ReviewRefused {
        Objects.requireNonNull(container, "container");
        final List<ReviewItem> own = new ArrayList<>();
        for (final ReviewItem each : items) {
            own.add(
                    each instanceof ForgeReply reply
                            ? new ReviewReply(reply.thread(), reply.body())
                            : each);
        }
        for (final ReviewItem item : own) {
            final Optional<ReviewRefusal> refused = check.check(push, item);
            if (refused.isPresent()) {
                throw new ReviewRefused(refused.get());
            }
            authority(producer, container, push, item);
        }
        final List<Kept> kept = new ArrayList<>();
        for (final ReviewItem item : own) {
            if (item instanceof ThreadResolve resolve) {
                // Only resolved, stamped with the task; a task never opens a thread again.
                store.state(push, resolve.thread(), ThreadState.RESOLVED, Origin.of(producer));
            } else {
                kept.add(store.keep(push, item, Origin.of(producer), null));
            }
        }
        return List.copyOf(kept);
    }

    /**
     * Refuses an item the producer may not write where it points; a task only with its container
     * named, as its recorded rights and place allow.
     */
    private void authority(
            final Producer producer,
            final @Nullable String container,
            final Push push,
            final ReviewItem item)
            throws ReviewRefused {
        // Exhaustive, so a new kind of producer is judged here before it can write anything.
        switch (producer) {
            case OwnerAccount _ -> {
                // A new thread anywhere in the push, a reply in any of its threads.
            }
            case Task _ -> {
                if (container == null || !tasks.may(container, push, item)) {
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
        if (item instanceof ThreadResolve resolve && !holds(push, resolve.thread())) {
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
     * the check, the store, the writer, the counts and what a task may write. The daemon and the
     * command line each get one.
     *
     * @param pushes Finds the push a call names.
     * @param check The check of an item against its push.
     * @param store Where items and thread states are kept.
     * @param writer What writes a push's threads as SARIF pages.
     * @param counts What counts a push's open findings, open threads and runs.
     * @param tasks What a task may write into a push's threads.
     */
    public record Parts(
            Pushes pushes,
            CommentCheck check,
            ReviewStore store,
            ReviewWriter writer,
            ReviewCounts counts,
            TaskWrites tasks) {

        /** Refuses a missing part. */
        public Parts {
            Objects.requireNonNull(pushes, "pushes");
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(writer, "writer");
            Objects.requireNonNull(counts, "counts");
            Objects.requireNonNull(tasks, "tasks");
        }

        /**
         * Returns the intake over this check, store and tasks' rights.
         *
         * @return The intake.
         */
        public CommentIntake intake() {
            return new CommentIntake(check, store, tasks);
        }

        /**
         * Returns a machine's parts: the waiting pushes as the gate holds them, each item checked
         * against its commit in the push's own mirror, the threads kept in the owner account's
         * journals, which also count them, the pages written in Porelo's review profile, and each
         * task's recorded rights and place.
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
                    journals,
                    RecordedTaskWrites.of(
                            context,
                            MachineTaskRights.of(
                                    context,
                                    ForgeInstanceFile.of(context),
                                    RecordedTaskRights.Forwarded.NONE)));
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
