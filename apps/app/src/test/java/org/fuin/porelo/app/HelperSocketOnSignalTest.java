package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a helper serving a unix socket removes it when a signal ends it, not only when it
 * returns: a dead socket left behind reads as a live one to whoever looks.
 */
class HelperSocketOnSignalTest {

    @TempDir Path dir;

    @Test
    void anAgentEndedBySigtermLeavesNoSocket() throws Exception {
        final Path socket = dir.resolve("run").resolve("agent.sock");
        final Process agent =
                start(
                        "vault",
                        "agent",
                        "--ephemeral",
                        "--socket",
                        socket.toString(),
                        "--seconds",
                        "60");
        try {
            final BufferedReader said =
                    new BufferedReader(
                            new InputStreamReader(agent.getInputStream(), StandardCharsets.UTF_8));
            String line = said.readLine();
            while (line != null && !line.startsWith("agent ")) {
                line = said.readLine();
            }
            assertThat(line).as("the agent says it serves").isNotNull();
            assertThat(socket).exists();

            agent.destroy();

            assertThat(agent.waitFor(30, TimeUnit.SECONDS)).as("it ends on SIGTERM").isTrue();
            assertThat(socket).as("no dead socket is left").doesNotExist();
        } finally {
            agent.destroyForcibly();
        }
    }

    private Process start(final String... arguments) throws Exception {
        final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final List<String> command =
                new java.util.ArrayList<>(
                        List.of(
                                java,
                                "-cp",
                                System.getProperty("java.class.path"),
                                PoreloCli.class.getName()));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("XDG_CONFIG_HOME", dir.resolve("config").toString());
        builder.environment().put("XDG_DATA_HOME", dir.resolve("data").toString());
        builder.environment().put("XDG_STATE_HOME", dir.resolve("state").toString());
        builder.environment()
                .put("XDG_RUNTIME_DIR", Files.createDirectories(dir.resolve("run")).toString());
        return builder.start();
    }
}
