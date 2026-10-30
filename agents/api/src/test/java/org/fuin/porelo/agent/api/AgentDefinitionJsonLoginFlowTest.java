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

/** Tests that the flow survives the adapter's description crossing the process boundary. */
class AgentDefinitionJsonLoginFlowTest {

    private static AgentDefinition definition() {
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
                        login:
                          arguments: []
                        """),
                "test.yaml");
    }

    @ParameterizedTest
    @EnumSource(LoginFlow.class)
    void theDeclaredFlowSurvivesTheWireInBothDirections(LoginFlow flow) {
        final Map<String, Object> sent =
                new LinkedHashMap<>(AgentDefinitionJson.write(definition()));
        sent.put("loginFlow", flow.value());

        final AgentDefinition read = AgentDefinitionJson.read(sent);
        final Map<String, Object> written = AgentDefinitionJson.write(read);

        assertThat(read.loginFlow()).as("the host receives the adapter's flow").isEqualTo(flow);
        assertThat(written)
                .as("the server sends the declared spelling")
                .containsEntry("loginFlow", flow.value());
        assertThat(AgentDefinitionJson.read(written))
                .as("the complete definition survives the round trip")
                .isEqualTo(read);
    }

    @Test
    void anExplicitNullFlowIsUndeclared() {
        final Map<String, Object> sent =
                new java.util.LinkedHashMap<>(AgentDefinitionJson.write(definition()));
        sent.put("loginFlow", null);

        assertThat(AgentDefinitionJson.read(sent).loginFlow())
                .as("an adapter writing null for an absent field is an older adapter")
                .isNull();
    }

    @Test
    void anOlderAdapterLeavesTheFlowUndeclared() {
        final Map<String, Object> sent = AgentDefinitionJson.write(definition());

        assertThat(sent).as("no invented flow for an older adapter").doesNotContainKey("loginFlow");
        assertThat(AgentDefinitionJson.read(sent).loginFlow())
                .as("the missing wire field keeps the older sign-in behavior")
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "", "LOOPBACK"})
    void refusesAnUnknownFlowFromAnAdapter(String value) {
        final Map<String, Object> sent =
                new LinkedHashMap<>(AgentDefinitionJson.write(definition()));
        sent.put("loginFlow", value);

        assertThatThrownBy(() -> AgentDefinitionJson.read(sent))
                .as("an invalid wire flow must not bypass the sign-in policy")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("login.flow");
    }
}
