package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.porelo.app.CredentialDeclarations;
import org.fuin.porelo.core.credential.Credential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** A manual unlock must not become a command that stores an unread credential. */
class CredentialActionTest {
    @ParameterizedTest
    @EnumSource(
            value = Credential.Kind.class,
            names = {"SSH_KEY", "TOKEN"})
    void aMissingValueStillUsesTheFileThePersonSupplied(Credential.Kind kind) {
        final Credential credential =
                new Credential(
                        "forge",
                        kind,
                        kind == Credential.Kind.SSH_KEY
                                ? "ssh://forge.example.org"
                                : "https://forge.example.org",
                        null,
                        "git",
                        Credential.Source.VAULT);
        final var check =
                new CredentialDeclarations.Check(
                        CredentialDeclarations.Outcome.MISSING_VALUE,
                        credential,
                        CredentialDeclarations.storeCommandFor(credential),
                        "the value is missing");

        assertThat(VaultMethods.declarationCommand(check, credential, "/example/credential"))
                .as("the supplied file must not become an interactive request for the value")
                .isEqualTo(
                        CredentialDeclarations.storeCommandFor(credential, "/example/credential"));
    }

    @Test
    void aManualUnlockIsNotReplacedByVaultStorage() {
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
                        CredentialDeclarations.Outcome.VAULT_LOCKED,
                        credential,
                        "",
                        "unlock from the client computer");

        assertThat(VaultMethods.declarationCommand(check, credential, "/example/token"))
                .as("an empty action must preserve a manual unlock instead of inventing vault put")
                .isEmpty();
    }
}
