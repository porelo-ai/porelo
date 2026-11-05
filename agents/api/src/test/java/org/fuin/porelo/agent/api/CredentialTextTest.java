package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Holds what a credential says of itself when something prints it by accident. */
class CredentialTextTest {

    @Test
    void itsTextNamesItsTypeAndNeitherItsSecretNorItsLength() {
        final String secret = "s".repeat(137);

        assertThat(Credential.of("api-key", secret).toString())
                .contains("api-key")
                .doesNotContain(secret.substring(0, 8))
                .doesNotContain("137");
    }
}
