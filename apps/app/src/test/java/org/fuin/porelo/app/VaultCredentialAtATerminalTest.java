package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** The answer goes to git's pipe; to a person's screen it would be the secret in the open. */
class VaultCredentialAtATerminalTest {

    @TempDir Path dir;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private int run(final boolean terminal) throws Exception {
        final Path file = Files.writeString(dir.resolve("token"), "the-secret\n");
        final VaultCredentialCommand command = new VaultCredentialCommand();
        command.standardOutputIsATerminal = () -> terminal;
        final InputStream before = System.in;
        System.setIn(new ByteArrayInputStream("protocol=https\n\n".getBytes()));
        try {
            return new CommandLine(command)
                    .setOut(new PrintWriter(out))
                    .setErr(new PrintWriter(err))
                    .execute("--file", file.toString(), "get");
        } finally {
            System.setIn(before);
        }
    }

    @Test
    void aTerminalGetsNoSecretAndIsToldWhy() throws Exception {
        assertThat(run(true)).isEqualTo(2);

        assertThat(out.toString()).doesNotContain("the-secret");
        assertThat(err.toString()).contains("git").doesNotContain("the-secret");
    }

    @Test
    void gitsPipeGetsTheAnswer() throws Exception {
        assertThat(run(false)).isZero();

        assertThat(out.toString()).contains("password=the-secret");
    }
}
