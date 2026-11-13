package org.fuin.porelo.app;

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
import org.fuin.porelo.wire.SystemProgram;
import org.jspecify.annotations.Nullable;

/** The single execution path for transport verbs, including verbs added by later transports. */
final class TransportVerbs implements CommandRunner {

    /** Separates tool resolution from execution for callers that supply infrastructure. */
    interface Programs extends CommandRunner {
        /** Returns how this runner locates namespace programs. */
        Function<String, Path> programs();
    }

    private final CommandRunner runner;
    private final Function<String, Path> programs;
    private final List<String> reaches;
    private final boolean offline;

    TransportVerbs(final CommandRunner runner, final Function<String, Path> programs) {
        this(runner, programs, List.of(), false);
    }

    private TransportVerbs(
            final CommandRunner runner,
            final Function<String, Path> programs,
            final List<String> reaches,
            final boolean offline) {
        this.runner = runner;
        this.programs = programs;
        this.reaches = List.copyOf(reaches);
        this.offline = offline;
    }

    /** Retains a supplied executor's tool resolution, including in descriptions and tests. */
    static TransportVerbs of(final CommandRunner runner) {
        return runner instanceof TransportVerbs verbs
                ? verbs
                : new TransportVerbs(runner, programs(runner));
    }

    /** Uses explicit infrastructure when supplied, otherwise checked distribution programs. */
    static Function<String, Path> programs(final CommandRunner runner) {
        return runner instanceof Programs supplied ? supplied.programs() : SystemProgram::find;
    }

    /** Takes the network allowance from the setup contract, never transport-internal files. */
    TransportVerbs reaching(final List<String> reaches, final boolean offline) {
        return new TransportVerbs(runner, programs, reaches, offline);
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
                command.inheritsEnvironment() ? HelperEnvironment.chosen(command) : command;
        // Setup alone is not yet fenced: its first local endpoint is chosen inside the verb.
        if (command.arguments().size() > 1 && "setup".equals(command.arguments().get(1)))
            return chosen;
        return new TransportFence(runner, reaches, offline, programs).command(chosen);
    }
}
