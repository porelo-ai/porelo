package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.runtime.ImageSettings;
import org.fuin.porelo.runtime.Podman;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for preparing an environment without starting a task.
 *
 * <p>The depths are the feature, so what is asserted is what actually reaches podman. A depth that
 * was accepted, reported and then not applied would be the worst version of this: somebody waits
 * for a full rebuild, gets a cached one, and the thing they were trying to fix is still there.
 */
class PreparationTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), command -> 0);
    }

    private Path projectFile(Path dir) throws Exception {
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                """);
        return file;
    }

    /** Writes the person's local override for the repository {@code backend}, and returns it. */
    private Path override(Path dir, Path file, String image) throws Exception {
        final var project = org.fuin.porelo.core.project.ProjectReader.read(file);
        final Path override =
                ImageChoice.override(context(dir), project, project.repository("backend"));
        Files.createDirectories(override.getParent());
        Files.writeString(override, image);
        return override;
    }

    private Preparation.Result prepare(Path dir, Podman.Rebuild rebuild, boolean dryRun)
            throws Exception {
        return Preparation.prepare(
                context(dir), projectFile(dir), null, rebuild, dryRun, new PrintWriter(out, true));
    }

    /** The arguments of the podman build that actually ran. */
    private List<String> build() {
        return runner.invocations().stream()
                .map(command -> command.arguments())
                .filter(arguments -> arguments.contains("build"))
                .findFirst()
                .orElse(List.of());
    }

    private ImageSettings recorded(final Path dir, final String key) throws Exception {
        final String line =
                Files.readAllLines(
                                context(dir)
                                        .paths()
                                        .tasks()
                                        .buildContext(key)
                                        .resolve("Containerfile"))
                        .stream()
                        .filter(each -> each.startsWith("LABEL org.fuin.porelo.settings="))
                        .findFirst()
                        .orElseThrow();
        return ImageSettings.parse(
                        line.substring(line.indexOf('"') + 1, line.length() - 1)
                                .replaceAll("\\\\(.)", "$1"))
                .orElseThrow();
    }

    @Test
    void aPreparedImageRecordsThatItsSettingsAreTheProjectFiles(@TempDir Path dir)
            throws Exception {
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                """
                project:
                  name: "demo"
                  security_class: "offline"
                image:
                  from: "ubuntu:24.04"
                  run: |
                    RUN apt-get install -y maven
                """);

        Preparation.prepare(
                context(dir), file, null, Podman.Rebuild.CACHED, false, new PrintWriter(out, true));

        final var settings = recorded(dir, "demo");
        assertThat(settings.baseImage()).isEqualTo("ubuntu:24.04");
        assertThat(settings.baseImageSource()).isEqualTo("project.yml");
        assertThat(settings.packages())
                .containsExactly(new ImageSettings.Sourced("maven", "project.yml"));
        assertThat(settings.lines())
                .containsExactly(
                        new ImageSettings.Sourced("RUN apt-get install -y maven", "project.yml"));
        assertThat(settings.securityClass()).isEqualTo("offline");
        assertThat(settings.securityClassSource()).isEqualTo("project.yml");
        assertThat(settings.agent()).as("no agent here to install").isEmpty();
    }

    @Test
    void theDefaultProjectsImageRecordsPorelosSettings(@TempDir Path dir) throws Exception {
        // A person must not go looking for a project.yml that Default does not have.
        final PoreloContext context = context(dir);

        Preparation.prepare(
                context,
                new DefaultProject(context).file(),
                null,
                Podman.Rebuild.CACHED,
                false,
                new PrintWriter(out, true));

        final var settings = recorded(dir, "default");
        assertThat(settings.baseImageSource()).isEqualTo("porelo");
        assertThat(settings.securityClass()).isEqualTo("guarded");
        assertThat(settings.securityClassSource()).isEqualTo("porelo");
    }

    @Test
    void aRepositorysOwnLinesNameWhereTheyCameFrom(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                repositories:
                  backend:
                """);
        override(
                dir,
                file,
                "image:\n  from: \"debian:13\"\n  run: |\n    RUN apt-get install -y gradle\n");

        Preparation.prepare(
                context(dir),
                file,
                "backend",
                null,
                Podman.Rebuild.CACHED,
                false,
                new PrintWriter(out, true));

        final var settings = recorded(dir, "demo_backend");
        assertThat(settings.baseImageSource())
                .as("the image is the override's, whole")
                .endsWith(".porelo.yml (local override)");
        assertThat(settings.packages())
                .extracting(ImageSettings.Sourced::value)
                .containsExactly("gradle");
        assertThat(settings.lines().getFirst().source()).endsWith(".porelo.yml (local override)");
    }

    @Test
    void aRepositorysImageHoldsNoLineOfTheProjects(@TempDir Path dir) throws Exception {
        // Taken whole: the project's lines on the repository's base image make an image nobody
        // wrote.
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                  run: |
                    RUN apt-get install -y maven
                repositories:
                  backend:
                """);
        override(
                dir,
                file,
                "image:\n  from: \"debian:13\"\n  env:\n    NPM_TOKEN: \"the-value-itself\"\n");

        Preparation.prepare(
                context(dir),
                file,
                "backend",
                null,
                Podman.Rebuild.CACHED,
                false,
                new PrintWriter(out, true));

        final var settings = recorded(dir, "demo_backend");
        assertThat(settings.lines())
                .extracting(ImageSettings.Sourced::source)
                .as("the repository's line alone")
                .satisfiesExactly(only -> assertThat(only).endsWith("(local override)"));
        assertThat(settings.lines().getLast().value())
                .as("recorded by its name")
                .isEqualTo("ENV NPM_TOKEN=" + ImageSettings.WITHHELD);
    }

    @Test
    void aCachedRebuildPassesNeitherFlag(@TempDir Path dir) throws Exception {

        // Not "skip the build": the build runs and podman's cache decides layer by layer, which is
        // what every task start already does.
        assertThat(prepare(dir, Podman.Rebuild.CACHED, false).outcome())
                .isEqualTo(Preparation.Outcome.PREPARED);

        assertThat(build()).contains("build").doesNotContain("--no-cache");
        assertThat(String.join(" ", build())).doesNotContain("PORELO_LAYER_EPOCH");
    }

    @Test
    void rebuildingTheAgentInvalidatesFromTheSeamAndNotTheWholeImage(@TempDir Path dir)
            throws Exception {

        // There is no podman flag for "rebuild from here". It works by changing a build argument
        // placed where the agent's layers begin - so if this stopped being passed, the middle depth
        // would silently become the cheapest one.
        prepare(dir, Podman.Rebuild.AGENT, false);

        assertThat(String.join(" ", build()))
                .contains("--build-arg")
                .contains("PORELO_LAYER_EPOCH=");
        assertThat(build())
                .as("keeping the packages is the whole point of this depth")
                .doesNotContain("--no-cache");
    }

    @Test
    void discardingEverythingSaysSoToPodman(@TempDir Path dir) throws Exception {

        prepare(dir, Podman.Rebuild.EVERYTHING, false);

        assertThat(build()).contains("--no-cache");
    }

    @Test
    void aRepositorysImageIsPreparedUnderItsOwnNameFromItsOwnSources(@TempDir Path dir)
            throws Exception {

        // Prepared under the project's name, it would be an image no start on that repository
        // uses, and the start would build its own anyway.
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                repositories:
                  backend:
                """);
        override(dir, file, "image:\n  from: \"debian:13\"\n  env:\n    FROM_OVERRIDE: \"yes\"\n");

        final Preparation.Result result =
                Preparation.prepare(
                        context(dir),
                        file,
                        "backend",
                        null,
                        Podman.Rebuild.CACHED,
                        true,
                        new PrintWriter(out, true));

        assertThat(result.outcome()).isEqualTo(Preparation.Outcome.PREVIEWED);
        assertThat(result.image()).as("the repository's image").isEqualTo("porelo/demo_backend");
        assertThat(out.toString())
                .as("each key's source")
                .contains("env        FROM_OVERRIDE from ")
                .contains(".porelo.yml (local override)");
    }

    @Test
    void aPreparedSnippetNamesTheFileThatSuppliedIt(@TempDir Path dir) throws Exception {
        final Path file = projectFile(dir);
        Files.writeString(file, Files.readString(file) + "repositories:\n  backend:\n");
        final Path override =
                override(dir, file, "image:\n  from: \"debian:13\"\n  run: RUN true\n");

        final Preparation.Result result =
                Preparation.prepare(
                        context(dir),
                        file,
                        "backend",
                        null,
                        Podman.Rebuild.CACHED,
                        true,
                        new PrintWriter(out, true));

        assertThat(result.outcome())
                .as("a valid snippet can be previewed")
                .isEqualTo(Preparation.Outcome.PREVIEWED);
        assertThat(out.toString())
                .as("the snippet must identify the source that will run as root")
                .contains("snippet   1 lines from " + override + " (local override)");
    }

    @Test
    void aRepositoryTheProjectDoesNotHaveIsNamed(@TempDir Path dir) throws Exception {

        final Preparation.Result result =
                Preparation.prepare(
                        context(dir),
                        projectFile(dir),
                        "nowhere",
                        null,
                        Podman.Rebuild.CACHED,
                        true,
                        new PrintWriter(out, true));

        assertThat(result.outcome()).isEqualTo(Preparation.Outcome.FAILED);
        assertThat(result.detail()).contains("has no repository 'nowhere'");
        assertThat(build()).isEmpty();
    }

    @Test
    void aPreviewBuildsNothingAtAll(@TempDir Path dir) throws Exception {

        // Somebody checking what a rebuild would cost must not pay it by asking.
        assertThat(prepare(dir, Podman.Rebuild.EVERYTHING, true).outcome())
                .isEqualTo(Preparation.Outcome.PREVIEWED);

        assertThat(build()).isEmpty();
    }

    @Test
    void aProjectFileThatCannotBeReadIsNamedRatherThanBuiltAround(@TempDir Path dir) {

        final Preparation.Result result =
                Preparation.prepare(
                        context(dir),
                        dir.resolve("nothing-here.yml"),
                        null,
                        Podman.Rebuild.CACHED,
                        false,
                        new PrintWriter(out, true));

        assertThat(result.outcome()).isEqualTo(Preparation.Outcome.NO_SUCH_PROJECT);
        assertThat(result.detail()).isNotBlank();
    }

    @Test
    void everyDepthSaysWhatItKeeps() {

        // A depth whose cost is not stated is the button people press once and then avoid.
        for (final Podman.Rebuild rebuild : Podman.Rebuild.values()) {
            assertThat(Preparation.describe(rebuild)).isNotBlank();
        }
        assertThat(Preparation.describe(Podman.Rebuild.AGENT)).contains("keep the base image");
    }

    @Test
    void progressIsReportedBeforeTheBuildRatherThanAfterIt(@TempDir Path dir) throws Exception {

        // A build takes minutes, and an interface showing nothing for that long is
        // indistinguishable from one that hung.
        prepare(dir, Podman.Rebuild.CACHED, false);

        assertThat(out.toString()).contains("building").contains("rebuild");
    }
}
