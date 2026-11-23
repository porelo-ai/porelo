package org.fuin.porelo.agent.api;

import java.nio.file.Path;
import java.util.Map;

/**
 * The {@code main} every agent binary shares.
 *
 * <p>An agent's own entry point is three lines:
 *
 * <pre>
 * public static void main(String[] args) {
 *     AgentMain.run(new ExampleAgent(), args);
 * }
 * </pre>
 *
 * Two modes, and both are useful, each after an optional {@code --limits <line>}, the four factors
 * every time limit follows, which the agent receives through {@link Agent#withLimits}. {@code
 * serve} is what Porelo spawns. {@code describe} prints the definition as JSON, which makes an
 * installed agent inspectable with nothing but the binary and {@code jq} - worth having when an
 * operator is asking why Porelo will not use an agent they just installed.
 */
public final class AgentMain {

    private AgentMain() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs an agent binary.
     *
     * @param agent The agent this binary serves.
     * @param arguments Command line arguments.
     */
    public static void run(Agent agent, String[] arguments) {
        System.exit(execute(agent, arguments, System.out, System.err));
    }

    /**
     * Runs an agent binary and returns the exit code.
     *
     * @param given The agent this binary serves, before it is given its time factors.
     * @param arguments Command line arguments.
     * @param out Standard output.
     * @param err Standard error.
     * @return Exit code.
     */
    public static int execute(
            Agent given, String[] arguments, java.io.PrintStream out, java.io.PrintStream err) {

        // Porelo names the time factors before the mode: <agent> --limits <line> serve <socket>.
        final org.fuin.porelo.wire.LimitFactors.Leading leading;
        try {
            leading = org.fuin.porelo.wire.LimitFactors.leading(arguments);
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            return 64;
        }
        final int first = leading.next();
        final org.fuin.porelo.wire.LimitFactors limits = leading.factors();
        final Agent agent =
                java.util.Objects.requireNonNull(
                        given.withLimits(limits), "an agent's withLimits returns an agent");
        final String mode = arguments.length > first ? arguments[first] : "describe";

        try {
            switch (mode) {
                case "describe" -> {
                    out.println(
                            org.fuin.porelo.wire.Json.write(
                                    Map.of(
                                            "protocolVersion",
                                                    Integer.valueOf(AgentProtocol.VERSION),
                                            "definition",
                                                    AgentDefinitionJson.write(
                                                            agent.definition()))));
                    return 0;
                }
                case "serve" -> {
                    if (arguments.length < first + 2) {
                        err.println(
                                "usage: "
                                        + agent.name()
                                        + " ["
                                        + org.fuin.porelo.wire.LimitFactors.OPTION
                                        + " <line>] serve <socket>");
                        return 2;
                    }
                    try (AgentServer server =
                            new AgentServer(agent, Path.of(arguments[first + 1]))) {
                        // Printed so a caller waiting for readiness has something to wait for other
                        // than a sleep.
                        out.println("ready " + server.socketPath());
                        out.flush();
                        server.serve();
                    }
                    return 0;
                }
                default -> {
                    err.println("unknown mode '" + mode + "', expected 'describe' or 'serve'");
                    return 2;
                }
            }
        } catch (RuntimeException ex) {
            err.println(agent.name() + ": " + ex.getMessage());
            return 70;
        }
    }
}
