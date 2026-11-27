package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.Sidecar;
import org.fuin.porelo.wire.TaskProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task's row and record carry its workspace and its four parts, each written out, and the gate
 * push it works at, as its start recorded them, and how many of its items wait for the owner
 * account's decision.
 */
class TaskRunPlaceTest {

    private static final String TASK = "porelo-demo-fix";

    private static final String OTHER = "porelo-demo-other";

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
    void aRowCarriesTheDefaultPartsWrittenOutAtItsOwnGatePushAndNothingHeld() throws IOException {
        final Path state = context.paths().tasks().containerState(TASK);
        profile(state);
        RunPlace.ownPush("demo", "demo", "fix").writeTo(state);
        runner.answering("ps", row(true));

        final Map<String, Object> row = rows().getFirst();

        assertThat(row)
                .as("its workspace and its parts, each written out")
                .containsEntry("workspace", "own")
                .containsEntry("rights", List.of("push", "reply"))
                .containsEntry("readLanded", List.of())
                .containsEntry("writeAs", "author")
                .containsEntry("projectServices", Boolean.TRUE)
                .doesNotContainKeys("role", "readOnly");
        assertThat(row)
                .as("its own push, the project's own repository left out")
                .containsEntry("place", Map.of("gatePush", Map.of("name", "fix")));
        assertThat(row).as("nothing held").containsEntry("held", 0);
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
    void aTaskWhoseStartRecordedNothingShowsPartsHoldingNothingNoPlaceAndNothingHeld()
            throws IOException {
        // One started before a task's parts were recorded.
        profile(context.paths().tasks().containerState(TASK));
        runner.answering("ps", row(true));

        final Map<String, Object> row = rows().getFirst();

        assertThat(row)
                .as("no role and no place without a record")
                .doesNotContainKeys("role", "place");
        assertThat(row)
                .as("the parts that hold nothing, which is what it is given")
                .containsEntry("workspace", "none")
                .containsEntry("rights", List.of())
                .containsEntry("projectServices", Boolean.FALSE);
        assertThat(row).as("held is never absent").containsEntry("held", 0);
    }

    @Test
    void aTaskWithoutAGateShowsTheKindItRecordedAndNoPlace() throws IOException {
        final Path state = context.paths().tasks().containerState(TASK);
        profile(state);
        RunPlace.recordParts(
                state,
                new TaskParts.Asked(null, null, null, null, false, true, false).withoutGate());
        runner.answering("ps", row(true));

        assertThat(rows().getFirst())
                .as("read only on its repository, no right, no place")
                .containsEntry("workspace", "readOnly")
                .containsEntry("rights", List.of())
                .doesNotContainKey("place");
    }

    @Test
    void aTaskTheMachinesRestartTookDownKeepsItsPartsAndPlace() throws IOException {
        // The runtime directory went with the reboot; the record a reboot does not reach holds
        // them.
        final Path record = context.paths().tasks().taskRecord(TASK);
        profile(record);
        RunPlace.ownPush("demo", null, "fix").writeTo(record);
        runner.answering("ps", row(false));

        assertThat(context.paths().tasks().containerState(TASK)).doesNotExist();
        assertThat(rows().getFirst())
                .as("read from the durable record")
                .containsEntry("workspace", "own")
                .containsEntry("place", Map.of("gatePush", Map.of("name", "fix")));
    }

    @Test
    void thePartsAndPlaceAreSavedWhereARebootDoesNotReachThem() throws IOException {
        final Path state = Files.createDirectories(context.paths().tasks().containerState(TASK));
        RunPlace.ownPush("demo", null, "fix").writeTo(state);

        new TaskState(context).save(TASK);

        assertThat(RunPlace.readFrom(context.paths().tasks().taskRecord(TASK)))
                .as("the saved copy")
                .isEqualTo(new RunPlace(TaskParts.ownWork(), null, "fix"));
    }

    @Test
    void aMalformedRecordGivesNoPlaceAndNoRightNeverAMadeUpOne() throws IOException {
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
                    .doesNotContainKeys("role", "place")
                    .containsEntry("rights", List.of());
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

        RunPlace.record(context, TASK, null, TaskParts.withoutGate(false, false));

        assertThat(rows().getFirst())
                .as("no place from the saved copy of the task before")
                .doesNotContainKeys("role", "place");
        assertThat(RunPlace.readFrom(record)).as("the saved copy is gone").isNull();
    }

    @Test
    void aTasksRecordCarriesItsPartsItsPlaceAndNothingHeld() throws IOException {
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
                        TaskParts.ownWork(),
                        RunPlace.ownPush("demo", "demo", "fix"));

        final Map<?, ?> record = recordsAnswered().getFirst();

        assertThat(record.get("workspace")).as("its workspace").isEqualTo("own");
        assertThat(record.get("rights")).as("its rights").isEqualTo(List.of("push", "reply"));
        assertThat(record.containsKey("role")).as("no role").isFalse();
        assertThat(record.get("place"))
                .as("its own gate push")
                .isEqualTo(Map.of("gatePush", Map.of("name", "fix")));
        assertThat(record.get("held")).as("nothing held").isEqualTo(0L);
    }

    @Test
    void aRecordOfATaskWithoutAGateHasItsPartsNoPlaceAndNothingHeld() throws IOException {
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
                        TaskParts.withoutGate(false, false),
                        null);

        final Map<?, ?> record = recordsAnswered().getFirst();

        assertThat(record.containsKey("role")).as("no role").isFalse();
        assertThat(record.get("workspace")).as("no workspace").isEqualTo("none");
        assertThat(record.containsKey("place")).as("no place without a gate").isFalse();
        assertThat(record.get("held")).as("nothing held").isEqualTo(0L);
    }

    @Test
    void aHeldItemRaisesTheRowsCountAndADecisionLowersIt() throws Exception {
        final Path state = context.paths().tasks().containerState(TASK);
        profile(state);
        RunPlace.ownPush("demo", null, "fix").writeTo(state);
        runner.answering("ps", row(true));
        final HoldJournal holds = new HoldJournal(context.paths().gate().reviewHolds());
        final Held held = holdOne(holds);

        assertThat(rows().getFirst()).as("the row with one item held").containsEntry("held", 1);

        holds.decide(TASK, held.id(), held.digest(), Decision.REFUSE);

        assertThat(rows().getFirst()).as("the row once it is decided").containsEntry("held", 0);
    }

    @Test
    void aTasksRecordCountsWhatIsHeldWhileTheTaskIsThere() throws Exception {
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
                        TaskParts.ownWork(),
                        RunPlace.ownPush("demo", "demo", "fix"));
        final HoldJournal holds = new HoldJournal(context.paths().gate().reviewHolds());
        final Held held = holdOne(holds);

        assertThat(recordsAnswered().getFirst().get("held"))
                .as("the record with one item held")
                .isEqualTo(1L);

        holds.decide(TASK, held.id(), held.digest(), Decision.RELEASE);

        assertThat(recordsAnswered().getFirst().get("held"))
                .as("the record once it is decided")
                .isEqualTo(0L);
    }

    @Test
    void aHoldJournalThatCannotBeReadLeavesOutOnlyItsTasksCountAndTheListGoesOn() throws Exception {
        profile(context.paths().tasks().containerState(TASK));
        profile(context.paths().tasks().containerState(OTHER));
        runner.answering("ps", row(TASK, true) + row(OTHER, true));
        holdOne(new HoldJournal(context.paths().gate().reviewHolds()));
        Files.writeString(
                context.paths().gate().reviewHolds().resolve(TASK).resolve("holds"), "not json\n");
        final List<String> said = new java.util.ArrayList<>();

        final TaskInventory inventory = new TaskInventory(context, null);
        final List<Map<String, Object>> rows = inventory.rows(inventory.tasks(), said::add);

        assertThat(rows).as("both tasks listed").hasSize(2);
        assertThat(rows.get(0))
                .as("the row of the task whose journal cannot be read")
                .doesNotContainKey("held");
        assertThat(rows.get(1)).as("the other task's row").containsEntry("held", 0);
        assertThat(said).as("what the log is told, once").hasSize(1);
        assertThat(said.getFirst())
                .as("names the task and the journal")
                .contains(TASK)
                .contains("journal");
    }

    @Test
    void aTasksRecordWhoseHoldJournalCannotBeReadIsAnsweredWithoutItsCount() throws Exception {
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
                        TaskParts.ownWork(),
                        RunPlace.ownPush("demo", "demo", "fix"));
        holdOne(new HoldJournal(context.paths().gate().reviewHolds()));
        Files.writeString(
                context.paths().gate().reviewHolds().resolve(TASK).resolve("holds"), "not json\n");

        final Map<?, ?> record = recordsAnswered().getFirst();
        assertThat(record.get("task")).as("the task's record is answered").isEqualTo(TASK);
        assertThat(record.containsKey("held"))
                .as("the record over a journal that cannot be read has no count")
                .isFalse();
    }

    @Test
    void aStoreBehindASymbolicLinkLeavesOutEveryCountAndTheListGoesOn() throws Exception {
        profile(context.paths().tasks().containerState(TASK));
        profile(context.paths().tasks().containerState(OTHER));
        runner.answering("ps", row(TASK, true) + row(OTHER, true));
        final Path store = context.paths().gate().reviewHolds();
        final Path elsewhere = dir.resolve("elsewhere");
        holdOne(new HoldJournal(elsewhere));
        Files.createDirectories(store.getParent());
        Files.createSymbolicLink(store, elsewhere);
        final List<String> said = new java.util.ArrayList<>();

        final TaskInventory inventory = new TaskInventory(context, null);
        final List<Map<String, Object>> rows = inventory.rows(inventory.tasks(), said::add);

        assertThat(rows).as("both tasks listed").hasSize(2);
        assertThat(rows)
                .as("no row with a count")
                .allSatisfy(row -> assertThat(row).as("a row").doesNotContainKey("held"));
        assertThat(said).as("one line for each task").hasSize(2);
    }

    @Test
    void aJournalThatFailsInAnyOfItsThreeWaysLeavesOutTheCountAndSaysWhy() {
        for (final RuntimeException failure :
                List.of(
                        new java.io.UncheckedIOException(new IOException("journal damaged")),
                        new IllegalStateException("journal refused"),
                        new IllegalArgumentException("journal entry not read"))) {
            final List<String> said = new java.util.ArrayList<>();
            final org.fuin.porelo.gate.review.ReviewHolds failing =
                    (org.fuin.porelo.gate.review.ReviewHolds)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {org.fuin.porelo.gate.review.ReviewHolds.class},
                                    (proxy, method, arguments) -> {
                                        throw failure;
                                    });

            assertThat(TaskInventory.held(failing, TASK, said::add))
                    .as("the count over %s", failure)
                    .isNull();
            assertThat(said)
                    .as("what the log is told over %s", failure)
                    .singleElement()
                    .asString()
                    .contains(TASK);
        }
    }

    @Test
    void oneRecordOfALiveTaskCarriesItsHeldCountNowAndNoneOverAnUnreadableJournal()
            throws Exception {
        final TaskWorkRecords records = new TaskWorkRecords(context);
        records.started(
                TASK,
                "demo",
                "demo",
                null,
                "an-agent",
                null,
                "agent",
                null,
                null,
                TaskParts.ownWork(),
                RunPlace.ownPush("demo", "demo", "fix"));
        final String startedAt = String.valueOf(recordsAnswered().getFirst().get("startedAt"));
        holdOne(new HoldJournal(context.paths().gate().reviewHolds()));

        assertThat(records.record("demo", TASK, startedAt).orElseThrow().get("held"))
                .as("the record with one item held")
                .isEqualTo(1L);

        Files.writeString(
                context.paths().gate().reviewHolds().resolve(TASK).resolve("holds"), "not json\n");

        assertThat(records.record("demo", TASK, startedAt).orElseThrow())
                .as("the record over a journal that cannot be read")
                .doesNotContainKey("held");
    }

    /** Holds one outgoing item of the task's first run at its own push. */
    private static Held holdOne(final HoldJournal holds) {
        final String place = "demo/fix";
        final int number = holds.startRun(TASK, place);
        final Push push = new Push("demo", "demo", "fix", "1".repeat(40), null, "0123456789ab");
        return holds.hold(
                new Run(place, new Pinned(push, null), number),
                TASK,
                Direction.OUTGOING,
                new ReviewReply("t-1", new Body("why")),
                null,
                null);
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
        return row(TASK, running);
    }

    private static String row(final String task, final boolean running) {
        return task
                + "\t"
                + (running ? "Up 4 minutes" : "Exited (0) 1 minute ago")
                + "\t1788500000\t0\tdemo\tguarded\tapp\t\t\n";
    }
}
