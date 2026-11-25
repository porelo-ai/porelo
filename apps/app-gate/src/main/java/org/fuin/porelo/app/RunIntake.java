package org.fuin.porelo.app;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.gate.review.CommentCheck;
import org.fuin.porelo.gate.review.CurrentRuns;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.ProfileChannel;
import org.fuin.porelo.gate.review.ProfileItem;
import org.fuin.porelo.gate.review.ProfileReading;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewPlaces;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRole;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.Task;

/**
 * Takes a run's submission from its task, author or reviewer, and holds each of its comments and
 * replies for the owner account's release, bound to the run's place, its pinned commit and the
 * digest of the item's exact text.
 *
 * <p>The one way a task's words go towards a place: the submission is read as the task's, judged by
 * the task's role there, checked against the run's pinned push, and held only when every item
 * passes. It keeps nothing itself; whether a named thread exists is the place's to say when a
 * released item is written.
 *
 * <p>A refusal holds nothing of the submission. A failure of the hold store itself may leave the
 * items before it held: undecided and shown to the owner account, never released by this class.
 */
public final class RunIntake implements ReviewerComments {

    /** The rule a submission of a task with no current run breaks. */
    static final String NO_RUN = "run: none for the task";

    /** The rule a submission breaks when its run's place is no longer found. */
    static final String PLACE_UNREACHABLE = "run: place unreachable";

    /** The rule a submission without items breaks. */
    static final String NO_ITEM = "submission: no item";

    /**
     * The rule an item breaks that names a producer, id or thread other than its task's channel.
     */
    static final String NOT_OWN = "intake: item not the task's own";

    /** The rule a new comment breaks where the task may only reply. */
    static final String REPLIES_ONLY = "role: only replies at this place";

    private final ProfileReading reading;

    private final CommentCheck check;

    private final ReviewRole role;

    private final ReviewHolds holds;

    private final CurrentRuns currentRuns;

    private final ReviewPlaces places;

    /**
     * Constructor with what reads, judges, checks and holds, and what knows a task's run and its
     * place.
     *
     * @param reading The one input path for profile documents.
     * @param check The check of an item against the run's pinned push.
     * @param role What the task's role allows it at a place.
     * @param holds Where items wait for the owner account's decision.
     * @param currentRuns The run each task is in now.
     * @param places Finds a run's place by its name.
     */
    public RunIntake(
            final ProfileReading reading,
            final CommentCheck check,
            final ReviewRole role,
            final ReviewHolds holds,
            final CurrentRuns currentRuns,
            final ReviewPlaces places) {
        this.reading = Objects.requireNonNull(reading, "reading");
        this.check = Objects.requireNonNull(check, "check");
        this.role = Objects.requireNonNull(role, "role");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.currentRuns = Objects.requireNonNull(currentRuns, "currentRuns");
        this.places = Objects.requireNonNull(places, "places");
    }

    @Override
    public void take(final String task, final byte[] document) throws ProfileRefused {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(document, "document");
        final Optional<Run> found = currentRuns.of(task);
        if (found.isEmpty()) {
            throw new ProfileRefused(NO_RUN);
        }
        final Run run = found.get();
        // A hold is bound to its place by name; one no longer found could never be released there.
        final Optional<ReviewPlace> place = places.named(run.place());
        if (place.isEmpty()) {
            throw new ProfileRefused(PLACE_UNREACHABLE);
        }
        final Task own = new Task(task);
        final List<ProfileItem> items =
                reading.read(document, new ProfileChannel.FromTask(own)).items();
        if (items.isEmpty()) {
            throw new ProfileRefused(NO_ITEM);
        }
        // The channel stamps the producer and a submission carries no id or thread; anything else
        // was not read as this task's.
        for (final ProfileItem each : items) {
            if (!own.equals(each.producer()) || each.id() != null || each.thread() != null) {
                throw new ProfileRefused(NOT_OWN);
            }
        }
        // Every item is judged before any is held, so a refusal holds nothing of the submission.
        for (final ProfileItem each : items) {
            if (each.item() instanceof ReviewComment && !role.mayOpenThread(task, place.get())) {
                throw new ProfileRefused(REPLIES_ONLY);
            }
        }
        for (final ProfileItem each : items) {
            final Optional<ReviewRefusal> refused = check.check(run.push(), each.item());
            if (refused.isPresent()) {
                throw new ProfileRefused(rule(refused.get()));
            }
        }
        for (final ProfileItem each : items) {
            holds.hold(run, task, Direction.OUTGOING, each.item(), null, null);
        }
    }

    /** Names a check's refusal by its contract error and rule, never by the item's text. */
    private static String rule(final ReviewRefusal refusal) {
        final String error = refusal.error().wireName();
        return refusal.detail() == null
                ? "check: " + error
                : "check: " + error + " (" + refusal.detail() + ")";
    }
}
