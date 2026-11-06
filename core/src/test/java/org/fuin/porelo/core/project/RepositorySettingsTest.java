package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.project.ProjectReader.WithRepository;
import org.fuin.porelo.core.project.file.RepositorySettings;
import org.junit.jupiter.api.Test;

class RepositorySettingsTest {

    private static final String PROJECT =
            """
            project:
              name: "default"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
            egress:
              domains:
                - "repo.example.org"
                - "mirror.example.org"
            limits:
              memory: "4g"
            repositories:
              shop: {}
            """;

    @Test
    void aRepositorysFileAddsToTheProjectAndSaysWhatItAsksFor() {
        final WithRepository read =
                read(
                        """
                        egress:
                          domains:
                            - "build.example.org"
                            - "!mirror.example.org"
                        limits:
                          memory: "8g"
                        """);

        assertThat(read.project().egress().domains())
                .containsExactly("repo.example.org", "build.example.org");
        assertThat(read.project().limits().memory()).isEqualTo("8g");
        assertThat(read.asked())
                .containsExactly(
                        new RepositorySettings.Asked(
                                "egress.domains[build.example.org]",
                                "build.example.org",
                                "",
                                false),
                        new RepositorySettings.Asked("limits.memory", "8g", "4g", false),
                        new RepositorySettings.Asked(
                                "egress.domains[mirror.example.org]",
                                "",
                                "mirror.example.org",
                                true));
    }

    @Test
    void aFileThatAsksNothingBeyondItsImageAsksNothing() {
        final WithRepository read = read("image:\n  run: \"RUN true\"\n");

        assertThat(read.asked()).as("the image is chosen and shown apart").isEmpty();
        assertThat(read.project().egress().domains())
                .containsExactly("repo.example.org", "mirror.example.org");
    }

    @Test
    void refusesWhatDefinesAProjectByItsName() {
        assertThatThrownBy(() -> read("repositories:\n  other: {}\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("porelo.yml of shop")
                .hasMessageContaining("'repositories'");
        assertThatThrownBy(() -> read("project:\n  name: \"other\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'project.name'");
    }

    @Test
    void theProjectsChecksJudgeWhatTheMergeMakes() {
        assertThatThrownBy(() -> read("limits:\n  hand_in: \"lots\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("hand_in");
    }

    private static WithRepository read(final String repository) {
        return ProjectReader.read(
                new StringReader(PROJECT),
                "project.yml",
                SnippetFiles.beside(Path.of("project.yml")),
                List.of(new RepositorySettings.Layer("porelo.yml of shop", repository)));
    }
}
