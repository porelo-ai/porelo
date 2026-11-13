package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.project.file.RepositorySettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@code readings} section, which names the directory a reading of an issue writes
 * its findings into, inside the repository.
 */
class ReadingsDirectoryTest {

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

    private static ProjectReader.WithRepository withRepository(
            final String project, final String repository) {
        return ProjectReader.read(
                new StringReader(HEAD + project),
                "project.yml",
                SnippetFiles.beside(Path.of("project.yml")),
                List.of(new RepositorySettings.Layer("porelo.yml of shop", repository)));
    }

    @Test
    void aProjectThatNamesNoneHasTheDefault() {
        assertThat(read("").readingsDirectory())
                .as("with no readings section, a reading writes into 'readings'")
                .isEqualTo("readings");
    }

    @Test
    void aProjectsDirectoryIsRead() {
        assertThat(read("readings:\n  directory: \"doc/readings\"\n").readingsDirectory())
                .isEqualTo("doc/readings");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "directory: \"../x\"",
                "directory: \"a/../../x\"",
                "directory: \"..\"",
                "directory: \"/x\"",
                "directory: \"\"",
                "directory: \"a\\nb\""
            })
    void aDirectoryOutsideTheRepositoryIsRefusedWhenTheFileIsRead(final String entry) {
        // A reading writes its findings there, so a path that leaves the repository would write
        // where no reviewer of it looks, and over what is not the repository's.
        assertThatThrownBy(() -> read("readings:\n  " + entry + "\n"))
                .as("a readings directory outside the repository must not reach a task")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("readings.directory");
    }

    @Test
    void aRepositorysPoreloYmlNamingReadingsIsRefusedByItsName() {
        // Where readings go is the signed project file's alone: a repository's own file could
        // otherwise move them where its tasks may write what they like.
        assertThatThrownBy(
                        () ->
                                withRepository(
                                        "readings:\n  directory: \"doc/readings\"\n",
                                        "readings:\n  directory: \"review\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining(
                        "porelo.yml of shop, line 1: 'readings' is the project's to say");
    }

    @Test
    void aRepositoryThatNamesNoneKeepsTheProjects() {
        assertThat(
                        withRepository("readings:\n  directory: \"doc/readings\"\n", "")
                                .project()
                                .readingsDirectory())
                .isEqualTo("doc/readings");
    }
}
