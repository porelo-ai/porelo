package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** How many subagents a project lets its tasks' agents run at the same time. */
class ProjectSubagentsTest {

    private static final String HEAD =
            """
            project:
              name: "acme"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    private static Project read(String yaml) {
        return ProjectReader.read(new StringReader(yaml), "test");
    }

    @Test
    void aProjectThatSaysNothingAllowsNone() {
        assertThat(read(HEAD).limits().subagents())
                .as("no subagent unless the project or the start asks for one")
                .isZero();
        assertThat(read(HEAD + "limits:\n  memory: 2g\n").limits().subagents())
                .as("a limits block without the key allows none too")
                .isZero();
    }

    @Test
    void aProjectsNumberIsItsTasksAndARepositorysReplacesItForThatRepository() {
        final Project project =
                read(
                        HEAD
                                + """
                limits:
                  subagents: 2
                repositories:
                  web:
                    limits:
                      subagents: 4
                  docs:
                    limits:
                      memory: 2g
                """);

        assertThat(project.limits().subagents()).as("the project's number").isEqualTo(2);
        assertThat(
                        project.limitsFor(
                                        java.util.Objects.requireNonNull(project.repository("web")))
                                .subagents())
                .as("a repository's own number over the project's")
                .isEqualTo(4);
        assertThat(
                        project.limitsFor(
                                        java.util.Objects.requireNonNull(
                                                project.repository("docs")))
                                .subagents())
                .as("a repository that says nothing keeps the project's, not the default")
                .isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "two", "1.5", "\"2\"", "[]", "99999999999"})
    void aNumberBelowNoneOrNoWholeNumberIsRefused(String value) {
        assertThatThrownBy(() -> read(HEAD + "limits:\n  subagents: " + value + "\n"))
                .as("a bound nobody can read must not pass as none")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("limits.subagents");
        assertThatThrownBy(
                        () ->
                                read(
                                        HEAD
                                                + "repositories:\n  web:\n    limits:\n"
                                                + "      subagents: "
                                                + value
                                                + "\n"))
                .as("nor under a repository")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("repositories.web.limits.subagents");
    }
}
