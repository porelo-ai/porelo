package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@code porelo task list} once and {@code --watch} against a daemon. Harm: a watching
 * view that shows a machine differently from the listing - another project's task, a task left out,
 * a row in another format - which an operator reads to decide what to stop; and a view that leaves
 * nothing on screen where the daemon is missing.
 */
class TaskListWatchTest {

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** Two projects' tasks, as the runtime lists them and as their starts recorded them. */
    private void twoProjects() throws IOException {
        runner.answering(
                "ps",
                LegacyTaskFixture.listed(
                        "porelo-uc-shell-1\tUp 4 minutes\t1700000000\t0\tuc\tguarded\tuc\t\n"
                                + "porelo-ab-build\tExited (0) 2 minutes ago\t1700000000\t1700000100\tab\tguarded\tab\t\n",
                        "c0ffeec0ffee"));
        recorded("porelo-uc-shell-1", "uc");
        recorded("porelo-ab-build", "ab");
    }

    private void recorded(final String container, final String project) throws IOException {
        final Path state = dir.resolve("run/porelo").resolve(container);
        Files.createDirectories(state);
        Sidecar.recordId(state, "c0ffeec0ffee");
        new Sidecar(
                        Sidecar.VERSION,
                        project,
                        "guarded",
                        state.resolve("r.nft").toString(),
                        state.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        state.toString())
                .writeTo(state.resolve("sidecar.json"));
    }

    private int execute(final PoreloContext context, final String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    /**
     * A daemon that sends the machine's list once, as its own {@code Watch} maps it, and ends the
     * stream; its {@code Prompts} sends nothing while the view lasts.
     */
    private VarlinkServer daemon(final PoreloContext context) throws IOException {
        final Path socket = context.paths().daemonSocket();
        Files.createDirectories(socket.getParent());
        final TaskInventory inventory = new TaskInventory(context);
        final VarlinkServer daemon =
                new VarlinkServer(socket, TasksContract.INTERFACE)
                        .method(
                                "Watch",
                                (parameters, replies) -> {
                                    final List<TaskInventory.Task> found = inventory.tasks();
                                    replies.last(
                                            Map.of(
                                                    "tasks",
                                                    inventory.rows(found),
                                                    "unrecognized",
                                                    inventory.unrecognized(found)));
                                })
                        .method(
                                "Prompts",
                                (parameters, replies) -> {
                                    while (replies.open()) {
                                        try {
                                            Thread.sleep(20);
                                        } catch (InterruptedException ex) {
                                            Thread.currentThread().interrupt();
                                            return;
                                        }
                                    }
                                });
        Thread.ofVirtual().start(daemon);
        return daemon;
    }

    /** The table's lines of a watching view's output, with the marker column taken off. */
    private static List<String> table(final String watched, final int rows) {
        return watched.lines()
                .limit(rows)
                .map(line -> line.substring(TaskWatch.UNMARKED.length()))
                .toList();
    }

    @Test
    void theWatchingFormShowsEveryRowAsTheListingPrintsIt() throws IOException {
        final PoreloContext context = context();
        twoProjects();

        assertThat(execute(context, "task", "list")).as(err.toString()).isZero();
        final List<String> once = out.toString().lines().toList();
        try (VarlinkServer daemon = daemon(context)) {
            assertThat(execute(context, "task", "list", "--watch")).as(err.toString()).isZero();
        }

        assertThat(once).as("a header and two rows").hasSizeGreaterThanOrEqualTo(3);
        assertThat(table(out.toString(), 3))
                .as("the same header and rows, from the daemon's rows instead of the inventory")
                .containsExactlyElementsOf(once.subList(0, 3));
    }

    @Test
    void aProjectShowsOnlyItsTasksInBothFormsAndNoProjectShowsEveryTask() throws IOException {
        final PoreloContext context = context();
        twoProjects();

        assertThat(execute(context, "task", "list", "--project", "uc")).isZero();
        final String once = out.toString();
        final String watched;
        final String everything;
        try (VarlinkServer daemon = daemon(context)) {
            assertThat(execute(context, "task", "list", "--watch", "--project", "uc")).isZero();
            watched = out.toString();
            assertThat(execute(context, "task", "list", "--watch")).isZero();
            everything = out.toString();
        }

        assertThat(once).as("listed once").contains("shell-1 ").doesNotContain("build ");
        assertThat(watched).as("watched").contains("shell-1 ").doesNotContain("build ");
        assertThat(everything)
                .as("without a project the whole machine")
                .contains("shell-1 ")
                .contains("build ");
    }

    @Test
    void withoutADaemonItSaysSoAndListsOnce() throws IOException {
        final PoreloContext context = context();
        twoProjects();

        final int code = execute(context, "task", "list", "--watch");

        assertThat(code).as("what was asked for was not done").isEqualTo(TaskListCommand.NO_DAEMON);
        assertThat(err.toString())
                .as("names what could not be done and why")
                .contains("porelod does not answer")
                .contains("cannot be watched");
        assertThat(out.toString())
                .as("the listing once instead")
                .contains("shell-1 ")
                .contains("build ");
    }
}
