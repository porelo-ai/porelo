package org.fuin.porelo.gate.review;

/** What a run does at its place: the task's own work, or another's review. */
public enum Role {
    /** Builds the work at its place and answers its review. */
    AUTHOR,
    /** Reviews work at a place, pushes nothing there. */
    REVIEWER;

    /**
     * Returns the role a task's run records, by its contract word.
     *
     * @param word {@code AUTHOR} or {@code REVIEWER}.
     * @return The role.
     */
    public static Role of(final String word) {
        return valueOf(word);
    }
}
