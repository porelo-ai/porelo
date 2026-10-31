package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Path;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
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
}
