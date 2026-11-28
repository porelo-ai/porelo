package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.HoldRefused;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.Task;

/**
 * Writes a run's checked items that wait for nobody, as {@link HoldingRule} chose, and records each
 * in the event log by its task and place, never its text, before it goes: a log that cannot take
 * the entry refuses the submission, and an item it would have named stays unwritten or held.
 *
 * <p>At a waiting push the items go into the push's threads as the task itself, through {@link
 * CommentIntake}, which judges every item by the task's recorded rights and place before it keeps
 * any: a refusal writes nothing.
 *
 * <p>Towards an online project's pull request nothing is written here: every item is held and then
 * released by this rule, bound to the run's pinned commit and its digest as a release by the owner
 * account is, and the released items' own sending writes it once, with the credential lent for that
 * write, marking what the forge answered, so one of unknown outcome is never sent again. Each item
 * is held before any is released; the journal has no entry that holds an item released, so between
 * its two lines the item is undecided, and one left so by a failure waits for the owner account.
 */
public final class UnheldWrites {

    /** The rule a submission breaks whose run was started again before its items were released. */
    static final String RUN_STARTED_AGAIN = "intake: the run was started again";

    /**
     * The rule a submission breaks whose item would go unheld while the event log is unwritable.
     */
    static final String NOT_RECORDED =
            "intake: the event log cannot record an item that goes unheld";

    /**
     * Records an event in the account's event log.
     *
     * <p>A stand-in records what it was given.
     */
    @FunctionalInterface
    public interface Recorder {

        /**
         * Records one event.
         *
         * @param kind The event's kind.
         * @param project The project, or "".
         * @param task The task's container.
         * @param detail What happened; never an item's text.
         * @throws java.io.IOException When the log cannot take it; the item it names never goes.
         */
        void record(String kind, String project, String task, String detail)
                throws java.io.IOException;
    }

    private final CommentIntake comments;

    private final ReviewHolds holds;

    private final Runnable sending;

    private final Recorder events;

    /**
     * Constructor.
     *
     * @param comments The intake of the waiting pushes' threads.
     * @param holds Where a pull request's items are released from.
     * @param sending Starts a pass of the released items' sending, off the caller's thread.
     * @param events Records each item that went without a hold.
     */
    public UnheldWrites(
            final CommentIntake comments,
            final ReviewHolds holds,
            final Runnable sending,
            final Recorder events) {
        this.comments = Objects.requireNonNull(comments, "comments");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.sending = Objects.requireNonNull(sending, "sending");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Writes a run's checked items the way the rule chose.
     *
     * @param way {@link HoldingRule.Way#THREADS} or {@link HoldingRule.Way#FORGE}.
     * @param run The task's run.
     * @param task The task's container.
     * @param items The items, in order, each checked against the run's pinned push.
     * @throws ProfileRefused When the threads refuse an item, by its contract error, and nothing is
     *     written; when the run was started again before every item was released, and only those
     *     released before it go; or by {@link #NOT_RECORDED} when the event log cannot take an
     *     item's entry, and only those recorded before it go.
     * @throws IllegalArgumentException For the way that holds.
     */
    public void write(
            final HoldingRule.Way way,
            final Run run,
            final String task,
            final List<ReviewItem> items)
            throws ProfileRefused {
        switch (way) {
            case THREADS -> threads(run, task, items);
            case FORGE -> forge(run, task, items);
            case HOLD -> throw new IllegalArgumentException("a held item is not written at once");
        }
    }

    private void threads(final Run run, final String task, final List<ReviewItem> items)
            throws ProfileRefused {
        if (run.pinned().facts() != null) {
            throw new IllegalArgumentException("a pull request has no threads of a push");
        }
        // Recorded before any is kept: an item the log cannot name never goes. One the threads
        // then refuse leaves its entry, so the log never says less than went.
        for (final ReviewItem item : items) {
            recorded(run, task, item);
        }
        try {
            comments.takeAsTask(task, new Task(task), run.push(), items);
        } catch (TaskRightMissing ex) {
            throw new ProfileRefused(ex.rule());
        } catch (ReviewRefused ex) {
            throw new ProfileRefused("threads: " + ex.refusal().error().wireName());
        }
    }

    private void forge(final Run run, final String task, final List<ReviewItem> items)
            throws ProfileRefused {
        if (run.pinned().facts() == null) {
            throw new IllegalArgumentException("a gate push is no forge's");
        }
        // Every item held before any is released: a run started again meanwhile lapses them all,
        // and nothing goes out.
        final List<Held> held = new ArrayList<>();
        for (final ReviewItem item : items) {
            final Held one = holds.hold(run, task, Direction.OUTGOING, item, null, null, false);
            if (one.state() != HeldState.UNDECIDED) {
                throw new ProfileRefused(RUN_STARTED_AGAIN);
            }
            held.add(one);
        }
        boolean released = false;
        try {
            for (final Held one : held) {
                // Recorded before it is released: one the log cannot name waits for the owner.
                recorded(run, task, one.item());
                try {
                    holds.decide(task, one.id(), one.digest(), Decision.RELEASE);
                } catch (HoldRefused ex) {
                    // Lapsed by a later start meanwhile: the rest is never sent.
                    throw new ProfileRefused(RUN_STARTED_AGAIN);
                }
                released = true;
            }
        } finally {
            if (released) {
                // Sent once, by the one sending path, never from the message pass's thread.
                sending.run();
            }
        }
    }

    /** Fails closed: an entry the log cannot take refuses the item it would name. */
    private void recorded(final Run run, final String task, final ReviewItem item)
            throws ProfileRefused {
        try {
            events.record(
                    EventLog.UNHELD,
                    run.push().project(),
                    task,
                    switch (item) {
                                case ReviewComment _ -> "comment";
                                case org.fuin.porelo.gate.review.ThreadResolve _ -> "resolve";
                                case org.fuin.porelo.gate.review.ReviewReply _,
                                        org.fuin.porelo.gate.review.ForgeReply _ ->
                                        "reply";
                            }
                            + " at "
                            + run.place()
                            + ", run "
                            + run.number());
        } catch (java.io.IOException | RuntimeException ex) {
            throw new ProfileRefused(NOT_RECORDED);
        }
    }
}
