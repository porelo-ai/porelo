package org.fuin.porelo.agent.api;

import org.jspecify.annotations.Nullable;

/**
 * What an agent's adapter can enforce of the subagents a task allows its agent.
 *
 * <p>A task asks for one number: 0 for none, 1 or more for the most subagents the agent may run at
 * the same time. The adapter turns it into the agent's own switch, and declares here how far that
 * switch reaches, so that a task asking for more than it can hold is refused before it starts
 * rather than run with less than it asked for.
 */
public enum SubagentLimit {

    /**
     * The adapter can switch its agent's subagents off and bound nothing else: only 0 is held. What
     * a definition that declares nothing is read as.
     */
    OFF("off"),

    /** The adapter bounds how many subagents its agent runs at the same time: any count is held. */
    COUNT("count");

    /** The most subagents a task may ask for: the largest number its command line takes. */
    public static final int MOST = Integer.MAX_VALUE;

    private final String value;

    SubagentLimit(String value) {
        this.value = value;
    }

    /**
     * Returns the value used in definitions and reports.
     *
     * @return The declared spelling.
     */
    public String value() {
        return value;
    }

    /**
     * Returns whether the adapter can hold a task to this number.
     *
     * @param subagents How many subagents the task allows at the same time.
     * @return {@code true} when the adapter can enforce it.
     */
    public boolean enforces(int subagents) {
        return subagents == 0 || (this == COUNT && subagents > 0);
    }

    /**
     * Reads a number of subagents as a varlink call carries it, where every number is a double.
     *
     * @param value What was given.
     * @return The number, or {@code null} when it is not a whole number from 0 to {@link #MOST}: a
     *     fraction, a number past the bound, a string or a boolean is no number of subagents.
     */
    public static @Nullable Integer wholeNumber(@Nullable Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        final double given = number.doubleValue();
        return given >= 0 && given <= MOST && given == Math.rint(given)
                ? Integer.valueOf((int) given)
                : null;
    }

    /**
     * Reads a declared limit.
     *
     * @param value The declaration.
     * @param origin Where it was read, for a refusal.
     * @return The limit.
     * @throws AgentException If the declaration is not one of the values.
     */
    public static SubagentLimit of(@Nullable Object value, String origin) {
        for (final SubagentLimit limit : values()) {
            if (limit.value.equals(value)) {
                return limit;
            }
        }
        throw new AgentException(origin + ": subagents must be off or count, not '" + value + "'");
    }
}
