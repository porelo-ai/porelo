package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.fuin.porelo.app.CommitVerifyCommand;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.StandardInput;
import org.fuin.porelo.app.VaultPrompt;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The verifier's standard error as porelo-host passes it on: a pusher whose push brings more
 * refused commits than are named learns how many more there are.
 */
class RefusedCommitsReachThePusherTest {
    @TempDir Path directory;

    @Test
    void thePusherSeesTheNamedRefusalsAndHowManyMoreAreRefused() throws Exception {
        final CommandRunner runner =
                command -> {
                    final var args = command.arguments();
                    String text = "";
                    if (args.contains("--show-object-format")) text = "sha1\n";
                    else if (args.contains("rev-list")) {
                        final StringBuilder lines = new StringBuilder();
                        for (int index = 1; index <= 150; index++)
                            lines.append("%040x\n".formatted(index));
                        text = lines.toString();
                    } else if (args.contains("cat-file"))
                        text = "tree " + "c".repeat(40) + "\n\nunsigned\n";
                    return new CommandResult(command, 0, text, "");
                };
        final Path signers = directory.resolve("signers");
        Files.writeString(signers, "");
        final var context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                XdgPaths.of(name -> null, directory), directory.resolve("bin")),
                        args -> 0,
                        name -> name.equals("TMPDIR") ? directory.toString() : null,
                        false,
                        VaultPrompt.NOBODY,
                        new StandardInput(
                                new ByteArrayInputStream(
                                        ("0".repeat(40)
                                                        + " "
                                                        + "b".repeat(40)
                                                        + " refs/heads/main\n")
                                                .getBytes(StandardCharsets.US_ASCII)),
                                false));
        final var command = new CommitVerifyCommand();
        command.setContext(context);
        final var cli = new picocli.CommandLine(command);
        final var output = new StringWriter();
        final var errors = new StringWriter();
        cli.setOut(new PrintWriter(output));
        cli.setErr(new PrintWriter(errors));
        final int code =
                cli.execute(
                        "--repository",
                        directory.toString(),
                        "--signers",
                        signers.toString(),
                        "--certifiers",
                        signers.toString(),
                        "--json-lines");
        assertThat(code).as(errors.toString()).isEqualTo(65);
        final var answer =
                PushJudgement.verified(
                        new HostPrograms.Result(code, output.toString(), errors.toString()),
                        Set.of("refs/heads/main"));
        assertThat(answer.status()).isEqualTo(403);
        assertThat(answer.text().lines())
                .as("every refused commit the verifier names, then how many more")
                .hasSize(101)
                .endsWith("porelo: 50 more commits are refused");
        assertThat(answer.text().lines().limit(100))
                .allMatch(line -> line.matches("porelo: [0-9a-f]{40} .+"));
    }
}
