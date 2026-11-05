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
              from: "ubuntu:24.04"
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

    @ParameterizedTest
    @ValueSource(strings = {"-1", "two", "1.5", "\"2\"", "[]", "99999999999"})
    void aNumberBelowNoneOrNoWholeNumberIsRefused(String value) {
        assertThatThrownBy(() -> read(HEAD + "limits:\n  subagents: " + value + "\n"))
                .as("a bound nobody can read must not pass as none")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("limits.subagents");
    }
}
