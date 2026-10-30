package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Tests that an unreachable sign-in is refused without starting a different credential path. */
class VaultLoginCommandTest {

    @Test
    void rendersTheSshRefusalWithoutOfferingToStoreAKey(@TempDir Path dir) {
        final VaultLoginCommand command = new VaultLoginCommand();
        command.setContext(
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> {
                            throw new AssertionError("no credential command may run");
                        }));
        command.login =
                (context, agent, dryRun, force, printer) ->
                        new AgentLogin.Result(
                                AgentLogin.Outcome.LOOPBACK_OVER_SSH,
                                "",
                                "",
                                0,
                                "'example' needs a local browser redirect");
        command.asker =
                question -> {
                    throw new AssertionError("no alternative sign-in may be offered");
                };
        command.importer =
                agent -> {
                    throw new AssertionError("no credential may be imported");
                };
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final CommandLine cli = new CommandLine(command);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));

        final int code = cli.execute("example");

        assertThat(code).as("a sign-in that cannot reach the person is refused").isEqualTo(69);
        assertThat(err.toString())
                .as("the agent's refusal reaches the person")
                .contains("example", "local browser redirect");
        assertThat(out.toString() + err)
                .as("the refusal does not suggest storing a key")
                .doesNotContain("vault put", "Store its key");
    }
}
