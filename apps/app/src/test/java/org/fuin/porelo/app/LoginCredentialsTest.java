package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.agent.api.Credential;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link LoginCredentials}: a client secret accompanying a sign-in is kept hidden beside
 * the refresh token, never exposed in settings, and removed when the grant is removed.
 */
class LoginCredentialsTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private static Credential signInWithSecret(final String secret) {
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(Credential.REFRESH_TOKEN, "rt-1");
        attributes.put(Credential.EXPIRES_AT, "1790856000000");
        attributes.put(Credential.TOKEN_URL, "https://oauth2.googleapis.com/token");
        attributes.put(Credential.CLIENT_ID, "client-id-1");
        attributes.put(Credential.CLIENT_SECRET, secret);
        return new Credential("oauth", "at-1", attributes);
    }

    @Test
    void clientSecretIsAHiddenEntryNeverASettingAndIsRemovedWithTheGrant(@TempDir Path dir) {

        // Settings are shown by 'vault list'; the client secret is kept in a hidden entry beside
        // the refresh token and removed whenever the grant entry is removed.
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        LoginCredentials.put(entries, "google-sign-in", signInWithSecret("client-secret-1"), true);

        assertThat(entries.get("google-sign-in").settings())
                .as("client secret is never stored as a setting")
                .doesNotContainKey(Credential.CLIENT_SECRET)
                .doesNotContainKey("client_secret")
                .doesNotContainValue("client-secret-1");
        assertThat(entries.get(TaskSecrets.CLIENT_SECRET_PREFIX + "google-sign-in"))
                .as("client secret is stored in its own hidden entry")
                .isNotNull()
                .extracting(VaultEntry::value)
                .isEqualTo("client-secret-1");
        assertThat(TaskSecrets.reserved(TaskSecrets.CLIENT_SECRET_PREFIX + "google-sign-in"))
                .as("client secret prefix is reserved")
                .isTrue();
        assertThat(TaskSecrets.credentialsOnly(entries))
                .as("credentialsOnly hides the client secret entry")
                .containsOnlyKeys("google-sign-in");

        // Replacing with an imported (non-renewable) sign-in removes the grant and the client
        // secret with it.
        LoginCredentials.put(entries, "google-sign-in", signInWithSecret("client-secret-1"), false);
        assertThat(entries)
                .as("non-renewable sign-in removes both the grant and the client secret")
                .doesNotContainKey(TaskSecrets.GRANT_PREFIX + "google-sign-in")
                .doesNotContainKey(TaskSecrets.CLIENT_SECRET_PREFIX + "google-sign-in");

        // Removing the credential via 'vault remove' also removes the hidden client secret entry.
        Assumptions.assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            final Map<String, VaultEntry> stored = new LinkedHashMap<>();
            LoginCredentials.put(
                    stored, "google-sign-in", signInWithSecret("client-secret-1"), true);
            context.vault().write(stored, PASSPHRASE);
            keyring.store(PASSPHRASE);

            final CommandLine cmd = PoreloCli.commandLine(context);
            cmd.setOut(new PrintWriter(new StringWriter()));
            cmd.setErr(new PrintWriter(new StringWriter()));
            assertThat(cmd.execute("vault", "remove", "google-sign-in"))
                    .as("vault remove succeeds")
                    .isZero();
            assertThat(context.vault().read(PASSPHRASE))
                    .as("vault remove removes the credential, the grant and the client secret")
                    .isEmpty();
        } finally {
            keyring.forget();
        }
    }
}
