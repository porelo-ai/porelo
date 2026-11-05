package org.fuin.porelo.agent.api;

/**
 * What an agent is told about the task it is being set up for.
 *
 * <p>A record rather than a parameter list because this has already grown three times, and each
 * growth was a breaking change to {@link AgentProtocol} for every installed agent. Adding a field
 * here is not.
 *
 * @param token Token the agent should present, standing in for the real credential.
 * @param credentialType Kind of credential it stands in for.
 * @param workspace Absolute path of the working directory inside the container.
 * @param endpoint Where the agent should send its requests - a socket path or a URL, whichever it
 *     declared it can address, with the dialect's path already on it - or empty when the task is
 *     not brokered.
 * @param provider Name of the provider serving this task, as the provider declares itself. An agent
 *     that registers providers by name needs it; one pointed by a variable does not.
 * @param subagents The most subagents the agent may run at the same time inside the task; 0 for
 *     none. Here as well as in a {@link RunRequest}, because an attended agent is started without
 *     one, and an adapter whose agent reads the switch from a file writes it here.
 */
public record SetupContext(
        String token,
        String credentialType,
        String workspace,
        String endpoint,
        String provider,
        int subagents) {
    /** Without what it holds, as a line must never carry that. */
    @Override
    public String toString() {
        return "SetupContext["
                + credentialType
                + ", "
                + workspace
                + ", "
                + endpoint
                + ", "
                + provider
                + "]";
    }

    /**
     * Constructor with checks.
     *
     * @param token Token the agent should present.
     * @param credentialType Kind of credential it stands in for.
     * @param workspace Working directory inside the container.
     * @param endpoint Where the agent sends its requests, or empty.
     * @param provider Name of the provider serving the task.
     * @param subagents The most subagents at the same time; 0 for none.
     * @throws AgentException If {@code subagents} is below 0.
     */
    public SetupContext {
        if (subagents < 0) {
            throw new AgentException("subagents must be 0 or more, not " + subagents);
        }
    }

    /**
     * Constructor for a task that allows no subagents.
     *
     * @param token Token the agent should present.
     * @param credentialType Kind of credential it stands in for.
     * @param workspace Working directory inside the container.
     * @param endpoint Where the agent sends its requests, or empty.
     * @param provider Name of the provider serving the task.
     */
    public SetupContext(
            String token,
            String credentialType,
            String workspace,
            String endpoint,
            String provider) {
        this(token, credentialType, workspace, endpoint, provider, 0);
    }

    /**
     * Returns this context as the parameters of a {@code ContainerSetup} call.
     *
     * <p>Here rather than at the call site so that adding a field to this record and forgetting to
     * send it is a test failure rather than an agent that is quietly told nothing. That is not
     * hypothetical: the provider was added, the agent read it, the client never sent it, and a task
     * got an empty provider name and failed to authenticate.
     *
     * @return Parameters, one per component of this record.
     */
    public java.util.Map<String, Object> parameters() {
        return java.util.Map.of(
                "token",
                token,
                "credentialType",
                credentialType,
                "workspace",
                workspace,
                "endpoint",
                endpoint,
                "provider",
                provider,
                "subagents",
                Integer.valueOf(subagents));
    }

    /**
     * Returns whether this task is brokered at all.
     *
     * @return {@code true} when there is an endpoint to point the agent at.
     */
    public boolean brokered() {
        return endpoint != null && !endpoint.isBlank();
    }
}
