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
        return run(terminal, "the-secret\n");
    }

    private int run(final boolean terminal, final String stored) throws Exception {
        final Path file = Files.writeString(dir.resolve("token"), stored);
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

    @Test
    void aValueOfSeveralLinesIsNotHandedToGitWhichWouldEchoTheSecondOne() throws Exception {
        assertThat(run(false, "the-secret\nsecond-half\n")).isZero();

        assertThat(out.toString())
                .as("git warns 'invalid credential line: <line>', the line in the open")
                .isEmpty();
        assertThat(err.toString()).contains("line break").doesNotContain("second-half");
    }

    @Test
    void onlyAPipeASocketOrAFileGetsTheAnswer() throws Exception {
        final Path file = Files.writeString(dir.resolve("answer"), "");

        assertThat(VaultCredentialCommand.mayAnswer("pipe:[1234]")).isTrue();
        assertThat(VaultCredentialCommand.mayAnswer("socket:[1234]")).isTrue();
        assertThat(VaultCredentialCommand.mayAnswer(file.toString())).isTrue();
        assertThat(VaultCredentialCommand.mayAnswer("/dev/null")).isTrue();

        assertThat(VaultCredentialCommand.mayAnswer("/dev/pts/3")).isFalse();
        assertThat(VaultCredentialCommand.mayAnswer("/dev/tty1")).isFalse();
        assertThat(VaultCredentialCommand.mayAnswer("/dev/hvc0")).isFalse();
        assertThat(VaultCredentialCommand.mayAnswer("/dev/console")).isFalse();
        assertThat(VaultCredentialCommand.mayAnswer(null)).as("nothing says where").isFalse();
    }
}
