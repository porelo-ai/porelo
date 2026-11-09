package org.fuin.porelo.host;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** The socket services' forge gate and the operator's measurement. */
final class HostForgeCommands {
    private HostForgeCommands() {}

    static CommandLine commandLine(Map<String, String> environment) {
        return new CommandLine(new PoreloHost.Group())
                .addSubcommand("gate", new Gate(environment))
                .addSubcommand("measure", new Measure());
    }

    static boolean unprivileged(String[] args) {
        return args.length >= 2
                && args[0].equals("forge")
                && (args[1].equals("gate") || args[1].equals("measure"));
    }

    @Command(
            name = "gate",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Serve one socket-activated forge connection.")
    static final class Gate implements Callable<Integer> {
        private final Map<String, String> environment;

        @CommandLine.ArgGroup(exclusive = true, multiplicity = "1")
        private Modes modes = new Modes();

        static final class Modes {
            @CommandLine.Option(names = "--web", required = true)
            boolean web;

            @CommandLine.Option(names = "--git", required = true)
            boolean git;

            @CommandLine.Option(names = "--runner", required = true)
            boolean runner;
        }

        @CommandLine.Option(names = "--upstream", required = true)
        private Path upstream;

        Gate(Map<String, String> environment) {
            this.environment = environment;
        }

        @Override
        public Integer call() throws Exception {
            if (environment.containsKey("SUDO_USER"))
                throw new HostRefusal(
                        65, "porelo-forge-gate: the socket service never runs through sudo");
            if (!upstream.isAbsolute())
                throw new HostRefusal(
                        64, "porelo-forge-gate: upstream must be an absolute Unix socket path");
            final String mode = modes.web ? "web" : modes.runner ? "runner" : "git";
            try {
                final var channel = HostSocketActivation.accepted(environment, mode);
                final var gate = ForgeGate.installed(mode, channel, upstream);
                // Closing Java's inherited channel detaches stderr too; the process owns its
                // lifetime.
                try (var connection = new GateConnection(channel, false)) {
                    gate.serve(connection.input, connection.output);
                }
            } catch (java.io.IOException ex) {
                throw new HostRefusal(1, "porelo-forge-gate: connection failed");
            }
            return 0;
        }
    }

    @Command(
            name = "measure",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Measure an installed gate, including activation and web keep-alive.")
    static final class Measure implements Callable<Integer> {
        @CommandLine.Option(names = "--socket", required = true)
        private Path socket;

        @CommandLine.Option(names = "--host", required = true)
        private String host;

        @CommandLine.Option(names = "--path", defaultValue = "/")
        private String path;

        @CommandLine.Option(names = "--requests", defaultValue = "1000")
        private int requests;

        @CommandLine.Option(names = "--concurrency", defaultValue = "32")
        private int concurrency;

        @CommandLine.Option(names = "--per-connection", defaultValue = "8")
        private int perConnection;

        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            final var result =
                    ForgeMeasure.run(socket, host, path, requests, concurrency, perConnection);
            spec.commandLine().getOut().println(HostJson.write(result));
            return ((Number)
                                            java.util.Objects.requireNonNull(
                                                    result.get("failures"),
                                                    "The measure reports every failed request"))
                                    .longValue()
                            == 0
                    ? 0
                    : 1;
        }
    }
}
