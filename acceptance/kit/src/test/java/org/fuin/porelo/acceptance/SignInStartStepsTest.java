package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the real shell interpretation of the acceptance login poll without a machine. */
class SignInStartStepsTest {
    @Test
    void thePollLooksUpTheActualLoginName(@TempDir Path dir) throws Exception {
        // Quoting the name as data would poll another container forever instead of completing
        // sign-in.
        final Path called = dir.resolve("called");
        final Path podman = dir.resolve("podman");
        Files.writeString(
                podman,
                "#!/bin/sh\nprintf '%s' \"$2\" > \"$B534_CALL\"\necho 'stub: waiting for sign-in'\n");
        podman.toFile().setExecutable(true);
        final String login = "porelo-login-1000000000001";
        final ProcessBuilder command =
                new ProcessBuilder("sh", "-c", SignInStartSteps.awaitingSignIn(login));
        command.environment().put("PATH", dir + ":" + System.getenv("PATH"));
        command.environment().put("B534_CALL", called.toString());
        final Process process = command.start();
        try {
            assertThat(process.waitFor(3, TimeUnit.SECONDS))
                    .as("the fixture poll is bounded")
                    .isTrue();
            assertThat(process.exitValue())
                    .as(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .isZero();
            assertThat(called)
                    .as("the poll must address the actual container, without literal quotes")
                    .hasContent(login);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
