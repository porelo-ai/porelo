package org.fuin.porelo.app;

import java.util.Objects;
import org.fuin.porelo.gate.review.TaskParts;
import org.jspecify.annotations.Nullable;

/**
 * The parts and place a start took once they were checked, before anything is made.
 *
 * @param asked What the start named of the parts, each member named or absent: a start that brings
 *     a task back is held to its recorded parts by it.
 * @param parts The parts a new task records: the named ones, the rest by their default for the
 *     workspace.
 * @param place The place the start named, with the parts, or {@code null} where it named none: a
 *     task with a gate then works at its own gate push, one without has no place.
 */
public record StartParts(TaskParts.Asked asked, TaskParts parts, @Nullable RunPlace place) {

    /** Refuses a missing part. */
    public StartParts {
        Objects.requireNonNull(asked, "asked");
        Objects.requireNonNull(parts, "parts");
    }

    /**
     * Returns what a start naming no part and no place takes at its own work.
     *
     * @return It.
     */
    public static StartParts ownWork() {
        final TaskParts.Asked nothing = TaskParts.Asked.nothing();
        return new StartParts(nothing, nothing.atOwnWork(), null);
    }
}
