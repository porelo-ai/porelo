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
 * A task's row carries its run's role and the gate push it works at, as its start recorded them,
 * and how many review items there are held back from it.
 */
class TaskRunPlaceTest {

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
    void aRowCarriesTheAuthorsRoleAtItsOwnGatePushAndNothingHeld() throws IOException {
        final Path state = context.paths().tasks().containerState(TASK);
        profile(state);
        RunPlace.ownPush("demo", "demo", "fix").writeTo(state);
        runner.answering("ps", row(true));

        final Map<String, Object> row = rows().getFirst();

        assertThat(row).as("the run's role").containsEntry("role", "AUTHOR");
        assertThat(row)
                .as("its own push, the project's own repository left out")
                .containsEntry("place", Map.of("gatePush", Map.of("name", "fix")));
        assertThat(row).as("nothing is held in Porelo yet").containsEntry("held", 0);
    }

    @Test
    void aPushOfAnotherRepositoryOfTheProjectIsNamedByIt() throws IOException {
        final Path state = context.paths().tasks().containerState(TASK);
        profile(state);
        RunPlace.ownPush("demo", "web", "fix").writeTo(state);
        runner.answering("ps", row(true));

        assertThat(rows().getFirst())
                .as("a repository other than the project's own is named")
                .containsEntry(
                        "place", Map.of("gatePush", Map.of("repository", "web", "name", "fix")));
    }

    @Test
    void aTaskWhoseStartRecordedNoPlaceHasNoRoleNoPlaceAndNothingHeld() throws IOException {
        // A task without a gate, or one started before a run's place was recorded.
        profile(context.paths().tasks().containerState(TASK));
        runner.answering("ps", row(true));

        final Map<String, Object> row = rows().getFirst();

        assertThat(row)
                .as("no role and no place without a gate")
                .doesNotContainKeys("role", "place");
        assertThat(row).as("held is never absent").containsEntry("held", 0);
    }

    @Test
    void aTaskTheMachinesRestartTookDownKeepsItsRoleAndPlace() throws IOException {
        // The runtime directory went with the reboot; the record a reboot does not reach holds
        // them.
        final Path record = context.paths().tasks().taskRecord(TASK);
        profile(record);
        RunPlace.ownPush("demo", null, "fix").writeTo(record);
        runner.answering("ps", row(false));

        assertThat(context.paths().tasks().containerState(TASK)).doesNotExist();
        assertThat(rows().getFirst())
                .as("read from the durable record")
                .containsEntry("role", "AUTHOR")
                .containsEntry("place", Map.of("gatePush", Map.of("name", "fix")));
    }

    @Test
    void theRoleAndPlaceAreSavedWhereARebootDoesNotReachThem() throws IOException {
        final Path state = Files.createDirectories(context.paths().tasks().containerState(TASK));
        RunPlace.ownPush("demo", null, "fix").writeTo(state);

        new TaskState(context).save(TASK);

        assertThat(RunPlace.readFrom(context.paths().tasks().taskRecord(TASK)))
                .as("the saved copy")
                .isEqualTo(new RunPlace("AUTHOR", null, "fix"));
    }

    @Test
    void aMalformedRecordGivesNoRoleAndNoPlaceNeverAMadeUpOne() throws IOException {
        final Path state = context.paths().tasks().containerState(TASK);
        profile(state);
        runner.answering("ps", row(true));
        for (final String written :
                List.of(
                        "{\"role\":\"AUTHOR\",\"place\":{\"gatePush\":{\"name\":\"../x\"}}}",
                        "{\"role\":\"AUTHOR\",\"place\":{\"gatePush\":{\"name\":\"\"}}}",
                        "{\"role\":\"AUTHOR\",\"place\":{\"gatePush\":{\"name\":\"Fix It\"}}}",
                        "{\"role\":\"AUTHOR\",\"place\":{\"gatePush\":{\"repository\":\"a/b\",\"name\":\"fix\"}}}",
                        "{\"role\":\"OWNER\",\"place\":{\"gatePush\":{\"name\":\"fix\"}}}",
                        "{\"role\":\"AUTHOR\"}",
                        "not json")) {
            Files.writeString(state.resolve("run-place"), written);

            assertThat(rows().getFirst())
                    .as("refused: %s", written)
                    .doesNotContainKeys("role", "place");
        }
    }

    @Test
    void aTaskMadeAgainWithoutAGateLosesTheSavedPlaceOfTheOneBefore() throws IOException {
        // An earlier task of the name worked at a push, and its place was saved for a reboot.
        final Path state = context.paths().tasks().containerState(TASK);
        profile(state);
        final Path record = context.paths().tasks().taskRecord(TASK);
        Files.createDirectories(record);
        RunPlace.ownPush("demo", null, "fix").writeTo(record);
        runner.answering("ps", row(true));

        RunPlace.record(context, TASK, null);

        assertThat(rows().getFirst())
                .as("no place from the saved copy of the task before")
                .doesNotContainKeys("role", "place");
        assertThat(RunPlace.readFrom(record)).as("the saved copy is gone").isNull();
    }

    @Test
    void aTasksRecordCarriesItsRunsRoleItsPlaceAndNothingHeld() throws IOException {
        new TaskWorkRecords(context)
                .started(
                        TASK,
                        "demo",
                        "demo",
                        null,
                        "an-agent",
                        null,
                        "agent",
                        null,
                        null,
                        RunPlace.ownPush("demo", "demo", "fix"));

        final Map<?, ?> record = recordsAnswered().getFirst();

        assertThat(record.get("role")).as("the run's role").isEqualTo("AUTHOR");
        assertThat(record.get("place"))
                .as("its own gate push")
                .isEqualTo(Map.of("gatePush", Map.of("name", "fix")));
        assertThat(record.get("held")).as("nothing held").isEqualTo(0L);
    }

    @Test
    void aRecordOfATaskWithoutAGateHasNoRoleNoPlaceAndNothingHeld() throws IOException {
        new TaskWorkRecords(context)
                .started(TASK, "demo", "demo", null, "an-agent", null, "agent", null, null, null);

        final Map<?, ?> record = recordsAnswered().getFirst();

        assertThat(record.containsKey("role")).as("no role without a gate").isFalse();
        assertThat(record.containsKey("place")).as("no place without a gate").isFalse();
        assertThat(record.get("held")).as("nothing held").isEqualTo(0L);
    }

    /** What 'porelo task records --json' answers for the project. */
    private List<Map<?, ?>> recordsAnswered() {
        final java.io.StringWriter text = new java.io.StringWriter();
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new java.io.PrintWriter(text));
        assertThat(
                        cli.execute(
                                "task",
                                "records",
                                "--project",
                                "demo",
                                "--since",
                                "2026-01-01T00:00:00Z",
                                "--json"))
                .as("records answered")
                .isZero();
        final List<Map<?, ?>> records = new java.util.ArrayList<>();
        for (final Object record : (List<?>) Json.parseExact(text.toString())) {
            records.add((Map<?, ?>) record);
        }
        return records;
    }

    private List<Map<String, Object>> rows() {
        final TaskInventory inventory = new TaskInventory(context, null);
        return inventory.rows(inventory.tasks());
    }

    /** A profile as a start writes it. */
    private static void profile(final Path state) throws IOException {
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
        Files.writeString(state.resolve(TaskProfile.FILE), Json.write(document));
    }

    private static String row(final boolean running) {
        return TASK
                + "\t"
                + (running ? "Up 4 minutes" : "Exited (0) 1 minute ago")
                + "\t1788500000\t0\tdemo\tguarded\tapp\t\t\n";
    }
}
