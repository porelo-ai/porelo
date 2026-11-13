package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.fuin.porelo.core.project.file.RepositorySettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@code limits.pass_on}, how long the gate waits for a forge that takes an online task's
 * push before it tells the agent the forge did not answer.
 */
class PassOnLimitTest {

    private static final String HEAD =
            """
            project:
              name: "p"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
            repositories:
              shop: {}
            """;

    private static Project read(final String section) {
        return ProjectReader.read(new StringReader(HEAD + section), "project.yml");
    }

    @Test
    void aProjectThatSetsNoneWaitsTwoMinutes() {
        assertThat(read("").limits().passOn())
                .as("long enough for a large push to a forge that answers slowly")
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(Limits.defaults().passOn()).isEqualTo(Limits.DEFAULT_PASS_ON);
    }

    @ParameterizedTest
    @CsvSource({"90s, 90", "2m, 120", "1h, 3600", "10s, 10", "60m, 3600"})
    void aTimeIsReadWithItsUnit(final String written, final long seconds) {
        assertThat(read("limits:\n  pass_on: \"" + written + "\"\n").limits().passOn())
                .isEqualTo(Duration.ofSeconds(seconds));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "9s", "2h", "61m", "none", "90", "", "1.5m", "-1m"})
    void aTimeOutsideTenSecondsToAnHourIsRefusedWhenTheFileIsRead(final String written) {
        // There is no 'none': the point is that a push ends.
        assertThatThrownBy(() -> read("limits:\n  pass_on: \"" + written + "\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("limits.pass_on");
    }

    @Test
    void aRepositorysOwnLimitReplacesTheProjects() {
        final ProjectReader.WithRepository read =
                ProjectReader.read(
                        new StringReader(HEAD + "limits:\n  pass_on: \"90s\"\n"),
                        "project.yml",
                        SnippetFiles.beside(Path.of("project.yml")),
                        List.of(
                                new RepositorySettings.Layer(
                                        "porelo.yml of shop", "limits:\n  pass_on: \"5m\"\n")));

        assertThat(read.project().limits().passOn()).isEqualTo(Duration.ofMinutes(5));
    }
}
