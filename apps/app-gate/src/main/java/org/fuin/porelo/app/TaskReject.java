package org.fuin.porelo.app;

import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.TaskRights;
import org.jspecify.annotations.Nullable;

/**
 * A task's one judgment: the reject of the waiting push it reads, which discards that push as the
 * owner account's {@code gate reject} does and tells the task that pushed it why.
 *
 * <p><strong>Judged by what the task's start recorded, never by what the item says</strong>: a
 * reject names no push. Its place is the run's: another task's push waiting at this machine's gate,
 * the one the task's recorded place names in its own project, never its own, pushed by the task its
 * pin recorded; the task holds {@code reject}; the push still waits at the very commit the run was
 * pinned to, pushed by that same task; and the upstream has not got it already. Everything is
 * judged before anything of the submission goes on, and the push is discarded last, at exactly that
 * commit.
 *
 * <p>A submission no certified key signed is judged as a signed one: a reject is only ever at a
 * waiting push, nothing leaves the machine, and the host stamps the mailbox it came through. Fail
 * closed: a record that cannot be read rejects nothing.
 */
public final class TaskReject {

    /** The most characters a reason holds: it is a task's text another task and a person read. */
    static final int MAX_REASON = PersonNote.MAX_REASON;

    /** The rule a reject of a task without the right breaks. */
    static final String MAY_NOT_REJECT = RunIntake.rightMissing(Right.REJECT, "discard the push");

    /** The rule a reject breaks whose run is not at another task's push its place names. */
    static final String NOT_ITS_PLACE = "reject: NotItsPlace - only the waiting push it reads";

    /** The rule a reject of the task's own push breaks. */
    static final String OWN_PUSH = "reject: OwnPush - a task never rejects its own work";

    /** The rule a reason too long breaks. */
    static final String TOO_LONG = "reject: reason longer than " + MAX_REASON + " characters";

    /** The rule a reject breaks whose push no longer waits. */
    static final String GONE = "reject: NoSuchPush - nothing waits there any more";

    /** The rule a reject breaks whose push moved since its run was pinned. */
    static final String MOVED = "reject: PushMoved - the push moved since the run was pinned";

    /** The rule a reject of a push the upstream has already breaks. */
    static final String UPSTREAM = "reject: AlreadyUpstream - the upstream already has it";

    /** The rule a reject breaks where this machine wired no gate for one. */
    static final String NOT_TAKEN = "reject: not taken here";

    /** The rule a reject breaks whose push git could not discard, though it did not move. */
    static final String GATE_FAILED = "reject: GateFailed - git did not discard the push";

    /** The rule a reject breaks whose task's record or gate could not be read. */
    static final String UNREADABLE = "reject: Unreadable - the record of the task or the gate";

    /** What a judged reject does once everything else of its submission went on. */
    @FunctionalInterface
    public interface Act {

        /**
         * Discards the push.
         *
         * @throws ProfileRefused When the push moved or the upstream got it since it was judged;
         *     nothing was discarded then.
         */
        void run() throws ProfileRefused;
    }

    /** Nothing to do: the submission holds no reject. */
    static final Act NOTHING = () -> {};

    /**
     * Judges a submission's reject.
     *
     * <p>A stand-in answers fixed acts or refusals.
     */
    @FunctionalInterface
    public interface Judge {

        /**
         * Judges it, before anything of the submission goes on.
         *
         * @param task The submitting task's container name.
         * @param run Its current run.
         * @param place The run's place.
         * @param reason The reject's reason, or {@code null} when the submission holds none.
         * @return What to do last.
         * @throws ProfileRefused When the reject breaks a rule; nothing is done then.
         */
        Act judge(String task, Run run, ReviewPlace place, @Nullable Body reason)
                throws ProfileRefused;
    }

    /** Refuses every reject: a machine that wired no gate for one. */
    public static final Judge NONE =
            (task, run, place, reason) -> {
                if (reason == null) {
                    return NOTHING;
                }
                throw new ProfileRefused(NOT_TAKEN);
            };

    /**
     * The gate a reject acts at: what the upstream has, and the one path that discards a push.
     *
     * <p>A stand-in records what it was asked.
     */
    public interface Gate {

        /**
         * Whether the upstream already has the push, read anew.
         *
         * @param push The push.
         * @return True when the last measurement found it there.
         * @throws ProfileRefused When that cannot be read: nothing is discarded unseen.
         */
        boolean alreadyUpstream(Push push) throws ProfileRefused;

        /**
         * Discards the push at exactly its commit, records the rejecting task and tells the task
         * that pushed it, as {@link PushRejection#byTask} does.
         *
         * @param push The push as the run was pinned to it.
         * @param rejecting The rejecting task as its records name it.
         * @param reason The reason.
         * @throws ProfileRefused When the push moved meanwhile; nothing was discarded then.
         */
        void discard(Push push, TaskIdentity rejecting, String reason) throws ProfileRefused;
    }

    private final TaskRights rights;

    private final RecordedTaskWrites.Places places;

    private final RecordedTaskWrites.Owners owners;

    private final CommentIntake.Pushes pushes;

    private final Gate gate;

    /**
     * Constructor.
     *
     * @param rights The task's recorded parts.
     * @param places The task's recorded place.
     * @param owners The task's recorded project and container id.
     * @param pushes The pushes as they wait now.
     * @param gate Where the push is discarded.
     */
    public TaskReject(
            final TaskRights rights,
            final RecordedTaskWrites.Places places,
            final RecordedTaskWrites.Owners owners,
            final CommentIntake.Pushes pushes,
            final Gate gate) {
        this.rights = Objects.requireNonNull(rights, "rights");
        this.places = Objects.requireNonNull(places, "places");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.pushes = Objects.requireNonNull(pushes, "pushes");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /**
     * Returns a machine's: each task's recorded parts, place and owner, the pushes as the gate
     * holds them, and the gate's one path of a reject.
     *
     * @param context The machine.
     * @param rights The machine's recorded rights.
     * @param parts The pushes as they wait, and the review's threads a reject's note carries.
     * @return The judgment.
     */
    public static TaskReject of(
            final PoreloContext context, final TaskRights rights, final CommentIntake.Parts parts) {
        return new TaskReject(
                rights,
                RecordedTaskWrites.places(context),
                RecordedTaskWrites.owners(context),
                parts.pushes(),
                PushRejection.gate(context, parts.store(), parts.writer()));
    }

    /**
     * Judges a submission's reject, as {@link Judge#judge} says.
     *
     * @param task The submitting task's container name.
     * @param run Its current run.
     * @param place The run's place.
     * @param reason The reject's reason, or {@code null} when the submission holds none.
     * @return What to do last.
     * @throws ProfileRefused When the reject breaks a rule; nothing is done then.
     */
    public Act judge(
            final String task, final Run run, final ReviewPlace place, final @Nullable Body reason)
            throws ProfileRefused {
        if (reason == null) {
            return NOTHING;
        }
        // The right and the place first, before the gate is touched.
        if (!mayReject(task, place)) {
            throw new ProfileRefused(MAY_NOT_REJECT);
        }
        // Signed or not alike: a reject is only ever at a waiting push, nothing leaves the
        // machine, and the host stamps the mailbox it came through.
        final Push pinned = run.push();
        final TaskIdentity rejecting = placeOf(task, place, pinned);
        if (reason.text().codePointCount(0, reason.text().length()) > MAX_REASON) {
            throw new ProfileRefused(TOO_LONG);
        }
        final Push now;
        try {
            now = pushes.waiting(pinned.project(), pinned.repository(), pinned.name());
        } catch (ReviewRefused ex) {
            throw new ProfileRefused(GONE);
        } catch (RuntimeException ex) {
            throw new ProfileRefused(UNREADABLE);
        }
        if (!now.head().equals(pinned.head()) || !now.task().equals(pinned.task())) {
            throw new ProfileRefused(MOVED);
        }
        if (gate.alreadyUpstream(pinned)) {
            throw new ProfileRefused(UPSTREAM);
        }
        final String text = reason.text();
        return () -> gate.discard(pinned, rejecting, text);
    }

    private boolean mayReject(final String task, final ReviewPlace place) throws ProfileRefused {
        try {
            return rights.mayReject(task, place);
        } catch (RuntimeException ex) {
            // A record that cannot be read gives no right.
            throw new ProfileRefused(UNREADABLE);
        }
    }

    /**
     * Returns the rejecting task's identity when the run's place is the push its start recorded:
     * another task's push of its own project, waiting at the gate, named exactly.
     */
    private TaskIdentity placeOf(final String task, final ReviewPlace place, final Push pinned)
            throws ProfileRefused {
        final RunPlace recorded;
        final Optional<TaskIdentity> owner;
        try {
            recorded = places.of(task);
            owner = owners.of(task);
        } catch (RuntimeException ex) {
            throw new ProfileRefused(UNREADABLE);
        }
        if (!(place instanceof WaitingPushPlace)
                || recorded == null
                || owner.isEmpty()
                || !recorded.parts().readsAnothersWork()
                || recorded.pullRequest() != null
                || !recorded.name().equals(pinned.name())
                || !Objects.equals(
                        recorded.repository(),
                        RunPlace.otherThanOwn(pinned.project(), pinned.repository()))
                || !owner.get().project().equals(pinned.project())) {
            throw new ProfileRefused(NOT_ITS_PLACE);
        }
        if (pinned.task().equals(owner.get().recordedId())) {
            throw new ProfileRefused(OWN_PUSH);
        }
        // The work its pin recorded, pushed by that task: never another task's push that came to
        // wait under the name; one that waits now is then checked against this same task.
        final RunPlace.Pin pin = recorded.pinned();
        if (pin == null || !pinned.task().equals(pin.pushedBy())) {
            throw new ProfileRefused(NOT_ITS_PLACE);
        }
        return owner.get();
    }
}
