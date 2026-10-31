package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for a task's key and certificate, kept in the vault and gone with the task. */
class TaskSigningSecretsTest {

    private static final String TASK = "porelo-p-task";

    @TempDir Path dir;

    @Test
    void aTasksKeyAndCertificateAreKeptReadBackAndForgottenWithItAlone() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "no kernel keyring here");
        final PoreloContext context = context();
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault()
                    .write(
                            Map.of(
                                    TaskSecrets.PREFIX + "porelo-p-other/signing-key",
                                    VaultEntry.of("other-seed"),
                                    TaskSecrets.PREFIX + "porelo-p-task2/gate-token",
                                    VaultEntry.of("t2")),
                            passphrase);
            keyring.store(passphrase);
            final TaskSecrets secrets = new TaskSecrets(context);

            assertThat(
                            secrets.keepSigning(
                                    TASK,
                                    new TaskSecrets.Signing(
                                            "c2VlZA==", "ssh-ed25519-cert-v01@openssh.com AAAA")))
                    .isEmpty();
            assertThat(secrets.signing(TASK))
                    .contains(
                            new TaskSecrets.Signing(
                                    "c2VlZA==", "ssh-ed25519-cert-v01@openssh.com AAAA"));
            assertThat(secrets.signing("porelo-p-none")).isEmpty();
            assertThat(TaskSecrets.reserved(TaskSecrets.PREFIX + TASK + "/signing-key")).isTrue();

            assertThat(secrets.keepForgeToken(TASK, "host-made")).isEmpty();
            assertThat(secrets.forgeToken(TASK)).contains("host-made");
            assertThat(secrets.forgeToken("porelo-p-none")).isEmpty();

            assertThat(secrets.forget(TASK)).isEmpty();
            assertThat(context.vault().read(passphrase))
                    .as("the task's key goes with it, another task's stays")
                    .doesNotContainKeys(
                            TaskSecrets.PREFIX + TASK + "/signing-key",
                            TaskSecrets.PREFIX + TASK + "/certificate",
                            TaskSecrets.PREFIX + TASK + "/forge-token")
                    .containsKeys(
                            TaskSecrets.PREFIX + "porelo-p-other/signing-key",
                            TaskSecrets.PREFIX + "porelo-p-task2/gate-token");
        } finally {
            keyring.forget();
        }
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }
}
