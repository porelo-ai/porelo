package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link AgentCertifier}: a task's key certified through the person's own agent. */
class AgentCertifierTest {

    @TempDir Path dir;

    @Test
    void thePersonsAgentCertifiesTheTaskKeyAndNoKeyFileIsRead() throws Exception {
        run(
                "ssh-keygen",
                "-q",
                "-t",
                "ed25519",
                "-N",
                "",
                "-C",
                "person",
                "-f",
                dir.resolve("person").toString());
        final String person = keyOnly(Files.readString(dir.resolve("person.pub")));
        final Path socket = dir.resolve("agent.sock");
        final Process agent =
                new ProcessBuilder("ssh-agent", "-D", "-a", socket.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            for (int i = 0; i < 100 && !Files.exists(socket); i++) {
                Thread.sleep(20);
            }
            final ProcessBuilder add =
                    new ProcessBuilder("ssh-add", "-q", dir.resolve("person").toString());
            add.environment().put("SSH_AUTH_SOCK", socket.toString());
            assertThat(
                            add.redirectErrorStream(true)
                                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                    .start()
                                    .waitFor())
                    .isZero();
            // Gone from disk: only the agent holds it now.
            Files.delete(dir.resolve("person"));

            final TaskCertification.Issued issued =
                    TaskCertification.certify(
                            "demo",
                            "fix",
                            "alice",
                            new AgentCertifier(person, socket),
                            List.of(person),
                            Clock.systemUTC());

            assertThat(issued.certificate().signer()).isEqualTo(person);
            assertThat(issued.certificate().principals()).containsExactly("task:demo:fix");

            assertThatThrownBy(
                            () ->
                                    TaskCertification.certify(
                                            "demo",
                                            "fix",
                                            "alice",
                                            new AgentCertifier(person, dir.resolve("none.sock")),
                                            List.of(person),
                                            Clock.systemUTC()))
                    .isInstanceOf(TaskCertification.Refused.class)
                    .hasMessageContaining("agent");
        } finally {
            agent.destroy();
            agent.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static String keyOnly(String line) {
        final String[] fields = line.strip().split(" ");
        return fields[0] + " " + fields[1];
    }

    private static void run(String... command) throws Exception {
        assertThat(
                        new ProcessBuilder(command)
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .isZero();
    }
}
