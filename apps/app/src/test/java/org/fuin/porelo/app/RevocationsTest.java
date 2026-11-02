package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Harm guarded: a damaged revocation list read as one that revokes nothing. */
class RevocationsTest {

    private final CommandRunner runner = new ProcessCommandRunner();

    @ParameterizedTest
    @ValueSource(strings = {"", "corrupt\n"})
    void a_damaged_list_refuses(final String content, @TempDir final Path dir) throws IOException {
        // OpenSSH's text revocation fallback treats an empty file as revoking nothing.
        final Path krl = dir.resolve("revoked.krl");
        Files.writeString(krl, content);

        assertThatThrownBy(() -> Revocations.requireRevocations(runner, krl))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("revocation list");
    }

    @Test
    void a_missing_list_that_is_named_refuses(@TempDir final Path dir) {
        assertThatThrownBy(() -> Revocations.requireRevocations(runner, dir.resolve("none.krl")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("revocation list");
    }

    @Test
    void none_named_is_nothing_to_check() {
        assertThatCode(() -> Revocations.requireRevocations(runner, null))
                .doesNotThrowAnyException();
    }

    @Test
    void a_list_ssh_keygen_made_is_taken(@TempDir final Path dir) {
        final Path krl = dir.resolve("revoked.krl");
        runner.runOrFail(Command.of("ssh-keygen", "-q", "-k", "-f", krl.toString()));

        assertThatCode(() -> Revocations.requireRevocations(runner, krl))
                .doesNotThrowAnyException();
    }
}
