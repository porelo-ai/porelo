package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link TaskCertifying}: certified when the starter's agent holds a certifier's key. */
class TaskCertifyingTest {

    @TempDir Path dir;

    @Test
    void aStartIsCertifiedOnlyWhenTheAgentHoldsACertifiersKeyOrThePersonsMachineCertifies()
            throws Exception {
        final String person = keygen("person");
        final String other = keygen("other");
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

            assertThat(
                            TaskCertifying.atStart(
                                    "demo",
                                    "fix",
                                    "alice",
                                    List.of(other, person),
                                    null,
                                    socket,
                                    Clock.systemUTC()))
                    .as("the agent holds a certifier's key")
                    .isNotNull()
                    .satisfies(
                            issued -> {
                                assertThat(issued.certificate().signer()).isEqualTo(person);
                                final String line = issued.certificate().line();
                                // Its gate checks the key that certified it, not the agent's first.
                                assertThat(
                                                TaskCertifying.renewing(
                                                        TaskCertification.NO_PROJECT,
                                                        line,
                                                        List.of()))
                                        .isEqualTo(List.of(person));
                                assertThat(TaskCertifying.renewing("demo", line, List.of()))
                                        .as("a project without certifiers: no key may")
                                        .isEmpty();
                                assertThat(TaskCertifying.renewing("demo", line, List.of(other)))
                                        .isEqualTo(List.of(other));
                            });
            assertThat(
                            TaskCertifying.atStart(
                                    "demo",
                                    "fix",
                                    "alice",
                                    List.of(other),
                                    null,
                                    socket,
                                    Clock.systemUTC()))
                    .as("it holds none of them: uncertified")
                    .isNull();
            assertThat(
                            TaskCertifying.atStart(
                                    "demo",
                                    "fix",
                                    "alice",
                                    List.of(),
                                    null,
                                    socket,
                                    Clock.systemUTC()))
                    .as("no certifiers, no machine of the person: uncertified")
                    .isNull();
            assertThat(
                            TaskCertifying.atStart(
                                    "demo",
                                    "fix",
                                    "alice",
                                    List.of(person),
                                    null,
                                    null,
                                    Clock.systemUTC()))
                    .as("no agent")
                    .isNull();
            assertThat(
                            TaskCertifying.atStart(
                                    "default",
                                    "shell",
                                    "alice",
                                    List.of(),
                                    new AgentCertifier(person, socket),
                                    null,
                                    Clock.systemUTC()))
                    .as("from the person's machine, a task with no project")
                    .isNotNull();
            assertThat(
                            TaskCertifying.atStart(
                                    "demo",
                                    "fix",
                                    "alice",
                                    List.of(),
                                    new AgentCertifier(person, socket),
                                    null,
                                    Clock.systemUTC()))
                    .as("from the person's machine, a project naming no certifier: uncertified")
                    .isNull();
            assertThat(
                            TaskCertifying.atStart(
                                    TaskCertification.NO_PROJECT,
                                    "shell",
                                    "alice",
                                    List.of(),
                                    null,
                                    socket,
                                    Clock.systemUTC()))
                    .as("a task with no project, by the key in the starter's own agent")
                    .isNotNull()
                    .satisfies(
                            issued ->
                                    assertThat(issued.signers())
                                            .isEqualTo(
                                                    "task:default:* cert-authority,namespaces=\"git\" "
                                                            + person
                                                            + "\n"));
        } finally {
            agent.destroy();
            agent.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private String keygen(String name) throws Exception {
        assertThat(
                        new ProcessBuilder(
                                        "ssh-keygen",
                                        "-q",
                                        "-t",
                                        "ed25519",
                                        "-N",
                                        "",
                                        "-C",
                                        name,
                                        "-f",
                                        dir.resolve(name).toString())
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .isZero();
        final String[] fields = Files.readString(dir.resolve(name + ".pub")).strip().split(" ");
        return fields[0] + " " + fields[1];
    }
}
