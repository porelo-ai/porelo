package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Tests for {@link VaultRemoveCommand}. */
class VaultRemoveCommandTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private PoreloContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private int execute(PoreloContext context, String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    @Test
    void saysSoWhenThereIsNoVault(@TempDir Path dir) {
        assertThat(execute(context(dir), "vault", "remove", "anything")).isEqualTo(69);
        assertThat(err.toString()).contains("no vault at");
    }

    @Test
    void removesOnlyTheNamedEntry(@TempDir Path dir) {
        final var vault = context(dir).vault();
        vault.write(Map.of("keep", VaultEntry.of("one"), "drop", VaultEntry.of("two")), PASSPHRASE);

        vault.update(
                PASSPHRASE,
                entries -> {
                    entries.remove("drop");
                    return entries;
                });

        assertThat(vault.read(PASSPHRASE)).containsOnlyKeys("keep");
    }

    @Test
    void removingSomethingAbsentIsNotAFailure(@TempDir Path dir) {

        // The wanted state is "not in the vault", and it already holds. Failing here would make a
        // cleanup script stop on its second run.
        final var vault = context(dir).vault();
        vault.write(Map.of("keep", VaultEntry.of("one")), PASSPHRASE);

        vault.update(
                PASSPHRASE,
                entries -> {
                    assertThat(entries.remove("absent")).isNull();
                    return entries;
                });

        assertThat(vault.read(PASSPHRASE)).containsOnlyKeys("keep");
    }

    @Test
    void aGrantWhoseServiceCannotBeToldSaysWhy() {
        assertThat(VaultRemoveCommand.revoke(null, "rt-1")).contains("cannot be told");
        assertThat(
                        VaultRemoveCommand.revoke(
                                new VaultEntry(
                                        "-",
                                        "oauth-device",
                                        Map.of(
                                                "client_id",
                                                "x",
                                                "device_authorization_url",
                                                "https://a.invalid/d",
                                                "token_url",
                                                "https://a.invalid/t")),
                                "rt-1"))
                .contains("revocation_url")
                .doesNotContain("rt-1");
    }

    @Test
    void aPromptedOpenerStillSeesTheGrantThatRefusesRemoval(@TempDir final Path dir) {
        final PoreloContext context = context(dir).asking(prompt -> PASSPHRASE.clone());
        context.vault()
                .write(
                        Map.of(
                                "forge",
                                new VaultEntry("example-token", "oauth-device"),
                                TaskSecrets.GRANT_PREFIX + "forge",
                                new VaultEntry("example-grant", VaultAuthorizeCommand.HELD)),
                        PASSPHRASE);
        try {
            assertThat(execute(context, "vault", "remove", "forge"))
                    .as("a grant that cannot be revoked needs explicit consent to remove")
                    .isEqualTo(1);
            assertThat(context.vault().read(PASSPHRASE))
                    .as("the grant and credential remain until consent is given")
                    .containsKeys("forge", TaskSecrets.GRANT_PREFIX + "forge");
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void aGrantAPersonGaveIsRevokedAtTheServiceBeforeItIsRemovedHere(@TempDir Path dir)
            throws Exception {
        // Harm: a refresh token deleted here that the service still honours. A person's grant is
        // kept with the same type as what renews an agent's own sign-in, and was taken for one:
        // forgotten here, never revoked there.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final java.util.List<String> revoked = new java.util.concurrent.CopyOnWriteArrayList<>();
        final com.sun.net.httpserver.HttpServer service =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext(
                "/revoke",
                exchange -> {
                    revoked.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8));
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        service.start();
        final String base = "http://127.0.0.1:" + service.getAddress().getPort();
        final PoreloContext context = context(dir);
        final org.fuin.porelo.vault.KernelKeyring keyring =
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            context.vault()
                    .write(
                            Map.of(
                                    "forge",
                                    new VaultEntry(
                                            "-",
                                            "oauth-device",
                                            Map.of(
                                                    "client_id",
                                                    "porelo-client",
                                                    "device_authorization_url",
                                                    base + "/device",
                                                    "token_url",
                                                    base + "/token",
                                                    "revocation_url",
                                                    base + "/revoke"))),
                            PASSPHRASE);
            keyring.store(PASSPHRASE);
            VaultAuthorizeCommand.keep(context, "forge", "rt-1");

            assertThat(execute(context, "vault", "remove", "forge")).as(err.toString()).isZero();

            assertThat(revoked)
                    .as("the service told, with the refresh token and the client")
                    .singleElement()
                    .satisfies(
                            asked ->
                                    assertThat(asked)
                                            .contains("token=rt-1", "client_id=porelo-client"));
            assertThat(out.toString()).contains("revoked").doesNotContain("rt-1");
            assertThat(context.vault().read(PASSPHRASE)).isEmpty();
        } finally {
            keyring.forget();
            service.stop(0);
        }
    }

    @Test
    void namesTheListGitHubShowsTheAppIn() {

        // Measured on 2026-10-19: Pi's app, Iv1.b507a08c87ecfe98, is a GitHub App, shown under
        // "Authorized
        // GitHub Apps"; the message sent the person to "Authorized OAuth Apps", where it is not.
        final String tokenUrl = "https://github.com/login/oauth/access_token";

        assertThat(VaultRemoveCommand.revokedWhere(entry("Iv1.b507a08c87ecfe98", tokenUrl)))
                .as("a GitHub App")
                .contains("Authorized GitHub Apps")
                .doesNotContain("OAuth");
        assertThat(VaultRemoveCommand.revokedWhere(entry("Ov23li8tweQw6odWQebz", tokenUrl)))
                .as("an OAuth App")
                .contains("Authorized OAuth Apps");
        assertThat(VaultRemoveCommand.revokedWhere(entry("unknown", tokenUrl)))
                .as("a client id of neither kind names both")
                .contains("Authorized GitHub Apps")
                .contains("Authorized OAuth Apps");
    }

    private static VaultEntry entry(final String clientId, final String tokenUrl) {
        return new VaultEntry(
                "-", "oauth-device", Map.of("client_id", clientId, "token_url", tokenUrl));
    }

    @Test
    void aTokenThatNeverExpiresIsKeptUntilThePersonSaysTheyKnowItLivesOnAndThenSaysWhereToRevokeIt(
            @TempDir Path dir) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context(dir);
        final org.fuin.porelo.vault.KernelKeyring keyring =
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            context.vault()
                    .write(
                            Map.of(
                                    "copilot",
                                    new VaultEntry(
                                            "-",
                                            "oauth-device",
                                            Map.of(
                                                    "client_id",
                                                    "Ov23li",
                                                    "device_authorization_url",
                                                    "https://github.com/login/device/code",
                                                    "token_url",
                                                    "https://github.com/login/oauth/access_token")),
                                    TaskSecrets.GRANT_PREFIX + "copilot",
                                    new VaultEntry("gho_1", VaultAuthorizeCommand.HELD, Map.of())),
                            PASSPHRASE);
            keyring.store(PASSPHRASE);

            // Only the application's owner can revoke it at GitHub; forgetting it here would leave
            // it working there.
            assertThat(execute(context, "vault", "remove", "copilot")).isEqualTo(1);
            assertThat(err.toString())
                    .contains("only the application's owner can")
                    .contains("Settings → Applications → Authorized OAuth Apps")
                    .doesNotContain("gho_1");
            assertThat(context.vault().read(PASSPHRASE))
                    .containsKey(TaskSecrets.GRANT_PREFIX + "copilot");

            assertThat(execute(context, "vault", "remove", "copilot", "--without-revoking"))
                    .isZero();
            assertThat(out.toString()).contains("works there until it is revoked on GitHub");
            assertThat(context.vault().read(PASSPHRASE)).isEmpty();
        } finally {
            keyring.forget();
        }
    }
}
