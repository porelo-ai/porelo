package org.fuin.porelo.host;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;

/** The host's own program, invoked by sudo rules or root's units. */
@Command(
        name = "porelo-host",
        mixinStandardHelpOptions = true,
        versionProvider = HostVersion.class,
        description = "The host's own program, run by sudo rules and root's units.",
        exitCodeOnInvalidInput = 64,
        exitCodeOnExecutionException = 69)
public final class PoreloHost implements Callable<Integer> {
    @CommandLine.Spec private CommandLine.Model.CommandSpec spec;
    private final Map<String, String> environment;
    private final HostFiles files;
    private final Function<Map<String, String>, HostPerson> person;

    /** Uses only the installed host tree and the sudo identity. */
    public PoreloHost() {
        this(System.getenv(), new HostFiles(), HostPerson::from);
    }

    PoreloHost(
            Map<String, String> environment,
            HostFiles files,
            Function<Map<String, String>, HostPerson> person) {
        this.environment = Map.copyOf(environment);
        this.files = files;
        this.person = person;
    }

    /** Checks the root boundary before handing any argument to picocli. */
    public static void main(String[] args) {
        // The caller's LANG and LC_* choose neither case folding nor the bytes written.
        java.util.Locale.setDefault(java.util.Locale.ROOT);
        System.exit(
                run(
                        args,
                        System.getenv(),
                        HostUid::real,
                        new PrintWriter(
                                new java.io.OutputStreamWriter(
                                        System.out, java.nio.charset.StandardCharsets.UTF_8),
                                true),
                        new PrintWriter(
                                new java.io.OutputStreamWriter(
                                        System.err, java.nio.charset.StandardCharsets.UTF_8),
                                true)));
    }

    @FunctionalInterface
    interface Uid {
        long real() throws IOException;
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            Uid uid,
            PrintWriter out,
            PrintWriter err) {
        return run(args, environment, uid, out, err, new HostFiles(), HostPerson::from);
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            Uid uid,
            PrintWriter out,
            PrintWriter err,
            HostFiles files,
            Function<Map<String, String>, HostPerson> person) {
        try {
            HostEnvironment.check(environment);
            final boolean informational = args.length == 1 && answersAlone(args[0]);
            if (!informational && uid.real() != 0) {
                throw new HostRefusal(
                        77,
                        "porelo-host: root uid required; use the installed sudo rule or root's unit");
            }
            // picocli answers a version or help request even beside an argument it cannot match,
            // so "-Dx=y --version" would exit 0: before the verb they stand alone or not at all.
            if (args.length > 1
                    && java.util.Arrays.stream(args)
                            .takeWhile(argument -> argument.startsWith("-"))
                            .anyMatch(PoreloHost::answersAlone)) {
                throw new HostRefusal(
                        64, "porelo-host: --version and --help take no other argument");
            }
            return commandLine(new PoreloHost(environment, files, person))
                    .setOut(out)
                    .setErr(err)
                    .execute(args);
        } catch (HostRefusal ex) {
            err.println(ex.getMessage());
            return ex.exitCode();
        } catch (IOException ex) {
            err.println("porelo-host: cannot establish the real uid; check procfs");
            return 77;
        }
    }

    private static boolean answersAlone(String argument) {
        return argument.equals("--version")
                || argument.equals("-V")
                || argument.equals("--help")
                || argument.equals("-h");
    }

    static CommandLine commandLine() {
        return commandLine(new PoreloHost());
    }

    private static CommandLine commandLine(PoreloHost host) {
        final CommandLine command = new CommandLine(host);
        command.addSubcommand(
                "org", new CommandLine(new Group()).addSubcommand("status", new OrgStatus(host)));
        command.addSubcommand(
                "task", new CommandLine(new Group()).addSubcommand("show", new TaskShow(host)));
        command.addSubcommand("project", HostProject.commandLine());
        command.setExpandAtFiles(false);
        command.setExecutionExceptionHandler(
                (exception, line, parse) -> {
                    if (exception instanceof HostRefusal refusal) {
                        // Keep the predecessor's diagnostic contract for the ported verbs.
                        final String message =
                                java.util.Objects.requireNonNull(
                                        refusal.getMessage(),
                                        "HostRefusal always has a safe diagnostic");
                        line.getErr()
                                .println(
                                        message.startsWith("porelo-host:")
                                                ? message
                                                : "porelo-host-person: " + message);
                        return refusal.exitCode();
                    }
                    line.getErr()
                            .println("porelo-host-person: cannot read or change the host's files");
                    return 73;
                });
        command.setColorScheme(CommandLine.Help.defaultColorScheme(Ansi.OFF));
        command.getCommandSpec().usageMessage().autoWidth(false).width(80);
        return command;
    }

    /** Refuses an invocation without a verb. */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return 64;
    }

    @Command(mixinStandardHelpOptions = true, exitCodeOnInvalidInput = 64)
    static final class Group implements Callable<Integer> {
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getErr());
            return 64;
        }
    }

    @Command(
            name = "status",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Show root's last applied organization and host state.")
    static final class OrgStatus implements Callable<Integer> {
        private final PoreloHost host;

        @CommandLine.Option(
                names = "--json",
                description = "Print the complete host status as JSON.")
        private boolean json;

        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        OrgStatus(PoreloHost host) {
            this.host = host;
        }

        @Override
        public Integer call() throws IOException {
            if (host.environment.containsKey("SUDO_USER")) {
                throw new HostRefusal(65, "org status is root's alone and never runs through sudo");
            }
            final var conf = host.files.path("/etc/porelo-host/forge.conf");
            host.files.check(conf, 69, true, false, -1);
            try (var lock = HostConfigurationLock.acquire(conf, true)) {
                final var status = new HostOrganization(host.files).status();
                if (json) {
                    spec.commandLine().getOut().println(HostJson.write(status));
                } else {
                    spec.commandLine()
                            .getOut()
                            .println(
                                    status.get("host")
                                            + ": "
                                            + (status.get("organization") == null
                                                    ? "no organization"
                                                    : status.get("organization"))
                                            + "; "
                                            + HostJson.array(status.get("persons")).size()
                                            + " persons; "
                                            + HostJson.array(status.get("projects")).size()
                                            + " projects");
                }
            }
            return 0;
        }
    }

    @Command(
            name = "show",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Show one of the sudo person's task certificate records as JSON.")
    static final class TaskShow implements Callable<Integer> {
        private final PoreloHost host;

        @CommandLine.Parameters(index = "0", paramLabel = "<fingerprint>")
        private String fingerprint;

        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        TaskShow(PoreloHost host) {
            this.host = host;
        }

        @Override
        public Integer call() throws IOException {
            HostTasks.checkFingerprint(fingerprint);
            final HostPerson person = host.person.apply(host.environment);
            final var conf = host.files.path("/etc/porelo-host/forge.conf");
            host.files.check(conf, 69, true, false, -1);
            try (var lock = HostConfigurationLock.acquire(conf, false)) {
                spec.commandLine()
                        .getOut()
                        .println(
                                HostJson.write(
                                        new HostTasks(host.files).show(person, fingerprint)));
            }
            return 0;
        }
    }
}
