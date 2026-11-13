package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The visible mark for work that runs without a check: a task whose commits no certified key signs,
 * and a task of a project whose file nothing verified.
 *
 * <p>There is no switch that turns the checks off; what already runs unchecked is named instead,
 * wherever it is shown. A mark is always a checker's verdict - this machine's, its gate's or a
 * receiving Porelo's - read from state that exists, never what a sender wrote about itself. An
 * absent value therefore reads as the weaker one: no certificate is {@link #UNSIGNED}, never
 * certified.
 */
public final class TrustMark {

    /** No certified key of the task's own signs its work. */
    public static final String UNSIGNED = "unsigned";

    /** Nothing verified the file of the task's project. */
    public static final String UNVERIFIED = "unverified";

    private TrustMark() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the mark of a task.
     *
     * @param certified Whether its commits are signed by a certified key of its own.
     * @param verified Whether its project's file was verified, or needs none.
     * @return {@code unsigned}, {@code unverified}, both joined by {@code ", "}, or "" for neither.
     */
    public static String of(final boolean certified, final boolean verified) {
        final List<String> marks = new ArrayList<>(2);
        if (!certified) {
            marks.add(UNSIGNED);
        }
        if (!verified) {
            marks.add(UNVERIFIED);
        }
        return String.join(", ", marks);
    }

    /**
     * Says whether a task's project counts as verified, from what the task recorded.
     *
     * @param project The task's project, or {@code null} when nothing recorded one.
     * @param commit What its project file was verified at, or {@code null} or "" when nothing
     *     verified it.
     * @return {@code true} for a recorded commit, and for the project {@code default}, which has no
     *     file to verify.
     */
    public static boolean verified(final @Nullable String project, final @Nullable String commit) {
        return DefaultProject.is(project) || commit != null && !commit.isBlank();
    }

    /**
     * Says whether this machine verified a project's file, as a receiver judges a sender's project.
     *
     * @param context The machine.
     * @param project The project's name.
     * @return {@code true} for the organization's project, whose file a start judged, and for the
     *     project {@code default}; {@code false} for one a person wrote or one this machine only
     *     ran a task for, and for one it does not know.
     */
    public static boolean verifiedHere(final PoreloContext context, final String project) {
        final ProjectSource.Outcome outcome;
        try {
            outcome = ProjectSource.resolve(context, project).outcome();
        } catch (final RuntimeException ex) {
            // A project that cannot be resolved was verified by nothing here.
            return false;
        }
        return outcome == ProjectSource.Outcome.ORGANIZATION
                || outcome == ProjectSource.Outcome.BUILT_IN;
    }
}
