package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.porelo.core.credential.Credential;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Prevents an inspection or unlock action from creating an unrelated vault entry. */
class CredentialRemedyTest {
    @ParameterizedTest
    @EnumSource(
            value = CredentialDeclarations.Outcome.class,
            names = {"VAULT_LOCKED", "NO_VAULT", "KEY_REFUSED"})
    void onlyAMissingOrExpiredVaultValueIsOfferedForStorage(
            CredentialDeclarations.Outcome outcome) {
        final Credential credential =
                new Credential(
                        "forge",
                        Credential.Kind.TOKEN,
                        "https://forge.example.org",
                        null,
                        "git",
                        Credential.Source.VAULT);
        final var check =
                new CredentialDeclarations.Check(
                        outcome,
                        credential,
                        outcome == CredentialDeclarations.Outcome.VAULT_LOCKED
                                ? "porelo vault unlock"
                                : "porelo credentials list",
                        "not ready");
        final Offer offer = offer();

        assertThat(CredentialsCheckCommand.store(check, offer))
                .as("a remedy that does not store a value must not run vault put")
                .isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = Credential.Source.class,
            names = {"AGENT", "FILE", "ENVIRONMENT"})
    void aValueOwnedElsewhereIsNeverOfferedForVaultStorage(Credential.Source source) {
        final Credential credential =
                new Credential(
                        "forge",
                        Credential.Kind.TOKEN,
                        "https://forge.example.org",
                        null,
                        "git",
                        source);
        final var check =
                new CredentialDeclarations.Check(
                        CredentialDeclarations.Outcome.MISSING_VALUE,
                        credential,
                        "ssh-add -l",
                        "not ready");

        assertThat(CredentialsCheckCommand.store(check, offer()))
                .as("restoring another source must not create a vault copy")
                .isNull();
    }

    private Offer offer() {
        return new Offer(
                question -> "y", false, false, new java.io.PrintWriter(new java.io.StringWriter()));
    }
}
