package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
import org.fuin.porelo.agent.api.Finding;
import org.fuin.porelo.app.AgentChoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentEntryTest {

    private static final String MINIMAL =
            """
            name: example
            binary: example-cli

            git_identity:
              name: Example
              email: noreply@example.com

            headless:
              prompt_flag: "-p"
            """;

    private static AgentDefinition read(String yaml) {
        return AgentDefinitionReader.read(new StringReader(yaml), "test.yaml");
    }

    @ParameterizedTest
    @ValueSource(strings = {"paste-code", "device-code", "loopback"})
    void answersTheFlowItsDefinitionNames(String flow) {
        final var entry =
                AgentMethods.entry(
                        "example",
                        read(MINIMAL + "login:\n  flow: " + flow + "\n"),
                        Path.of("/usr/libexec/porelo/agents/example"));

        assertThat(entry)
                .as(
                        "an interface reads the sign-in's flow from the daemon, as agents --json says it")
                .containsEntry("loginFlow", flow);
    }

    @Test
    void leavesTheFlowOutWhenTheDefinitionNamesNone() {
        final var entry =
                AgentMethods.entry(
                        "example", read(MINIMAL), Path.of("/usr/libexec/porelo/agents/example"));

        assertThat(entry)
                .as("an undeclared flow is absent, never a guess")
                .doesNotContainKey("loginFlow")
                .containsEntry("name", "example")
                .containsEntry("canLogIn", false);
    }

    private static Map<String, Object> foundEntry(final boolean throughVault) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        AgentMethods.found(
                entry,
                new AgentChoice.Found(
                        "example",
                        Finding.SignedIn.SIGNED_IN,
                        false,
                        "alpha",
                        null,
                        null,
                        List.of(),
                        null,
                        throughVault));
        return entry;
    }

    @Test
    void anAgentSignedInThroughTheVaultSaysSoBesideItsProvider() {

        // Prevents the window naming a sign-in by the vault as files of the agent's own.
        assertThat(foundEntry(true))
                .as("signed in, to the vault's provider, through the vault")
                .containsEntry("found", "SIGNED_IN")
                .containsEntry("foundProvider", "alpha")
                .containsEntry("foundVia", "vault");
    }

    @Test
    void anAgentSignedInByItsOwnFilesCarriesNoVia() {

        // Prevents every sign-in reading as the vault's, which would make the field say nothing.
        assertThat(foundEntry(false))
                .as("absent where the agent's own sign-in files were found")
                .containsEntry("found", "SIGNED_IN")
                .doesNotContainKey("foundVia");
    }
}
