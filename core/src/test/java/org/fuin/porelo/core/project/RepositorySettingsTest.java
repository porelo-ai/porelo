package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.project.ProjectReader.WithRepository;
import org.fuin.porelo.core.project.file.RepositorySettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static final String IMAGE_WITH_RUN =
            "  from: \"ubuntu:24.04\"\n  run: \"RUN project-line\"\n";

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

    @Test
    void aRunFileInTheRepositorysFileReadsBesideTheProjectsRun(@TempDir final Path dir)
            throws Exception {
        final Path project = Files.createDirectories(dir.resolve("project")).resolve("project.yml");
        Files.writeString(project, PROJECT.replace("  from: \"ubuntu:24.04\"\n", IMAGE_WITH_RUN));
        Files.writeString(
                Files.createDirectories(dir.resolve("shop")).resolve("own.run"), "RUN own-line\n");

        final WithRepository read =
                ProjectReader.read(
                        new StringReader(Files.readString(project)),
                        project.toString(),
                        SnippetFiles.beside(project),
                        List.of(
                                new RepositorySettings.Layer(
                                        "porelo.yml of shop",
                                        "image:\n  run_file: \"own.run\"\n"
                                                + "limits:\n  memory: \"8g\"\n")));

        assertThat(read.project().imageSnippet())
                .as("the image choice joins the repository's lines after these")
                .isEqualTo("RUN project-line");
        assertThat(read.project().limits().memory())
                .as("the file's other settings are merged")
                .isEqualTo("8g");
        assertThat(read.removed()).as("no layer's lines take the project's away").isEmpty();
    }

    @Test
    void oneRepositoryFileHoldingBothRunAndRunFileIsStillRefused() {
        assertThatThrownBy(
                        () -> read("image:\n  run: \"RUN true\"\n" + "  run_file: \"own.run\"\n"))
                .as("within one file the two keys exclude each other")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("porelo.yml of shop")
                .hasMessageContaining("mutually exclusive");
    }

    private static WithRepository read(final String repository) {
        return ProjectReader.read(
                new StringReader(PROJECT),
                "project.yml",
                SnippetFiles.beside(Path.of("project.yml")),
                List.of(new RepositorySettings.Layer("porelo.yml of shop", repository)));
    }
}
