package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code porelo task reports} answers what the daemon's Reports answers; records carry them. */
class TaskReportsCommandTest {
    private static final String TASK = "porelo-uc-work";

    /** The container id of the task, as its start recorded it. */
    private static final String ID = "a".repeat(64);

    /** Where the answers recorded for the hand-over are written, under the build's own target. */
    private static final Path HANDOVER = Path.of("..", "..", "target", "handover");

    /**
     * A format character, written escaped: twelve bytes for each, the longest a character of a
     * report can be sent.
     */
    private static final String WIDE = new String(Character.toChars(0xE0041));

    @TempDir Path dir;

    private PoreloContext context() {
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

    /** What one run of the command line wrote, and its exit code. */
    private record Ran(int code, String out, String err) {}

    private static Ran run(final PoreloContext context, final String... arguments) {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final int code = cli.execute(arguments);
        return new Ran(code, out.toString(), err.toString());
    }

    private static Map<String, Object> fields(final Object parsed) {
        final Map<String, Object> fields = new LinkedHashMap<>();
        ((Map<?, ?>) parsed).forEach((key, value) -> fields.put(String.valueOf(key), value));
        return fields;
    }

    private static void recorded(final String name, final String json) throws Exception {
        Files.createDirectories(HANDOVER);
        Files.writeString(HANDOVER.resolve(name), json, StandardCharsets.UTF_8);
    }

    @Test
    void aPageCutAtOneMebibyteSaysMoreAndTheNextPageGoesOn() throws Exception {
        final PoreloContext context = context();
        final TaskReports reports = new TaskReports(context.paths());
        for (int number = 1; number <= 3; number++) {
            reports.report(TASK, ID, null, "status", WIDE.repeat(TaskReports.MOST_TEXT), 3);
        }
        final Ran first = run(context, "task", "reports", TASK, "--after", "0", "--json");
        assertThat(first.code()).as("answered: " + first.err()).isZero();
        recorded("task-reports-more.json", first.out());
        final Map<String, Object> page = fields(Json.parseExact(first.out().strip()));
        assertThat((List<?>) page.get("reports")).as("one widest fits, two do not").hasSize(1);
        assertThat(page)
                .as("the cut is said, and the task's last number")
                .containsEntry("more", true)
                .containsEntry("last", 3L);
        assertThat(page)
                .as("the same answer the daemon's Reports gives")
                .isEqualTo(Json.parseExact(Json.write(reports.page(TASK, 0).asMap())));
        final Map<String, Object> next =
                fields(
                        Json.parseExact(
                                run(context, "task", "reports", TASK, "--after", "1", "--json")
                                        .out()
                                        .strip()));
        assertThat((List<?>) next.get("reports"))
                .as("the next page starts after the last one read")
                .singleElement()
                .satisfies(report -> assertThat(fields(report)).containsEntry("seq", 2L));
    }

    @Test
    void aTaskThatReportedNothingIsAnEmptyAnswerAtZero() throws Exception {
        final Ran ran = run(context(), "task", "reports", TASK, "--after", "0", "--json");
        assertThat(ran.code()).as("answered: " + ran.err()).isZero();
        recorded("task-reports-empty.json", ran.out());
        assertThat(fields(Json.parseExact(ran.out().strip())))
                .as("nothing, at 0, nothing left out")
                .containsEntry("reports", List.of())
                .containsEntry("last", 0L)
                .containsEntry("more", false);
    }

    @Test
    void thePlainFormShowsEachReportsNumberKindAndTextLineByLine() throws Exception {
        final PoreloContext context = context();
        new TaskReports(context.paths())
                .report(TASK, ID, null, "finding", "First line.\r\nSecond.", 1);
        final Ran ran = run(context, "task", "reports", TASK);
        assertThat(ran.code()).as("answered: " + ran.err()).isZero();
        assertThat(ran.out())
                .as("a head line, then the text indented, a carriage return never written")
                .contains("1  ")
                .contains("  finding\n")
                .contains("  First line.\n  Second.\n")
                .doesNotContain("\r");
    }

    @Test
    void thePlainFormEscapesWhatWouldChangeHowALineShows() throws Exception {
        // A task's words are printed under a head line Porelo writes: they must not pass for one.
        final PoreloContext context = context();
        new TaskReports(context.paths())
                .report(TASK, ID, null, "status", "Done.\u200b\u200fNot done.\u061c", 1);
        final Ran ran = run(context, "task", "reports", TASK);
        assertThat(ran.code()).as("answered: " + ran.err()).isZero();
        assertThat(ran.out())
                .as("each format character shown as its escape, never acting")
                .contains("Done.\\u200b\\u200fNot done.\\u061c")
                .doesNotContain("\u200b", "\u200f", "\u061c");
    }

    @Test
    void aNegativeAfterOrANameThatIsNoTasksIsRefused() {
        final PoreloContext context = context();
        assertThat(run(context, "task", "reports", TASK, "--after", "-1").code())
                .as("no number before the first")
                .isEqualTo(64);
        assertThat(run(context, "task", "reports", "../x").code())
                .as("no task's name")
                .isEqualTo(64);
    }

    @Test
    void eachRecordCarriesTheReportsOfItsOwnTask() throws Exception {
        final PoreloContext context = context();
        final TaskWorkRecords records = new TaskWorkRecords(context);
        records.started(TASK, "uc", "work", null, "worker-one", null, "agent");
        new TaskReports(context.paths())
                .report(TASK, ID, records.startedAt(TASK), "status", "Half done.", 2);
        final Ran ran =
                run(
                        context,
                        "task",
                        "records",
                        "--project",
                        "uc",
                        "--since",
                        "2026-01-01",
                        "--json");
        assertThat(ran.code()).as("answered: " + ran.err()).isZero();
        final List<?> answered = (List<?>) Json.parseExact(ran.out().strip());
        assertThat(fields(answered.getFirst()).get("reports"))
                .as("the record carries its task's report as Reports answers it")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .singleElement()
                .satisfies(
                        report ->
                                assertThat(fields(report))
                                        .containsEntry("seq", 1L)
                                        .containsEntry("kind", "status")
                                        .containsEntry("text", "Half done.")
                                        .containsOnlyKeys("seq", "kind", "text", "at"));
    }

    @Test
    void aRecordCarriesTheKeyItsStartWasGivenAndNoneWhenItWasGivenNone() throws Exception {
        final PoreloContext context = context();
        final TaskWorkRecords records = new TaskWorkRecords(context);
        records.started(TASK, "uc", "work", null, "worker-one", null, "agent", null, "offer-17");
        records.started(
                "porelo-uc-other", "uc", "work", null, "worker-one", null, "agent", null, null);
        new TaskReports(context.paths())
                .report(TASK, ID, records.startedAt(TASK), "status", "Half done.", 2);
        final Ran ran =
                run(
                        context,
                        "task",
                        "records",
                        "--project",
                        "uc",
                        "--since",
                        "2026-01-01",
                        "--json");
        assertThat(ran.code()).as("answered: " + ran.err()).isZero();
        final Map<String, Map<String, Object>> byTask = new LinkedHashMap<>();
        for (final Object record : (List<?>) Json.parseExact(ran.out().strip())) {
            byTask.put(String.valueOf(fields(record).get("task")), fields(record));
        }
        assertThat(byTask.get(TASK))
                .as("the caller finds the task it started by its key")
                .containsEntry("callerKey", "offer-17");
        assertThat(byTask.get("porelo-uc-other"))
                .as("a start given no key leaves the field out, never an empty one")
                .doesNotContainKey("callerKey");
        recorded("task-records-caller-key.json", ran.out());
    }
}
