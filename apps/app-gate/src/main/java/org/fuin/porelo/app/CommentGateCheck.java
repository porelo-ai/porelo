package org.fuin.porelo.app;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.gate.review.CommentGate;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.ProfileWriting;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.Run;

/**
 * Judges each comment of a conversation by its place's reading setting, for one task's runs.
 * Whoever wrote a comment cannot be told apart, so under {@link CommentReading#EXTENDED} it passes
 * the message filter as an external peer's message does: written as a one-result profile 2 page,
 * the data part of an inbound {@code comment} message. One the filter refuses, or any when the
 * filter cannot run, is withheld from the run and held for the owner account with its rule. Under
 * {@link CommentReading#TRUSTED} it passes on the input path's format and size checks alone. The
 * comment keeps its producer either way, and nothing is remembered: the next run judges it again.
 */
public final class CommentGateCheck implements CommentGate {

    /** The rule a comment is withheld by when the message filter cannot run. */
    public static final String FILTER_UNAVAILABLE = "filter: could not run";

    /** The rule a comment is withheld by when the filter refused it without naming a rule. */
    public static final String FILTER_REFUSED = InboundFilter.UNNAMED_RULE;

    /** The rule a comment is withheld by when no page can be written for the filter. */
    public static final String NO_PAGE = "filter: no pull request facts";

    private final ProfileWriting writing;
    private final InboundFilter filter;
    private final ReviewHolds holds;
    private final String task;

    /**
     * Constructor with the writer, the filter, the holds and the task.
     *
     * @param writing Writes a comment as the one-result page the filter checks.
     * @param filter The message filter's inbound check.
     * @param holds Where a withheld comment is held for the owner account.
     * @param task The task whose runs this judges, since a run does not name its task.
     */
    public CommentGateCheck(
            final ProfileWriting writing,
            final InboundFilter filter,
            final ReviewHolds holds,
            final String task) {
        this.writing = Objects.requireNonNull(writing, "writing");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.task = Objects.requireNonNull(task, "task");
    }

    @Override
    public Optional<String> judge(
            final ReviewPlace place, final Run run, final Kept item, final Kept root) {
        if (place.reading() == CommentReading.TRUSTED) {
            // The format and size checks already ran on the input path.
            return Optional.empty();
        }
        final Optional<String> rule = filtered(run, item, root);
        rule.ifPresent(each -> holds.hold(run, task, Direction.INCOMING, item.item(), item, each));
        return rule;
    }

    /** Puts the comment through the filter as an external peer's message, failing closed. */
    private Optional<String> filtered(final Run run, final Kept item, final Kept root) {
        final PullRequestFacts facts = run.pinned().facts();
        if (facts == null) {
            return Optional.of(NO_PAGE);
        }
        final Optional<String> rule;
        try {
            rule = filter.refuse(facts.ref().forge(), writing.one(facts, item, root));
        } catch (final IOException | RuntimeException cannotRun) {
            // No page or no verdict: never a comment let through unchecked.
            return Optional.of(FILTER_UNAVAILABLE);
        }
        // A blank rule id is still a refusal, and a hold names its rule.
        return rule.map(each -> each.isBlank() ? FILTER_REFUSED : each);
    }
}
