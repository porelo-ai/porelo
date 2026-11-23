package org.fuin.porelo.app;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.SecurityClass;
import org.fuin.porelo.wire.LimitFactors;
import org.fuin.porelo.wire.SystemProgram;
import org.jspecify.annotations.Nullable;

/**
 * The single execution path for transport verbs, including verbs added by later transports.
 *
 * <p>Every verb is handed the limits line of the account that starts it, before the verb: {@code
 * porelo-message-transport-<name> --limits <line> <verb> ...}.
 */
final class TransportVerbs implements CommandRunner {

    /** Separates tool resolution from execution for callers that supply infrastructure. */
    interface Programs extends CommandRunner {
        /** Returns how this runner locates namespace programs. */
        Function<String, Path> programs();

        /** Returns how host names are resolved before a fenced launch. */
        default TransportFence.Resolver resolver() {
            return InetAddress::getAllByName;
        }

        /** Returns the host's resolver configuration, replaceable by a test fixture. */
        default Path resolvConf() {
            return Path.of("/etc/resolv.conf");
        }
    }

    private final CommandRunner runner;
    private final Function<String, Path> programs;
    private final List<String> reaches;
    private final boolean offline;
    private final TransportFence.Resolver resolver;
    private final Path resolvConf;
    private final LimitFactors limits;

    /** Hands every verb the factors of the account that starts it. */
    TransportVerbs(
            final CommandRunner runner,
            final Function<String, Path> programs,
            final LimitFactors limits) {
        this(
                runner,
                programs,
                runner instanceof Programs supplied
                        ? supplied.resolver()
                        : InetAddress::getAllByName,
                runner instanceof Programs supplied
                        ? supplied.resolvConf()
                        : Path.of("/etc/resolv.conf"),
                limits);
    }

    /** Keeps DNS lookups and the resolver configuration independent of the test machine. */
    TransportVerbs(
            final CommandRunner runner,
            final Function<String, Path> programs,
            final TransportFence.Resolver resolver,
            final Path resolvConf,
            final LimitFactors limits) {
        this(runner, programs, List.of(), false, resolver, resolvConf, limits);
    }

    private TransportVerbs(
            final CommandRunner runner,
            final Function<String, Path> programs,
            final List<String> reaches,
            final boolean offline,
            final TransportFence.Resolver resolver,
            final Path resolvConf,
            final LimitFactors limits) {
        this.runner = runner;
        this.programs = programs;
        this.reaches = List.copyOf(reaches);
        this.offline = offline;
        this.resolver = resolver;
        this.resolvConf = resolvConf;
        this.limits = limits;
    }

    /**
     * Retains a supplied executor's tool resolution, including in descriptions and tests.
     *
     * @param runner How commands are run; verbs already made keep the factors they were made with,
     *     which are their starter's.
     * @param limits The factors of the account that starts the transport.
     * @return The verbs.
     */
    static TransportVerbs of(final CommandRunner runner, final LimitFactors limits) {
        return runner instanceof TransportVerbs verbs
                ? verbs
                : new TransportVerbs(runner, programs(runner), limits);
    }

    /** Uses explicit infrastructure when supplied, otherwise checked distribution programs. */
    static Function<String, Path> programs(final CommandRunner runner) {
        return runner instanceof Programs supplied ? supplied.programs() : SystemProgram::find;
    }

    /** Returns the factors every verb is handed: those of the account that starts it. */
    LimitFactors limits() {
        return limits;
    }

    /** Takes the network allowance from the setup contract, never transport-internal files. */
    TransportVerbs reaching(final List<String> reaches, final boolean offline) {
        return new TransportVerbs(runner, programs, reaches, offline, resolver, resolvConf, limits);
    }

    /** Lifecycle verbs name their project explicitly; no container name is decoded. */
    TransportVerbs forCommand(
            final PoreloContext context,
            final String scheme,
            final List<String> arguments,
            final boolean loopbackOnly) {
        if ("setup".equals(arguments.getFirst()) || "settings".equals(arguments.getFirst()))
            return this;
        final int project = arguments.indexOf("--project");
        return project < 0
                ? account(context, scheme)
                : project(context, scheme, arguments.get(project + 1), loopbackOnly);
    }

    /** Sending uses the same project restriction as polling and lifecycle calls. */
    static boolean offline(final PoreloContext context, final String project) {
        final @Nullable Path file = ProjectSource.resolve(context, project).file();
        return file == null || ProjectReader.read(file).securityClass() == SecurityClass.OFFLINE;
    }

    /** Reads the saved conversation and the project's current security class. */
    TransportVerbs project(
            final PoreloContext context,
            final String scheme,
            final String project,
            final boolean loopbackOnly) {
        return knownProject(context, scheme, project, loopbackOnly || offline(context, project));
    }

    /** Uses a project policy the caller already read, even before that project is registered. */
    TransportVerbs knownProject(
            final PoreloContext context,
            final String scheme,
            final String project,
            final boolean loopbackOnly) {
        final TransportLifecycle.Conversation conversation =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory())
                        .conversation(scheme, project);
        return reaching(conversation == null ? List.of() : conversation.reaches(), loopbackOnly);
    }

    /** An account clear can need the endpoints of more than one saved project. */
    TransportVerbs account(final PoreloContext context, final String scheme) {
        final Path directory = context.paths().xdg().state().resolve("transport").resolve(scheme);
        if (!Files.isDirectory(directory)) return reaching(List.of(), false);
        final List<String> saved = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (final Path file :
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .toList()) {
                final String name = file.getFileName().toString();
                final TransportVerbs project =
                        project(context, scheme, name.substring(0, name.length() - 5), false);
                saved.addAll(
                        project.offline
                                ? project.reaches.stream()
                                        .filter(TaskConversations::loopback)
                                        .toList()
                                : project.reaches);
            }
        } catch (final java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Cannot read the transport's saved reaches", ex);
        }
        return reaching(saved, false);
    }

    /** Starts every ordinary verb after the namespace rules have loaded. */
    @Override
    public CommandResult run(final Command command) {
        return runner.run(command(command));
    }

    /** Keeps diagnostics streaming through the same execution path. */
    @Override
    public CommandResult run(final Command command, final Consumer<String> lines) {
        return runner.run(command(command), lines);
    }

    private Command command(final Command command) {
        final Command chosen =
                handed(command.inheritsEnvironment() ? HelperEnvironment.chosen(command) : command);
        // Setup's unfenced network is accepted: its first local endpoint is chosen inside the verb.
        if (command.arguments().size() > 1 && "setup".equals(command.arguments().get(1)))
            return chosen;
        return new TransportFence(runner, reaches, offline, programs, resolver, resolvConf)
                .command(chosen);
    }

    /** The transport, the limits line, then the verb and the rest, everything else kept. */
    private Command handed(final Command command) {
        final List<String> arguments = new ArrayList<>();
        arguments.add(command.arguments().getFirst());
        arguments.add(LimitFactors.OPTION);
        arguments.add(limits.line());
        arguments.addAll(command.arguments().subList(1, command.arguments().size()));
        final Command handed =
                new Command(
                        arguments,
                        command.workingDirectory(),
                        command.environment(),
                        command.input(),
                        command.inheritsEnvironment());
        final byte @Nullable [] binaryInput = command.binaryInput();
        final Command withInput =
                binaryInput == null ? handed : handed.withBinaryInput(binaryInput);
        final java.time.@Nullable Duration timeout = command.timeout();
        return timeout == null ? withInput : withInput.withTimeout(timeout);
    }
}
