package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void aRepositoryAddsToTheProjectsImageKeyByKey() {

        // The operator's decision: porelo.yml adds to the project's defaults. A value it sets wins,
        // its lines come after the project's, and its variables join the project's.
        final ImageChoice choice =
                ImageChoice.merge(
                        List.of(
                                new ImageSource(
                                        "file-base", "RUN file", Map.of("A", "file"), "porelo.yml"),
                                new ImageSource(
                                        "project-base",
                                        "RUN project",
                                        Map.of("A", "project", "B", "project"),
                                        "project")));

        assertThat(choice.baseImage()).as("the repository's value wins").isEqualTo("file-base");
        assertThat(choice.baseFrom()).isEqualTo("porelo.yml");
        assertThat(choice.snippet())
                .as("the project's lines, then the repository's")
                .isEqualTo("RUN project\nRUN file");
        assertThat(choice.snippetFrom()).isEqualTo("project, then porelo.yml");
        assertThat(choice.env()).isEqualTo(Map.of("A", "file", "B", "project"));
        assertThat(choice.envFrom()).isEqualTo(Map.of("A", "porelo.yml", "B", "project"));
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
                          from: "project-base"
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
                                                        "project-base",
                                                        "RUN x",
                                                        Map.of(),
                                                        "porelo.yml"),
                                                own))
                                .applyTo(project, backend)
                                .imageName())
                .as("a repository's own snippet")
                .isEqualTo("porelo/acme_backend");
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
                                                "file-base",
                                                "RUN true",
                                                Map.of("A", "say \"hi\" for $HOME \\ now"),
                                                "porelo.yml"),
                                        ImageSource.of(project)))
                        .applyTo(project, project.repositories().getFirst());

        assertThat(built.imageSnippet())
                .isEqualTo("RUN true\nENV A=\"say \\\"hi\\\" for \\$HOME \\\\ now\"\n");
        assertThat(built.baseImage()).isEqualTo("file-base");
    }

    @Test
    void aTaskStartedFromACheckoutReadsItsOverrideThenItsFile(@TempDir Path dir) throws Exception {

        // In the project default, the files are the person's own working tree, the override first.
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        final Path checkout = Files.createDirectories(dir.resolve("work"));
        Files.writeString(checkout.resolve(".porelo.yml"), "image:\n  from: \"local\"\n");
        Files.writeString(
                checkout.resolve("porelo.yml"),
                "image:\n  from: \"checked-in\"\n  run: \"RUN from-the-checkout\"\n");
        new DefaultProject(context).add(checkout.toString(), "work", checkout.toString());
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                """
                                project:
                                  name: "default"
                                  security_class: "guarded"
                                image:
                                  from: "default-base"
                                repositories:
                                  work: {}
                                """),
                        "project.yml");

        final ImageChoice choice =
                ImageChoice.of(context, project, project.repositories().getFirst());

        assertThat(choice.baseImage()).as("from the override").isEqualTo("local");
        assertThat(choice.baseFrom()).endsWith("work/.porelo.yml");
        assertThat(choice.snippet())
                .as("the checked-in file's lines, the override setting only the base")
                .isEqualTo("RUN from-the-checkout");
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

    private PoreloContext imageContext(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void anEmptyRepositoryUsesTheProjectsImage(@TempDir Path dir) throws Exception {
        // A new repository must allow work to start before its first commit exists.
        final PoreloContext context = imageContext(dir);
        final Project project = acme();
        final var repository = project.repositories().getFirst();
        final Path upstream = dir.resolve("upstream.git");
        git(dir, "init", "-q", "--bare", "--initial-branch=main", upstream.toString());
        final Path mirror = GateSupport.mirror(context, project, repository);
        Files.createDirectories(mirror.getParent());
        git(dir, "clone", "-q", "--bare", upstream.toString(), mirror.toString());

        assertThat(ImageChoice.of(context, project, repository).baseImage())
                .as("an unborn default branch has no repository image to override the project's")
                .isEqualTo("project-base");
        assertThat(MirrorFile.defaultBranch(context.runner(), mirror))
                .as("an empty mirror stays distinct from a mirror that has not been made")
                .isEqualTo(new MirrorFile.Empty("refs/heads/main"));
        assertThat(MirrorFile.read(context.runner(), mirror, "porelo.yml"))
                .as("an empty repository has no image file to read")
                .isEmpty();
    }

    @Test
    void aMissingDefaultBranchRefusesTheImageWithoutLeakingTheUpstreamCredential(@TempDir Path dir)
            throws Exception {
        // Falling back silently would build the task from the wrong image.
        final PoreloContext context = imageContext(dir);
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                """
                project:
                  name: acme
                  security_class: guarded
                image:
                  from: project-base
                repositories:
                  backend:
                    upstream: https://user:example-secret@example.com/owner/backend.git
                """),
                        "project.yml");
        final var repository = project.repositories().getFirst();
        final Path source = Files.createDirectories(dir.resolve("source"));
        git(source, "init", "-q", "-b", "main", ".");
        Files.writeString(source.resolve("porelo.yml"), "image:\n  from: repository-base\n");
        git(source, "add", "porelo.yml");
        git(source, "commit", "-q", "-m", "reviewed image");
        final Path mirror = GateSupport.mirror(context, project, repository);
        Files.createDirectories(mirror.getParent());
        git(dir, "clone", "-q", "--bare", source.toString(), mirror.toString());
        git(mirror, "symbolic-ref", "HEAD", "refs/heads/master");

        assertThatThrownBy(() -> ImageChoice.of(context, project, repository))
                .as(
                        "an unreadable repository image must refuse the task and keep credentials private")
                .isInstanceOf(org.fuin.porelo.core.project.ProjectException.class)
                .hasMessageContaining("backend")
                .hasMessageContaining("refs/heads/master")
                .hasMessageContaining("its branches: main")
                .hasMessageContaining("https://example.com/owner/backend.git")
                .hasMessageNotContaining("user:")
                .hasMessageNotContaining("example-secret");
    }

    @Test
    void aPreviewReturnsTheMirrorsRefusalAndKeepsTheOtherSources(@TempDir Path dir)
            throws Exception {
        // Only the mirror can be repaired by a fetch; valid local choices must survive its refusal.
        final PoreloContext context = imageContext(dir);
        final Project project = acme();
        final var repository = project.repositories().getFirst();
        final Path source = Files.createDirectories(dir.resolve("source"));
        git(source, "init", "-q");
        Files.writeString(source.resolve("porelo.yml"), "egress: {}\n");
        git(source, "add", "porelo.yml");
        git(source, "commit", "-q", "-m", "refused image");
        final Path mirror = GateSupport.mirror(context, project, repository);
        Files.createDirectories(mirror.getParent());
        git(dir, "clone", "-q", "--bare", source.toString(), mirror.toString());
        final Path override = ImageChoice.override(context, project, repository);
        Files.createDirectories(override.getParent());
        Files.writeString(override, "image:\n  from: local-base\n  env: {LOCAL: local-value}\n");

        final ImageChoice.Preview preview = ImageChoice.preview(context, project, repository);

        assertThat(preview.choice().baseImage())
                .as("the refused mirror must not discard a local choice")
                .isEqualTo("local-base");
        assertThat(preview.choice().env())
                .as("local variables remain in the provisional image")
                .containsEntry("LOCAL", "local-value");
        assertThat(preview.mirrorRefusal())
                .as("only the mirror's refusal is deferred")
                .isInstanceOf(org.fuin.porelo.core.project.ProjectException.class)
                .hasMessageContaining("egress")
                .hasMessageContaining("porelo.yml at refs/heads/main of backend");
        assertThatThrownBy(() -> ImageChoice.of(context, project, repository))
                .as("the ordinary read stays strict")
                .hasMessageContaining("egress");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"override", ".porelo.yml", "porelo.yml"})
    void aPreviewStillThrowsForARefusedLocalSource(String source, @TempDir Path dir)
            throws Exception {
        // Host-side work must never start for a local file a fetch cannot repair.
        final PoreloContext context = imageContext(dir);
        final Project project;
        final Path file;
        if (source.equals("override")) {
            project = acme();
            file = ImageChoice.override(context, project, project.repositories().getFirst());
        } else {
            final Path checkout = Files.createDirectories(dir.resolve("work"));
            new DefaultProject(context).add(checkout.toString(), "backend", checkout.toString());
            project =
                    ProjectReader.read(
                            new StringReader(
                                    """
                    project:
                      name: default
                      security_class: guarded
                    image:
                      from: default-base
                    repositories:
                      backend: {}
                    """),
                            "project.yml");
            file = checkout.resolve(source);
        }
        Files.createDirectories(file.getParent());
        // In 'default' a file may add limits; a list of repositories is the project's alone.
        final String refused = source.equals("override") ? "limits" : "repositories";
        Files.writeString(file, refused + ": {}\n");

        assertThatThrownBy(
                        () ->
                                ImageChoice.preview(
                                        context, project, project.repositories().getFirst()))
                .as("a refused local source must be thrown before the mirror is read")
                .isInstanceOf(org.fuin.porelo.core.project.ProjectException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining(refused);
    }

    @Test
    void readsTheOverrideTheMirrorAndTheProjectInThatOrder(@TempDir Path dir) throws Exception {

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
                                  from: "project-base"
                                  env:
                                    FROM_PROJECT: "yes"
                                repositories:
                                  backend:
                                """),
                        "project.yml");
        final var backend = project.repositories().getFirst();

        final Path source = Files.createDirectories(dir.resolve("source"));
        git(source, "init", "-q", ".");
        Files.writeString(
                source.resolve("porelo.yml"),
                "image:\n  from: \"file-base\"\n  run: \"RUN from-file\"\n  env:\n    FROM_FILE: \"yes\"\n");
        git(source, "add", "-A");
        git(source, "commit", "-q", "-m", "reviewed");
        final Path mirror = GateSupport.mirror(context, project, backend);
        Files.createDirectories(mirror.getParent());
        git(dir, "clone", "-q", "--mirror", source.toString(), mirror.toString());

        final ImageChoice reviewed = ImageChoice.of(context, project, backend);
        assertThat(reviewed.baseImage()).as("from the mirror").isEqualTo("file-base");
        assertThat(reviewed.snippet()).isEqualTo("RUN from-file");
        assertThat(reviewed.snippetFrom()).contains("porelo.yml at refs/heads/main of backend");
        assertThat(reviewed.env())
                .as("the project's variables, then the mirror's")
                .isEqualTo(Map.of("FROM_PROJECT", "yes", "FROM_FILE", "yes"));

        final Path override = ImageChoice.override(context, project, backend);
        assertThat(override)
                .as("where the page tells a person to put it, not a path derived the same way")
                .isEqualTo(dir.resolve("config/porelo/projects/acme/backend/.porelo.yml"));
        Files.createDirectories(override.getParent());
        Files.writeString(override, "image:\n  from: \"local-base\"\n");

        final ImageChoice choice = ImageChoice.of(context, project, backend);

        assertThat(choice.baseImage()).as("from the override").isEqualTo("local-base");
        assertThat(choice.baseFrom()).contains("local override");
        assertThat(choice.snippet()).as("the mirror's lines stay").isEqualTo("RUN from-file");
        assertThat(choice.env())
                .as("the project's and the mirror's variables stay")
                .isEqualTo(Map.of("FROM_PROJECT", "yes", "FROM_FILE", "yes"));
    }
}
