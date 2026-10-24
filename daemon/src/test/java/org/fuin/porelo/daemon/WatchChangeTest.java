package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.AgentWaiting;
import org.fuin.porelo.app.HandIns;
import org.fuin.porelo.app.Mailbox;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.TaskBuilds;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchChangeTest {

    private static TaskInventory.Task task(
            final String state, final HandIns.Summary handIns, final TaskBuilds.View builds) {
        return new TaskInventory.Task(
                "porelo-uc-shell-2",
                "uc",
                "online",
                state,
                true,
                1,
                "example",
                "AGENT",
                null,
                "refs/heads/shell",
                "2026-10-06T12:00:00Z",
                TaskInventory.Activity.WORKING,
                null,
                "prompt",
                null,
                0,
                "RUNNING",
                "",
                "uc",
                null,
                AgentWaiting.Derived.NOTHING,
                Map.of(),
                Map.of(),
                null,
                null,
                handIns,
                builds);
    }

    private static PoreloContext context(Path dir) {
        final var runner = new FakeCommandRunner();
        runner.answering("ps", "porelo-uc-shell-2\tUp 4 minutes\t1788500000\t0\tuc\tguarded\n");
        final var xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void aLogWrittenAgainDoesNotChangeTheList(@TempDir Path dir) throws IOException {
        // Resending a busy task list on every log line floods an open Watch.
        final var context = context(dir);
        final var state = context.paths().tasks().containerState("porelo-uc-shell-2");
        Files.createDirectories(state);
        final var log = Files.writeString(state.resolve("task.log"), "first\n");
        final var first = Instant.parse("2026-10-01T10:00:00Z");
        final var second = first.plusSeconds(1);
        Files.setLastModifiedTime(log, FileTime.from(first));
        final var before = new TaskInventory(context).tasks().getFirst();
        Files.writeString(log, "second\n");
        Files.setLastModifiedTime(log, FileTime.from(second));
        final var after = new TaskInventory(context).tasks().getFirst();

        assertThat(after.asMap().get("lastWrote"))
                .as("the reply carries the new timestamp even when it does not trigger Watch")
                .isEqualTo(second.toString());
        assertThat(before.asMap().get("lastWrote"))
                .as("the two observations really have different log write times")
                .isEqualTo(first.toString());
        assertThat(PoreloDaemon.withoutAge(after))
                .as("a log timestamp alone must not resend the list")
                .isEqualTo(PoreloDaemon.withoutAge(before));
        Files.setLastModifiedTime(log, FileTime.from(Instant.now().plusSeconds(3600)));
        final var working = new TaskInventory(context).tasks().getFirst();
        assertThat(working.activity())
                .as("fresh output changes quiet to working")
                .isEqualTo(TaskInventory.Activity.WORKING);
        assertThat(PoreloDaemon.withoutAge(working))
                .as("an activity change must still resend the list")
                .isNotEqualTo(PoreloDaemon.withoutAge(after));
    }

    @Test
    void aNewMessageChangesTheList(@TempDir Path dir) throws IOException {
        // An interface must learn that a task has messages waiting without polling another call.
        final var context = context(dir);
        final var before = new TaskInventory(context).tasks().getFirst();
        final var mailbox = new Mailbox(context.paths().messaging().mailbox("porelo-uc-shell-2"));
        mailbox.create();
        Files.writeString(mailbox.inboxNew().resolve("one.json"), "{}");
        final var after = new TaskInventory(context).tasks().getFirst();

        assertThat(PoreloDaemon.withoutAge(after))
                .as("a new inbox message must resend the list")
                .isNotEqualTo(PoreloDaemon.withoutAge(before));
    }

    private static final TaskBuilds.Seen RUNNING =
            new TaskBuilds.Seen(
                    "c".repeat(40),
                    "running",
                    List.of(),
                    "2026-10-06T12:00:00Z",
                    "",
                    "0123456789ab");

    @Test
    void theAgeOfTheStateIsNoChange() {
        assertThat(
                        PoreloDaemon.withoutAge(
                                task(
                                        "Up 3 seconds",
                                        HandIns.Summary.none(),
                                        TaskBuilds.View.none())))
                .isEqualTo(
                        PoreloDaemon.withoutAge(
                                task(
                                        "Up 4 seconds",
                                        HandIns.Summary.none(),
                                        TaskBuilds.View.none())));
    }

    @Test
    void everythingAClientIsSentIsAChangeTheBuildsAndTheHandedFilesAmongIt() {
        final String before =
                PoreloDaemon.withoutAge(
                        task(
                                "Up 3 seconds",
                                HandIns.Summary.none(),
                                new TaskBuilds.View("stub-forge", "", List.of())));

        assertThat(
                        PoreloDaemon.withoutAge(
                                task(
                                        "Up 3 seconds",
                                        HandIns.Summary.none(),
                                        new TaskBuilds.View("stub-forge", "", List.of(RUNNING)))))
                .as("a push's build")
                .isNotEqualTo(before);
        assertThat(
                        PoreloDaemon.withoutAge(
                                task(
                                        "Up 3 seconds",
                                        HandIns.Summary.none(),
                                        new TaskBuilds.View(
                                                "stub-forge", "not installed", List.of()))))
                .as("a reader's problem")
                .isNotEqualTo(before);
        assertThat(
                        PoreloDaemon.withoutAge(
                                task(
                                        "Up 3 seconds",
                                        new HandIns.Summary(
                                                "0123456789ab",
                                                64,
                                                List.of(
                                                        new HandIns.HandedFile(
                                                                "spec.pdf",
                                                                3,
                                                                "a".repeat(64),
                                                                "2026-10-06T12:00:00Z",
                                                                "core",
                                                                "0123456789ab"))),
                                        new TaskBuilds.View("stub-forge", "", List.of()))))
                .as("a handed-in file")
                .isNotEqualTo(before);
    }
}
