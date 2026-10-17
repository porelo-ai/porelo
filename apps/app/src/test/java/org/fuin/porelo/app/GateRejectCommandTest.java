package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.UpstreamDistance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link GateRejectCommand} and {@link GatePendingCommand}, with real git: work that a
 * person pushed upstream by hand is not discarded and recorded as discarded unless they say so.
 */
class GateRejectCommandTest {

    private final CommandRunner runner = new ProcessCommandRunner();

    @Test
    void workAlreadyUpstreamIsMarkedPendingAndRejectedOnlyWithForce(@TempDir Path dir)
            throws Exception {

        // The mark was measured and recorded and never read: 'gate pending' showed the push as
        // waiting, and 'gate
        // reject' discarded work the upstream already had, recording the opposite of what happened.
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
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        final Path origin = dir.resolve("origin.git");
        git(dir, "init", "-q", "--bare", "-b", "main", origin.toString());
        final Path checkout = dir.resolve("app");
        git(dir, "clone", "-q", origin.toString(), checkout.toString());
        identity(checkout);
        Files.writeString(checkout.resolve("README.md"), "app\n");
        git(checkout, "add", "README.md");
        git(checkout, "commit", "-q", "-m", "start");
        git(checkout, "push", "-q", "origin", "main");
        final WorkOrigin.Choice choice = WorkOrigin.fromCheckout(context, checkout);
        final Project project = GateSupport.byName(context, choice.project());
        final GitGate gate =
                GateSupport.gate(
                        context,
                        project,
                        project.repository(choice.repository()),
                        null,
                        checkout.toString());
        gate.initialize();
        git(checkout, "commit", "-q", "--allow-empty", "-m", "the agent's change");
        git(
                checkout,
                "push",
                "-q",
                gate.mirror().toString(),
                "HEAD:" + GitGate.INCOMING + "writer");
        // Pushed by hand, past the gate, and measured as the watch measures it.
        git(checkout, "push", "-q", "origin", "HEAD:main");
        new UpstreamRecords(context.paths().projects().upstreamRecords())
                .put(
                        UpstreamRecords.key(project.name(), choice.repository()),
                        new UpstreamDistance.Distance(
                                0,
                                java.time.Instant.now(),
                                UpstreamDistance.Reason.MEASURED,
                                null,
                                List.of("writer")));

        final Run pending =
                run(
                        context,
                        "gate",
                        "pending",
                        "-p",
                        choice.project(),
                        "--repository",
                        choice.repository());
        assertThat(pending.out())
                .as("pending says it is upstream already")
                .contains("already upstream");

        final Run refused =
                run(
                        context,
                        "gate",
                        "reject",
                        "-p",
                        choice.project(),
                        "--repository",
                        choice.repository(),
                        "writer");
        assertThat(refused.code()).as(refused.out()).isNotZero();
        assertThat(refused.err()).contains("--force");
        assertThat(git(gate.mirror(), "for-each-ref", GitGate.INCOMING))
                .as("the work is still at the gate")
                .contains("writer");

        final Run forced =
                run(
                        context,
                        "gate",
                        "reject",
                        "-p",
                        choice.project(),
                        "--repository",
                        choice.repository(),
                        "--force",
                        "writer");
        assertThat(forced.code()).as(forced.err()).isZero();
        assertThat(git(gate.mirror(), "for-each-ref", GitGate.INCOMING))
                .as("cleared when the person says so")
                .doesNotContain("writer");
    }

    private record Run(int code, String out, String err) {}

    private Run run(final PoreloContext context, final String... arguments) {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final picocli.CommandLine cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        return new Run(cli.execute(arguments), out.toString(), err.toString());
    }

    private void identity(final Path repository) {
        git(repository, "config", "user.email", "t@example.com");
        git(repository, "config", "user.name", "T");
    }

    private String git(final Path in, final String... arguments) {
        final List<String> command = new java.util.ArrayList<>(List.of("git", "-C", in.toString()));
        command.addAll(List.of(arguments));
        final CommandResult result = runner.run(Command.of(command));
        assertThat(result.successful())
                .as(String.join(" ", command) + ": " + result.standardError())
                .isTrue();
        return result.standardOutput().strip();
    }
}
