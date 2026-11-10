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
    private final HostSweep sweep;
    private final HostTasks tasks;
    private final HostTokens tokens;
    private final ProjectRuntime runtime;
    private final java.time.Clock clock;
    private final PushService pushService;
    private final java.io.InputStream input;
    private final Function<Map<String, String>, HostPerson> person;

    /** Uses only the installed host tree and the sudo identity. */
    public PoreloHost() {
        this(System.getenv(), new HostFiles(), HostPerson::from);
    }

    PoreloHost(
            Map<String, String> environment,
            HostFiles files,
            Function<Map<String, String>, HostPerson> person) {
        this(environment, files, person, new HostSweep(files));
    }

    private PoreloHost(
            Map<String, String> environment,
            HostFiles files,
            Function<Map<String, String>, HostPerson> person,
            HostSweep sweep) {
        this(
                environment,
                files,
                person,
                sweep,
                new ProjectRuntime(files),
                System.in,
                java.time.Clock.systemUTC());
    }

    private PoreloHost(
            Map<String, String> environment,
            HostFiles files,
            Function<Map<String, String>, HostPerson> person,
            HostSweep sweep,
            ProjectRuntime runtime,
            java.io.InputStream input,
            java.time.Clock clock) {
        this(environment, files, person, sweep, runtime, input, clock, PoreloHost::servePush);
    }

    private PoreloHost(
            Map<String, String> environment,
            HostFiles files,
            Function<Map<String, String>, HostPerson> person,
            HostSweep sweep,
            ProjectRuntime runtime,
            java.io.InputStream input,
            java.time.Clock clock,
            PushService pushService) {
        this.sweep = sweep;
        this.runtime = runtime;
        this.clock = clock;
        this.pushService = pushService;
        this.tasks = new HostTasks(files, runtime, clock);
        this.tokens = new HostTokens(files, runtime, clock);
        this.input = input;
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
        return run(
                args,
                environment,
                uid,
                out,
                err,
                new HostFiles(),
                args.length >= 2 && args[0].equals("org") && args[1].equals("follow-now")
                        ? env -> HostPerson.from(env, java.time.Duration.ofSeconds(10))
                        : HostPerson::from);
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            Uid uid,
            PrintWriter out,
            PrintWriter err,
            HostFiles files,
            Function<Map<String, String>, HostPerson> person) {
        return run(args, environment, uid, out, err, files, person, new HostSweep(files));
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            Uid uid,
            PrintWriter out,
            PrintWriter err,
            HostFiles files,
            Function<Map<String, String>, HostPerson> person,
            HostSweep sweep) {
        return run(
                args,
                environment,
                uid,
                out,
                err,
                files,
                person,
                sweep,
                new ProjectRuntime(files),
                System.in,
                java.time.Clock.systemUTC());
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            Uid uid,
            PrintWriter out,
            PrintWriter err,
            HostFiles files,
            Function<Map<String, String>, HostPerson> person,
            HostSweep sweep,
            ProjectRuntime runtime,
            java.io.InputStream input,
            java.time.Clock clock) {
        return run(
                args,
                environment,
                uid,
                out,
                err,
                files,
                person,
                sweep,
                runtime,
                input,
                clock,
                PoreloHost::servePush);
    }

    @FunctionalInterface
    interface PushService {
        void serve(HostPush judge) throws IOException;
    }

    private static void servePush(HostPush judge) throws IOException {
        final var socket = HostSocketActivation.standardInput();
        final long peer = HostSocketActivation.peerUid();
        try (var connection = new GateConnection(socket, false, 30)) {
            connection.readDeadline(java.time.Duration.ofSeconds(30));
            judge.serve(connection.input, connection.output, peer);
        }
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            Uid uid,
            PrintWriter out,
            PrintWriter err,
            HostFiles files,
            Function<Map<String, String>, HostPerson> person,
            HostSweep sweep,
            ProjectRuntime runtime,
            java.io.InputStream input,
            java.time.Clock clock,
            PushService pushService) {
        try {
            HostEnvironment.check(environment);
            final boolean informational = args.length == 1 && answersAlone(args[0]);
            if (!informational && uid.real() != 0 && !HostForgeCommands.unprivileged(args)) {
                if (args.length > 0
                        && args[0].equals("project")
                        && !(args.length > 1 && args[1].equals("check-central"))) {
                    throw new HostRefusal(69, "porelo-host: runs only as root");
                }
                if (args.length > 0
                        && (args[0].equals("task")
                                || args[0].equals("person")
                                || args[0].equals("forge")
                                || args[0].equals("judge-push"))) {
                    throw new HostRefusal(69, "porelo-host: runs only as root");
                }
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
            return commandLine(
                            new PoreloHost(
                                    environment,
                                    files,
                                    person,
                                    sweep,
                                    runtime,
                                    input,
                                    clock,
                                    pushService))
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
                "org",
                new CommandLine(new Group())
                        .addSubcommand("status", new OrgStatus(host))
                        .addSubcommand("follow", new OrgFollow(host))
                        .addSubcommand("follow-now", new OrgFollowNow(host))
                        .addSubcommand("refresh", new OrgRefresh(host))
                        .addSubcommand("renew-pin", new OrgRenewPin(host)));
        command.addSubcommand(
                "task",
                new CommandLine(new Group())
                        .addSubcommand("show", new TaskShow(host))
                        .addSubcommand("start", new TaskChange(host, true))
                        .addSubcommand("end", new TaskChange(host, false))
                        .addSubcommand("sweep", new TaskSweep(host)));
        command.addSubcommand(
                "forge",
                HostForgeCommands.commandLine(host.environment)
                        .addSubcommand("issue-token", new ForgeToken(host, false))
                        .addSubcommand("lend-token", new ForgeToken(host, true)));
        command.addSubcommand("project", HostProject.commandLine(host.environment));
        command.addSubcommand(
                "person",
                new CommandLine(new Group())
                        .addSubcommand("purge", new PersonPurge(host))
                        .addSubcommand("restore-runtime", new PersonRestoreRuntime(host)));
        command.addSubcommand("pool", HostPoolCommands.commandLine(host.environment));
        command.addSubcommand("judge-push", new JudgePush(host));
        command.setExpandAtFiles(false);
        command.setExecutionExceptionHandler(
                (exception, line, parse) -> {
                    final var rootParse = command.getParseResult();
                    final var area = rootParse != null ? rootParse.subcommand() : null;
                    final String group = area == null ? "" : area.commandSpec().name();
                    final var verb = area == null ? null : area.subcommand();
                    final String action = verb == null ? "" : verb.commandSpec().name();
                    if (group.equals("pool")
                            || group.equals("forge")
                                    && (action.equals("gate") || action.equals("measure"))) {
                        if (exception instanceof HostRefusal refusal) {
                            final String message =
                                    java.util.Objects.requireNonNull(
                                            refusal.getMessage(),
                                            "HostRefusal always has a safe diagnostic");
                            line.getErr()
                                    .println(
                                            group.equals("forge") && action.equals("measure")
                                                    ? "porelo-host forge measure: " + message
                                                    : message);
                            return group.equals("pool") && refusal.exitCode() >= 69
                                    ? 1
                                    : refusal.exitCode();
                        }
                        line.getErr()
                                .println(
                                        group.equals("pool")
                                                ? "porelo-host pool "
                                                        + action
                                                        + ": cannot read or change the pool's files or run its programs"
                                                : "porelo-host forge "
                                                        + action
                                                        + ": connection failed");
                        return 1;
                    }
                    if (exception instanceof HostRefusal refusal) {
                        final String message =
                                java.util.Objects.requireNonNull(
                                        refusal.getMessage(),
                                        "HostRefusal always has a safe diagnostic");
                        line.getErr()
                                .println(
                                        message.startsWith("porelo-host:")
                                                ? message
                                                : "porelo-host: " + message);
                        return refusal.exitCode();
                    }
                    line.getErr().println("porelo-host: cannot read or change the host's files");
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

    private void requireSudo(String verb) {
        if (environment.getOrDefault("SUDO_USER", "").isEmpty()) {
            throw new HostRefusal(64, verb + " runs through sudo, for the person who calls it");
        }
    }

    private HostPerson sudoPerson(String verb) {
        requireSudo(verb);
        try {
            return person.apply(environment);
        } catch (HostRefusal ex) {
            if (ex.exitCode() == 77) {
                throw new HostRefusal(
                        65,
                        java.util.Objects.requireNonNull(
                                ex.getMessage(), "Identity refusals have safe diagnostics"));
            }
            throw ex;
        }
    }

    private HostPeople people(PrintWriter err) {
        return new HostPeople(new FollowContext(files, runtime.files(), runtime, clock, err));
    }

    @Command(
            name = "purge",
            exitCodeOnInvalidInput = 64,
            description = "Purge a locked person whose seven-day grace period has ended.")
    static final class PersonPurge implements Callable<Integer> {
        private final PoreloHost host;

        @CommandLine.Parameters(index = "0", paramLabel = "<login>")
        private String login;

        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        PersonPurge(PoreloHost host) {
            this.host = host;
        }

        /** Answers only after the explicit target's purge completes. */
        @Override
        public Integer call() throws IOException {
            final var answer = host.people(spec.commandLine().getErr()).purge(login);
            spec.commandLine().getOut().println(HostJson.write(answer));
            return 0;
        }
    }

    @Command(
            name = "restore-runtime",
            exitCodeOnInvalidInput = 64,
            description =
                    "Restore admitted people's runtime files and restart their user managers.")
    static final class PersonRestoreRuntime implements Callable<Integer> {
        private final PoreloHost host;
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        PersonRestoreRuntime(PoreloHost host) {
            this.host = host;
        }

        /** Restores admitted people's runtime files and managers under the shared lock. */
        @Override
        public Integer call() throws IOException {
            final var answer = host.people(spec.commandLine().getErr()).restoreRuntime();
            spec.commandLine().getOut().println(HostJson.write(answer));
            return 0;
        }
    }

    @Command(
            name = "judge-push",
            exitCodeOnInvalidInput = 64,
            description = "Judge one forge hook request on the accepted socket on stdin.")
    static final class JudgePush implements Callable<Integer> {
        private final PoreloHost host;

        JudgePush(PoreloHost host) {
            this.host = host;
        }

        @Override
        public Integer call() throws IOException {
            if (host.environment.containsKey("SUDO_USER"))
                throw new HostRefusal(65, "verify-push is the forge hook's socket unit's alone");
            host.pushService.serve(
                    new HostPush(
                            host.files,
                            host.runtime,
                            host.clock,
                            host.spec.commandLine().getErr()));
            return 0;
        }
    }

    @Command(mixinStandardHelpOptions = true, exitCodeOnInvalidInput = 64)
    static final class TaskChange implements Callable<Integer> {
        private final PoreloHost host;
        private final boolean start;

        @CommandLine.Parameters(index = "0", paramLabel = "<task>")
        private String task;

        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        TaskChange(PoreloHost host, boolean start) {
            this.host = host;
            this.start = start;
        }

        @Override
        public Integer call() throws IOException {
            HostTasks.checkName(task);
            final var conf = host.files.path("/etc/porelo-host/forge.conf");
            host.files.check(conf, 69, true, false, -1);
            try (var lock = HostConfigurationLock.acquire(conf, false)) {
                new HostRecords(host.files).read();
                final HostPerson person = host.sudoPerson(start ? "task" : "end");
                final PrintWriter out = spec.commandLine().getOut();
                if (start) {
                    out.print(host.tasks.start(person, task, host.input));
                    out.flush();
                } else {
                    out.println(HostJson.write(host.tasks.end(person, task)));
                }
            }
            return 0;
        }
    }

    @Command(mixinStandardHelpOptions = true, exitCodeOnInvalidInput = 64)
    static final class ForgeToken implements Callable<Integer> {
        private final PoreloHost host;
        private final boolean lend;
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        ForgeToken(PoreloHost host, boolean lend) {
            this.host = host;
            this.lend = lend;
        }

        @Override
        public Integer call() throws IOException {
            final var conf = host.files.path("/etc/porelo-host/forge.conf");
            host.files.check(conf, 69, true, false, -1);
            try (var lock = HostConfigurationLock.acquire(conf, false)) {
                new HostRecords(host.files).read();
                final HostPerson person = host.sudoPerson("forge-token");
                spec.commandLine().getOut().print(host.tokens.issue(person, lend));
                spec.commandLine().getOut().flush();
            }
            return 0;
        }
    }

    @Command
    abstract static class OrgChange implements Callable<Integer> {
        private final PoreloHost host;
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        OrgChange(PoreloHost host) {
            this.host = host;
        }

        @Override
        public Integer call() throws IOException {
            HostFollow.root(spec.name(), host.environment);
            final HostFollowNow service = new HostFollowNow(host.files, host.runtime);
            final var invocation =
                    spec.name().equals("follow") ? service.begin(host.environment) : null;
            int exit = 73;
            try {
                final var answer =
                        new HostFollow(host.files, spec.commandLine().getErr())
                                .run(spec.name(), host.environment, System.in);
                spec.commandLine().getOut().println(HostJson.write(answer));
                exit = 0;
                return exit;
            } catch (HostRefusal ex) {
                exit = ex.exitCode();
                throw ex;
            } finally {
                if (invocation != null) service.finish(invocation, exit);
            }
        }
    }

    @Command(
            name = "follow-now",
            exitCodeOnInvalidInput = 64,
            description =
                    "Ask the fixed root service for one follow pass and wait for its completion.")
    static final class OrgFollowNow implements Callable<Integer> {
        private final PoreloHost host;
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        OrgFollowNow(PoreloHost host) {
            this.host = host;
        }

        @Override
        public Integer call() throws IOException {
            final HostFollowNow service = new HostFollowNow(host.files, host.runtime);
            host.requireSudo("org follow-now");
            final var entry = service.capture();
            final HostPerson person = host.sudoPerson("org follow-now");
            final var completion = service.follow(person, entry);
            spec.commandLine().getOut().println(HostJson.write(completion.answer()));
            return completion.exitCode();
        }
    }

    @Command(
            name = "follow",
            exitCodeOnInvalidInput = 64,
            description = "Follow the signed organization from root's timer.")
    static final class OrgFollow extends OrgChange {
        OrgFollow(PoreloHost host) {
            super(host);
        }
    }

    @Command(
            name = "refresh",
            exitCodeOnInvalidInput = 64,
            description = "Follow immediately with a waiting migration's token on stdin.")
    static final class OrgRefresh extends OrgChange {
        OrgRefresh(PoreloHost host) {
            super(host);
        }
    }

    @Command(
            name = "renew-pin",
            exitCodeOnInvalidInput = 64,
            description = "Take renewed admin pins from root on stdin.")
    static final class OrgRenewPin extends OrgChange {
        OrgRenewPin(PoreloHost host) {
            super(host);
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
            name = "sweep",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description = "Delete ended task tokens from root's timer.")
    static final class TaskSweep implements Callable<Integer> {
        private final PoreloHost host;
        @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

        TaskSweep(PoreloHost host) {
            this.host = host;
        }

        @Override
        public Integer call() throws IOException {
            final var conf = host.files.path("/etc/porelo-host/forge.conf");
            host.files.check(conf, 69, true, false, -1);
            try (var lock = HostConfigurationLock.acquire(conf, false)) {
                spec.commandLine()
                        .getOut()
                        .println(HostJson.write(host.sweep.run(host.environment)));
            }
            return 0;
        }
    }

    @Command(
            name = "show",
            mixinStandardHelpOptions = true,
            exitCodeOnInvalidInput = 64,
            description =
                    "Show a task certificate record for root or a sudo member of its project as JSON.")
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
            final HostPerson person =
                    host.environment.containsKey("SUDO_USER")
                            ? host.person.apply(host.environment)
                            : null;
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
