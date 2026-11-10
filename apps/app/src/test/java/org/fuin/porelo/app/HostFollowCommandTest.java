package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class HostFollowCommandTest {
    @TempDir Path directory;
    private final FakeCommandRunner runner = new FakeCommandRunner();
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private CommandLine cli() {
        final var context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                XdgPaths.of(name -> null, directory), directory.resolve("bin")),
                        args -> 0,
                        name -> null,
                        false,
                        prompt -> null);
        final var line = PoreloCli.commandLine(context);
        line.setOut(new PrintWriter(out, true));
        line.setErr(new PrintWriter(err, true));
        final HostFollowCommand follow =
                (HostFollowCommand)
                        line.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("follow")
                                .getCommand();
        follow.taken = path -> path;
        follow.found = name -> Path.of("/usr/bin", name);
        return line;
    }

    // A window must not turn a refused or failed privileged update into success.
    @ParameterizedTest
    @ValueSource(ints = {0, 65, 69, 73})
    void thePersonsCommandPreservesTheHostAnswerAndStatus(int code) {
        final String answer =
                code == 65
                        ? ""
                        : "{\"invocation\":\""
                                + "a".repeat(32)
                                + "\",\"completed\":true,\"exitCode\":"
                                + code
                                + "}\n";
        final String diagnostic = code == 65 ? "porelo-host: person refused\n" : "";
        runner.failing("sudo -n /usr/sbin/porelo-host org follow-now", code, answer, diagnostic);
        assertThat(cli().execute("host", "follow")).as(err.toString()).isEqualTo(code);
        assertThat(out.toString()).as("the host completion stays intact").isEqualTo(answer);
        assertThat(err.toString()).as("the refusal stays intact").isEqualTo(diagnostic);
        assertThat(runner.invocations()).as("one checked sudo invocation").hasSize(1);
        final var command = runner.invocations().getFirst();
        assertThat(command.arguments())
                .as("no person, service, token or shell argument")
                .containsExactly(
                        "/usr/bin/sudo", "-n", "/usr/sbin/porelo-host", "org", "follow-now");
        assertThat(command.inheritsEnvironment()).as("caller secrets do not reach sudo").isFalse();
        assertThat(command.environment())
                .as("the fixed login-independent environment")
                .containsOnlyKeys("PATH", "LC_ALL");
        assertThat(command.workingDirectory())
                .as("caller working directory does not reach sudo")
                .isEqualTo(Path.of("/"));
    }

    // An older host cannot be presented as having completed an organization update.
    @Test
    void anOlderHostExplainsThatFollowIsUnavailable() {
        runner.failing(
                "sudo -n /usr/sbin/porelo-host org follow-now",
                64,
                "",
                "Unmatched argument: 'follow-now'\n");
        assertThat(cli().execute("host", "follow")).as(err.toString()).isEqualTo(69);
        assertThat(err.toString())
                .as("the client names the missing host capability")
                .contains("unavailable", "update");
    }

    // A writable program must not be executed through the privileged sudo boundary.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void untrustedProgramsAreRefusedBeforeSudoRuns(boolean host) {
        final var line = cli();
        final HostFollowCommand follow =
                (HostFollowCommand)
                        line.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("follow")
                                .getCommand();
        if (host)
            follow.taken =
                    path -> {
                        throw new UncheckedIOException(new IOException("untrusted host program"));
                    };
        else
            follow.found =
                    name -> {
                        throw new UncheckedIOException(new IOException("untrusted sudo"));
                    };
        assertThat(line.execute("host", "follow"))
                .as("untrusted executable is unavailable")
                .isEqualTo(69);
        assertThat(runner.invocations()).as("no untrusted program executes").isEmpty();
    }

    // A missing sudo program cannot be reported as a completed host update.
    @Test
    void anUnavailableSudoNamesTheRuleToCheck() {
        runner.missing("/usr/bin/sudo");
        assertThat(cli().execute("host", "follow"))
                .as("execution failure is unavailable")
                .isEqualTo(69);
        assertThat(err.toString())
                .as("the person gets an available next action")
                .contains("sudo rule");
        assertThat(out.toString()).as("no completed answer was obtained").isEmpty();
    }
}
