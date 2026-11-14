package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.project.file.RepositorySettings;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@code project.secrets}: what a start does about a line of the workspace that looks
 * like a credential.
 */
class SecretsSettingTest {

    private static Project read(final String project) {
        return ProjectReader.read(
                new StringReader(
                        "project:\n  name: \"p\"\n  security_class: \"guarded\"\n"
                                + project
                                + "image:\n  from: \"ubuntu:24.04\"\n"),
                "project.yml");
    }

    @Test
    void aProjectThatSaysNothingWarns() {
        assertThat(read("").secrets()).isEqualTo(Project.SECRETS_WARN);
        assertThat(read("").refusesSecrets()).isFalse();
    }

    @Test
    void aProjectMayRefuse() {
        assertThat(read("  secrets: \"refuse\"\n").refusesSecrets()).isTrue();
    }

    @Test
    void anythingElseIsRefusedWhenTheFileIsRead() {
        assertThatThrownBy(() -> read("  secrets: \"ignore\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("project.secrets")
                .hasMessageContaining("warn or refuse");
    }

    @Test
    void aRepositorysOwnFileCannotLowerIt() {
        // The repository is what the agent pushes to: it would otherwise turn the refusal off.
        assertThatThrownBy(
                        () ->
                                ProjectReader.read(
                                        new StringReader(
                                                "project:\n  name: \"p\"\n  security_class:"
                                                        + " \"guarded\"\n  secrets: \"refuse\"\n"
                                                        + "image:\n  from: \"ubuntu:24.04\"\n"
                                                        + "repositories:\n  shop: {}\n"),
                                        "project.yml",
                                        SnippetFiles.beside(Path.of("project.yml")),
                                        List.of(
                                                new RepositorySettings.Layer(
                                                        "porelo.yml of shop",
                                                        "project:\n  secrets: \"warn\"\n"))))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'project.secrets' is the project's to say");
    }
}
