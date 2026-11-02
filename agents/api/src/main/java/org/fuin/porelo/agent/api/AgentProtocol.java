package org.fuin.porelo.agent.api;

/**
 * The contract between Porelo and an agent binary.
 *
 * <p>Porelo and the agents are packaged, versioned and installed separately, so at some point an
 * operator will have a combination nobody tested. The version here is what makes that a clear
 * message instead of a strange failure: an agent states which protocol it speaks, and Porelo
 * refuses one older than it needs rather than guessing at a partially-matching shape.
 *
 * <p><strong>{@code Agent1} only grows.</strong> A method or an optional field may be added, and
 * neither side has to release for it: an agent built against an older contract goes on working, a
 * method it lacks answers {@code org.varlink.service.MethodNotFound}, and Porelo says of that "this
 * agent cannot say", never "nothing found". A renamed, removed or reinterpreted method or field
 * raises {@link #LOWEST}.
 */
public final class AgentProtocol {

    /** Varlink interface both sides implement. */
    public static final String INTERFACE = "org.fuin.porelo.Agent1";

    /**
     * Version of this contract: what an agent built against it reports in {@code Describe}.
     *
     * <p>Raised with every addition, so that an agent says how much of the contract it knows.
     */
    public static final int VERSION = 4;

    /**
     * The lowest version this Porelo needs.
     *
     * <p>An agent at or above it is accepted, a newer one included, since Porelo calls only methods
     * it knows. Raised only when a method or field is renamed, removed or changes its meaning.
     */
    public static final int LOWEST = 3;

    /** Returns the agent's definition and the protocol version it speaks. */
    public static final String DESCRIBE = INTERFACE + ".Describe";

    /** Reads the agent's credential out of a config directory. */
    public static final String EXTRACT_CREDENTIAL = INTERFACE + ".ExtractCredential";

    /** Says what the agent finds of itself on this machine; never a credential's value. */
    public static final String FIND = INTERFACE + ".Find";

    /** Returns the files an agent needs placed in a container before it starts. */
    public static final String CONTAINER_SETUP = INTERFACE + ".ContainerSetup";

    /** Builds the command line for a non-interactive run. */
    public static final String BUILD_COMMAND = INTERFACE + ".BuildCommand";

    /** Streams formatted log lines read from a file. */
    public static final String FORMAT_LOG = INTERFACE + ".FormatLog";

    /** Formats lines of the agent's output in place; a line it hides answers null. */
    public static final String FORMAT = INTERFACE + ".Format";

    /**
     * Reads one line of the agent's output for the end of its run. Answered by an agent that knows
     * its end.
     */
    public static final String ENDED = INTERFACE + ".Ended";

    private AgentProtocol() {
        throw new UnsupportedOperationException("Utility class");
    }
}
