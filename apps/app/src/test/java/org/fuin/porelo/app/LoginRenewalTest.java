package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.agent.api.Credential;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.supervisor.TokenPurchase;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link LoginCredentials} and {@link LoginRenewal}: an agent's own sign-in kept whole,
 * and renewed on the host, once, whichever task's broker gets there first.
 */
class LoginRenewalTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private HttpServer server;

    private final List<String> bodies = new CopyOnWriteArrayList<>();

    private final AtomicReference<String> answer =
            new AtomicReference<>(
                    "{\"access_token\":\"at-2\",\"refresh_token\":\"rt-2\",\"expires_in\":28800}");

    private final AtomicReference<Integer> status = new AtomicReference<>(200);

    private final StringWriter err = new StringWriter();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/oauth/token",
                exchange -> {
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    final byte[] payload = answer.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status.get(), payload.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String tokenUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/oauth/token";
    }

    private Credential signIn(final Instant ends) {
        return new Credential(
                "oauth",
                "at-1",
                Map.of(
                        Credential.REFRESH_TOKEN,
                        "rt-1",
                        Credential.EXPIRES_AT,
                        String.valueOf(ends.toEpochMilli()),
                        Credential.TOKEN_URL,
                        tokenUrl(),
                        Credential.CLIENT_ID,
                        "the-agents-client"));
    }

    private VaultFile vault(final Path dir, final Credential credential, final boolean own) {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        LoginCredentials.put(entries, "anthropic", credential, own);
        vault.write(entries, PASSPHRASE);
        return vault;
    }

    private LoginRenewal renewal(final VaultFile vault, final Instant at) {
        return new LoginRenewal(
                "anthropic",
                vault.read(PASSPHRASE).get("anthropic"),
                vault,
                () -> VaultFile.Opener.passphrase(PASSPHRASE),
                HttpClient.newHttpClient(),
                Clock.fixed(at, ZoneOffset.UTC),
                new PrintWriter(err));
    }

    @Test
    void keepsWhatASignInLeftWithTheRefreshTokenHiddenBesideIt(@TempDir Path dir) {

        // Kept whole: both used to keep the token and its type and drop the rest, so nothing could
        // renew it.
        final Map<String, VaultEntry> entries =
                vault(dir, signIn(NOW.plusSeconds(3600)), true).read(PASSPHRASE);

        assertThat(entries.get("anthropic").value()).isEqualTo("at-1");
        assertThat(entries.get("anthropic").settings())
                .containsEntry("expires_at", "2026-10-01T13:00:00Z")
                .containsEntry("token_url", tokenUrl())
                .containsEntry("client_id", "the-agents-client")
                .doesNotContainValue("rt-1");
        assertThat(entries.get(TaskSecrets.GRANT_PREFIX + "anthropic"))
                .isEqualTo(new VaultEntry("rt-1", "refresh-token", Map.of()));
    }

    @Test
    void keepsTheAttributesItsProviderSendsBesideTheTokenAndNoOther(@TempDir Path dir)
            throws Exception {

        // A provider that wants the account on every request refuses a request without it, and a
        // sign-in renewed once must still carry it; an attribute nobody named may be a secret.
        final Credential signIn = signIn(NOW.plusSeconds(30));
        final Map<String, String> attributes = new LinkedHashMap<>(signIn.attributes());
        attributes.put("account_id", "the-account");
        attributes.put("id_token", "a-token-nobody-asked-for");
        attributes.put("org_id", "x".repeat(300));
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        LoginCredentials.put(
                entries,
                "anthropic",
                new Credential(signIn.type(), signIn.secret(), attributes),
                true,
                java.util.Set.of("account_id", "org_id"));
        vault.write(entries, PASSPHRASE);

        assertThat(vault.read(PASSPHRASE).get("anthropic").settings())
                .as("as stored")
                .containsEntry("account_id", "the-account")
                .doesNotContainValue("a-token-nobody-asked-for")
                .as("a value too long for a header is a token, never a printed setting")
                .doesNotContainKey("org_id");
        assertThat(renewal(vault, NOW).current()).as("renewed").isEqualTo("at-2");
        assertThat(vault.read(PASSPHRASE).get("anthropic").settings())
                .as("after a renewal")
                .containsEntry("account_id", "the-account");
    }

    @Test
    void aCredentialLackingWhatItsProviderSendsBesideTheTokenIsRefusedBeforeUse() {

        // Sent without it, the provider answers as for a wrong sign-in; a value with a line break
        // in it would write a header of its own into the request.
        final Map<String, String> headers = Map.of("ChatGPT-Account-Id", "account_id");

        assertThat(CredentialHeaders.lacking(null, headers))
                .as("nothing stored")
                .isEqualTo("account_id");
        assertThat(CredentialHeaders.lacking(new VaultEntry("at", "oauth", Map.of()), headers))
                .as("a sign-in kept before the setting was")
                .isEqualTo("account_id");
        assertThat(
                        CredentialHeaders.lacking(
                                new VaultEntry(
                                        "at", "oauth", Map.of("account_id", "a\r\nX-Other: b")),
                                headers))
                .as("a value that is no header value")
                .isEqualTo("account_id");
        assertThat(
                        CredentialHeaders.lacking(
                                new VaultEntry("at", "oauth", Map.of("account_id", "the-account")),
                                headers))
                .as("a sign-in that has it")
                .isNull();
        assertThat(CredentialHeaders.lacking(null, Map.of()))
                .as("a provider that wants nothing beside the token")
                .isNull();
    }

    @Test
    void keepsAnImportsEndButNeverWhatWouldRenewIt(@TempDir Path dir) {

        // An import copies what the agent on this machine keeps renewing; renewing the copy would
        // spend the refresh token the developer's own session renews with.
        final Map<String, VaultEntry> entries =
                vault(dir, signIn(NOW.plusSeconds(3600)), false).read(PASSPHRASE);

        assertThat(entries.get("anthropic").settings()).containsOnlyKeys("expires_at");
        assertThat(entries).doesNotContainKey(TaskSecrets.GRANT_PREFIX + "anthropic");
        assertThat(LoginCredentials.renewable(entries.get("anthropic"))).isFalse();
    }

    @Test
    void usesTheStoredTokenUntilShortlyBeforeItEndsAndAsksNobody(@TempDir Path dir)
            throws Exception {

        final VaultFile vault = vault(dir, signIn(NOW.plusSeconds(3600)), true);

        assertThat(renewal(vault, NOW).current()).isEqualTo("at-1");
        assertThat(bodies).isEmpty();
    }

    @Test
    void renewsOnTheHostWhenItNearsItsEndAndKeepsWhatTheServiceRotated(@TempDir Path dir)
            throws Exception {

        final VaultFile vault = vault(dir, signIn(NOW.plusSeconds(30)), true);

        assertThat(renewal(vault, NOW).current()).isEqualTo("at-2");

        assertThat(bodies)
                .singleElement()
                .asString()
                .contains("grant_type=refresh_token")
                .contains("refresh_token=rt-1")
                .contains("client_id=the-agents-client");
        final Map<String, VaultEntry> kept = vault.read(PASSPHRASE);
        assertThat(kept.get("anthropic").value()).isEqualTo("at-2");
        assertThat(kept.get("anthropic").settings())
                .containsEntry("expires_at", "2026-10-01T20:00:00Z");
        assertThat(kept.get(TaskSecrets.GRANT_PREFIX + "anthropic").value()).isEqualTo("rt-2");
    }

    @Test
    void takesTheTokenAnotherTasksBrokerRenewedInsteadOfSpendingTheRefreshTokenAgain(
            @TempDir Path dir) throws Exception {

        // Two tasks, two brokers, one refresh token the service rotates: the second must not renew
        // with it.
        final VaultFile vault = vault(dir, signIn(NOW.plusSeconds(30)), true);
        final LoginRenewal first = renewal(vault, NOW);
        final LoginRenewal second = renewal(vault, NOW);

        assertThat(first.current()).isEqualTo("at-2");
        assertThat(second.current()).isEqualTo("at-2");

        assertThat(bodies).hasSize(1);
    }

    @Test
    void saysASignInTheServiceEndedAsOneToMakeAgain(@TempDir Path dir) {

        status.set(400);
        answer.set("{\"error\":\"invalid_grant\"}");
        final VaultFile vault = vault(dir, signIn(NOW.plusSeconds(30)), true);

        assertThatThrownBy(() -> renewal(vault, NOW).current())
                .isInstanceOf(TokenPurchase.Ended.class)
                .hasMessageContaining("sign in again with 'porelo vault login'");
        assertThat(vault.read(PASSPHRASE).get("anthropic").value()).isEqualTo("at-1");
    }

    @Test
    void aRenewalThatFailedIsAnsweredFromMemoryForAMomentThenAskedAgain(@TempDir Path dir) {

        // Every request of the task asks for the token: after a refusal each one opened the vault
        // and spent the dead refresh token at the service again, one after another behind the lock.
        // Asked again after a moment, so a sign-in made anew reaches a running broker.
        status.set(400);
        answer.set("{\"error\":\"invalid_grant\"}");
        final VaultFile vault = vault(dir, signIn(NOW.plusSeconds(30)), true);
        final Instant[] now = {NOW};
        final Clock clock =
                new Clock() {
                    @Override
                    public java.time.ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(final java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return now[0];
                    }
                };
        final LoginRenewal renewal =
                new LoginRenewal(
                        "anthropic",
                        vault.read(PASSPHRASE).get("anthropic"),
                        vault,
                        () -> VaultFile.Opener.passphrase(PASSPHRASE),
                        HttpClient.newHttpClient(),
                        clock,
                        new PrintWriter(err));

        assertThatThrownBy(renewal::current).isInstanceOf(TokenPurchase.Ended.class);
        assertThatThrownBy(renewal::current)
                .as("the same answer, a moment later")
                .isInstanceOf(TokenPurchase.Ended.class)
                .hasMessageContaining("sign in again");
        assertThat(bodies).as("the service is asked once for both").hasSize(1);

        now[0] = NOW.plusSeconds(6);
        assertThatThrownBy(renewal::current).isInstanceOf(TokenPurchase.Ended.class);
        assertThat(bodies).as("asked again once the moment has passed").hasSize(2);
    }

    @Test
    void refreshFormCarriesClientSecretWhenTheSignInHadOneAndOmitsItWhenItHadNone(@TempDir Path dir)
            throws Exception {

        // A desktop OAuth client must send client_secret alongside client_id when renewing; a
        // public PKCE client sends none.
        final Map<String, String> withSecret =
                new LinkedHashMap<>(signIn(NOW.plusSeconds(30)).attributes());
        withSecret.put(Credential.CLIENT_SECRET, "client-secret-1");
        final VaultFile withSecretVault =
                vault(
                        dir.resolve("with-secret"),
                        new Credential("oauth", "at-1", withSecret),
                        true);

        assertThat(renewal(withSecretVault, NOW).current())
                .as("renewed with client secret")
                .isEqualTo("at-2");
        assertThat(bodies)
                .as("refresh form for a sign-in with a client secret")
                .singleElement()
                .asString()
                .contains("client_secret=client-secret-1");

        bodies.clear();
        final VaultFile withoutSecretVault =
                vault(dir.resolve("without-secret"), signIn(NOW.plusSeconds(30)), true);

        assertThat(renewal(withoutSecretVault, NOW).current())
                .as("renewed without client secret")
                .isEqualTo("at-2");
        assertThat(bodies)
                .as("refresh form for a sign-in without a client secret")
                .singleElement()
                .asString()
                .doesNotContain("client_secret");
    }

    @Test
    void aSignInMarkedDueRenewsOnItsFirstRequestOnceAndReportsItsNewEnd(@TempDir final Path dir)
            throws Exception {
        // A forced renewal must keep the rotated grant without exposing credentials.
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                arguments -> 0)
                        .asking(prompt -> PASSPHRASE.clone());
        final Map<String, String> attributes =
                new LinkedHashMap<>(signIn(NOW.plusSeconds(10 * 86400)).attributes());
        attributes.put(Credential.CLIENT_SECRET, "client-secret-1");
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        LoginCredentials.put(
                entries, "anthropic", new Credential("oauth", "at-1", attributes), true);
        final VaultFile vault = context.vault();
        vault.write(entries, PASSPHRASE);
        final StringWriter out = new StringWriter();
        final StringWriter commandErr = new StringWriter();
        final VaultRenewCommand command = new VaultRenewCommand(Clock.fixed(NOW, ZoneOffset.UTC));
        command.setContext(context);
        final CommandLine cli = new CommandLine(command);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(commandErr));
        assertThat(cli.execute("anthropic")).as("the command marks the sign-in due").isZero();
        assertThat(vault.read(PASSPHRASE).get("anthropic").settings())
                .as("the broker starts from the marked entry")
                .containsEntry("expires_at", NOW.toString());
        final LoginRenewal renewal = renewal(vault, NOW);

        assertThat(renewal.current().equals("at-2"))
                .as("the first request uses the renewed token")
                .isTrue();

        assertThat(bodies.size())
                .as("the first request makes exactly one token purchase")
                .isEqualTo(1);
        final Map<String, VaultEntry> kept = vault.read(PASSPHRASE);
        assertThat(kept.get(TaskSecrets.GRANT_PREFIX + "anthropic").value().equals("rt-2"))
                .as("the rotated refresh token is written back")
                .isTrue();
        assertThat(kept.get("anthropic").settings())
                .as("the stored token has the new end")
                .containsEntry("expires_at", NOW.plusSeconds(28800).toString());
        assertNoSecretValues(out.toString(), commandErr.toString(), err.toString());
        assertThat(err.toString())
                .as("a successful renewal is reported once with its new end")
                .isEqualTo(
                        "porelo: renewed the sign-in for 'anthropic' on the host; it now ends 2026-10-01T20:00:00Z"
                                + System.lineSeparator());
        err.getBuffer().setLength(0);

        assertThat(renewal.current().equals("at-2"))
                .as("the next request reuses the good token")
                .isTrue();

        assertThat(bodies.size()).as("a good token is not bought again").isEqualTo(1);
        assertThat(err.toString()).as("using a good token reports no renewal").isEmpty();
        assertNoSecretValues(err.toString());
    }

    @Test
    void aTokenAlreadyRenewedByAnotherBrokerIsUsedWithoutARequestOrRenewalLine(
            @TempDir final Path dir) throws Exception {
        // A second broker must not spend a rotated grant again or claim the first broker's renewal.
        final VaultFile vault = vault(dir, signIn(NOW), true);
        final LoginRenewal renewal = renewal(vault, NOW);
        vault.update(
                PASSPHRASE,
                entries -> {
                    final VaultEntry old = entries.get("anthropic");
                    final Map<String, String> settings = new LinkedHashMap<>(old.settings());
                    settings.put("expires_at", NOW.plusSeconds(28800).toString());
                    entries.put("anthropic", new VaultEntry("at-2", old.type(), settings));
                    entries.put(
                            TaskSecrets.GRANT_PREFIX + "anthropic",
                            new VaultEntry("rt-2", "refresh-token"));
                    return entries;
                });

        assertThat(renewal.current().equals("at-2"))
                .as("the broker adopts the token now in the vault")
                .isTrue();

        assertThat(bodies.size()).as("the stand-in receives no token request").isZero();
        assertNoSecretValues(err.toString());
        assertThat(err.toString())
                .as("adopting another broker's token reports no renewal")
                .isEmpty();
    }

    @Test
    void aFailedRenewalPrintsNoSuccessOrSecretValue(@TempDir final Path dir) {
        // A service's error can carry secrets, and claiming renewal would hide a broken sign-in.
        status.set(400);
        answer.set(
                "{\"error\":\"invalid_grant\",\"error_description\":\"at-1 rt-1 client-secret-1\"}");
        final VaultFile vault = vault(dir, signIn(NOW), true);

        assertThatThrownBy(() -> renewal(vault, NOW).current())
                .as("the existing refusal is preserved")
                .isInstanceOf(TokenPurchase.Ended.class)
                .hasMessageContaining("sign in again with 'porelo vault login'")
                .satisfies(failure -> assertNoSecretValues(failure.getMessage()));

        assertNoSecretValues(err.toString());
        assertThat(err.toString()).as("a failed renewal reports no success").isEmpty();
    }

    @Test
    void aRenewalsOutputNeverExposesAnyCredentialValue(@TempDir final Path dir) throws Exception {
        // A broker's standard error is saved with the task and must never hold its credentials.
        final Map<String, String> attributes = new LinkedHashMap<>(signIn(NOW).attributes());
        attributes.put(Credential.CLIENT_SECRET, "client-secret-1");
        final VaultFile vault = vault(dir, new Credential("oauth", "at-1", attributes), true);

        renewal(vault, NOW).current();

        assertNoSecretValues(err.toString());
    }

    private static void assertNoSecretValues(final String... streams) {
        for (final String stream : streams) {
            for (final String secret :
                    new String[] {"at-1", "at-2", "rt-1", "rt-2", "client-secret-1"}) {
                assertThat(stream.contains(secret))
                        .as("no output exposes a sign-in secret")
                        .isFalse();
            }
        }
    }
}
