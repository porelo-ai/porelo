package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An agent says how it takes a reasoning effort and which values; Porelo hands one over so. */
class AgentEffortTest {

    private static final String BASE =
            """
            name: example
            binary: example-cli

            git_identity:
              name: Example
              email: noreply@example.com

            headless:
              prompt_flag: ""
              model_flag: "--model"

            sandboxed:
              arguments: ["--yolo"]
            """;

    private static AgentDefinition read(String yaml) {
        return AgentDefinitionReader.read(new StringReader(yaml), "t.yaml");
    }

    private static final String CODEX_LIKE =
            BASE
                    + """

            effort:
              arguments: ["-c", "model_reasoning_effort={effort}"]
              values: [none, low, medium, high]
            """;

    @Test
    void aDeclaredEffortIsReadWithItsValuesInTheirOrder() {
        final AgentDefinition definition = read(CODEX_LIKE);

        assertThat(definition.effort().values()).containsExactly("none", "low", "medium", "high");
        assertThat(definition.effort().arguments())
                .containsExactly("-c", "model_reasoning_effort={effort}");
    }

    @Test
    void anAgentThatDeclaresNoneTakesNoEffort() {
        assertThat(read(BASE).effort().values()).isEmpty();
        assertThat(read(BASE).efforted(List.of("example-cli", "--yolo", "go"), null))
                .containsExactly("example-cli", "--yolo", "go");
    }

    @Test
    void theEffortGoesBehindTheSandboxedArgumentsSoAPositionalPromptStaysLast() {
        assertThat(read(CODEX_LIKE).efforted(List.of("example-cli", "--yolo", "do it"), "high"))
                .containsExactly(
                        "example-cli", "--yolo", "-c", "model_reasoning_effort=high", "do it");
    }

    @Test
    void noEffortAddsNothingEvenWhereTheAgentTakesOne() {
        assertThat(read(CODEX_LIKE).efforted(List.of("example-cli", "--yolo", "do it"), null))
                .containsExactly("example-cli", "--yolo", "do it");
    }

    @Test
    void aValueTheAgentDoesNotListIsRefusedNamingTheOnesItTakes() {
        assertThatThrownBy(
                        () ->
                                read(CODEX_LIKE)
                                        .efforted(List.of("example-cli", "--yolo", "go"), "max"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("'max'")
                .hasMessageContaining("none, low, medium, high");
        assertThatThrownBy(() -> read(BASE).efforted(List.of("example-cli", "go"), "high"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("takes no effort");
    }

    @Test
    void aSectionWithoutThePlaceOfTheValueOrWithoutValuesIsRefused() {
        assertThatThrownBy(
                        () ->
                                read(
                                        BASE
                                                + """

                effort:
                  arguments: ["--effort"]
                  values: [low]
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("{effort}");
        assertThatThrownBy(
                        () ->
                                read(
                                        BASE
                                                + """

                effort:
                  arguments: ["--effort", "{effort}"]
                  values: []
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("values");
        assertThatThrownBy(
                        () ->
                                read(
                                        BASE
                                                + """

                effort:
                  arguments: ["--effort", "{effort}"]
                  values: [low, "a b"]
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("'a b'");
        assertThatThrownBy(
                        () ->
                                read(
                                        BASE
                                                + """

                effort:
                  arguments: ["--effort", "{effort}"]
                  values: [low]
                  default: low
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("default");
    }

    @Test
    void aBareOffThatYamlReadsAsFalseIsRefusedRatherThanOfferedAsFalse() {
        // YAML 1.1 reads off, on, yes and no as booleans: taken as text, an agent would be offered
        // "false" and started with --thinking false.
        assertThatThrownBy(
                        () ->
                                read(
                                        BASE
                                                + """

                effort:
                  arguments: ["--thinking", "{effort}"]
                  values: [off, low]
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("quote");
        assertThat(
                        read(BASE
                                        + """

                effort:
                  arguments: ["--thinking", "{effort}"]
                  values: ["off", low]
                """)
                                .effort()
                                .values())
                .containsExactly("off", "low");
    }

    @Test
    void theAgentsOwnDescriptionCarriesItsEffort() {
        final AgentDefinition definition = read(CODEX_LIKE);

        final AgentDefinition back =
                AgentDefinitionJson.read(AgentDefinitionJson.write(definition));

        assertThat(back.effort()).isEqualTo(definition.effort());
        assertThat(
                        AgentDefinitionJson.read(AgentDefinitionJson.write(read(BASE)))
                                .effort()
                                .values())
                .as("an adapter older than the field describes none")
                .isEmpty();
    }
}
