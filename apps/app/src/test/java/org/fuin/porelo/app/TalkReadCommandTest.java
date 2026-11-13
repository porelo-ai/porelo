package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Test for what {@link TalkReadCommand} lets reach a terminal. */
class TalkReadCommandTest {

    @Test
    void neverShowsAnUnsignedMessageAsSimplyFromAPerson(@TempDir final Path dir)
            throws IOException {
        // A keyless task claimed a person wrote it; the role is its word, the mark this host's.
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
        Files.writeString(
                mailbox.hold().resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hi\"}]}");
        new MessageRecord(mailbox)
                .append(
                        MessageRecord.HELD,
                        "m-1.json",
                        "",
                        "",
                        "it was not sent: no key of its own",
                        MessageRecord.OUT,
                        TrustMark.UNSIGNED);
        final StringWriter out = new StringWriter();
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));

        assertThat(cmd.execute("talk", "read", "porelo-p-t", "m-1")).isZero();

        assertThat(out.toString())
                .contains("from      a person, unsigned" + System.lineSeparator())
                .contains("mark      unsigned" + System.lineSeparator());
    }

    @Test
    void writesAControlSequenceOutInsteadOfLettingItAct() {
        // A message built to exercise it: a title change, a cursor move and a screen clear.
        assertThat(TalkReadCommand.shown("hi\u001b]0;owned\u0007\u001b[2J\u001b[H"))
                .isEqualTo("hi\\x1b]0;owned\\x07\\x1b[2J\\x1b[H");
    }

    @Test
    void writesOutTheSingleByteIntroducerAndDelete() {
        assertThat(TalkReadCommand.shown("a\u009b31mb\u007f")).isEqualTo("a\\x9b31mb\\x7f");
    }

    @Test
    void leavesTextTabsAndOtherScriptsAsTheyAre() {
        assertThat(TalkReadCommand.shown("Can you\treview it? – Grüße, 日本"))
                .isEqualTo("Can you\treview it? – Grüße, 日本");
    }
}
