package org.fuin.porelo.build.api;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The whole main method of a build reader: {@code describe} prints what it is, {@code serve
 * <socket>} answers Porelo.
 */
public final class BuildMain {

    private BuildMain() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs a reader and exits with its code.
     *
     * @param reader The reader this executable serves.
     * @param arguments Command line arguments.
     */
    public static void run(final BuildReader reader, final String[] arguments) {
        System.exit(execute(reader, arguments, System.out, System.err));
    }

    /**
     * Runs a reader and returns the exit code.
     *
     * @param given The reader, before it is given its time factors.
     * @param arguments Command line arguments.
     * @param out Standard output.
     * @param err Standard error.
     * @return 0, 2 for a wrong command line, 70 when it failed.
     */
    public static int execute(
            final BuildReader given,
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
        final BuildReader reader;
        try {
            reader =
                    java.util.Objects.requireNonNull(
                            given.withLimits(leading.factors()),
                            "withLimits returns what to serve");
        } catch (RuntimeException ex) {
            err.println(given.forge() + ": " + ex.getMessage());
            return 70;
        }
        final String mode = arguments.length > first ? arguments[first] : "describe";
        try {
            switch (mode) {
                case "describe" -> {
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("protocolVersion", Integer.valueOf(BuildProtocol.VERSION));
                    answer.put("forge", reader.forge());
                    out.println(org.fuin.porelo.wire.Json.write(answer));
                    return 0;
                }
                case "serve" -> {
                    if (arguments.length < first + 2) {
                        err.println(
                                "usage: " + reader.forge() + " [--limits <line>] serve <socket>");
                        return 2;
                    }
                    try (BuildServer server =
                            new BuildServer(reader, Path.of(arguments[first + 1]))) {
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
            err.println(reader.forge() + ": " + ex.getMessage());
            return 70;
        }
    }
}
