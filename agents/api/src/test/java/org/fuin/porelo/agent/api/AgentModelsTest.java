package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Keeps unusable model choices from crossing the agent definition contract. */
class AgentModelsTest {

    private static final String BASE =
            """
            name: example
            binary: example-cli
            git_identity:
              name: Example
              email: noreply@example.com
            headless:
              prompt_flag: ""
            """;

    private static AgentDefinition read(String models) {
        return AgentDefinitionReader.read(new StringReader(BASE + models), "models.yaml");
    }

    @Test
    void modelsKeepProviderAndModelOrderAcrossTheAgentsOwnDescription() {
        final AgentDefinition definition =
                read(
                        """
                     models:
                       second:
                         - {id: large, name: Large Model}
                         - {id: small, name: Small Model}
                       first:
                         - {id: large, name: Another Large Model}
                     """);
        final Map<String, Object> description = AgentDefinitionJson.write(definition);
        final Map<?, ?> models = (Map<?, ?>) description.get("models");

        assertThat(models).as("provider order is the definition's order").isNotNull();
        assertThat(models.keySet().stream().map(Object::toString).toList())
                .as("providers are not sorted or merged")
                .containsExactly("second", "first");
        assertThat(models.get("second"))
                .as("ids and display names keep their order and spelling")
                .isEqualTo(
                        List.of(
                                Map.of("id", "large", "name", "Large Model"),
                                Map.of("id", "small", "name", "Small Model")));
        assertThat(AgentDefinitionJson.write(AgentDefinitionJson.read(description)).get("models"))
                .as("the process description carries the same provider model lists")
                .isEqualTo(models);
    }

    private static Stream<Arguments> malformed() {
        return Stream.of(
                Arguments.of("models: []", "models"),
                Arguments.of("models: null", "models"),
                Arguments.of("models: {Bad: [{id: large, name: Large}]}", "models.Bad"),
                Arguments.of("models: {'': [{id: large, name: Large}]}", "models."),
                Arguments.of("models: {123: [{id: large, name: Large}]}", "models.123"),
                Arguments.of("models: {p: []}", "models.p"),
                Arguments.of("models: {p: {id: large, name: Large}}", "models.p"),
                Arguments.of("models: {p: [large]}", "models.p"),
                Arguments.of("models: {p: [{id: '', name: Large}]}", "models.p.id"),
                Arguments.of("models: {p: [{id: ' ', name: Large}]}", "models.p.id"),
                Arguments.of("models: {p: [{name: Large}]}", "models.p.id"),
                Arguments.of("models: {p: [{id: 123, name: Large}]}", "models.p.id"),
                Arguments.of("models: {p: [{id: off, name: Large}]}", "models.p.id"),
                Arguments.of("models: {p: [{id: large, name: 123}]}", "models.p.name"),
                Arguments.of("models: {p: [{id: large, name: off}]}", "models.p.name"),
                Arguments.of("models: {p: [{id: large}]}", "models.p.name"),
                Arguments.of("models: {p: [{id: large, name: Large, default: true}]}", "default"),
                Arguments.of(
                        "models: {p: [{id: large, name: A}, {id: large, name: B}]}", "models.p.id"),
                // Harm: an empty section reads as no list at all, so a typo hides the agent's menu.
                Arguments.of("models: {}", "models"),
                // Harm: what reaches the window and a terminal is plain text of a bounded size.
                Arguments.of("models: {p: [{id: large, name: ' '}]}", "models.p.name"),
                Arguments.of("models: {p: [{id: large, name: \"L\\e[31m\"}]}", "models.p.name"),
                Arguments.of("models: {p: [{id: large, name: \"L\\u202Earge\"}]}", "models.p.name"),
                Arguments.of("models: {p: [{id: \"lar\\nge\", name: Large}]}", "models.p.id"),
                Arguments.of(
                        "models: {p: [{id: large, name: " + "n".repeat(101) + "}]}",
                        "models.p.name"),
                Arguments.of(
                        "models: {p: [{id: " + "i".repeat(201) + ", name: Large}]}", "models.p.id"),
                Arguments.of(
                        "models: {p: ["
                                + java.util.stream.IntStream.rangeClosed(1, 101)
                                        .mapToObj(n -> "{id: m" + n + ", name: M" + n + "}")
                                        .collect(java.util.stream.Collectors.joining(", "))
                                + "]}",
                        "models.p"));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void malformedModelsAreRefusedNamingTheirMember(String yaml, String member) {
        assertThatThrownBy(() -> read(yaml))
                .as("a malformed choice must not be offered to a person")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("models.yaml")
                .hasMessageContaining(member);
    }

    @Test
    void aMalformedDescriptionIsRefusedInsteadOfSilentlyLosingItsModels() {
        final Map<String, Object> description =
                new LinkedHashMap<>(AgentDefinitionJson.write(read("")));
        description.put("models", Map.of("p", List.of(Map.of("id", "large", "name", 123))));

        assertThatThrownBy(() -> AgentDefinitionJson.read(description))
                .as("a process description is checked as a file is")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("models.p.name");
    }

    @Test
    void anOlderDescriptionDeclaresNoModels() {
        final Map<String, Object> description =
                new LinkedHashMap<>(AgentDefinitionJson.write(read("")));
        description.remove("models");

        assertThat(AgentDefinitionJson.read(description).models())
                .as("absence remains compatible with an older adapter")
                .isEqualTo(AgentModels.NONE);
        assertThat(AgentDefinitionJson.write(AgentDefinitionJson.read(description)))
                .as("an agent with no models describes none, which reads back")
                .doesNotContainKey("models");
    }

    private static Stream<Arguments> malformedConstructors() {
        return Stream.of(
                Arguments.of(
                        (org.assertj.core.api.ThrowableAssert.ThrowingCallable)
                                () -> new AgentModels.Model(null, "Name"),
                        "models.id"),
                Arguments.of(
                        (org.assertj.core.api.ThrowableAssert.ThrowingCallable)
                                () -> new AgentModels.Model("id", null),
                        "models.name"),
                Arguments.of(
                        (org.assertj.core.api.ThrowableAssert.ThrowingCallable)
                                () -> new AgentModels(null),
                        "models"),
                Arguments.of(
                        (org.assertj.core.api.ThrowableAssert.ThrowingCallable)
                                () ->
                                        new AgentModels(
                                                java.util.Collections.singletonMap(
                                                        null,
                                                        List.of(
                                                                new AgentModels.Model(
                                                                        "id", "Name")))),
                        "models.null"),
                Arguments.of(
                        (org.assertj.core.api.ThrowableAssert.ThrowingCallable)
                                () ->
                                        new AgentModels(
                                                java.util.Collections.singletonMap("p", null)),
                        "models.p"),
                Arguments.of(
                        (org.assertj.core.api.ThrowableAssert.ThrowingCallable)
                                () ->
                                        new AgentModels(
                                                Map.of(
                                                        "p",
                                                        java.util.Collections.singletonList(null))),
                        "models.p"));
    }

    @ParameterizedTest
    @MethodSource("malformedConstructors")
    void aProgrammaticallyDeclaredModelIsRefusedBeforeItCanCrashTheDescription(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable construct, String member) {
        assertThatThrownBy(construct)
                .as("an adapter's invalid model must be refused with its member instead of an NPE")
                .isInstanceOf(AgentException.class)
                .hasMessageContaining(member);
    }
}
