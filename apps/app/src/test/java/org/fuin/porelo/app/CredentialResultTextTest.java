package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What a credential's result prints of itself, as its length is for its owner alone. */
class CredentialResultTextTest {

    @Test
    void anImportsResultDoesNotPrintTheLength() {
        final CredentialImport.Result result =
                new CredentialImport.Result(
                        CredentialImport.Outcome.values()[0], "a", "api-key", 137, "file", "");

        assertThat(result.toString()).contains("api-key").doesNotContain("137");
    }

    @Test
    void aLoginsResultDoesNotPrintTheLength() {
        final AgentLogin.Result result =
                new AgentLogin.Result(AgentLogin.Outcome.STORED, "a", "api-key", 137, "");

        assertThat(result.toString()).contains("api-key").doesNotContain("137");
    }
}
