package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link TaskSigner}: a task's commit signed with its key, through its certificate. */
class TaskSignerTest {

    @TempDir Path dir;

    @Test
    void aCommitSignedForATaskVerifiesForItsPrincipalAgainstItsCertifier() throws Exception {
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
                "porelo task fix by alice",
                "-n",
                "task:demo:fix",
                "-V",
                "+1d",
                dir.resolve("task.pub").toString());
        final TaskSecrets.Signing signing =
                new TaskSecrets.Signing(
                        OpenSshPrivateKey.seedBase64(Files.readString(dir.resolve("task"))),
                        Files.readString(dir.resolve("task-cert.pub")).strip());
        final byte[] payload = "tree 1\n\nfix\n".getBytes(StandardCharsets.UTF_8);

        Files.writeString(
                dir.resolve("payload.sig"),
                TaskSigner.of(() -> Optional.of(signing)).sign(payload));
        Files.write(dir.resolve("payload"), payload);
        Files.writeString(
                dir.resolve("allowed"),
                "task:demo:* cert-authority,namespaces=\"git\" "
                        + Files.readString(dir.resolve("person.pub")));

        final Process verify =
                new ProcessBuilder(
                                "ssh-keygen",
                                "-Y",
                                "verify",
                                "-n",
                                "git",
                                "-I",
                                "task:demo:fix",
                                "-f",
                                dir.resolve("allowed").toString(),
                                "-s",
                                dir.resolve("payload.sig").toString())
                        .redirectInput(dir.resolve("payload").toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertThat(verify.waitFor()).isZero();
    }

    @Test
    void aTaskWhoseKeyCannotBeReadSignsNothingAndSaysWhy() {
        assertThatThrownBy(() -> TaskSigner.of(Optional::empty).sign(new byte[] {1}))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("key");
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
