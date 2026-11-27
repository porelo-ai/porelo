package org.fuin.porelo.app;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewStore;
import org.fuin.porelo.gate.review.ReviewWriter;
import org.jspecify.annotations.Nullable;

/**
 * The one path a waiting push is rejected on, whoever rejects it: the owner account through {@code
 * gate reject} or the daemon, or a task holding {@code reject} through its mailbox. Each records
 * the reject in the event log, keeps the pushing task's end before that task is told, and tells it
 * the reason.
 *
 * <p>A task's reject differs where a task's word is less than a person's: it takes no force, reads
 * whether the upstream has the push anew at the moment it acts, keeps the rejecting task in the end
 * before the ref goes so that is never lost, discards the ref only at the commit the rejecting
 * task's run was pinned to, and its note names the rejecting task as its sender, never a person.
 */
public final class PushRejection {

    private PushRejection() {}

    /**
     * What an owner's reject did after the ref went.
     *
     * @param told Whether a task was told.
     * @param unkept Why the pushing task's end was not kept, or null when it was.
     * @param untold Why the task could not be told, or null.
     */
    public record Outcome(
            boolean told, @Nullable IOException unkept, @Nullable IOException untold) {}

    /**
     * Records the owner account's reject of a push whose ref is gone, keeps the pushing task's end,
     * then tells the task, in that order: a note that cannot be written loses no end.
     *
     * @param context This machine.
     * @param project The push's project.
     * @param name The push's name.
     * @param detail The event log's detail, as the caller words it.
     * @param rejected The push as it waited, read before the ref went.
     * @param reason What the person said, or null.
     * @param store The review's threads.
     * @param writer Writes the open ones as SARIF.
     * @return What was kept and told.
     */
    public static Outcome byOwner(
            final PoreloContext context,
            final Project project,
            final String name,
            final String detail,
            final PersonNote.Rejected rejected,
            final @Nullable String reason,
            final ReviewStore store,
            final ReviewWriter writer) {
        EventLog.record(context, EventLog.REJECTED, project.name(), name, detail);
        IOException unkept = null;
        try {
            new GateEnds(context).rejected(project.name(), name, rejected, store);
        } catch (IOException ex) {
            unkept = ex;
        }
        // Told whether or not a reason was given: an agent whose work silently vanished hands it
        // over again.
        try {
            return new Outcome(
                    PersonNote.rejectedWork(
                            context, project, name, reason, rejected, store, writer),
                    unkept,
                    null);
        } catch (IOException ex) {
            return new Outcome(false, unkept, ex);
        }
    }

    /**
     * Discards a push a task holding {@code reject} judged, at exactly the commit its run was
     * pinned to, and tells the task that pushed it.
     *
     * @param context This machine.
     * @param pinned The push as the rejecting task's run was pinned to it.
     * @param rejecting The rejecting task, as its records name it.
     * @param reason Its reason.
     * @param store The review's threads.
     * @param writer Writes the open ones as SARIF.
     * @throws ProfileRefused When the gate cannot be read, the upstream has the push, or the push
     *     moved: nothing was discarded then.
     */
    public static void byTask(
            final PoreloContext context,
            final Push pinned,
            final TaskIdentity rejecting,
            final String reason,
            final ReviewStore store,
            final ReviewWriter writer)
            throws ProfileRefused {
        Objects.requireNonNull(rejecting, "rejecting");
        Objects.requireNonNull(reason, "reason");
        final Project project;
        final Repository repository;
        final GitGate gate;
        try {
            project = GateSupport.byName(context, pinned.project());
            repository = repositoryOf(project, pinned.repository());
            gate = GateSupport.gate(context, project, repository, null, null);
        } catch (RuntimeException ex) {
            throw new ProfileRefused(TaskReject.UNREADABLE);
        }
        // Read anew, now: a measurement made when the reject was judged is no answer here.
        if (alreadyUpstream(context, project, repository, pinned.name())) {
            throw new ProfileRefused(TaskReject.UPSTREAM);
        }
        act(
                context,
                pinned,
                rejecting,
                reason,
                new Ref() {
                    @Override
                    public void rejectAt(final String name, final String commit) {
                        gate.rejectAt(name, commit);
                    }

                    @Override
                    public @Nullable String head(final String name) {
                        final GitGate.Waiting waiting = gate.waiting(name);
                        return waiting == null ? null : waiting.commit();
                    }
                },
                (push, by, said) ->
                        PersonNote.rejectedByTask(context, push, by, said, store, writer),
                new GateEnds(context),
                store);
    }

    /** The waiting ref a task's reject discards. A stand-in fails as asked. */
    interface Ref {

        /**
         * Deletes the ref only while it points at the commit.
         *
         * @param name The push's name.
         * @param commit The commit it must point at.
         * @throws GateException When nothing was deleted.
         */
        void rejectAt(String name, String commit);

        /**
         * Returns the commit the ref points at now.
         *
         * @param name The push's name.
         * @return The commit, or null when nothing waits under the name.
         * @throws GateException When git cannot tell.
         */
        @Nullable String head(String name);
    }

    /** Tells the pushing task of a task's reject. A stand-in answers fixed outcomes. */
    @FunctionalInterface
    interface Note {

        /**
         * Tells it.
         *
         * @param pinned The push as it was pinned, bound to the task that pushed it.
         * @param rejecting The rejecting task.
         * @param reason The reason.
         * @return Why the pushing task was not told, or empty when it was.
         * @throws IOException When the note could not be written.
         */
        Optional<String> tell(Push pinned, TaskIdentity rejecting, String reason)
                throws IOException;
    }

    /**
     * Keeps the reject's end, discards the ref at its commit, records the reject and tells the
     * pushing task, in that order.
     */
    static void act(
            final PoreloContext context,
            final Push pinned,
            final TaskIdentity rejecting,
            final String reason,
            final Ref ref,
            final Note note,
            final GateEnds ends,
            final ReviewStore store)
            throws ProfileRefused {
        final GateEnds.Written written;
        try {
            // Kept before the ref goes, so the rejecting task is never lost with it.
            written = ends.rejectedBy(pinned, rejecting.container(), store);
        } catch (IOException ex) {
            throw new ProfileRefused(TaskReject.UNREADABLE);
        }
        try {
            ref.rejectAt(pinned.name(), pinned.head());
        } catch (GateException ex) {
            final String rule = moved(ref, pinned) ? TaskReject.MOVED : TaskReject.GATE_FAILED;
            putBack(context, pinned, ends, written, rule);
            throw new ProfileRefused(rule);
        }
        String untold;
        try {
            untold = note.tell(pinned, rejecting, reason).orElse(null);
        } catch (IOException ex) {
            untold = PersonNote.UNTOLD_UNWRITTEN;
        }
        EventLog.record(
                context,
                EventLog.REJECTED,
                pinned.project(),
                pinned.name(),
                "discarded, rejected by "
                        + rejecting.container()
                        + (untold == null ? "" : "; the pushing task was not told: " + untold));
        try {
            PersonNote.rejectAnswered(context, rejecting, pinned.name(), untold);
        } catch (IOException ex) {
            // The push is gone and the event log says so: nothing of the reject is undone.
        }
    }

    /**
     * Whether the ref is gone or points elsewhere since the reject was judged: else git failed, and
     * so does a ref git cannot read.
     */
    private static boolean moved(final Ref ref, final Push pinned) {
        try {
            return !pinned.head().equals(ref.head(pinned.name()));
        } catch (GateException ex) {
            return false;
        }
    }

    /**
     * Puts the end before back while the end still is the reject's own; an end kept since, by an
     * approval or the next push, stays. An end that could not be put back is recorded.
     */
    private static void putBack(
            final PoreloContext context,
            final Push pinned,
            final GateEnds ends,
            final GateEnds.Written written,
            final String rule) {
        try {
            ends.restoreIf(pinned.task(), written);
        } catch (IOException ex) {
            EventLog.record(
                    context,
                    EventLog.REJECTED,
                    pinned.project(),
                    pinned.name(),
                    "not discarded ("
                            + rule
                            + "); its end could not be put back and may read rejected until its"
                            + " next end");
        }
    }

    /**
     * Returns the gate a task's reject acts at on this machine.
     *
     * @param context This machine.
     * @param store The review's threads.
     * @param writer Writes the open ones as SARIF.
     * @return The gate.
     */
    static TaskReject.Gate gate(
            final PoreloContext context, final ReviewStore store, final ReviewWriter writer) {
        return new TaskReject.Gate() {
            @Override
            public boolean alreadyUpstream(final Push push) throws ProfileRefused {
                try {
                    final Project project = GateSupport.byName(context, push.project());
                    return PushRejection.alreadyUpstream(
                            context,
                            project,
                            repositoryOf(project, push.repository()),
                            push.name());
                } catch (RuntimeException ex) {
                    throw new ProfileRefused(TaskReject.UNREADABLE);
                }
            }

            @Override
            public void discard(final Push push, final TaskIdentity rejecting, final String reason)
                    throws ProfileRefused {
                byTask(context, push, rejecting, reason, store, writer);
            }
        };
    }

    private static boolean alreadyUpstream(
            final PoreloContext context,
            final Project project,
            final Repository repository,
            final String name)
            throws ProfileRefused {
        try {
            return GateSupport.alreadyUpstream(context, project, repository).contains(name);
        } catch (RuntimeException ex) {
            // Records that cannot be read discard nothing unseen.
            throw new ProfileRefused(TaskReject.UNREADABLE);
        }
    }

    /** The repository a push names, among every one of its project, as the gate lists them. */
    private static Repository repositoryOf(final Project project, final String name) {
        for (final Repository each : project.allRepositories()) {
            if (each.name().equals(name)) {
                return each;
            }
        }
        throw new IllegalArgumentException("no repository " + name + " in " + project.name());
    }
}
