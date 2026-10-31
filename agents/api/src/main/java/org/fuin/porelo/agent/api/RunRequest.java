package org.fuin.porelo.agent.api;

import org.jspecify.annotations.Nullable;

/**
 * What a caller wants an agent to do.
 *
 * @param prompt The instruction.
 * @param model Model to use, or {@code null} for the agent's default.
 * @param maxTurns Turn limit, or {@code null} for none.
 * @param resumeSession Session to continue, or {@code null} to start fresh.
 * @param verbose Whether to ask for verbose output.
 * @param machineReadable Whether to ask for the agent's structured output format.
 * @param subagents The most subagents the agent may run at the same time inside the task; 0 for
 *     none. The adapter turns it into the agent's own switch.
 */
public record RunRequest(
        String prompt,
        @Nullable String model,
        @Nullable Integer maxTurns,
        @Nullable String resumeSession,
        boolean verbose,
        boolean machineReadable,
        int subagents) {

    /**
     * Constructor with checks.
     *
     * @param prompt The instruction.
     * @param model Model to use, or {@code null}.
     * @param maxTurns Turn limit, or {@code null}.
     * @param resumeSession Session to continue, or {@code null}.
     * @param verbose Whether to ask for verbose output.
     * @param machineReadable Whether to ask for structured output.
     * @param subagents The most subagents at the same time; 0 for none.
     * @throws AgentException If {@code subagents} is below 0.
     */
    public RunRequest {
        if (subagents < 0) {
            throw new AgentException("subagents must be 0 or more, not " + subagents);
        }
    }

    /**
     * Constructor for a request that allows no subagents.
     *
     * @param prompt The instruction.
     * @param model Model to use, or {@code null}.
     * @param maxTurns Turn limit, or {@code null}.
     * @param resumeSession Session to continue, or {@code null}.
     * @param verbose Whether to ask for verbose output.
     * @param machineReadable Whether to ask for structured output.
     */
    public RunRequest(
            String prompt,
            @Nullable String model,
            @Nullable Integer maxTurns,
            @Nullable String resumeSession,
            boolean verbose,
            boolean machineReadable) {
        this(prompt, model, maxTurns, resumeSession, verbose, machineReadable, 0);
    }

    /**
     * Creates a plain request.
     *
     * @param prompt The instruction.
     * @return New request.
     */
    public static RunRequest of(String prompt) {
        return new RunRequest(prompt, null, null, null, false, false, 0);
    }
}
