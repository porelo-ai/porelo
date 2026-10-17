package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for what {@code talk release} prints: a name the agent chose, never its terminal controls.
 */
class TalkReleaseOutputTest {

    @Test
    void aFileNameTheAgentChoseCannotClearThePersonsScreen(@TempDir Path dir) throws Exception {

        // The name of a held message is the agent's; printed as it came, ESC[2J cleared the screen
        // a person was deciding on.
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                "XDG_DATA_HOME".equals(name)
                                        ? dir.resolve("data").toString()
                                        : null,
                        dir);
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox("porelo-p-t"));
        mailbox.create();
        final String name = "\u001b[2Jm-1.json";
        Files.writeString(
                mailbox.hold().resolve(name),
                "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"x\"}}");

        final StringWriter out = new StringWriter();
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(out));
        cmd.execute("talk", "release", "porelo-p-t", "m-1");

        assertThat(out.toString()).contains("m-1.json").doesNotContain("\u001b");
    }
}
