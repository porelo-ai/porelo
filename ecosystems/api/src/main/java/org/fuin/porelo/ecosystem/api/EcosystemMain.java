package org.fuin.porelo.ecosystem.api;

import java.io.PrintStream;
import java.nio.file.Path;
import org.fuin.porelo.wire.Json;

/**
 * The whole main method of a build-tool plugin: {@code describe} prints what it is, {@code serve
 * <socket>} answers Porelo.
 */
public final class EcosystemMain {

    private EcosystemMain() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs a plugin and exits with its code.
     *
     * @param ecosystem The plugin this executable serves.
     * @param arguments Command line arguments.
     */
    public static void run(final Ecosystem ecosystem, final String[] arguments) {
        System.exit(execute(ecosystem, arguments, System.out, System.err));
    }

    /**
     * Runs a plugin and returns the exit code.
     *
     * @param given The plugin, before it is given its time factors.
     * @param arguments Command line arguments.
     * @param out Standard output.
     * @param err Standard error.
     * @return 0, 2 for a wrong command line, 70 when it failed.
     */
    public static int execute(
            final Ecosystem given,
            final String[] arguments,
            final PrintStream out,
            final PrintStream err) {

        // Porelo names the time factors before the mode: <program> --limits <line> serve <socket>.
        final org.fuin.porelo.wire.LimitFactors.Leading leading;
        try {
            leading = org.fuin.porelo.wire.LimitFactors.leading(arguments);
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            return 64;
        }
        final int first = leading.next();
        final Ecosystem ecosystem;
        try {
            ecosystem =
                    java.util.Objects.requireNonNull(
                            given.withLimits(leading.factors()),
                            "withLimits returns what to serve");
        } catch (RuntimeException ex) {
            err.println(given.ecosystem() + ": " + ex.getMessage());
            return 70;
        }
        final String mode = arguments.length > first ? arguments[first] : "describe";
        try {
            switch (mode) {
                case "describe" -> {
                    out.println(Json.write(EcosystemServer.description(ecosystem)));
                    return 0;
                }
                case "serve" -> {
                    if (arguments.length < first + 2) {
                        err.println(
                                "usage: "
                                        + ecosystem.ecosystem()
                                        + " [--limits <line>] serve <socket>");
                        return 2;
                    }
                    try (EcosystemServer server =
                            new EcosystemServer(ecosystem, Path.of(arguments[first + 1]))) {
                        // What Porelo waits for: the socket file appears before bind() returns, so
                        // waiting for the file could connect to a socket nobody listens on.
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
            err.println(ecosystem.ecosystem() + ": " + ex.getMessage());
            return 70;
        }
    }
}
