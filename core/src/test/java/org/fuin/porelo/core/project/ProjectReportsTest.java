package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.fuin.porelo.core.project.file.ProjectFileReader;
import org.fuin.porelo.core.project.file.ProjectFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** How many reports a project lets one of its tasks hand back. */
class ProjectReportsTest {

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
        assertThat(read(HEAD).limits().reports())
                .as("no report unless the project file allows one")
                .isZero();
        assertThat(read(HEAD + "limits:\n  memory: 2g\n").limits().reports())
                .as("a limits block without the key allows none too")
                .isZero();
    }

    @Test
    void aProjectNamesHowManyReportsOneTaskMayHandBack() {
        assertThat(read(HEAD + "limits:\n  reports: 2\n").limits().reports())
                .as("the bound the file names")
                .isEqualTo(2);
    }

    @Test
    void theBoundSurvivesWritingTheFileBack() {
        final var file =
                ProjectFileReader.read(new StringReader(HEAD + "limits:\n  reports: 3\n"), "test");
        assertThat(ProjectFileWriter.write(file))
                .as("a file written back keeps its bound")
                .contains("reports: 3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "101", "two", "1.5", "\"2\"", "[]", "99999999999"})
    void aNumberBelowNoneOrNoWholeNumberIsRefused(String value) {
        assertThatThrownBy(() -> read(HEAD + "limits:\n  reports: " + value + "\n"))
                .as("a bound nobody can read must not pass as none")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("limits.reports");
    }

    @Test
    void theKeyOfTheRetiredIdeasIsNoLongerTaken() {
        assertThatThrownBy(() -> read(HEAD + "limits:\n  ideas: 2\n"))
                .as("a file still naming ideas is told so, never read as allowing reports")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("ideas");
    }
}
