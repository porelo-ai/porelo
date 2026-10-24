package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/** Guards marking a sign-in due without replacing or exposing its credentials. */
class VaultRenewCommandTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private final String token = UUID.randomUUID().toString();

    private final String refresh = UUID.randomUUID().toString();

    private final String clientSecret = UUID.randomUUID().toString();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private PoreloContext context(final Path dir) {
        return new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0)
                .asking(prompt -> PASSPHRASE.clone());
    }

    private Map<String, VaultEntry> entries() {
        return Map.of(
                "chatgpt",
                new VaultEntry(
                        token,
                        "oauth",
                        Map.of(
                                "token_url", "https://auth.example.org/oauth/token",
                                "client_id", "the-agents-client",
                                "expires_at", NOW.plusSeconds(10 * 86400).toString(),
                                "account_id", "the-account")),
                TaskSecrets.GRANT_PREFIX + "chatgpt",
                new VaultEntry(refresh, "refresh-token", Map.of("kept", "grant-setting")),
                TaskSecrets.CLIENT_SECRET_PREFIX + "chatgpt",
                new VaultEntry(clientSecret, "client-secret", Map.of("kept", "client-setting")));
    }

    private int execute(final PoreloContext context, final String name) {
        final VaultRenewCommand command = new VaultRenewCommand(CLOCK);
        command.setContext(context);
        final CommandLine cli = new CommandLine(command);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        return cli.execute(name);
    }

    private void assertNoSecrets() {
        for (final String secret : new String[] {token, refresh, clientSecret}) {
            assertThat(out.toString().contains(secret) || err.toString().contains(secret))
                    .as("neither stream exposes a credential value")
                    .isFalse();
        }
    }

    @Test
    void marksOnlyTheSignInsEndDueAndPreservesEveryCredential(@TempDir final Path dir) {
        // Replacing the entry or its companions loses the sign-in that the broker must renew.
        final PoreloContext context = context(dir);
        final Map<String, VaultEntry> before = entries();
        context.vault().write(before, PASSPHRASE);

        assertThat(execute(context, "chatgpt")).as("marking a renewable sign-in succeeds").isZero();

        final Map<String, VaultEntry> after = context.vault().read(PASSPHRASE);
        assertThat(after.get("chatgpt").settings().get("expires_at"))
                .as("the next broker must see the sign-in as due now")
                .isEqualTo(NOW.toString());
        final Map<String, String> settings = new LinkedHashMap<>(before.get("chatgpt").settings());
        settings.put("expires_at", NOW.toString());
        final Map<String, VaultEntry> expected = new LinkedHashMap<>(before);
        expected.put("chatgpt", new VaultEntry(token, "oauth", settings));
        assertThat(after.equals(expected))
                .as("only expires_at changes; values, types and every other setting survive")
                .isTrue();
        assertThat(out.toString())
                .as("the person knows which task will renew")
                .isEqualTo(
                        "marked 'chatgpt' due: the next task's first request renews it on the host; a running task keeps its token until it ends"
                                + System.lineSeparator());
        assertThat(err.toString()).as("success has no refusal").isEmpty();
        assertNoSecrets();
    }

    @Test
    void refusesAnImportedSignInWithoutChangingTheVault(@TempDir final Path dir) throws Exception {
        // Renewing an imported sign-in would spend the developer's own session's refresh token.
        final Map<String, VaultEntry> imported = new LinkedHashMap<>(entries());
        imported.remove(TaskSecrets.GRANT_PREFIX + "chatgpt");
        assertRefused(
                context(dir),
                imported,
                "chatgpt",
                "imported from this machine's own agent; renewing it would end that agent's session; sign in with 'porelo vault login' to have it renewed here");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api-key", "token"})
    void refusesACredentialThatCannotBeRenewedWithoutChangingTheVault(
            final String type, @TempDir final Path dir) throws Exception {
        // Marking a key or plain token due would promise a renewal that can never happen.
        final Map<String, VaultEntry> entries = new LinkedHashMap<>(entries());
        entries.put("chatgpt", new VaultEntry(token, type, Map.of("kept", "setting")));
        assertRefused(context(dir), entries, "chatgpt", "is not a renewable sign-in");
    }

    @Test
    void refusesAnUnknownNameThroughTheVaultCommandWithoutChangingTheVault(@TempDir final Path dir)
            throws Exception {
        // An absent name must not create an empty credential or claim that a renewal was scheduled.
        final PoreloContext context = context(dir);
        final Map<String, VaultEntry> entries = entries();
        context.vault().write(entries, PASSPHRASE);
        final byte[] before = Files.readAllBytes(context.vault().path());
        final CommandLine cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));

        assertThat(cli.execute("vault", "renew", "absent"))
                .as("unknown names are refused")
                .isEqualTo(2);

        assertThat(err.toString())
                .as("the refusal names the missing entry")
                .contains("the vault holds no 'absent'");
        assertUnchanged(context, entries, before);
    }

    private void assertRefused(
            final PoreloContext context,
            final Map<String, VaultEntry> entries,
            final String name,
            final String reason)
            throws Exception {
        context.vault().write(entries, PASSPHRASE);
        final byte[] before = Files.readAllBytes(context.vault().path());

        assertThat(execute(context, name)).as("an unsafe renewal is refused").isEqualTo(2);

        assertThat(err.toString()).as("the person is told why renewal is refused").contains(reason);
        assertUnchanged(context, entries, before);
    }

    private void assertUnchanged(
            final PoreloContext context, final Map<String, VaultEntry> entries, final byte[] before)
            throws Exception {
        assertThat(context.vault().read(PASSPHRASE).equals(entries))
                .as("a refusal preserves every vault entry")
                .isTrue();
        assertThat(Files.readAllBytes(context.vault().path()))
                .as("a refusal does not rewrite the encrypted vault")
                .isEqualTo(before);
        assertThat(out.toString()).as("a refusal never claims success").isEmpty();
        assertNoSecrets();
    }

    @ParameterizedTest
    @ValueSource(strings = {"own", "imported", "api-key", "token", "absent"})
    void noCommandOutcomeExposesAnyCredential(final String kind, @TempDir final Path dir) {
        // A printed secret persists in terminal scrollback or captured command output.
        final PoreloContext context = context(dir);
        final Map<String, VaultEntry> entries = new LinkedHashMap<>(entries());
        if (kind.equals("imported")) {
            entries.remove(TaskSecrets.GRANT_PREFIX + "chatgpt");
        } else if (kind.equals("api-key") || kind.equals("token")) {
            entries.put("chatgpt", new VaultEntry(token, kind));
        }
        context.vault().write(entries, PASSPHRASE);

        execute(context, kind.equals("absent") ? "absent" : "chatgpt");

        assertNoSecrets();
    }
}
