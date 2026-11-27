package org.fuin.porelo.app;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.TaskParts;
import org.jspecify.annotations.Nullable;

/**
 * Which of the methods a project's {@code mcp} section names a task's own endpoint carries: the
 * section is the project's bound, and within it {@code Start}, {@code Stop}, {@code Done} and
 * {@code Report} are carried only where the task's recorded rights hold {@code start}, {@code
 * stop}, {@code done} and {@code report}. The daemon holds each call to it and its guide tells the
 * task the same list.
 */
public final class EndpointRights {

    /** The methods a task's endpoint may carry beside the reading ones, each to its right. */
    private static final Map<String, Right> WRITING =
            Map.of(
                    "Start", Right.START,
                    "Stop", Right.STOP,
                    "Done", Right.DONE,
                    "Report", Right.REPORT);

    private EndpointRights() {}

    /**
     * Returns the methods besides the reading ones a task's endpoint may carry at all.
     *
     * @return Their names, without the interface.
     */
    public static Set<String> writing() {
        return WRITING.keySet();
    }

    /**
     * Tells whether a task's endpoint carries a method as far as its recorded rights go.
     *
     * @param parts The task's recorded parts, or {@code null} when it recorded none or they do not
     *     read: no right at all.
     * @param method The method, without the interface.
     * @return True for a reading method, and for a writing one whose right the task holds.
     */
    public static boolean carries(final @Nullable TaskParts parts, final String method) {
        final Right right = WRITING.get(method);
        return right == null || parts != null && parts.holds(right);
    }

    /**
     * Returns, of the methods a section names, those a task's endpoint carries.
     *
     * @param parts The task's recorded parts, or {@code null} for none.
     * @param methods The section's methods, in its order.
     * @return Those carried, in the same order.
     */
    public static List<String> carried(
            final @Nullable TaskParts parts, final List<String> methods) {
        return methods.stream().filter(method -> carries(parts, method)).toList();
    }

    /**
     * Reads a task's recorded parts now, failing closed.
     *
     * @param context The machine.
     * @param container The task's container.
     * @return Them, or {@code null} when it recorded none or they do not read.
     */
    public static @Nullable TaskParts recorded(
            final PoreloContext context, final String container) {
        try {
            return RunPlace.recordedParts(context, container);
        } catch (IllegalStateException unreadable) {
            return null;
        }
    }
}
