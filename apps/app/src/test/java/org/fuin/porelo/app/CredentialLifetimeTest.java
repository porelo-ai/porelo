package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;

/** Tests for {@link CredentialLifetime}: which entries nothing ends. */
class CredentialLifetimeTest {

    @Test
    void aPlainKeyHasNoEnd() {
        assertThat(CredentialLifetime.endless(VaultEntry.of("a-key"), null)).isTrue();
    }

    @Test
    void anEntryWithItsEndHasOne() {
        assertThat(
                        CredentialLifetime.endless(
                                new VaultEntry(
                                        "a-key",
                                        null,
                                        Map.of("expires_at", "2027-01-31T00:00:00Z")),
                                null))
                .isFalse();
    }

    @Test
    void aRenewableSignInKeepsItsEndAndIsNotMarked() {
        final VaultEntry signIn =
                new VaultEntry(
                        "an-access-token",
                        "oauth",
                        Map.of(
                                "expires_at", "2027-01-31T00:00:00Z",
                                "token_url", "https://auth.example.com/token",
                                "client_id", "a-client"));

        assertThat(CredentialLifetime.endless(signIn, null)).isFalse();
    }

    @Test
    void aRenewableSignInThatNamedNoEndIsNotMarkedEither() {
        // A sign-in whose agent named no end is renewed on its first use, and the renewal writes
        // the end: its access token ends, though no expires_at says so yet.
        final VaultEntry signIn =
                new VaultEntry(
                        "an-access-token",
                        "oauth",
                        Map.of(
                                "token_url", "https://auth.example.com/token",
                                "client_id", "a-client"));

        assertThat(CredentialLifetime.endless(signIn, null)).isFalse();
    }

    @Test
    void aGrantHeldAsAnAccessTokenHasNoEnd() {
        assertThat(
                        CredentialLifetime.endless(
                                new VaultEntry("", "oauth-device", Map.of()),
                                new VaultEntry("a-token", CredentialLifetime.HELD, Map.of())))
                .isTrue();
    }

    @Test
    void aGrantRenewedByARefreshTokenOrNotYetGrantedIsNotMarked() {
        final VaultEntry grant = new VaultEntry("", "oauth-device", Map.of());

        assertThat(
                        CredentialLifetime.endless(
                                grant, new VaultEntry("a-refresh", "refresh-token", Map.of())))
                .isFalse();
        assertThat(CredentialLifetime.endless(grant, null)).as("nobody granted it yet").isFalse();
    }

    @Test
    void anEndThatIsNoInstantIsNamed() {
        assertThat(CredentialLifetime.malformedEnd(Map.of("expires_at", "next week")))
                .contains("not an ISO-8601 instant");
        assertThat(CredentialLifetime.malformedEnd(Map.of("expires_at", "2027-01-31T00:00:00Z")))
                .isNull();
        assertThat(CredentialLifetime.malformedEnd(Map.of())).isNull();
    }
}
