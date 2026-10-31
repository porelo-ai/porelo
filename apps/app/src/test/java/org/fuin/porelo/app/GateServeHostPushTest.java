package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link GateServeCommand}'s {@code --host-push}: which repository of a host's forge a
 * task's branch is pushed to, with the task's own token.
 */
class GateServeHostPushTest {

    private static final String REFUSED =
            "porelo: --host-push takes <project>/<repo>, with --pass-on and without --branch";

    private final CommandRunner runner = new ProcessCommandRunner();

    @TempDir Path dir;

    private PoreloContext context;

    private String project;

    @BeforeEach
    void aProjectFromACheckout() {
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
        context = new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        final Path origin = dir.resolve("origin.git");
        git(dir, "init", "-q", "--bare", "-b", "main", origin.toString());
        final Path checkout = dir.resolve("app");
        git(dir, "clone", "-q", origin.toString(), checkout.toString());
        git(checkout, "config", "user.email", "t@example.com");
        git(checkout, "config", "user.name", "T");
        git(checkout, "commit", "-q", "--allow-empty", "-m", "start");
        git(checkout, "push", "-q", "origin", "main");
        project = WorkOrigin.fromCheckout(context, checkout).project();
    }

    @Test
    void aHostPushIsRefusedUnlessItNamesOneRepositoryOfAPassedOnTasksOwnBranch() {

        // The value names the repository of the host's forge pushed to, with the task's token: a
        // path climbing out or a second word would push somewhere nobody named, and a pass-on told
        // another branch would push one the host does not.
        final List<List<String>> refused =
                List.of(
                        List.of("--host-push", "demo/repo"),
                        List.of("--host-push", "demo/repo", "--pass-on", "--branch", "fix"),
                        List.of("--host-push", "demo", "--pass-on"),
                        List.of("--host-push", "demo/a/b", "--pass-on"),
                        List.of("--host-push", "../x/y", "--pass-on"),
                        List.of("--host-push", "demo/re po", "--pass-on"),
                        List.of("--host-push", "demo/", "--pass-on"),
                        List.of("--host-push", "/repo", "--pass-on"),
                        List.of("--host-push", "../x", "--pass-on"),
                        List.of("--host-push", "x/..", "--pass-on"),
                        List.of("--host-push", "demo/.git", "--pass-on"));
        for (final List<String> each : refused) {
            final Run run = serve(each);

            assertThat(run.code()).as(each + ": " + run.err()).isEqualTo(2);
            assertThat(run.err()).as(each.toString()).contains(REFUSED);
            assertThat(run.out()).as("nothing served for " + each).doesNotContain("mirror");
        }
    }

    @Test
    void aHostPushOfOneRepositoryWithItsPassOnIsServed() {
        final Run run = serve(List.of("--host-push", "demo/repo", "--pass-on"));

        assertThat(run.err()).doesNotContain(REFUSED);
        assertThat(run.code()).as(run.err()).isZero();
        assertThat(run.out()).contains("passing refs/porelo/incoming/fix on as porelo/fix");
    }

    private record Run(int code, String out, String err) {}

    /** Serves the task {@code fix}'s gate for a second, with these arguments as well. */
    private Run serve(final List<String> more) {
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "gate",
                                "serve",
                                "-p",
                                project,
                                "--port",
                                "0",
                                "--seconds",
                                "1",
                                "--ref",
                                "refs/porelo/incoming/fix"));
        arguments.addAll(more);
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final picocli.CommandLine cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        return new Run(
                cli.execute(arguments.toArray(new String[0])), out.toString(), err.toString());
    }

    private void git(final Path in, final String... arguments) {
        final List<String> command = new ArrayList<>(List.of("git", "-C", in.toString()));
        command.addAll(List.of(arguments));
        final CommandResult result = runner.run(Command.of(command));
        assertThat(result.successful())
                .as(String.join(" ", command) + ": " + result.standardError())
                .isTrue();
    }
}
