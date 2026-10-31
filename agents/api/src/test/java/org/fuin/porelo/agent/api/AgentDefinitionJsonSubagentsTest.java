package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests that what an adapter can enforce of its subagents survives its description. */
class AgentDefinitionJsonSubagentsTest {

    private static AgentDefinition definition(String declared) {
        return AgentDefinitionReader.read(
                new StringReader(
                        """
                        name: example
                        binary: example-cli
                        git_identity:
                          name: Example
                          email: noreply@example.com
                        headless:
                          prompt_flag: "-p"
                        """
                                + declared),
                "test.yaml");
    }

    @ParameterizedTest
    @EnumSource(SubagentLimit.class)
    void theDeclarationSurvivesTheWireInBothDirections(SubagentLimit limit) {
        final AgentDefinition declared = definition("subagents: " + limit.value() + "\n");

        final Map<String, Object> written = AgentDefinitionJson.write(declared);
        final AgentDefinition read = AgentDefinitionJson.read(written);

        assertThat(written)
                .as("the adapter sends the declared spelling")
                .containsEntry("subagents", limit.value());
        assertThat(read.subagents()).as("Porelo reads what the adapter declared").isEqualTo(limit);
        assertThat(read).as("the complete definition survives the round trip").isEqualTo(declared);
    }

    @Test
    void anOlderAdapterThatSendsNoneCanHoldOnlyNone() {
        final Map<String, Object> sent =
                new LinkedHashMap<>(AgentDefinitionJson.write(definition("")));
        sent.remove("subagents");

        assertThat(AgentDefinitionJson.read(sent).subagents())
                .as("an adapter older than the field is started only without subagents")
                .isEqualTo(SubagentLimit.OFF);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "on", "COUNT", "depth"})
    void refusesAnUnknownDeclarationFromAnAdapter(String value) {
        final Map<String, Object> sent =
                new LinkedHashMap<>(AgentDefinitionJson.write(definition("")));
        sent.put("subagents", value);

        assertThatThrownBy(() -> AgentDefinitionJson.read(sent))
                .as("a declaration Porelo cannot read must not start a task as if it held")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("subagents");
    }
}
