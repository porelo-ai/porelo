package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.Sidecar;
import org.fuin.porelo.wire.TaskProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A program that starts a task finds it again by a key of its own: List's row carries it, after a
 * restart too, and a person's label never touches it.
 */
class TaskCallerKeyTest {

    private static final String TASK = "porelo-demo-fix";

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context;

    @BeforeEach
    void machine() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    default -> null;
                                },
                        dir);
        context = new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    @Test
    void aRowCarriesTheKeyItsStartWasGiven() throws IOException {
        // The window that handed a planner's offer to this task finds it again by the offer's key.
        profile(context.paths().tasks().containerState(TASK), "offer 17/a");
        runner.answering("ps", row(true));

        assertThat(rows().getFirst()).containsEntry("callerKey", "offer 17/a");
    }

    @Test
    void aTaskStartedWithoutAKeyHasAnEmptyOne() throws IOException {
        profile(context.paths().tasks().containerState(TASK), null);
        runner.answering("ps", row(true));

        assertThat(rows().getFirst()).as("never absent").containsEntry("callerKey", "");
    }

    @Test
    void aTaskTheMachinesRestartTookDownStillCarriesItsKey() throws IOException {
        // The runtime directory went with the reboot; the record saved where a reboot does not
        // reach is what the row is read from.
        final Path record = context.paths().tasks().taskRecord(TASK);
        profile(record, "offer-17");
        runner.answering("ps", row(false));

        assertThat(context.paths().tasks().containerState(TASK)).doesNotExist();
        assertThat(rows().getFirst()).containsEntry("callerKey", "offer-17");
    }

    @Test
    void aRuntimeDirectoryWithoutTheKeyLeavesItToTheRecord() throws IOException {
        // Made again after a reboot before anything was put back into it: what it lacks is not
        // "no key", and the caller would take the task for one it never started.
        profile(context.paths().tasks().taskRecord(TASK), "offer-17");
        Files.createDirectories(context.paths().tasks().containerState(TASK));
        runner.answering("ps", row(false));

        assertThat(rows().getFirst()).containsEntry("callerKey", "offer-17");
    }

    @Test
    void aStartThatFailedBeforeItsProfileWasWrittenStillCarriesItsKey() throws IOException {
        // Kept beside the starting branch, before anything else of the start can fail.
        final Path state = Files.createDirectories(context.paths().tasks().containerState(TASK));
        Files.writeString(state.resolve("caller-key"), "offer-17");
        runner.answering("ps", row(false));

        assertThat(rows().getFirst()).containsEntry("callerKey", "offer-17");
    }

    @Test
    void theEarlyKeyIsSavedWhereARebootDoesNotReachIt() throws IOException {
        final Path state = Files.createDirectories(context.paths().tasks().containerState(TASK));
        Files.writeString(state.resolve("caller-key"), "offer-17");

        new TaskState(context).save(TASK);

        assertThat(context.paths().tasks().taskRecord(TASK).resolve("caller-key"))
                .hasContent("offer-17");
    }

    @Test
    void aLabelLeavesTheKeyAsItsStartGaveIt() throws IOException {
        // A caption is a person's and changes; the key is the caller's and does not.
        final Path state = context.paths().tasks().containerState(TASK);
        profile(state, "offer-17");
        runner.answering("ps", row(true));

        assertThat(new TaskControl(context).label(TASK, "second attempt"))
                .isEqualTo(TaskControl.Labelled.LABELLED);
        assertThat(new TaskControl(context).label(TASK, null))
                .isEqualTo(TaskControl.Labelled.CLEARED);

        assertThat(rows().getFirst()).containsEntry("callerKey", "offer-17");
        assertThat(
                        (Map<?, ?>)
                                Json.parse(
                                        Files.readString(
                                                context.paths()
                                                        .tasks()
                                                        .taskRecord(TASK)
                                                        .resolve(TaskProfile.FILE))))
                .as("and the saved copy a reboot reads")
                .extracting(document -> document.get("callerKey"))
                .isEqualTo("offer-17");
    }

    private List<Map<String, Object>> rows() {
        final TaskInventory inventory = new TaskInventory(context, null);
        return inventory.rows(inventory.tasks());
    }

    /** A profile as a start writes it, the key among its fields when it was given one. */
    private static void profile(final Path state, final String key) throws IOException {
        Files.createDirectories(state);
        new Sidecar(
                        Sidecar.VERSION,
                        "demo",
                        "guarded",
                        state.resolve("r.nft").toString(),
                        state.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        state.toString())
                .writeTo(state.resolve("sidecar.json"));
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", TaskProfile.VERSION);
        document.put("agent", "an-agent");
        document.put("mode", "agent");
        document.put("startedAt", "2026-11-12T10:00:00Z");
        document.put("clearance", "prompt");
        if (key != null) {
            document.put("callerKey", key);
        }
        Files.writeString(state.resolve(TaskProfile.FILE), Json.write(document));
    }

    private static String row(final boolean running) {
        return TASK
                + "\t"
                + (running ? "Up 4 minutes" : "Exited (0) 1 minute ago")
                + "\t1788500000\t0\tdemo\tguarded\tapp\t\t\n";
    }
}
