package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link TaskCertifying}: certified when the starter's agent holds a certifier's key. */
class TaskCertifyingTest {

    @TempDir Path dir;

    @RegisterExtension final TestSshAgent agents = new TestSshAgent();

    @Test
    void aStartChecksItsCertifiersWithoutAskingThePersonsComputerToSign() throws Exception {
        // A preflight must neither reject a usable key nor ask for a certificate twice.
        final String person = keygen("person");
        final String other = keygen("other");
        final Path socket = dir.resolve("checking.sock");
        agentHolding(socket);
        final TaskCertification.Certifier remote =
                (key, principal, keyId, validBefore) -> {
                    throw new AssertionError("a preflight must not ask for a signature");
                };

        assertThat(TaskCertifying.canCertify("demo", List.of(other, person), null, socket))
                .as("any certifier's key held by the local agent can certify")
                .isTrue();
        assertThat(TaskCertifying.canCertify("demo", List.of(other), null, socket))
                .as("an unrelated agent key cannot certify the project's task")
                .isFalse();
        assertThat(TaskCertifying.canCertify("demo", List.of(person), null, null))
                .as("no remote certifier and no agent cannot certify")
                .isFalse();
        assertThat(TaskCertifying.canCertify("demo", List.of(person), remote, null))
                .as("the person's computer can certify without an agent on the host")
                .isTrue();
        assertThat(TaskCertifying.canCertify("demo", List.of(), remote, socket))
                .as("a project naming no certifiers stays uncertified even with a remote")
                .isFalse();
        assertThat(TaskCertifying.canCertify(TaskCertification.NO_PROJECT, List.of(), null, socket))
                .as("a task with no project can use the agent's first Ed25519 key")
                .isTrue();
    }

    @Test
    void aStartIsCertifiedOnlyWhenTheAgentHoldsACertifiersKeyOrThePersonsMachineCertifies()
            throws Exception {
        final String person = keygen("person");
        final String other = keygen("other");
        final Path socket = dir.resolve("agent.sock");
        agents.start(socket);
        try {
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
            agents.close();
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

    @Test
    void aStartNotesWhenItsCertificateEndsForAListToRead(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path state) throws Exception {
        assertThat(TaskState.certifiedUntil(state)).as("none yet").isEmpty();

        TaskState.certifiedUntil(state, java.time.Instant.parse("2026-11-11T00:00:00Z"));
        assertThat(TaskState.certifiedUntil(state)).isEqualTo("2026-11-11T00:00:00Z");

        java.nio.file.Files.writeString(state.resolve(TaskState.CERTIFIED_UNTIL), "not an instant");
        assertThat(TaskState.certifiedUntil(state)).as("what does not read says nothing").isEmpty();
    }

    /** An agent holding the person's key, for a test to sign through. */
    private void agentHolding(final Path socket) throws Exception {
        agents.start(socket);
        final ProcessBuilder add =
                new ProcessBuilder("ssh-add", "-q", dir.resolve("person").toString());
        add.environment().put("SSH_AUTH_SOCK", socket.toString());
        assertThat(
                        add.redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .isZero();
    }

    /** A person's computer answering a start: it signs what it is told, or something else. */
    private CertificateWaits.Asker answering(
            final CertificateWaits waits,
            final String person,
            final Path socket,
            final String signedPrincipal) {
        return new CertificateWaits.Asker() {
            @Override
            public void ask(final java.util.Map<String, Object> reply) {
                final java.util.Map<?, ?> asked = (java.util.Map<?, ?>) reply.get("certify");
                waits.certificate(
                        String.valueOf(asked.get("start")),
                        new AgentCertifier(person, socket)
                                .certify(
                                        String.valueOf(asked.get("key")),
                                        signedPrincipal,
                                        String.valueOf(asked.get("key_id")),
                                        java.time.Instant.parse(
                                                String.valueOf(asked.get("valid_before")))));
            }

            @Override
            public boolean open() {
                return true;
            }
        };
    }

    @Test
    void aStartInDefaultOnAMachineThatIsNoHostAsksNobody() {
        assertThat(TaskCertifying.asksThePerson(TaskCertification.NO_PROJECT, false))
                .as("nothing there needs a certificate, and no key is named to give one with")
                .isFalse();
        assertThat(TaskCertifying.asksThePerson(TaskCertification.NO_PROJECT, true))
                .as("a host starts nothing uncertified, and its starter's key certifies")
                .isTrue();
        assertThat(TaskCertifying.asksThePerson("demo", false))
                .as("a project's members' keys are named, on a machine that is no host too")
                .isTrue();
        assertThat(TaskCertifying.asksThePerson("demo", true)).isTrue();
    }

    @Test
    void aStartIsCertifiedByThePersonsComputerThroughItsWait() throws Exception {
        final String person = keygen("person");
        final Path socket = dir.resolve("waited.sock");
        agentHolding(socket);
        try {
            final CertificateWaits waits = new CertificateWaits();

            final TaskCertification.Issued issued =
                    TaskCertifying.atStart(
                            "demo",
                            "fix",
                            "alice",
                            List.of(person),
                            waits.certifier(
                                    answering(waits, person, socket, "task:demo:fix"),
                                    List.of(person)),
                            null,
                            Clock.systemUTC());

            assertThat(issued).isNotNull();
            assertThat(issued.certificate().signer()).isEqualTo(person);
            assertThat(issued.principal()).isEqualTo("task:demo:fix");
        } finally {
            agents.close();
        }
    }

    @Test
    void aCertificateForAnotherTaskIsRefusedThroughItsWaitToo() throws Exception {
        final String person = keygen("person");
        final Path socket = dir.resolve("waited.sock");
        agentHolding(socket);
        try {
            final CertificateWaits waits = new CertificateWaits();

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    TaskCertifying.atStart(
                                            "demo",
                                            "fix",
                                            "alice",
                                            List.of(person),
                                            waits.certifier(
                                                    answering(
                                                            waits,
                                                            person,
                                                            socket,
                                                            "task:demo:other"),
                                                    List.of(person)),
                                            null,
                                            Clock.systemUTC()))
                    .isInstanceOf(TaskCertification.Refused.class)
                    .hasMessageContaining("must name exactly task:demo:fix");
        } finally {
            agents.close();
        }
    }
}
