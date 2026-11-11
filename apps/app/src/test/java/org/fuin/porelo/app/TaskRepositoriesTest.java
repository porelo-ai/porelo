package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests how the repositories a task reads reach it, without a container runtime. */
class TaskRepositoriesTest {

    private static final String TASK = "porelo-shop-all";

    @TempDir Path dir;

    private final FakeCommandRunner commands =
            new FakeCommandRunner().answering(" create ", "0123456789abcdef");

    private PoreloPaths paths() {
        return new PoreloPaths(
                new XdgPaths(
                        dir.resolve("config"),
                        dir.resolve("data"),
                        dir.resolve("state"),
                        dir.resolve("run"),
                        dir),
                dir.resolve("bin"));
    }

    private void start() throws IOException {
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                """
                project:
                  name: shop
                  security_class: guarded
                image:
                  from: ubuntu:24.04
                """),
                        "test");
        new TaskRunner(commands, paths()) {
            @Override
            public void startScreenWriter(String container) {}
        }.start(project, TASK, new PrintWriter(new StringWriter()));
    }

    @Test
    void theRepositoriesAreTheWorkspaceAndCannotBeWritten() throws IOException {
        final TaskRepositories reading = new TaskRepositories(paths(), TASK);
        Files.createDirectories(reading.directory().resolve("front"));
        Files.createDirectories(reading.directory().resolve("back"));
        // Nothing but a repository's directory is one of them, whatever lies beside them.
        Files.writeString(reading.directory().resolve("back.tar"), "");

        start();

        final Command create = commands.only(" create ");
        assertThat(create.arguments())
                .as("mounted read only where any task finds its workspace")
                .containsSequence("--volume", reading.directory() + ":/workspace:ro,Z");
        assertThat(reading.names()).containsExactly("back", "front");
    }

    @Test
    void aTaskOnOneRepositoryMountsNoneOfThem() throws IOException {
        start();

        assertThat(commands.only(" create ").arguments())
                .as("its workspace is a clone inside the container, never a host directory")
                .noneMatch(argument -> argument.endsWith(":/workspace:ro,Z"));
    }

    @Test
    void itsRemovalTakesWhatItRead() throws IOException {
        final TaskRepositories reading = new TaskRepositories(paths(), TASK);
        Files.createDirectories(reading.directory().resolve("front").resolve("src"));
        Files.writeString(reading.directory().resolve("front").resolve("src/app.js"), "x\n");
        Files.createSymbolicLink(
                reading.directory().resolve("front").resolve("outside"), dir.resolve("config"));
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config").resolve("kept"), "mine\n");

        reading.delete();

        assertThat(reading.directory()).doesNotExist();
        assertThat(dir.resolve("config").resolve("kept"))
                .as("a link in a repository is removed, never followed")
                .exists();
    }
}
