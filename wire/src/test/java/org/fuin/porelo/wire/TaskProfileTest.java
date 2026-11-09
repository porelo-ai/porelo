package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link TaskProfile}: what a task recorded of its model, read back as it was meant. */
class TaskProfileTest {

    @Test
    void aTasksModelAndWhereItCameFromAreReadBackAsRecorded(@TempDir Path dir) throws IOException {
        new TaskProfile(
                        TaskProfile.VERSION,
                        "agent-a",
                        TaskMode.UNATTENDED,
                        "do the work",
                        null,
                        "2026-11-08T00:00:00Z",
                        "deny",
                        null,
                        "provider-a",
                        "model-a",
                        null,
                        0,
                        null,
                        "project",
                        "Model A")
                .writeTo(dir);

        final TaskProfile read = TaskProfile.readFrom(dir);

        assertThat(read).as("the record is there").isNotNull();
        assertThat(read.modelFrom())
                .as("the row says the project chose it, not the start")
                .isEqualTo("project");
        assertThat(read.modelLabel()).as("the label the file gave it").isEqualTo("Model A");
        assertThat(read.model()).as("the model it runs on").isEqualTo("model-a");
    }

    @Test
    void aRecordFromBeforeSaysAModelCameFromTheStartAndNoneFromTheAgent(@TempDir Path dir)
            throws IOException {

        // Before the source was recorded only a start could name a model.
        final Path named = Files.createDirectories(dir.resolve("named"));
        Files.writeString(
                named.resolve(TaskProfile.FILE),
                "{\"version\":8,\"agent\":\"agent-a\",\"mode\":\"unattended\",\"model\":\"model-a\"}");
        final Path none = Files.createDirectories(dir.resolve("none"));
        Files.writeString(
                none.resolve(TaskProfile.FILE),
                "{\"version\":8,\"agent\":\"agent-a\",\"mode\":\"unattended\",\"model\":\"\"}");

        assertThat(TaskProfile.readFrom(named).modelFrom())
                .as("a model a task from before carries was its start's")
                .isEqualTo("start");
        assertThat(TaskProfile.readFrom(none).modelFrom())
                .as("a task from before without one runs on its agent's own")
                .isEqualTo("agent");
        assertThat(TaskProfile.readFrom(named).modelLabel()).as("nothing gave it a label").isNull();
    }
}
