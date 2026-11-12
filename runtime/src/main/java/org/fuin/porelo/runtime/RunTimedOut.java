package org.fuin.porelo.runtime;

import java.time.Duration;

/**
 * An agent's run went past its time limit. Only the client that ran it is ended here; the caller
 * ends the agent in its container, which this cannot do.
 */
public final class RunTimedOut extends ContainerException {

    private static final long serialVersionUID = 1L;

    private final Duration limit;

    /**
     * Constructor with the limit that passed.
     *
     * @param limit How long the run was allowed.
     */
    public RunTimedOut(Duration limit) {
        super("The agent did not finish within " + limit.toSeconds() + " seconds");
        this.limit = limit;
    }

    /**
     * Returns the limit that passed.
     *
     * @return How long the run was allowed.
     */
    public Duration limit() {
        return limit;
    }
}
