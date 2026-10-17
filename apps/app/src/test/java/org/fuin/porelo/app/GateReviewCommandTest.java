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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link GateReviewCommand}, with real git: what a review shows of work that waits at the
 * gate.
 */
class GateReviewCommandTest {

    private final CommandRunner runner = new ProcessCommandRunner();

    @Test
    void aReviewWithoutABaseShowsEveryCommitOfTheWorkOnARepositoryWhoseBranchIsNotMain(
            @TempDir Path dir) throws Exception {

        // Without --against it compared with 'main', which a repository on 'trunk' does not have:
        // the patch was the
        // last commit's alone, and a person approved commits the review never showed.
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
        git(dir, "init", "-q", "--bare", "-b", "trunk", origin.toString());
        final Path checkout = dir.resolve("app");
        git(dir, "clone", "-q", origin.toString(), checkout.toString());
        identity(checkout);
        Files.writeString(checkout.resolve("README.md"), "app\n");
        git(checkout, "add", "README.md");
        git(checkout, "commit", "-q", "-m", "start");
        git(checkout, "push", "-q", "origin", "trunk");
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
        final Path work = dir.resolve("work");
        git(dir, "clone", "-q", checkout.toString(), work.toString());
        identity(work);
        Files.writeString(work.resolve("first.txt"), "first\n");
        git(work, "add", ".");
        git(work, "commit", "-q", "-m", "the first change");
        Files.writeString(work.resolve("second.txt"), "second\n");
        git(work, "add", ".");
        git(work, "commit", "-q", "-m", "the second change");
        git(work, "push", "-q", gate.mirror().toString(), "HEAD:" + GitGate.INCOMING + "writer");

        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final picocli.CommandLine cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final int code =
                cli.execute(
                        "gate",
                        "review",
                        "-p",
                        choice.project(),
                        "--repository",
                        choice.repository(),
                        "writer");

        assertThat(code).as(err.toString()).isZero();
        assertThat(out.toString())
                .as("both commits' changes are in the patch")
                .contains("+first")
                .contains("+second");
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
