package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.ImageSource;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ImageChoice}: which source gives each key of a task's image, since the start
 * names it and a person changing one file must change exactly what that file says.
 */
class ImageChoiceTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @Test
    void anOverrideThatSetsTwoKeysChangesOnlyThose() {

        final ImageChoice choice =
                ImageChoice.merge(
                        List.of(
                                new ImageSource(
                                        "override-base", null, Map.of("A", "override"), "local"),
                                new ImageSource(
                                        null,
                                        "RUN from-entry",
                                        Map.of("A", "entry", "B", "entry"),
                                        "entry"),
                                new ImageSource(
                                        "file-base", "RUN from-file", Map.of("C", "file"), "file"),
                                new ImageSource(
                                        "project-base", "RUN project", Map.of(), "project")));

        assertThat(choice.baseImage()).as("base image").isEqualTo("override-base");
        assertThat(choice.baseFrom()).isEqualTo("local");
        assertThat(choice.snippet())
                .as("snippet, whole from one source")
                .isEqualTo("RUN from-entry");
        assertThat(choice.snippetFrom()).isEqualTo("entry");
        assertThat(choice.env())
                .as("variables, each from the first source that sets it")
                .containsEntry("A", "override")
                .containsEntry("B", "entry")
                .containsEntry("C", "file");
        assertThat(choice.envFrom())
                .containsEntry("A", "local")
                .containsEntry("B", "entry")
                .containsEntry("C", "file");
    }

    @Test
    void theProjectsImageIsWhatIsLeftWhenNothingElseSpeaks() {

        final ImageChoice choice =
                ImageChoice.merge(
                        List.of(new ImageSource("project-base", null, Map.of(), "project")));

        assertThat(choice.baseImage()).isEqualTo("project-base");
        assertThat(choice.snippet()).isNull();
        assertThat(choice.env()).isEmpty();
    }

    private static Project acme() {
        return ProjectReader.read(
                new StringReader(
                        """
                        project:
                          name: "acme"
                          security_class: "guarded"
                        image:
                          base_image: "project-base"
                        repositories:
                          backend: {}
                        """),
                "project.yml");
    }

    @Test
    void aRepositorysOwnImageIsNamedApartAndTheProjectsKeepsItsName() {

        // One name for both would let a task on one repository replace the other's image.
        final Project project = acme();
        final var backend = project.repositories().getFirst();
        final ImageSource own = ImageSource.of(project);

        assertThat(ImageChoice.merge(List.of(own)).applyTo(project, backend).imageName())
                .as("only the project's image")
                .isEqualTo("porelo/acme");
        assertThat(
                        ImageChoice.merge(
                                        List.of(
                                                new ImageSource(
                                                        null, "RUN x", Map.of(), "porelo.yml"),
                                                own))
                                .applyTo(project, backend)
                                .imageName())
                .as("a repository's own snippet")
                .isEqualTo("porelo/acme--backend");
    }

    @Test
    void aVariableBecomesOneEnvLineWhateverItHolds() {

        // A quote or a dollar sign left as written would end the value early or read another
        // variable while the image is built as root.
        final Project project = acme();
        final Project built =
                ImageChoice.merge(
                                List.of(
                                        new ImageSource(
                                                null,
                                                "RUN true",
                                                Map.of("A", "say \"hi\" for $HOME \\ now"),
                                                "porelo.yml"),
                                        ImageSource.of(project)))
                        .applyTo(project, project.repositories().getFirst());

        assertThat(built.imageSnippet())
                .isEqualTo("RUN true\nENV A=\"say \\\"hi\\\" for \\$HOME \\\\ now\"\n");
        assertThat(built.baseImage()).isEqualTo("project-base");
    }

    private void git(Path directory, String... arguments) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "user.name=T",
                                "-c",
                                "user.email=t@example.com",
                                "-c",
                                "init.defaultBranch=main"));
        all.addAll(List.of(arguments));
        runner.runOrFail(
                new Command(
                        all,
                        directory,
                        Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1"),
                        null));
    }

    @Test
    void readsTheOverrideTheEntryTheMirrorAndTheProjectInThatOrder(@TempDir Path dir)
            throws Exception {

        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                """
                                project:
                                  name: "acme"
                                  security_class: "guarded"
                                image:
                                  base_image: "project-base"
                                repositories:
                                  backend:
                                    image:
                                      env:
                                        FROM_ENTRY: "yes"
                                """),
                        "project.yml");
        final var backend = project.repositories().getFirst();

        final Path source = Files.createDirectories(dir.resolve("source"));
        git(source, "init", "-q", ".");
        Files.writeString(
                source.resolve("porelo.yml"),
                "image:\n  snippet: \"RUN from-file\"\n  env:\n    FROM_FILE: \"yes\"\n");
        git(source, "add", "-A");
        git(source, "commit", "-q", "-m", "reviewed");
        final Path mirror = GateSupport.mirror(context, project, backend);
        Files.createDirectories(mirror.getParent());
        git(dir, "clone", "-q", "--mirror", source.toString(), mirror.toString());

        final Path override = ImageChoice.override(context, project, backend);
        Files.createDirectories(override.getParent());
        Files.writeString(override, "image:\n  base_image: \"local-base\"\n");

        final ImageChoice choice = ImageChoice.of(context, project, backend);

        assertThat(choice.baseImage()).as("from the override").isEqualTo("local-base");
        assertThat(choice.baseFrom()).contains("local override");
        assertThat(choice.snippet()).as("from the mirror").isEqualTo("RUN from-file");
        assertThat(choice.snippetFrom()).contains("porelo.yml at refs/heads/main of backend");
        assertThat(choice.env())
                .containsEntry("FROM_ENTRY", "yes")
                .containsEntry("FROM_FILE", "yes");
    }
}
