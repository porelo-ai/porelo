package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshCertificate;
import org.fuin.porelo.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link TaskSigner}: a task's commit signed with its key, through its certificate. */
class TaskSignerTest {

    private static final byte[] PAYLOAD = "tree 1\n\nfix\n".getBytes(StandardCharsets.UTF_8);

    @TempDir Path dir;

    @Test
    void aCommitSignedForATaskVerifiesForItsPrincipalAgainstItsCertifier() throws Exception {
        final TaskSecrets.Signing signing = certified("task:demo:fix");

        Files.writeString(
                dir.resolve("payload.sig"),
                TaskSigner.of("demo", "fix", () -> Optional.of(signing)).sign(PAYLOAD));

        assertThat(verifies("task:demo:fix")).isTrue();
    }

    /**
     * A key certified for another task signs nothing for this one: ssh-keygen would not verify such
     * a signature as this task's, and the gate says why rather than make it.
     */
    @Test
    void aKeyWhoseCertificateNamesAnotherTaskSignsNothingAndSaysWhy() throws Exception {
        final TaskSecrets.Signing signing = certified("task:demo:other");
        Files.writeString(
                dir.resolve("payload.sig"),
                SshSignature.sign(
                        new SigningKey(Base64.getDecoder().decode(signing.seed()), "task"),
                        SshCertificate.read(signing.certificate()).blob(),
                        PAYLOAD,
                        "git"));
        assertThat(verifies("task:demo:other")).as("a good signature for the other task").isTrue();
        assertThat(verifies("task:demo:fix")).as("and none for this one").isFalse();

        assertThatThrownBy(
                        () ->
                                TaskSigner.of("demo", "fix", () -> Optional.of(signing))
                                        .sign(PAYLOAD))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("task:demo:other")
                .hasMessageContaining("task:demo:fix");
    }

    @Test
    void aTaskWhoseKeyCannotBeReadSignsNothingAndSaysWhy() {
        assertThatThrownBy(() -> TaskSigner.of("demo", "fix", Optional::empty).sign(new byte[] {1}))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("key");
    }

    /** A throwaway task key, certified for a principal by a throwaway person's key. */
    private TaskSecrets.Signing certified(String principal) throws Exception {
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
        run(
                "ssh-keygen",
                "-q",
                "-t",
                "ed25519",
                "-N",
                "",
                "-C",
                "task",
                "-f",
                dir.resolve("task").toString());
        run(
                "ssh-keygen",
                "-q",
                "-s",
                dir.resolve("person").toString(),
                "-I",
                "porelo task by alice",
                "-n",
                principal,
                "-V",
                "+1d",
                dir.resolve("task.pub").toString());
        Files.writeString(
                dir.resolve("allowed"),
                "task:demo:* cert-authority,namespaces=\"git\" "
                        + Files.readString(dir.resolve("person.pub")));
        return new TaskSecrets.Signing(
                OpenSshPrivateKey.seedBase64(Files.readString(dir.resolve("task"))),
                Files.readString(dir.resolve("task-cert.pub")).strip());
    }

    /** Whether ssh-keygen verifies the signature beside the payload as by a principal. */
    private boolean verifies(String principal) throws Exception {
        Files.write(dir.resolve("payload"), PAYLOAD);
        final Process verify =
                new ProcessBuilder(
                                "ssh-keygen",
                                "-Y",
                                "verify",
                                "-n",
                                "git",
                                "-I",
                                principal,
                                "-f",
                                dir.resolve("allowed").toString(),
                                "-s",
                                dir.resolve("payload.sig").toString())
                        .redirectInput(dir.resolve("payload").toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        return verify.waitFor() == 0;
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
