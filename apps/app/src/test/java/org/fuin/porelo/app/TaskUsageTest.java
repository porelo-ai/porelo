package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Map;
import org.fuin.porelo.core.UsageSums;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prevents missing counts becoming zero and billing history disappearing on removal. */
class TaskUsageTest {
    private static final String TASK = "porelo-uc-work";

    // Synthetic broker records include an aborted answer so unknown usage cannot look free.
    private static final String RECORD =
            """
            {"time":"2026-01-01T22:30:00Z","agent":"worker-one","model_answered":"model-a","input":70,"cache_read":30,"cache_write":null,"output":20,"reasoning":5,"end":"complete"}
            {"time":"2026-01-01T23:30:00Z","agent":"worker-one","model_answered":"model-a","input":7,"cache_read":null,"cache_write":null,"output":null,"reasoning":null,"end":"aborted"}
            {"time":"2026-01-02T00:30:00Z","agent":"worker-two","model_answered":null,"input":null,"cache_read":null,"cache_write":null,"output":null,"reasoning":null,"end":"error"}
            """;

    private PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    private Path record(PoreloContext context) throws Exception {
        final Path file = context.paths().tasks().usageRecord(TASK);
        Files.createDirectories(file.getParent());
        Files.writeString(file, RECORD);
        return file;
    }

    @Test
    void sumsEveryReportedFieldAndCountsUnknownRequestsInLocalTime(@TempDir Path dir)
            throws Exception {
        final var usage = UsageSums.read(record(context(dir)), ZoneId.of("Europe/Berlin"));
        final var wire = usage.asMap();
        assertThat(wire.get("totals"))
                .as("aborted and unknown fields stay visible")
                .isEqualTo(
                        Map.of(
                                "requests",
                                3L,
                                "input",
                                Map.of("tokens", 77L, "missing", 1L),
                                "cacheRead",
                                Map.of("tokens", 30L, "missing", 2L),
                                "cacheWrite",
                                count(null, 3),
                                "output",
                                Map.of("tokens", 20L, "missing", 2L),
                                "reasoning",
                                Map.of("tokens", 5L, "missing", 2L)));
        assertThat(usage.models().stream().map(UsageSums.Group::name))
                .as("unknown answering models have their own group")
                .containsExactly("model-a", "unknown");
        assertThat(usage.agents().stream().map(UsageSums.Group::name))
                .as("agents are summed without a join")
                .containsExactly("worker-one", "worker-two");
        assertThat(usage.days().stream().map(UsageSums.Group::name))
                .as("dates use the host's selected local time")
                .containsExactly("2026-01-01", "2026-01-02");
        assertThat(usage.hours().stream().map(UsageSums.Group::name))
                .as("hours include their offset")
                .containsExactly(
                        "2026-01-01T23:00+01:00",
                        "2026-01-02T00:00+01:00",
                        "2026-01-02T01:00+01:00");
    }

    private Map<String, Object> count(Long tokens, long missing) {
        final var count = new java.util.LinkedHashMap<String, Object>();
        count.put("tokens", tokens);
        count.put("missing", missing);
        return count;
    }

    @Test
    void statusAndItsJsonReadTheSameUsageForARemovedTask(@TempDir Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path file = record(context);
        new TaskUsage(context).archive(TASK);
        assertThat(Files.exists(file))
                .as("a reused task name starts a fresh usage record")
                .isFalse();
        final StringWriter text = new StringWriter();
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(text));
        assertThat(cli.execute("task", "status", TASK, "--json"))
                .as("a removed task's billing history is readable")
                .isZero();
        final var json = (Map<?, ?>) Json.parse(text.toString());
        assertThat(json.get("usage"))
                .as("status exposes the same sums as the service")
                .isEqualTo(Json.parse(Json.write(new TaskUsage(context).read(TASK).asMap())));
        text.getBuffer().setLength(0);
        cli.setErr(new PrintWriter(new StringWriter()));
        assertThat(cli.execute("task", "status", TASK))
                .as("no such task still answers as it did, for whoever scripts on it")
                .isEqualTo(69);
        assertThat(text.toString())
                .as("a person sees grouping and missing counts")
                .contains("model-a", "worker-one", "missing", "77");
    }

    @Test
    void aReusedTaskNameDoesNotReadThePreviousTasksCounts(@TempDir Path dir) throws Exception {
        final PoreloContext context = context(dir);
        record(context);
        new TaskUsage(context).archive(TASK);
        Files.createDirectories(context.paths().tasks().containerState(TASK));
        assertThat(new TaskUsage(context).read(TASK).state())
                .as("a new task with no answers has no usage yet")
                .isEqualTo("absent");
    }

    @Test
    void aRemovedTaskWithNoGenerationsDoesNotReadAnEarlierIncarnationsUsage(@TempDir Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        record(context);
        new TaskUsage(context).archive(TASK);
        new TaskUsage(context).archive(TASK);
        assertThat(new TaskUsage(context).read(TASK).totals().requests())
                .as("the most recently removed task made no generation calls")
                .isZero();
    }

    @Test
    void malformedAndMissingRecordsAreDifferentOutcomes(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("usage.jsonl");
        assertThat(UsageSums.read(file, ZoneId.of("UTC")).state())
                .as("missing history is absent")
                .isEqualTo("absent");
        Files.writeString(file, "x".repeat(2 * 1024 * 1024) + "\n");
        assertThat(UsageSums.read(file, ZoneId.of("UTC")).state())
                .as("failed history is never presented as no usage")
                .isEqualTo("unreadable");
    }

    @Test
    void aTaskParameterCannotReadOutsideTheUsageDirectory(@TempDir Path dir) throws Exception {
        final PoreloContext context = context(dir);
        final Path record = record(context);
        final String around =
                "porelo-work/../" + record.getFileName().toString().replace(".jsonl", "");

        assertThat(new TaskUsage(context).read(around).state())
                .as("the daemon's task argument is a name, never a file path")
                .isEqualTo("absent");
    }

    @Test
    void aHalfWrittenLastLineLeavesTheRestOfTheRecordReadable(@TempDir Path dir) throws Exception {
        // A broker killed mid-line must not cost a task's whole history.
        final Path file = record(context(dir));
        Files.writeString(file, "{\"time\":\"2026", java.nio.file.StandardOpenOption.APPEND);

        final var usage = UsageSums.read(file, ZoneId.of("UTC"));

        assertThat(usage.state()).as("state").isEqualTo("available");
        assertThat(usage.totals().requests()).as("the complete lines").isEqualTo(3);
    }

    @Test
    void aBrokenLineCountsAsARequestOfWhichNothingIsKnown(@TempDir Path dir) throws Exception {
        // The next line of a broker that follows a killed one joins its half line. That request
        // was billed, so it stays visible rather than taking the others with it.
        final Path file = record(context(dir));
        Files.writeString(
                file, "{\"time\":\"2026{\"a\":1}\n", java.nio.file.StandardOpenOption.APPEND);

        final var usage = UsageSums.read(file, ZoneId.of("UTC"));

        assertThat(usage.state()).as("state").isEqualTo("available");
        assertThat(usage.totals().requests()).as("every line").isEqualTo(4);
        assertThat(usage.totals().fields().getFirst().missing()).as("input missing").isEqualTo(2);
        assertThat(usage.totals().fields().getFirst().tokens()).as("input").isEqualTo(77L);
    }

    @Test
    void aNameTooLongForARecordIsAnsweredAsAnAbsentTask(@TempDir Path dir) {
        // A playbook asks for a task that is not there and reads the answer.
        final PoreloContext context = context(dir);
        final String name = "porelo-uc-" + "x".repeat(100);
        final StringWriter text = new StringWriter();
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(text));
        cli.setErr(new PrintWriter(new StringWriter()));

        assertThat(cli.execute("task", "status", name, "--json")).as("--json").isZero();
        assertThat(((Map<?, ?>) Json.parse(text.toString())).get("state"))
                .as("state")
                .isEqualTo("absent");
        assertThat(cli.execute("task", "status", name)).as("text").isEqualTo(69);
    }

    @Test
    void aRecordThatCannotBeRetainedDoesNotStopARemoval(@TempDir Path dir) throws Exception {
        // The task is gone by then: what follows in a removal has still to run.
        final PoreloContext context = context(dir);
        final Path usage = context.paths().tasks().usageRecord(TASK).getParent();
        Files.createDirectories(usage.getParent());
        Files.writeString(usage, "a file where the directory goes");

        new TaskUsage(context).archive(TASK);
        new TaskUsage(context).archive("x".repeat(100));

        assertThat(usage).as("left as it was").isRegularFile();
    }

    @Test
    void theNewestRemovedRecordIsFoundByItsNameNotItsFileTime(@TempDir Path dir) throws Exception {
        // Files restored onto a machine all carry the time of the restore.
        final PoreloContext context = context(dir);
        final Path directory = context.paths().tasks().usageRecord(TASK).getParent();
        Files.createDirectories(directory);
        final String line = RECORD.lines().findFirst().orElseThrow() + "\n";
        final Path older = directory.resolve(TASK + ".removed-1000000000000.jsonl");
        final Path newer = directory.resolve(TASK + ".removed-2000000000000.jsonl");
        Files.writeString(older, line + line);
        Files.writeString(newer, line);
        Files.setLastModifiedTime(
                newer, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000L));

        assertThat(new TaskUsage(context).read(TASK).totals().requests()).isEqualTo(1);
    }

    @Test
    void aUsageDirectoryThatCannotBeListedIsUnreadableForARemovedTask(@TempDir Path dir)
            throws Exception {
        // Status answers a program: it must say so, not stop with an exception.
        final PoreloContext context = context(dir);
        final Path directory = context.paths().tasks().usageRecord(TASK).getParent();
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(
                directory, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        try {
            assertThat(new TaskUsage(context).read(TASK).state()).isEqualTo("unreadable");
        } finally {
            Files.setPosixFilePermissions(
                    directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aBrokenLineStaysInEveryGroupSoTheyAddUpToTheTotal(@TempDir Path dir) throws Exception {
        final Path file = record(context(dir));
        Files.writeString(file, "broken\n", java.nio.file.StandardOpenOption.APPEND);

        final var usage = UsageSums.read(file, ZoneId.of("UTC"));

        assertThat(usage.models().stream().mapToLong(group -> group.totals().requests()).sum())
                .as("models")
                .isEqualTo(4);
        assertThat(usage.days().stream().mapToLong(group -> group.totals().requests()).sum())
                .as("days")
                .isEqualTo(4);
    }

    @Test
    void aModelNameWithATildeIsSummedUnderItsName(@TempDir Path dir) throws Exception {
        final Path file = record(context(dir));
        Files.writeString(
                file,
                "{\"time\":\"2026-01-01T22:30:00Z\",\"agent\":\"a\",\"model_answered\":\"~v/m\",\"input\":1}\n");

        final var usage = UsageSums.read(file, ZoneId.of("UTC"));

        assertThat(usage.models().stream().map(UsageSums.Group::name)).containsExactly("~v/m");
    }
}
