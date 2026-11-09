package org.fuin.porelo.planning.api;

import java.io.PrintStream;
import java.nio.file.Path;
import org.fuin.porelo.wire.Json;

/**
 * The whole main method of a planning adapter: {@code describe} prints what it is, {@code serve
 * <socket> <directory>} answers Porelo.
 */
public final class PlanningMain {

    private PlanningMain() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs an adapter and exits with its code.
     *
     * @param adapter The adapter this executable serves.
     * @param arguments Command line arguments.
     */
    public static void run(final PlanningAdapter adapter, final String[] arguments) {
        System.exit(execute(adapter, arguments, System.out, System.err));
    }

    /**
     * Runs an adapter and returns the exit code.
     *
     * @param adapter The adapter.
     * @param arguments Command line arguments.
     * @param out Standard output.
     * @param err Standard error.
     * @return 0, 2 for a wrong command line, 70 when it failed.
     */
    public static int execute(
            final PlanningAdapter adapter,
            final String[] arguments,
            final PrintStream out,
            final PrintStream err) {

        final String mode = arguments.length > 0 ? arguments[0] : "describe";
        try {
            switch (mode) {
                case "describe" -> {
                    out.println(Json.write(PlanningServer.description(adapter)));
                    return 0;
                }
                case "serve" -> {
                    if (arguments.length != 3) {
                        err.println("usage: " + adapter.name() + " serve <socket> <directory>");
                        return 2;
                    }
                    try (PlanningServer server =
                            new PlanningServer(
                                    adapter, Path.of(arguments[1]), Path.of(arguments[2]))) {
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
            err.println(adapter.name() + ": " + ex.getMessage());
            return 70;
        }
    }
}
