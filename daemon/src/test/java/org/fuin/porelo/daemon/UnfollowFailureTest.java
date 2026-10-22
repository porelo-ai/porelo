package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.app.FollowedProjects;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reports that updates stopped while preserving task data after a failed purge. */
class UnfollowFailureTest {
    @Test
    void aFailedPurgeStopsFollowingAndKeepsTheSurvivingTask(@TempDir Path dir) throws Exception {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final FakeCommandRunner runner = new FakeCommandRunner();
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0)
                        .recognizingTasks();
        final String task = "porelo-uc-shell-1";
        Sidecar.recordId(context.paths().tasks().containerState(task), "a1b2c3d4e5f6");
        runner.answering("ps", task + "\tUp 4 minutes\t\t\tuc\tguarded\t\t\ta1b2c3d4e5f6\n");
        runner.answering("{{.State.Status}}", "running");
        runner.failing("podman rm", 125, "runtime refused");
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, "project:\n  name: uc\n  security_class: guarded\n");
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(context.paths().projects().projectRegistry().resolve("uc"), file + "\n");
        Files.createDirectories(dir.resolve("data/porelo/mirrors/uc.git/objects"));
        final FollowedProjects follows =
                new FollowedProjects(context.paths().projects().followed());
        follows.write(new FollowedProjects.Followed("uc", "fixture.git", "", "", "", ""));
        final Path signers = context.paths().projects().configurationSigners();
        Files.createDirectories(signers.getParent());
        Files.writeString(signers, "uc signer-to-keep\n");
        final Map<String, Object> answer =
                ProjectMethods.unfollow(context, Map.of("name", "uc", "force", true));
        assertThat(answer.get("outcome"))
                .as("the runtime could not finish the requested purge")
                .isEqualTo("FAILED");
        assertThat(follows.find("uc"))
                .as("a failed purge stops updates before deleting project data")
                .isNull();
        assertThat(signers)
                .as("the stopped follow no longer trusts its pinned signer")
                .hasContent("");
        assertThat(answer.get("detail"))
                .as("the daemon names the stopped follow and the failed task removal")
                .asString()
                .contains("following uc has stopped", "task " + task + " was not removed");
        assertThat(context.paths().tasks().containerState(task))
                .as("the surviving task's state remains available")
                .exists();
        assertThat(dir.resolve("data/porelo/mirrors/uc.git"))
                .as("a failed task purge keeps the mirror that can hold unreviewed work")
                .exists();
    }
}
