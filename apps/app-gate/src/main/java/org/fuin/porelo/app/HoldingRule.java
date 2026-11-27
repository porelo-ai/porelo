package org.fuin.porelo.app;

import java.util.Objects;
import org.fuin.porelo.core.project.SecurityClass;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.Run;

/**
 * Decides whether a run's comments and replies wait for the owner account or go at once, by the
 * run's place and its project's security class alone; nothing the task says or recorded decides it.
 *
 * <ul>
 *   <li>At a waiting push at the gate nothing waits: the items are written into the push's threads
 *       at once.
 *   <li>At a pull request of a {@code guarded} project every item is held until the owner account
 *       releases or refuses it.
 *   <li>At a pull request of an {@code online} project every item goes to the forge at once, as the
 *       task's branch does.
 *   <li>An {@code offline} project has no forge: an item for a pull request there is refused, never
 *       sent.
 * </ul>
 *
 * <p>A submission no certified key signed goes at a gate push as a signed one does: nothing leaves
 * the machine, and the host stamps the mailbox each item came through. Toward a pull request it is
 * held marked unsigned in every class, as the one thing that shows the owner account it was
 * unsigned, and never goes at once. A place that is neither a gate push nor a pull request, and a
 * project whose file cannot be read, let nothing out.
 */
public final class HoldingRule {

    /** The rule an item for a pull request of an offline project breaks. */
    static final String OFFLINE = "intake: an offline project has no pull request";

    /** The rule a submission breaks whose place is neither a gate push nor a pull request. */
    static final String NO_PLACE = "intake: the place is neither a gate push nor a pull request";

    /** The rule a submission breaks when its project's class cannot be read. */
    static final String CLASS_UNREADABLE = "intake: the project file cannot be read";

    /** Where a run's items go once every check passed. */
    public enum Way {
        /** Held for the owner account's decision. */
        HOLD,
        /** Written into the waiting push's threads at once. */
        THREADS,
        /** Written to the pull request at once. */
        FORGE
    }

    /**
     * Reads a project's security class from its project file, as the gate reads it.
     *
     * <p>A stand-in answers a fixed class.
     */
    @FunctionalInterface
    public interface Classes {

        /**
         * Returns a project's class.
         *
         * @param project The project's name.
         * @return Its class.
         * @throws RuntimeException When its project file cannot be read.
         */
        SecurityClass of(String project);
    }

    private final Classes classes;

    /**
     * Constructor.
     *
     * @param classes Reads a project's class.
     */
    public HoldingRule(final Classes classes) {
        this.classes = Objects.requireNonNull(classes, "classes");
    }

    /**
     * Returns a machine's rule, each class read from the project file at each question.
     *
     * @param context The machine.
     * @return The rule.
     */
    public static HoldingRule of(final PoreloContext context) {
        return new HoldingRule(project -> GateSupport.byName(context, project).securityClass());
    }

    /**
     * Returns where a run's checked items go.
     *
     * @param run The run, whose pinned state says whether its place is a pull request and whose
     *     pinned push names the task's project.
     * @param place The run's place, as found by its name: a waiting push at the gate, or a pull
     *     request.
     * @param unsigned Whether no certified key signed the submission.
     * @return The way.
     * @throws ProfileRefused For a pull request of an offline project, a place that is neither a
     *     gate push nor a pull request, and when the project file cannot be read.
     */
    public Way way(final Run run, final ReviewPlace place, final boolean unsigned)
            throws ProfileRefused {
        if (place instanceof WaitingPushPlace) {
            return Way.THREADS;
        }
        if (run.pinned().facts() == null) {
            throw new ProfileRefused(NO_PLACE);
        }
        final Way way = atPullRequest(run);
        // Toward a forge only a hold shows the owner account that no certified key signed it.
        return unsigned ? Way.HOLD : way;
    }

    private Way atPullRequest(final Run run) throws ProfileRefused {
        final SecurityClass security;
        try {
            security = classes.of(run.push().project());
        } catch (RuntimeException ex) {
            // Fail closed: a class that cannot be read never lets an item out unheld.
            throw new ProfileRefused(CLASS_UNREADABLE);
        }
        return switch (security) {
            case GUARDED -> Way.HOLD;
            case ONLINE -> Way.FORGE;
            case OFFLINE -> throw new ProfileRefused(OFFLINE);
        };
    }
}
