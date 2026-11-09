package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.file.RepositorySettings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for the {@code providers} section, which names the model each provider's tasks run on. */
class ProviderModelsTest {

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

    @org.junit.jupiter.api.Test
    void aProjectsModelIsReadWithItsLabelAsTheProjects() {
        final Project project =
                read(
                        """
                        providers:
                          provider-a:
                            model: "model-a"
                            label: "Model A"
                        """);

        assertThat(project.providers())
                .as("the file's model is what a task on that provider is to run on")
                .isEqualTo(
                        Map.of(
                                "provider-a",
                                new ModelDefault("model-a", "Model A", ModelDefault.PROJECT)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "model: \"--exec=x\"",
                "model: \"two words\"",
                "model: \"line\\nbreak\"",
                "model: \"tab\\there\"",
                "model: \"\"",
                "model: yes",
                "model: 123",
                "label: \"no model\"",
                "model: \"model-a\"\n    label: \"two\\nlines\""
            })
    void aModelThatIsNotOneWordOrALabelThatIsNotOneLineIsRefusedAtRead(final String entry) {
        // The model goes on the agent's command line: anything but one word would be read as more.
        assertThatThrownBy(() -> read("providers:\n  provider-a:\n    " + entry + "\n"))
                .as("a model or label that is not one word or one line must not reach a start")
                .isInstanceOf(ProjectException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Provider_A", "-provider", "provider a"})
    void aProviderNameOfAnotherShapeIsRefusedAtRead(final String provider) {
        assertThatThrownBy(
                        () -> read("providers:\n  \"" + provider + "\":\n    model: \"model-a\"\n"))
                .as("no provider has such a name, so the entry could never apply")
                .isInstanceOf(ProjectException.class);
    }

    @org.junit.jupiter.api.Test
    void aRepositorysModelWinsAndTakesNoLabelFromTheProjects() {
        final ProjectReader.WithRepository read =
                withRepository(
                        """
                        providers:
                          provider-a:
                            model: "model-a"
                            label: "Model A"
                          provider-b:
                            model: "model-b"
                        """,
                        """
                        providers:
                          provider-a:
                            model: "model-c"
                        """);

        assertThat(read.project().providers().get("provider-a"))
                .as("the repository's model, said to be its own, and not under the project's label")
                .isEqualTo(new ModelDefault("model-c", "", ModelDefault.REPOSITORY));
        assertThat(read.project().providers().get("provider-b"))
                .as("a provider the repository leaves alone keeps the project's model")
                .isEqualTo(new ModelDefault("model-b", "", ModelDefault.PROJECT));
        assertThat(read.asked())
                .as("a model the repository brings is asked of the machine like its other settings")
                .extracting(RepositorySettings.Asked::key)
                .containsExactly("providers.provider-a.model");
    }

    @org.junit.jupiter.api.Test
    void aRepositorysModelThatIsNotOneWordIsRefused() {
        assertThatThrownBy(
                        () -> withRepository("", "providers:\n  provider-a:\n    model: \"-x\"\n"))
                .as("a repository's file is held to the same word as the project's")
                .isInstanceOf(ProjectException.class);
    }

    @org.junit.jupiter.api.Test
    void aRepositorysLabelWithoutItsModelIsRefusedRatherThanDroppingTheProjectsLabel() {

        // Kept, it would name the project's model; dropped, the project's own label is lost too.
        assertThatThrownBy(
                        () ->
                                withRepository(
                                        "providers:\n  provider-a:\n    model: \"model-a\"\n"
                                                + "    label: \"Model A\"\n",
                                        "providers:\n  provider-a:\n    label: \"Custom\"\n"))
                .as("a label belongs to the model written beside it")
                .isInstanceOf(ProjectException.class);
    }

    @org.junit.jupiter.api.Test
    void aRepositoryTakesTheProjectsModelAwayWholeOrNotAtAll() {
        assertThat(
                        withRepository(
                                        "providers:\n  provider-a:\n    model: \"model-a\"\n"
                                                + "    label: \"Model A\"\n",
                                        "providers:\n  provider-a: ~\n")
                                .project()
                                .providers())
                .as("the entry taken away whole runs the agent's own default")
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                withRepository(
                                        "providers:\n  provider-a:\n    model: \"model-a\"\n"
                                                + "    label: \"Model A\"\n",
                                        "providers:\n  provider-a:\n    model: ~\n"))
                .as("a model taken away under its label leaves an entry no start can run on")
                .isInstanceOf(ProjectException.class);
    }
}
