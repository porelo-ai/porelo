package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.build.api.InstalledBuildReader;
import org.fuin.porelo.core.project.Builds;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.core.project.SecurityClass;
import org.jspecify.annotations.Nullable;

/**
 * Starts the helper that tells an {@code online} task what the build of its push did, when its
 * project names a forge under {@code builds}.
 */
final class BuildsWiring {

    /** The helper's name: its pid file, its log and its entry in the resume record. */
    static final String HELPER = TaskBuilds.HELPER;

    private final PoreloContext context;

    private final CredentialWiring.Recorder recorder;

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param recorder Where a started helper is written down, so a resumed task starts it again.
     */
    BuildsWiring(final PoreloContext context, final CredentialWiring.Recorder recorder) {
        this.context = context;
        this.recorder = recorder;
    }

    /**
     * Returns the helper's command line: nothing secret, and nothing a resumed run has to change.
     *
     * @param context The machine.
     * @param container The task.
     * @param branch The branch it pushes to.
     * @param upstream The repository it pushes to.
     * @param builds What the project names.
     * @return Command and arguments.
     */
    static List<String> command(
            final PoreloContext context,
            final String container,
            final String branch,
            final String upstream,
            final Builds builds) {
        final Path state = context.paths().tasks().containerState(container);
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                PoreloBinary.path(),
                                "task",
                                "watch-builds",
                                container,
                                "--branch",
                                branch,
                                "--upstream",
                                upstream,
                                "--forge",
                                builds.forge(),
                                "--credential",
                                builds.credential(),
                                "--logs",
                                builds.logs(),
                                "--pid-file",
                                state.resolve(HELPER + ".pid").toString()));
        if (!builds.api().isEmpty()) {
            command.add("--api");
            command.add(builds.api());
        }
        return List.copyOf(command);
    }

    /**
     * Starts the helper, once the container runs: it hands files into it.
     *
     * @param project The project.
     * @param repository The repository the task works on.
     * @param container The task.
     * @param branch The branch an online task's gate passes its push on to at the upstream, as its
     *     start settled it, the one the forge builds; {@code null} for a task without a gate.
     * @param out Where progress goes.
     * @param err Where a reason it was not started goes.
     */
    void start(
            final Project project,
            final Repository repository,
            final String container,
            final @Nullable String branch,
            final PrintWriter out,
            final PrintWriter err) {
        final Builds builds = project.builds();
        if (builds == null || project.securityClass() != SecurityClass.ONLINE) {
            return;
        }
        final String upstream =
                repository.upstream() != null ? repository.upstream() : project.upstream();
        if (upstream == null || branch == null) {
            err.println(
                    "porelo: the task has no upstream branch, so it is not told what its builds did");
            err.flush();
            return;
        }
        try {
            InstalledBuildReader.find(
                    builds.forge(),
                    List.of(
                            context.paths().xdg().data().resolve("builds"),
                            PoreloPaths.packaged(InstalledBuildReader.PACKAGED)));
        } catch (RuntimeException ex) {
            // The task runs either way: the builds are what it is told, not what it needs to work.
            err.println(
                    "porelo: " + ex.getMessage() + " This task is not told what its builds did.");
            err.flush();
            said(container, builds.forge(), String.valueOf(ex.getMessage()));
            return;
        }
        final Path state = context.paths().tasks().containerState(container);
        final List<String> command = command(context, container, branch, upstream, builds);
        try {
            new ProcessBuilder(
                            org.fuin.porelo.core.process.Scope.around(
                                    "porelo " + container + " " + HELPER, command))
                    .redirectErrorStream(true)
                    .redirectOutput(state.resolve(HELPER + ".log").toFile())
                    .start();
            recorder.record(HELPER, command, Map.of(), TaskHelpers.AFTER);
            said(container, builds.forge(), "");
            out.println(
                    "builds         "
                            + builds.forge()
                            + ", read with '"
                            + builds.credential()
                            + "'; each verdict arrives in /porelo/files");
            // Said at the start, since the person who approves it must not be its author.
            out.println(
                    "pull request   opened from "
                            + branch
                            + " at the task's first push, as the forge account of '"
                            + builds.credential()
                            + "'");
        } catch (java.io.IOException ex) {
            err.println(
                    "porelo: the builds helper did not start ("
                            + ex.getMessage()
                            + "); the task is not told"
                            + " what its builds did");
            said(
                    container,
                    builds.forge(),
                    "the helper that follows them did not start: " + ex.getMessage());
        }
        out.flush();
        err.flush();
    }

    /**
     * Writes down for the task's view which reader follows its builds; a failure here only costs
     * that view.
     */
    private void said(final String container, final String forge, final String problem) {
        try {
            new TaskBuilds(context.paths().tasks()).reader(container, forge, problem);
        } catch (java.io.IOException ex) {
            // The helper still works; a person sees nothing about builds until it writes the view
            // itself.
        }
    }
}
