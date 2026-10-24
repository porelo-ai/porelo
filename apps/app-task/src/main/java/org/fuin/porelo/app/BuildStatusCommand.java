package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildReader;
import org.fuin.porelo.build.api.InstalledBuildReader;
import org.fuin.porelo.build.api.Target;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Asks a project's forge what a branch or a commit built. */
@Command(
        name = "status",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Reads a branch's or a commit's build from the project's forge.")
public class BuildStatusCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    interface Reader extends BuildReader, AutoCloseable {
        @Override
        void close();
    }

    @FunctionalInterface
    interface Factory {
        Reader open(PoreloContext context, String forge, Path socket);
    }

    enum Logs {
        none,
        failure,
        all
    }

    static final class Revision {
        @Option(
                names = "--branch",
                required = true,
                paramLabel = "<name>",
                description = "The branch at the upstream.")
        private @Nullable String branch;

        @Option(
                names = "--commit",
                required = true,
                paramLabel = "<sha>",
                description = "A full hexadecimal object name (40 or 64 characters).")
        private @Nullable String commit;
    }

    @Option(
            names = {"-p", "--project"},
            paramLabel = "<name>",
            description = "The project key; otherwise inferred from this checkout's origin.")
    private @Nullable String project;

    @Option(
            names = {"-r", "--repository"},
            paramLabel = "<name>",
            description = "The repository key in the project file.")
    private @Nullable String repository;

    @ArgGroup(exclusive = true, multiplicity = "1")
    private Revision revision = new Revision();

    @Option(
            names = "--logs",
            defaultValue = "failure",
            paramLabel = "none|failure|all",
            description = "Which jobs' logs to read. Default: ${DEFAULT-VALUE}.")
    private Logs logs = Logs.failure;

    @Option(names = "--json", description = "Writes one JSON object.")
    private boolean json;

    @Spec private CommandSpec spec;
    private PoreloContext context = PoreloContext.real();
    private final Factory factory;

    /** Creates the command with the installed build reader. */
    public BuildStatusCommand() {
        this(BuildStatusCommand::installed);
    }

    BuildStatusCommand(final Factory factory) {
        this.factory = factory;
    }

    /**
     * Sets the machine this command asks.
     *
     * @param context The machine.
     */
    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    /**
     * Asks for the build and renders its answer.
     *
     * @return The command's exit code.
     */
    @Override
    public Integer call() throws java.io.IOException {
        final var out = spec.commandLine().getOut();
        try {
            final BuildStatus answer =
                    BuildStatus.read(
                            context,
                            new BuildStatus.Request(
                                    project,
                                    repository,
                                    revision.branch,
                                    revision.commit,
                                    logs.name()),
                            factory,
                            spec.commandLine().getErr());
            out.println(json ? Json.write(answer.json()) : answer.human());
            return answer.exitCode();
        } catch (BuildStatus.Refused ex) {
            spec.commandLine()
                    .getErr()
                    .println(
                            "porelo: " + BuildStatus.terminalText(String.valueOf(ex.getMessage())));
            return ex.code();
        }
    }

    private static Reader installed(
            final PoreloContext context, final String forge, final Path socket) {
        final Path executable;
        try {
            executable =
                    InstalledBuildReader.find(
                            forge,
                            List.of(
                                    context.paths().xdg().data().resolve("builds"),
                                    PoreloPaths.packaged(InstalledBuildReader.PACKAGED)));
        } catch (org.fuin.porelo.build.api.BuildReaderException ex) {
            throw new BuildStatus.Refused(
                    BuildStatus.Reason.READER_UNAVAILABLE, String.valueOf(ex.getMessage()));
        }
        return new Installed(new InstalledBuildReader(executable, forge, socket));
    }

    private record Installed(InstalledBuildReader reader) implements Reader {
        @Override
        public String forge() {
            return reader.forge();
        }

        @Override
        public String head(Target target, String branch) {
            return reader.head(target, branch);
        }

        @Override
        public Build build(Target target, String commit, Build.Logs logs) {
            return reader.build(target, commit, logs);
        }

        @Override
        public void close() {
            reader.close();
        }
    }
}
