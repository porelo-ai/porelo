package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for finding the owner of retained task files. */
class TaskHistoryTest {

    @TempDir Path dir;

    private PoreloContext context() {
        return new PoreloContext(
                new FakeCommandRunner(),
                new PoreloPaths(
                        new XdgPaths(
                                dir.resolve("config"),
                                dir.resolve("data"),
                                dir.resolve("state"),
                                dir.resolve("run"),
                                dir),
                        dir.resolve("bin")),
                arguments -> 0);
    }

    @Test
    void anUnverifiableNameRefusesOnlyALookupOfItsOwnTask() throws Exception {
        // On the test VM one task's surviving folder refused every gate of its project.
        final PoreloContext context = context();
        TaskIdentityFixture.unverifiable(context, "porelo-web-a", "web", "a", "a".repeat(64));
        TaskIdentityFixture.record(context.paths(), "porelo-web-b", "web", "b", "b".repeat(64));

        final TaskIdentity found = TaskHistory.find(context.paths(), "web", "b");

        assertThat(found).as("another task of the project is still found").isNotNull();
        assertThat(found.container()).isEqualTo("porelo-web-b");
        assertThat(TaskHistory.find(context.paths(), "other", "a"))
                .as("a task of another project with the same name is not refused either")
                .isNull();
        assertThatThrownBy(() -> TaskHistory.find(context.paths(), "web", "a"))
                .as("the unverifiable task itself still refuses, in the same words")
                .isInstanceOf(ProjectException.class)
                .hasMessage(
                        "historical ownership cannot verify the surviving task files; list tasks"
                                + " before acting");
    }

    @Test
    void anUnverifiableNameWithNoReservationIsSkipped() throws Exception {
        final PoreloContext context = context();
        java.nio.file.Files.createDirectories(context.paths().tasks().taskRecord("porelo-web-a"));
        TaskIdentityFixture.record(context.paths(), "porelo-web-b", "web", "b", "b".repeat(64));

        assertThat(TaskHistory.find(context.paths(), "web", "a")).isNull();
        assertThat(TaskHistory.find(context.paths(), "web", "b")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnreadableRecordRefusesOnlyItsOwnTaskAndSaysWhatToDo() throws Exception {
        // A task started by an older Porelo, its record lacking a field now required, refused
        // every gate in the account, and told the person to list tasks, which changed nothing.
        final PoreloContext context = context();
        TaskIdentityFixture.record(context.paths(), "porelo-web-b", "web", "b", "b".repeat(64));
        TaskIdentityFixture.record(context.paths(), "porelo-web-a", "web", "a", "a".repeat(64));
        final Path older =
                context.paths().tasks().taskRecord("porelo-web-a").resolve("sidecar.json");
        final java.util.Map<String, Object> sidecar =
                new java.util.LinkedHashMap<>(
                        (java.util.Map<String, Object>)
                                org.fuin.porelo.wire.Json.parseExact(
                                        java.nio.file.Files.readString(older)));
        sidecar.remove("limits");
        java.nio.file.Files.writeString(older, org.fuin.porelo.wire.Json.write(sidecar));

        assertThat(TaskHistory.find(context.paths(), "web", "b"))
                .as("another task of the account is still found")
                .isNotNull();
        assertThatThrownBy(() -> TaskHistory.find(context.paths(), "web", "a"))
                .as("the task itself is refused by its name, with what to do")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("porelo-web-a")
                .hasMessageContaining("started again");
    }
}
