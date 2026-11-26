package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.gate.review.CommentGate;
import org.fuin.porelo.gate.review.Completeness;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.CurrentRuns;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.Role;
import org.fuin.porelo.gate.review.Run;
import org.jspecify.annotations.Nullable;

/**
 * Starts a task's run at a place, the same for a new pull request and one already worked on: the
 * task's undecided items at the place lapse, the place is pinned, the run begins, the whole
 * conversation is read with a credential lent for the call, each comment is judged by the place's
 * reading setting, and the comments that passed are delivered to the task as untrusted input with
 * how many were withheld. Nothing of an earlier run is kept: each run reads all of it again.
 *
 * <p>A start that cannot pin, read, judge, deliver or count stops with {@link RunRefused} naming
 * the step, and no run of the task is current then, an earlier one included: never a run without
 * its conversation. The run ends when the task ends; that is the caller's.
 */
public final class RunStart {

    /** Lends the read credential of a place's forge for one call. */
    @FunctionalInterface
    public interface Credentials {

        /**
         * Reads the credential the place is read with, from the vault, for this call.
         *
         * @param place The place.
         * @return The credential.
         * @throws PlaceUnreadable {@code MissingForgeToken} when the vault holds none.
         */
        ForgeCredential lend(ReviewPlace place) throws PlaceUnreadable;
    }

    /**
     * What a run's start delivered.
     *
     * @param run The run's number at the place.
     * @param delivered How many comments reached the task.
     * @param withheld How many were withheld, by the input path or the comment gate.
     * @param completeness How completely the conversation was read.
     * @param reason Why it is not complete; null when it is.
     * @param head The pinned commit the run reviews.
     * @param waiting How many of the task's items wait for the owner account's decision.
     */
    public record Outcome(
            int run,
            int delivered,
            int withheld,
            Completeness completeness,
            @Nullable String reason,
            String head,
            int waiting) {}

    private final ReviewHolds holds;
    private final CurrentRuns runs;
    private final CommentGate gate;
    private final ConversationDelivery delivery;
    private final Credentials credentials;

    /**
     * Constructor with the seams the start goes over.
     *
     * @param holds The held items, both ways.
     * @param runs The run each task is in now.
     * @param gate Judges each comment by the place's reading setting, and holds a withheld one.
     * @param delivery Hands the task its conversation.
     * @param credentials Lends the read credential per call.
     */
    public RunStart(
            final ReviewHolds holds,
            final CurrentRuns runs,
            final CommentGate gate,
            final ConversationDelivery delivery,
            final Credentials credentials) {
        this.holds = Objects.requireNonNull(holds, "holds");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /**
     * Starts a run of the task at the place.
     *
     * @param task The task's container name.
     * @param role The task's role there.
     * @param place The place.
     * @return What the run was given.
     * @throws RunRefused When the place could not be pinned or read, a comment not judged, the
     *     conversation not delivered or the waiting items not counted; no run of the task is
     *     current then.
     */
    public Outcome start(final String task, final Role role, final ReviewPlace place)
            throws RunRefused {
        final int number = holds.startRun(task, place.name());
        final Pinned pinned;
        try {
            pinned = place.pin(task);
        } catch (final PlaceUnreadable | RuntimeException unpinned) {
            // A new run had started, so the earlier one is over and its undecided items lapsed.
            runs.end(task);
            throw new RunRefused(RunRefused.Step.PIN, unpinned);
        }
        final Run run = new Run(place.name(), role, pinned, number);
        runs.begin(task, run);
        final Conversation read;
        try {
            read = place.conversation(pinned, credentials.lend(place));
        } catch (final PlaceUnreadable | RuntimeException unread) {
            runs.end(task);
            throw new RunRefused(RunRefused.Step.READ, unread);
        }
        final List<Kept> passed = new ArrayList<>();
        int gated = 0;
        try {
            final Map<String, Kept> roots = new HashMap<>();
            for (final Kept item : read.items()) {
                if (item.item() instanceof ReviewComment) {
                    roots.putIfAbsent(item.thread(), item);
                }
            }
            for (final Kept item : read.items()) {
                // A reply without its opening comment is judged against itself, which the gate
                // holds.
                final Kept root = roots.getOrDefault(item.thread(), item);
                // The gate holds what it withholds, so the start never holds it twice.
                if (gate.judge(place, run, item, root).isPresent()) {
                    gated++;
                } else {
                    passed.add(item);
                }
            }
        } catch (final RuntimeException unjudged) {
            runs.end(task);
            throw new RunRefused(RunRefused.Step.JUDGE, unjudged);
        }
        final Conversation delivered =
                new Conversation(passed, read.withheld(), read.completeness(), read.reason());
        try {
            delivery.deliver(
                    task,
                    number,
                    place.name(),
                    place.pages(pinned, delivered),
                    gated,
                    read.withheld().size(),
                    read.reason());
        } catch (final RuntimeException undelivered) {
            // Never a run without its conversation.
            runs.end(task);
            throw new RunRefused(RunRefused.Step.DELIVER, undelivered);
        }
        final int waiting;
        try {
            waiting = holds.count(task);
        } catch (final RuntimeException uncounted) {
            runs.end(task);
            throw new RunRefused(RunRefused.Step.COUNT, uncounted);
        }
        return new Outcome(
                number,
                passed.size(),
                read.withheld().size() + gated,
                read.completeness(),
                read.reason(),
                pinned.push().head(),
                waiting);
    }
}
