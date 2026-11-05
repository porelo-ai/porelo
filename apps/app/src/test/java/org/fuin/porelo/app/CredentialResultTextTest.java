package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a credential's result prints of itself, as its length is for its owner alone. */
class CredentialResultTextTest {

    private static final String SECRET = "s3cret-value";

    @Test
    void aTasksTokensPrintNoToken() {
        final TaskSecrets.Tokens tokens =
                new TaskSecrets.Tokens(SECRET, SECRET, Map.of("route", SECRET));

        assertThat(tokens.toString()).contains("route").doesNotContain(SECRET);
    }

    @Test
    void aHelperDoesNotPrintItsEnvironment() {
        final TaskHelpers.Helper helper =
                new TaskHelpers.Helper("h", List.of("run"), Map.of("KEY", SECRET), "before");

        assertThat(helper.toString()).contains("before").doesNotContain(SECRET);
    }

    @Test
    void aCredentialsPlumbingDoesNotPrintItsEnvironment() {
        final CredentialWiring.CredentialPlumbing plumbing =
                new CredentialWiring.CredentialPlumbing(
                        Path.of("/run/s"), "api.example", Map.of("KEY", SECRET), false);

        assertThat(plumbing.toString()).contains("api.example").doesNotContain(SECRET);
    }

    @Test
    void aRunningTaskDoesNotPrintItsEnvironment() {
        final TaskLaunch.Running running =
                new TaskLaunch.Running(
                        null, null, null, "porelo-uc-shell", Map.of("KEY", SECRET), null, null);

        assertThat(running.toString()).contains("porelo-uc-shell").doesNotContain(SECRET);
    }

    @Test
    void aTransportsActingDoesNotPrintItsEnvironment() {
        final TransportSend.Acting acting =
                new TransportSend.Acting(Map.of("KEY", SECRET), "to", Map.of("a", SECRET));

        assertThat(acting.toString()).contains("to").doesNotContain(SECRET);
    }

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
