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

    @Test
    void aGateCannotSignOrPushForAReplacementWithTheSameLogicalName() throws Exception {
        // A helper still serving an old endpoint must never borrow a later incarnation's key or
        // token.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final PoreloContext context =
                new PoreloContext(runner, context().paths(), arguments -> 0)
                        .asking(shown -> "fixture-passphrase".toCharArray());
        final CertifiedTaskKeys keys = new CertifiedTaskKeys(dir.resolve("certificates"));
        final var original = keys.task("p", "task");
        TaskIdentityFixture.record(context.paths(), TASK, "p", "task", "a".repeat(64));
        context.vault()
                .write(
                        Map.of(
                                TaskSecrets.PREFIX + TASK + "/signing-key",
                                        VaultEntry.of(original.seedBase64()),
                                TaskSecrets.PREFIX + TASK + "/certificate",
                                        VaultEntry.of(keys.certificate(original).line()),
                                TaskSecrets.PREFIX + TASK + "/forge-token",
                                        VaultEntry.of("original-token")),
                        "fixture-passphrase".toCharArray());
        try {
            context.openerAsking(false, new java.io.PrintWriter(java.io.Writer.nullWriter()));
            final var signing = GateServeCommand.signing(context, "p", "task");
            final var token = GateServeCommand.forgeToken(context, "p", "task");
            TaskIdentityFixture.record(context.paths(), TASK, "p", "task", "b".repeat(64));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> signing.sign(new byte[] {1}))
                    .as("the old gate's recorded id cannot authorize a replacement's signature")
                    .isInstanceOf(org.fuin.porelo.gate.GateException.class);
            final Path conf = Files.writeString(dir.resolve("forge.conf"), "git_port=3001\n");
            assertThat(
                            new HostPush(
                                            context,
                                            dir.resolve("mirror.git"),
                                            "task",
                                            "acme/repo",
                                            token,
                                            conf)
                                    .passOn("c".repeat(40)))
                    .as("the old gate cannot spend a replacement's forge token")
                    .isNotNull();
            assertThat(runner.invocations())
                    .as("no git push runs for the replaced identity")
                    .noneMatch(command -> command.arguments().contains("push"));
        } finally {
            context.forgetTyped();
        }
    }

    @Test
    void anOldGateSignsAndPushesWithItsOwnKeyAndTokenBesideAnEncodedSibling() throws Exception {
        // The gate must never sign or spend the token of the owner with an overlapping old
        // spelling.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final PoreloContext context =
                new PoreloContext(runner, context().paths(), arguments -> 0)
                        .asking(shown -> "fixture-passphrase".toCharArray());
        final CertifiedTaskKeys keys = new CertifiedTaskKeys(dir.resolve("certificates"));
        final var original = keys.task("web", "app-x");
        final var sibling = keys.task("web-app", "x");
        final String old = "porelo-web-app-x";
        final String other = "porelo-web-app_x";
        TaskIdentityFixture.record(context.paths(), old, "web", "app-x", "a".repeat(64));
        TaskIdentityFixture.record(context.paths(), other, "web-app", "x", "b".repeat(64));
        context.vault()
                .write(
                        Map.of(
                                TaskSecrets.PREFIX + old + "/signing-key",
                                        VaultEntry.of(original.seedBase64()),
                                TaskSecrets.PREFIX + old + "/certificate",
                                        VaultEntry.of(keys.certificate(original).line()),
                                TaskSecrets.PREFIX + old + "/forge-token",
                                        VaultEntry.of("original-token"),
                                TaskSecrets.PREFIX + other + "/signing-key",
                                        VaultEntry.of(sibling.seedBase64()),
                                TaskSecrets.PREFIX + other + "/certificate",
                                        VaultEntry.of(keys.certificate(sibling).line()),
                                TaskSecrets.PREFIX + other + "/forge-token",
                                        VaultEntry.of("sibling-token")),
                        "fixture-passphrase".toCharArray());
        try {
            context.openerAsking(false, new java.io.PrintWriter(java.io.Writer.nullWriter()));
            final TaskSecrets secrets = new TaskSecrets(context);
            final byte[] payload =
                    "tree 1\n\nreviewed work\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            final String signature =
                    TaskSigner.of(() -> secrets.signing("web", "app-x")).sign(payload);
            assertThat(
                            signature.equals(
                                    org.fuin.porelo.vault.SshSignature.sign(
                                            original,
                                            keys.certificate(original).blob(),
                                            payload,
                                            "git")))
                    .as("the signature verifies only against the old owner's original certificate")
                    .isTrue();
            assertThat(
                            signature.equals(
                                    org.fuin.porelo.vault.SshSignature.sign(
                                            sibling,
                                            keys.certificate(sibling).blob(),
                                            payload,
                                            "git")))
                    .as("the sibling cannot become this signature's owner")
                    .isFalse();
            final String siblingSignature =
                    TaskSigner.of(() -> secrets.signing("web-app", "x")).sign(payload);
            assertThat(
                            siblingSignature.equals(
                                    org.fuin.porelo.vault.SshSignature.sign(
                                            sibling,
                                            keys.certificate(sibling).blob(),
                                            payload,
                                            "git")))
                    .as("the sibling signs with its own certificate too")
                    .isTrue();
            final Path conf = Files.writeString(dir.resolve("forge.conf"), "git_port=3001\n");
            assertThat(
                            new HostPush(
                                            context,
                                            dir.resolve("mirror.git"),
                                            "app-x",
                                            "acme/repo",
                                            () -> secrets.forgeToken("web", "app-x"),
                                            conf)
                                    .passOn("c".repeat(40)))
                    .as("the old task's push is authorized by its original token")
                    .isNull();
            assertThat(
                            "original-token"
                                    .equals(
                                            runner.invocations()
                                                    .getLast()
                                                    .environment()
                                                    .get(HostPush.TOKEN_VARIABLE)))
                    .as("only the old owner's token reaches the push environment")
                    .isTrue();
            assertThat(
                            new HostPush(
                                            context,
                                            dir.resolve("sibling.git"),
                                            "x",
                                            "acme/repo",
                                            () -> secrets.forgeToken("web-app", "x"),
                                            conf)
                                    .passOn("d".repeat(40)))
                    .as("the encoded sibling has its own working gate")
                    .isNull();
            assertThat(
                            "sibling-token"
                                    .equals(
                                            runner.invocations()
                                                    .getLast()
                                                    .environment()
                                                    .get(HostPush.TOKEN_VARIABLE)))
                    .as("the encoded sibling keeps its distinct token")
                    .isTrue();
            context.forgetTyped();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    TaskSigner.of(() -> secrets.signing("web", "app-x"))
                                            .sign(payload))
                    .as("a locked vault signs nothing")
                    .isInstanceOf(org.fuin.porelo.gate.GateException.class);
            assertThat(
                            new HostPush(
                                            context,
                                            dir.resolve("mirror.git"),
                                            "app-x",
                                            "acme/repo",
                                            () -> secrets.forgeToken("web", "app-x"),
                                            conf)
                                    .passOn("c".repeat(40)))
                    .as("a locked vault pushes nothing")
                    .isNotNull();
            Files.writeString(context.vault().path(), "corrupt fixture");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    TaskSigner.of(() -> secrets.signing("web", "app-x"))
                                            .sign(payload))
                    .as("a corrupt vault signs nothing")
                    .isInstanceOfAny(
                            org.fuin.porelo.gate.GateException.class,
                            org.fuin.porelo.vault.VaultException.class);
        } finally {
            context.forgetTyped();
        }
    }

    @Test
    void contradictoryIdentityAndVaultOnlyLeftoversCannotAuthorizeSigning() throws Exception {
        // A retained secret alone must not authorize a replacement container to sign.
        final PoreloContext context = context().asking(shown -> "fixture-passphrase".toCharArray());
        context.vault()
                .write(
                        Map.of(
                                TaskSecrets.PREFIX + TASK + "/signing-key",
                                        VaultEntry.of("c2VlZA=="),
                                TaskSecrets.PREFIX + TASK + "/certificate",
                                        VaultEntry.of("fixture-certificate")),
                        "fixture-passphrase".toCharArray());
        try {
            context.openerAsking(false, new java.io.PrintWriter(java.io.Writer.nullWriter()));
            final TaskSecrets secrets = new TaskSecrets(context);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> TaskSigner.of(() -> secrets.signing(TASK)).sign(new byte[] {1}))
                    .as("vault-only leftovers cannot sign")
                    .isInstanceOf(org.fuin.porelo.gate.GateException.class);
            assertThat(secrets.forget(TASK))
                    .as("unidentified vault-only scopes cannot be deleted")
                    .isNotEmpty();
            TaskIdentityFixture.record(context.paths(), TASK, "p", "task", "a".repeat(64));
            org.fuin.porelo.wire.Sidecar.recordId(
                    context.paths().tasks().containerState(TASK), "b".repeat(64));
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> TaskSigner.of(() -> secrets.signing(TASK)).sign(new byte[] {1}))
                    .as("contradictory recorded ids cannot sign")
                    .isInstanceOf(org.fuin.porelo.gate.GateException.class);
        } finally {
            context.forgetTyped();
        }
    }

    private static final String TASK = "porelo-p-task";

    @TempDir Path dir;

    @Test
    void aTasksKeyAndCertificateAreKeptReadBackAndForgottenWithItAlone() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "no kernel keyring here");
        final PoreloContext context = context();
        TaskIdentityFixture.record(context.paths(), TASK, "p", "task", "a".repeat(64));
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
