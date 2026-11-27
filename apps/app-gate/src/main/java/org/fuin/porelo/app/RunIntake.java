package org.fuin.porelo.app;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.fuin.porelo.gate.review.CommentCheck;
import org.fuin.porelo.gate.review.CurrentRuns;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.ProfileChannel;
import org.fuin.porelo.gate.review.ProfileItem;
import org.fuin.porelo.gate.review.ProfileRead;
import org.fuin.porelo.gate.review.ProfileReading;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewPlaces;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.gate.review.TaskRights;
import org.fuin.porelo.gate.review.ThreadResolve;

/**
 * Takes a run's submission from its task and sends its comments and replies towards the run's
 * place: held for the owner account's release, bound to the run's place, its pinned commit and the
 * digest of the item's exact text, or written at once, as {@link HoldingRule} decides by the place
 * and the project's security class, never by anything of the task.
 *
 * <p>The one way a task's words go towards a place: the submission is read as the task's, judged by
 * the task's recorded rights there, checked against the run's pinned push, and held or written only
 * when every item passes. It keeps nothing itself; whether a named thread exists is the place's to
 * say when an item is written.
 *
 * <p>A submission no certified key signed is judged the same. At a gate push its items go at once,
 * as a signed one's do; toward a pull request each is held marked unsigned, so the owner account
 * sees it as such before deciding.
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

    /** The rule a submission breaks that names another place than its run's. */
    static final String NOT_ITS_RUN = "intake: names a place that is not the place of its run";

    /** The rule a new comment breaks where the task holds no right to open a thread. */
    static final String MAY_NOT_OPEN = rightMissing(Right.OPEN_THREADS, "open a thread");

    /** The rule a reply breaks where the task holds no right to answer in a thread. */
    static final String MAY_NOT_REPLY = rightMissing(Right.REPLY, "reply in a thread");

    /** The rule a resolve breaks where the task holds no right to mark a thread resolved. */
    static final String MAY_NOT_RESOLVE = rightMissing(Right.RESOLVE_THREADS, "resolve a thread");

    /** A forge's thread id as the review contract bounds it. */
    private static final Pattern FORGE_THREAD = Pattern.compile("[0-9]{1,64}");

    private final ProfileReading reading;

    private final CommentCheck check;

    private final TaskRights rights;

    private final ReviewHolds holds;

    private final CurrentRuns currentRuns;

    private final ReviewPlaces places;

    private final HoldingRule rule;

    private final UnheldWrites unheld;

    /** Judges a submission's reject; none is taken until a machine wires its gate. */
    private volatile TaskReject.Judge judge = TaskReject.NONE;

    /**
     * Constructor with what reads, judges, checks and holds, and what knows a task's run and its
     * place.
     *
     * @param reading The one input path for profile documents.
     * @param check The check of an item against the run's pinned push.
     * @param rights What the task's recorded parts allow it at a place.
     * @param holds Where items wait for the owner account's decision.
     * @param currentRuns The run each task is in now.
     * @param places Finds a run's place by its name.
     * @param rule Whether a run's items wait for the owner account or go at once.
     * @param unheld Writes the items that go at once.
     */
    public RunIntake(
            final ProfileReading reading,
            final CommentCheck check,
            final TaskRights rights,
            final ReviewHolds holds,
            final CurrentRuns currentRuns,
            final ReviewPlaces places,
            final HoldingRule rule,
            final UnheldWrites unheld) {
        this.reading = Objects.requireNonNull(reading, "reading");
        this.check = Objects.requireNonNull(check, "check");
        this.rights = Objects.requireNonNull(rights, "rights");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.currentRuns = Objects.requireNonNull(currentRuns, "currentRuns");
        this.places = Objects.requireNonNull(places, "places");
        this.rule = Objects.requireNonNull(rule, "rule");
        this.unheld = Objects.requireNonNull(unheld, "unheld");
    }

    /**
     * Takes a submission's reject with this judgment, from now on.
     *
     * @param judge Judges and acts on a reject.
     * @return This intake.
     */
    public RunIntake rejecting(final TaskReject.Judge judge) {
        this.judge = Objects.requireNonNull(judge, "judge");
        return this;
    }

    @Override
    public void take(final String task, final byte[] document, final boolean unsigned)
            throws ProfileRefused {
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
        final ProfileRead read = reading.read(document, new ProfileChannel.FromTask(own));
        final List<ProfileItem> items = read.items();
        if (items.isEmpty() && read.reject() == null) {
            throw new ProfileRefused(NO_ITEM);
        }
        // A place a run names is its run's own or none: the run decides where its items go.
        final String runsPlace = ownPlace(run);
        for (final String named : read.named()) {
            if (!runsPlace.equals(named)) {
                throw new ProfileRefused(NOT_ITS_RUN);
            }
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
            if (each.item() instanceof ReviewComment && !rights.mayOpenThread(task, place.get())) {
                throw new ProfileRefused(MAY_NOT_OPEN);
            }
            // A reply is read as a forge's or a push's, by where it goes; either needs the right.
            if ((each.item() instanceof ReviewReply || each.item() instanceof ForgeReply)
                    && !rights.mayReply(task, place.get())) {
                throw new ProfileRefused(MAY_NOT_REPLY);
            }
            if (each.item() instanceof ThreadResolve && !rights.mayResolve(task, place.get())) {
                throw new ProfileRefused(MAY_NOT_RESOLVE);
            }
        }
        for (final ProfileItem each : items) {
            final Optional<ReviewRefusal> refused = check.check(run.push(), each.item());
            if (refused.isPresent()) {
                throw new ProfileRefused(rule(refused.get()));
            }
            if (each.item() instanceof ThreadResolve resolve
                    && !atPlace(run, place.get(), resolve.thread())) {
                throw new ProfileRefused(rule(new ReviewRefusal(ReviewError.NO_SUCH_THREAD, null)));
            }
        }
        // Judged with every item, before any goes; the push is discarded last.
        final TaskReject.Act last = judge.judge(task, run, place.get(), read.reject());
        final HoldingRule.Way way = rule.way(run, place.get(), unsigned);
        if (way != HoldingRule.Way.HOLD) {
            if (!items.isEmpty()) {
                unheld.write(way, run, task, items.stream().map(ProfileItem::item).toList());
            }
        } else {
            // Never with a reject: its judging refused every place but a waiting push, where
            // nothing is held.
            for (final ProfileItem each : items) {
                holds.hold(run, task, Direction.OUTGOING, each.item(), null, null, unsigned);
            }
        }
        try {
            last.run();
        } catch (ProfileRefused refused) {
            if (items.isEmpty() || way == HoldingRule.Way.HOLD) {
                throw refused;
            }
            // Its items are the reject's reasons and went first; the answer says they stay.
            throw new ProfileRefused(
                    refused.rule()
                            + ". "
                            + items.size()
                            + (items.size() == 1 ? " item" : " items")
                            + " written. push not discarded");
        }
    }

    /** The place a run's submission may name, as {@link ProfileRead#named} writes it. */
    private static String ownPlace(final Run run) {
        final PullRequestFacts facts = run.pinned().facts();
        if (facts != null) {
            return "pullRequest:" + facts.ref().url();
        }
        final Push push = run.push();
        return "push:" + push.project() + "/" + push.repository() + "/" + push.name();
    }

    /**
     * Whether a thread a resolve names is one at the run's place, as far as the intake can see it:
     * at a gate push one of the pinned push's threads; at a pull request, whose conversation the
     * intake does not hold, a thread id of the forge contract's shape, 1 to 64 digits, which the
     * forge answers {@code NoSuchThread} for when it holds no such thread.
     */
    private static boolean atPlace(final Run run, final ReviewPlace place, final String thread) {
        if (place instanceof WaitingPushPlace push) {
            return push.holdsThread(run.push(), thread);
        }
        // Neither kind of place: no thread is one of it.
        return run.pinned().facts() != null && FORGE_THREAD.matcher(thread).matches();
    }

    /**
     * Returns the rule an act breaks whose right the task's start did not record: {@code
     * RightMissing: <right> (<act>)}, the right by its contract word.
     *
     * @param right The right the act needs.
     * @param act What was tried.
     * @return The rule.
     */
    static String rightMissing(final Right right, final String act) {
        return "RightMissing: " + right.word() + " (" + act + ")";
    }

    /** Names a check's refusal by its contract error and rule, never by the item's text. */
    private static String rule(final ReviewRefusal refusal) {
        final String error = refusal.error().wireName();
        return refusal.detail() == null
                ? "check: " + error
                : "check: " + error + " (" + refusal.detail() + ")";
    }
}
