package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.agent.api.Credential;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for keeping an agent's sign-in read from a directory: kept as a sign-in made here is, for a
 * test against a stand-in token endpoint, without a person.
 */
class AgentLoginFromDirectoryTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private static Credential signIn() {
        return new Credential(
                "oauth",
                "at-1",
                Map.of(
                        Credential.REFRESH_TOKEN,
                        "rt-1",
                        Credential.EXPIRES_AT,
                        "1790000000000",
                        Credential.TOKEN_URL,
                        "https://auth.example.com/oauth/token",
                        Credential.CLIENT_ID,
                        "the-agents-client"));
    }

    private static VaultFile emptyVault(final Path dir) {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(new java.util.LinkedHashMap<>(), PASSPHRASE);
        return vault;
    }

    private static AgentLogin.Result keep(
            final VaultFile vault, final Optional<Credential> found, final String tokenUrl) {
        return AgentLogin.keepFrom(
                vault,
                VaultFile.Opener.passphrase(PASSPHRASE),
                "anthropic",
                found,
                tokenUrl,
                Set.of(),
                Path.of("/run/kit/credentials"));
    }

    @Test
    void aSignInReadFromADirectoryIsKeptRenewableAsAfterASignInMadeHere(@TempDir Path dir) {
        final VaultFile vault = emptyVault(dir);

        final AgentLogin.Result result = keep(vault, Optional.of(signIn()), null);

        final Map<String, VaultEntry> entries = vault.read(PASSPHRASE);
        assertThat(result.outcome()).as("it was kept").isEqualTo(AgentLogin.Outcome.STORED);
        assertThat(LoginCredentials.renewable(entries.get("anthropic")))
                .as("renewable on the host, unlike an import of the host agent's own files")
                .isTrue();
        assertThat(entries.get(TaskSecrets.GRANT_PREFIX + "anthropic").value())
                .as("the refresh token is hidden beside the entry")
                .isEqualTo("rt-1");
        assertThat(entries.get("anthropic").settings())
                .as("the agent's own endpoint, when no stand-in is named")
                .containsEntry("token_url", "https://auth.example.com/oauth/token");
    }

    @Test
    void aStandInsTokenEndpointReplacesTheAgents(@TempDir Path dir) {
        final VaultFile vault = emptyVault(dir);

        keep(vault, Optional.of(signIn()), "http://127.0.0.1:8017/oauth/token");

        assertThat(vault.read(PASSPHRASE).get("anthropic").settings())
                .as("the renewal goes to the stand-in, never to the endpoint the file named")
                .containsEntry("token_url", "http://127.0.0.1:8017/oauth/token");
    }

    @Test
    void aDirectoryWithNoSignInKeepsNothing(@TempDir Path dir) {
        final VaultFile vault = emptyVault(dir);

        final AgentLogin.Result result = keep(vault, Optional.empty(), null);

        assertThat(result.outcome())
                .as("nothing found is said, not taken for a sign-in")
                .isEqualTo(AgentLogin.Outcome.NOTHING_TO_COLLECT);
        assertThat(result.detail()).as("it names the directory").contains("/run/kit/credentials");
        assertThat(vault.read(PASSPHRASE)).as("nothing was kept").isEmpty();
    }

    @Test
    void aStandInIsPlainHttpOnALiteralLoopbackAddress() {
        for (final String good : new String[] {"http://127.0.0.1:8080/token", "http://[::1]:9/t"}) {
            assertThat(AgentLogin.standInTokenUrl(good)).as("'%s'", good).isNull();
        }
        for (final String bad :
                new String[] {
                    "https://127.0.0.1/token",
                    "http://localhost/token",
                    "http://example.com/token",
                    "http://10.0.0.1/token",
                    "http://user@127.0.0.1/token",
                    "ftp://127.0.0.1/token",
                    "http://127.0.0.2/token",
                    "http://[0:0:0:0:0:0:0:1]/token",
                    "not a url"
                }) {
            assertThat(AgentLogin.standInTokenUrl(bad))
                    .as("'%s' would send the refresh token off this machine or to a name", bad)
                    .isNotNull();
        }
    }

    @Test
    void aTokenEndpointWithoutADirectoryOrOffThisMachineIsRefusedBeforeAnythingIsKept(
            @TempDir Path dir) {
        final StringWriter err = new StringWriter();

        final int alone =
                new CommandLine(new VaultLoginCommand())
                        .setErr(new PrintWriter(err))
                        .execute("an-agent", "--token-url", "http://127.0.0.1:9/token");
        final int offMachine =
                new CommandLine(new VaultLoginCommand())
                        .setErr(new PrintWriter(err))
                        .execute(
                                "an-agent",
                                "--credentials-from",
                                dir.toString(),
                                "--token-url",
                                "https://example.com/token");

        assertThat(alone).as("a stand-in without a directory to read").isEqualTo(64);
        assertThat(offMachine).as("a stand-in off this machine").isEqualTo(64);
        assertThat(err.toString())
                .as("each refusal says nothing was kept or what is missing")
                .contains("give --credentials-from")
                .contains("nothing was kept");
    }

    @Test
    void aSignInWithoutARefreshTokenIsKeptAndSaidNotRenewable(@TempDir Path dir) {
        final VaultFile vault = emptyVault(dir);

        final AgentLogin.Result result =
                keep(vault, Optional.of(new Credential("oauth", "at-1", Map.of())), null);

        assertThat(result.outcome()).as("it is kept").isEqualTo(AgentLogin.Outcome.STORED);
        assertThat(result.detail())
                .as("never said renewable when nothing renews it")
                .contains("not renewable");
    }

    @Test
    void aStandInOffThisMachineIsRefusedByTheKeepingItselfNotOnlyByTheCommand(@TempDir Path dir) {
        final VaultFile vault = emptyVault(dir);

        final AgentLogin.Result result =
                keep(vault, Optional.of(signIn()), "https://example.com/token");

        assertThat(result.outcome())
                .as("a caller that skips the command's check is refused too")
                .isEqualTo(AgentLogin.Outcome.FAILED);
        assertThat(vault.read(PASSPHRASE)).as("nothing was kept").isEmpty();
    }

    @Test
    void aDryRunOrForceBesideADirectoryIsRefusedRatherThanWrittenAnyway(@TempDir Path dir) {
        final StringWriter err = new StringWriter();

        final int code =
                new CommandLine(new VaultLoginCommand())
                        .setErr(new PrintWriter(err))
                        .execute("an-agent", "--credentials-from", dir.toString(), "--dry-run");

        assertThat(code).as("a dry run that would write is refused").isEqualTo(64);
        assertThat(err.toString()).as("it says why").contains("--dry-run");
    }
}
