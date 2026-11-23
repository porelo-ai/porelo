package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.supervisor.DeviceGrant;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for what a grant is kept as - one decision, which the terminal and the daemon's {@code
 * Authorize} share.
 */
class VaultAuthorizeCommandTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    @Test
    void aServiceHasThirtySecondsTimesTheServicesFactorToTakeTheConnection(
            @TempDir final Path dir) {
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0);

        assertThat(VaultAuthorizeCommand.http(context).connectTimeout())
                .as("at the factor 1.0")
                .contains(Duration.ofSeconds(30));
        assertThat(
                        VaultAuthorizeCommand.http(
                                        context.withAccount(
                                                AccountConfig.read(
                                                        new java.io.StringReader(
                                                                "limits:\n  services: 2\n"),
                                                        "t")))
                                .connectTimeout())
                .as("at 2.0, from the account's settings")
                .contains(Duration.ofSeconds(60));
    }

    @Test
    void aPersonHasTenMinutesToAnswerARedirectAtAnyFactor(@TempDir final Path dir)
            throws Exception {
        final int port;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
            port = free.getLocalPort();
        }
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                arguments -> 0)
                        .asking(prompt -> PASSPHRASE.clone());
        context.vault()
                .write(
                        Map.of(
                                "service",
                                new org.fuin.porelo.vault.VaultEntry(
                                        "-",
                                        org.fuin.porelo.supervisor.CodeGrant.KIND,
                                        Map.of(
                                                "client_id", "test-client",
                                                "authorization_url", "http://127.0.0.1:1/auth",
                                                "token_url", "http://127.0.0.1:1/token",
                                                "redirect_port", String.valueOf(port)))),
                        PASSPHRASE);
        context.requireOpener();
        final PoreloContext factored =
                context.withAccount(
                        AccountConfig.read(
                                new java.io.StringReader(
                                        "limits:\n  services: 10\n  machines: 10\n"
                                                + "  polling: 10\n  retries: 10\n"),
                                "t"));

        final VaultAuthorizeCommand.Flow flow = VaultAuthorizeCommand.flowFor(factored, "service");
        final VaultAuthorizeCommand.Shown shown = flow.start();
        try {
            assertThat(shown.expiresIn()).isEqualTo(Duration.ofMinutes(10));
        } finally {
            // Answers the redirect itself, so the listener the flow opened is closed.
            final String state =
                    java.util.Arrays.stream(shown.link().split("[?&]"))
                            .filter(part -> part.startsWith("state="))
                            .findFirst()
                            .orElseThrow();
            java.net.http.HttpClient.newHttpClient()
                    .send(
                            java.net.http.HttpRequest.newBuilder(
                                            java.net.URI.create(
                                                    "http://127.0.0.1:"
                                                            + port
                                                            + "/callback?error=access_denied&"
                                                            + state))
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding());
            flow.await();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"refresh", "lasting", "refused"})
    void authorizationReportsTheUseOnlyAfterKeepingAGrant(
            final String answer, @TempDir final Path dir) throws Exception {
        final com.sun.net.httpserver.HttpServer service =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext(
                "/device",
                exchange ->
                        reply(
                                exchange,
                                "{\"device_code\":\"device-for-this-test\",\"user_code\":\"TEST\","
                                        + "\"verification_uri\":\"https://service.example.org/verify\","
                                        + "\"expires_in\":60,\"interval\":1}"));
        service.createContext(
                "/token",
                exchange ->
                        reply(
                                exchange,
                                switch (answer) {
                                    case "refresh" ->
                                            "{\"access_token\":\"test-access-value\","
                                                    + "\"refresh_token\":\"test-refresh-value\",\"expires_in\":3600}";
                                    case "lasting" -> "{\"access_token\":\"test-access-value\"}";
                                    default -> "{\"error\":\"access_denied\"}";
                                }));
        service.start();
        try {
            final String endpoint = "http://127.0.0.1:" + service.getAddress().getPort();
            final PoreloContext context =
                    new PoreloContext(
                                    new FakeCommandRunner(),
                                    new PoreloPaths(
                                            XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                    arguments -> 0)
                            .asking(prompt -> PASSPHRASE.clone());
            context.vault()
                    .write(
                            Map.of(
                                    "service",
                                    new org.fuin.porelo.vault.VaultEntry(
                                            "-",
                                            DeviceGrant.KIND,
                                            Map.of(
                                                    "client_id", "test-client",
                                                    "device_authorization_url",
                                                            endpoint + "/device",
                                                    "token_url", endpoint + "/token"))),
                            PASSPHRASE);
            final Path file = dir.resolve("project.yml");
            java.nio.file.Files.writeString(
                    file,
                    "project:\n  name: shop\n  security_class: guarded\n"
                            + "image:\n  from: ubuntu:24.04\ncredentials:\n  service: search\n");
            new ProjectRegistry(context.paths().projects().projectRegistry())
                    .remember("shop", file);
            final java.io.StringWriter out = new java.io.StringWriter();
            final java.io.StringWriter err = new java.io.StringWriter();
            final picocli.CommandLine line = PoreloCli.commandLine(context);
            line.setOut(new java.io.PrintWriter(out));
            line.setErr(new java.io.PrintWriter(err));

            context.requireOpener();
            assertThat(context.credentials().get("service"))
                    .as("the authorization starts with its OAuth configuration readable")
                    .isNotNull()
                    .extracting(org.fuin.porelo.vault.VaultEntry::type)
                    .isEqualTo(DeviceGrant.KIND);

            final int code = line.execute("vault", "authorize", "service");

            final Map<String, org.fuin.porelo.vault.VaultEntry> entries =
                    context.vault().read(PASSPHRASE);
            if (answer.equals("refused")) {
                assertThat(code).as("a refusal stores no grant").isEqualTo(1);
                assertThat(entries)
                        .as("no grant is kept after refusal")
                        .doesNotContainKey("grant/service");
                assertThat(out.toString())
                        .as("a refused write must not claim a stored entry's use")
                        .doesNotContain("used for");
            } else {
                assertThat(code).as("the grant is kept: %s", err).isZero();
                assertThat(entries.get("grant/service").value())
                        .as("the reported use follows the successful grant write")
                        .isEqualTo(
                                answer.equals("refresh")
                                        ? "test-refresh-value"
                                        : "test-access-value");
                assertThat(out.toString())
                        .as(
                                "the logical entry's use is reported, rather than its hidden"
                                        + " grant's name")
                        .contains("used for  shop's search");
            }
            assertThat(out.toString() + err)
                    .as("reporting a use never reports a token")
                    .doesNotContain(
                            "test-access-value", "test-refresh-value", "device-for-this-test");
        } finally {
            service.stop(0);
            PoreloContext.forgetAsked();
        }
    }

    private static void reply(final com.sun.net.httpserver.HttpExchange exchange, final String json)
            throws java.io.IOException {
        final byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (final java.io.OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        } finally {
            exchange.close();
        }
    }

    @Test
    void aTokenThatNeverExpiresIsKeptAsItIsAndOneThatExpiresWithNothingToRenewItIsRefused(
            @TempDir Path dir) {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0);
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);

            final VaultAuthorizeCommand.Kept lasting =
                    VaultAuthorizeCommand.keepGranted(
                            context,
                            "copilot",
                            new DeviceGrant.Outcome("granted", null, "gho_1", null));
            final VaultAuthorizeCommand.Kept hourly =
                    VaultAuthorizeCommand.keepGranted(
                            context,
                            "hourly",
                            new DeviceGrant.Outcome("granted", null, "at-1", Duration.ofHours(1)));
            final VaultAuthorizeCommand.Kept renewed =
                    VaultAuthorizeCommand.keepGranted(
                            context,
                            "forge",
                            new DeviceGrant.Outcome(
                                    "granted", "rt-1", "at-1", Duration.ofHours(1)));

            assertThat(lasting.kept()).isTrue();
            assertThat(lasting.detail()).contains("does not expire").contains("cannot revoke");
            assertThat(hourly.kept()).isFalse();
            assertThat(hourly.detail()).contains("no refresh token");
            assertThat(renewed).isEqualTo(new VaultAuthorizeCommand.Kept(true, ""));
            final Map<String, org.fuin.porelo.vault.VaultEntry> grants =
                    context.readableGrants().orElseThrow();
            assertThat(grants.get("copilot").type()).isEqualTo(VaultAuthorizeCommand.HELD);
            assertThat(grants.get("copilot").value()).isEqualTo("gho_1");
            assertThat(grants.get("forge").type()).isEqualTo("refresh-token");
            assertThat(grants).doesNotContainKey("hourly");
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aGrantIsKeptInAVaultADevicesShareOpened(@TempDir Path dir) {
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");

        // The daemon opens the vault with a device's share and has no terminal to ask a passphrase
        // at: the person granted at the service, and keeping it asked for the passphrase alone, so
        // the grant was lost.
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0);
        final byte[] share = new byte[Keyslots.SHARE_BYTES];
        new java.util.Random(7).nextBytes(share);
        context.vault().write(Map.of(), PASSPHRASE);
        context.vault()
                .enroll(
                        org.fuin.porelo.vault.VaultFile.Opener.passphrase(PASSPHRASE),
                        share.clone(),
                        "the laptop",
                        "USER_SCOPED");
        VaultShare.keep(context.paths(), share, Duration.ofMinutes(5));
        try {
            final VaultAuthorizeCommand.Kept kept =
                    VaultAuthorizeCommand.keepGranted(
                            context,
                            "forge",
                            new DeviceGrant.Outcome(
                                    "granted", "rt-1", "at-1", Duration.ofHours(1)));

            assertThat(kept)
                    .as("kept with what opened the vault")
                    .isEqualTo(new VaultAuthorizeCommand.Kept(true, ""));
            assertThat(
                            context.vault()
                                    .read(
                                            org.fuin.porelo.vault.VaultFile.Opener.passphrase(
                                                    PASSPHRASE))
                                    .get(TaskSecrets.GRANT_PREFIX + "forge")
                                    .value())
                    .isEqualTo("rt-1");
        } finally {
            VaultShare.forget(context.paths());
        }
    }
}
