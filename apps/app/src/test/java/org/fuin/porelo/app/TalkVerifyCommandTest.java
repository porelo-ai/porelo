package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Test for {@link TalkVerifyCommand}: whether a mailbox holds together, as a person asks. */
class TalkVerifyCommandTest {

    private static final String TASK = "porelo-p-t";

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    @Test
    void aMessageFromAnotherTaskOfThisMachineIsIntactAsItWasDelivered(@TempDir final Path dir)
            throws IOException {
        // A task's message to its sibling is signed with this machine's own key, which a pass
        // believes; the check believed only the operator's allowed_signers and called it broken.
        final PoreloContext context = context(dir);
        final SigningKey own =
                HostKey.loadOrCreate(context.paths().messaging().messageKey(), "porelo@here");
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(TASK));
        mailbox.create();
        final String json = "{\"messageId\":\"m-1\"}";
        Files.writeString(mailbox.inbound().resolve("m-1.json"), json);
        Files.writeString(
                mailbox.inbound().resolve("m-1.json.sig"),
                SshSignature.sign(
                        own, json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));
        new MessageRecord(mailbox).append(MessageRecord.DELIVERED, "m-1.json", "m-1", "");
        assertThat(
                        new MessageDelivery()
                                .deliver(
                                        mailbox,
                                        List.of(
                                                new MessageDelivery.Peer(
                                                        own.comment(), List.of(own.keyBlob()))))
                                .delivered())
                .as("delivered as a pass delivers it")
                .hasSize(1);

        final int code = execute(context);

        assertThat(err.toString()).as("nothing named broken").isEmpty();
        assertThat(code).isZero();
        assertThat(out.toString()).contains("1 signature(s) checked").contains("intact");
    }

    private int execute(final PoreloContext context) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute("talk", "verify", TASK);
    }

    private static PoreloContext context(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }
}
