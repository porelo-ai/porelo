package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code parameters} a project declares: a value a start hands in names a task's
 * branch at the forge and reaches the agent, so one the declaration does not allow must never reach
 * a task.
 */
class ProjectParametersTest {

    private static final String HEAD =
            """
            project:
              name: "acme"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    private static Project read(String parameters) {
        return ProjectReader.read(new StringReader(HEAD + parameters), "project.yml");
    }

    private static final String DECLARED =
            """
            parameters:
              type:
                description: "The kind of work"
                values: [feature, bugfix]
                default: feature
              ticket:
                pattern: "[A-Z]+-[0-9]+"
                required: true
                env: "TASK_TICKET"
              note:
                env: none
            """;

    @Test
    void readsEachDeclarationAsWritten() {

        final Parameters parameters = read(DECLARED).parameters();

        assertThat(parameters.declared())
                .as("in the file's order")
                .containsOnlyKeys("type", "ticket", "note");
        assertThat(parameters.declared().get("type").env())
                .as("the default variable")
                .isEqualTo("PORELO_PARAM_TYPE");
        assertThat(parameters.declared().get("ticket").env()).isEqualTo("TASK_TICKET");
        assertThat(parameters.declared().get("note").env()).as("kept out of the task").isNull();
        assertThat(read("").parameters().declared()).as("none declared").isEmpty();
    }

    @Test
    void settlesAStartsValuesWithTheirOrigin() {

        final Map<String, Parameters.Given> resolved =
                read(DECLARED)
                        .parameters()
                        .resolve(Map.of("ticket", new Parameters.Given("ABC-12", "command line")));

        assertThat(resolved)
                .containsExactly(
                        Map.entry("type", new Parameters.Given("feature", Parameters.DEFAULT)),
                        Map.entry("ticket", new Parameters.Given("ABC-12", "command line")));
    }

    @Test
    void refusesAValueItsDeclarationDoesNotAllow() {

        final Parameters parameters = read(DECLARED).parameters();

        assertThatThrownBy(
                        () ->
                                parameters.resolve(
                                        Map.of(
                                                "type",
                                                new Parameters.Given("hotfix", "command line"),
                                                "ticket",
                                                new Parameters.Given("ABC-1", "environment"))))
                .as("outside its values")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("[feature, bugfix]");
        assertThatThrownBy(
                        () ->
                                parameters.resolve(
                                        Map.of(
                                                "ticket",
                                                new Parameters.Given("abc", "environment"))))
                .as("outside its pattern")
                .hasMessageContaining("[A-Z]+-[0-9]+");
        assertThatThrownBy(() -> parameters.resolve(Map.of()))
                .as("a required one missing")
                .hasMessageContaining("requires [ticket]");
        assertThatThrownBy(
                        () ->
                                parameters.resolve(
                                        Map.of(
                                                "ticket",
                                                new Parameters.Given("ABC-1", "command line"),
                                                "colour",
                                                new Parameters.Given("red", "command line"))))
                .as("one nobody declared")
                .hasMessageContaining("no parameter 'colour'");
        assertThatThrownBy(
                        () ->
                                parameters.resolve(
                                        Map.of(
                                                "ticket",
                                                new Parameters.Given("ABC-1\nX", "file x.yml"))))
                .as("a line break, which would reach the task's environment")
                .hasMessageContaining("control character");
    }

    @Test
    void fillsASettingsPlacesAndRefusesOneNoValueFills() {

        // A place left as written would name a branch at the forge that nobody looks for.
        final Parameters parameters = read(DECLARED).parameters();
        final Map<String, Parameters.Given> values =
                parameters.resolve(
                        Map.of("ticket", new Parameters.Given("ABC-12", "command line")));

        assertThat(parameters.fill("{type}/{ticket}-{task}", values, "fix", "acme", "web"))
                .isEqualTo("feature/ABC-12-fix");
        assertThat(parameters.fill("{project}/{repository}", values, "fix", "acme", "web"))
                .isEqualTo("acme/web");
        assertThatThrownBy(() -> parameters.fill("{tpye}/{task}", values, "fix", "acme", "web"))
                .as("a misspelt place")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'{tpye}'");
        assertThatThrownBy(() -> parameters.fill("{note}", values, "fix", "acme", "web"))
                .as("a declared one with no value")
                .hasMessageContaining("has no value");
    }

    @Test
    void parameterVariablesCannotReplaceTaskVariablesButKeepTheirOwnNamespace() {

        for (final String name : List.of("PATH", "HOME", "LANG", "PORELO_X")) {
            assertThatThrownBy(() -> new Parameter("x", "", null, null, null, false, name))
                    .as("%s must not replace the task's tools, home, locale, or credentials", name)
                    .isInstanceOf(ProjectException.class);
        }
        assertThat(new Parameter("x", "", null, null, null, false, "PORELO_PARAM_X").env())
                .as("the parameter namespace must remain available to project values")
                .isEqualTo("PORELO_PARAM_X");
    }

    @Test
    void refusesADeclarationThatCannotWork() {

        for (final String broken :
                new String[] {
                    "parameters:\n  type:\n    values: [a]\n    pattern: \"a\"\n",
                    "parameters:\n  type:\n    values: [a]\n    default: b\n",
                    "parameters:\n  Type:\n    values: [a]\n",
                    "parameters:\n  type:\n    env: \"A B\"\n",
                    "parameters:\n  type:\n    env: PATH\n",
                    "parameters:\n  type:\n    env: PORELO_TASK_REF\n",
                    "parameters:\n  one:\n    env: SAME\n  two:\n    env: SAME\n",
                    "repositories:\n  web:\n    parameters: {}\n"
                }) {
            assertThatThrownBy(() -> read(broken)).as(broken).isInstanceOf(ProjectException.class);
        }
        // A key no declaration knows is reported, as everywhere in the file, never silently taken.
        assertThat(
                        ProjectReader.unknownKeys(
                                new StringReader(HEAD + "parameters:\n  type:\n    colour: red\n"),
                                "project.yml"))
                .contains("parameters.type.colour");
    }
}
