package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a task reports back is the host's record, numbered in the task's own sequence. */
class TaskReportsTest {
    private static final String TASK = "porelo-uc-work";
    private static final String FIRST = "2026-11-20T08:00:00Z";
    private static final String LATER = "2026-11-21T08:00:00Z";

    /** The container id of the task, as its start recorded it. */
    private static final String ID = "a".repeat(64);

    /** The container id of a later task of the same name. */
    private static final String LATER_ID = "c".repeat(64);

    /**
     * A format character, written escaped: twelve bytes for each, the longest a character of a
     * report the host keeps can be sent.
     */
    private static final String WIDE = new String(Character.toChars(0xE0041));

    @TempDir Path dir;

    private PoreloPaths paths() {
        final XdgPaths xdg =
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
        return new PoreloPaths(xdg, dir.resolve("bin"));
    }

    private TaskReports reports() {
        return new TaskReports(paths());
    }

    private Path file() {
        return paths().tasks().workRecords().resolve(TASK + ".reports");
    }

    @Test
    void eachReportIsNumberedInTheTasksSequenceAndKeptInOrder() throws Exception {
        final TaskReports reports = reports();
        assertThat(reports.last(TASK)).as("a task that reported nothing is at 0").isZero();
        assertThat(reports.report(TASK, ID, FIRST, "status", "Half done.", 3).seq())
                .as("the first report's number")
                .isEqualTo(1);
        assertThat(reports.report(TASK, ID, FIRST, "finding", "Two.\nStill two.", 3).seq())
                .as("the second report's number")
                .isEqualTo(2);
        final TaskReports.Page page = reports.page(TASK, 0);
        assertThat(page.reports())
                .as("both, oldest first")
                .extracting(TaskReports.Report::seq)
                .containsExactly(1L, 2L);
        assertThat(page.reports().get(1).text())
                .as("line breaks kept as given")
                .isEqualTo("Two.\nStill two.");
        assertThat(page.reports().get(0).asMap())
                .as("what a caller is sent of one")
                .containsOnlyKeys("seq", "kind", "text", "at")
                .containsEntry("kind", "status");
        assertThat(page.last()).as("the last number").isEqualTo(2);
        assertThat(page.more()).as("nothing left out").isFalse();
        assertThat(reports.last(TASK)).as("the row's number").isEqualTo(2);
    }

    @Test
    void aReportPastTheBoundIsRefusedNamingItAndKeepsNothing() throws Exception {
        final TaskReports reports = reports();
        reports.report(TASK, ID, FIRST, "status", "One.", 2);
        reports.report(TASK, ID, FIRST, "status", "Two.", 2);
        assertThatThrownBy(() -> reports.report(TASK, ID, FIRST, "status", "Three.", 2))
                .as("a third is past the bound of two")
                .isInstanceOfSatisfying(
                        TaskReports.BoundReached.class,
                        ex -> assertThat(ex.most()).as("the bound it names").isEqualTo(2));
        assertThatThrownBy(() -> reports().report("porelo-uc-none", ID, null, "status", "Zero.", 0))
                .as("a bound of 0 allows none")
                .isInstanceOf(TaskReports.BoundReached.class);
        assertThat(reports.last(TASK)).as("the refused one kept nothing").isEqualTo(2);
    }

    @Test
    void aLaterTaskOfTheNameHasABoundOfItsOwnAndGoesOnWithTheSequence() throws Exception {
        // A caller that remembers the last number it read would skip a sequence started again.
        final TaskReports reports = reports();
        reports.report(TASK, ID, FIRST, "status", "Of the removed task.", 1);
        assertThat(reports.report(TASK, LATER_ID, LATER, "status", "Of the next task.", 1).seq())
                .as("the next task of the name goes on with the name's sequence")
                .isEqualTo(2);
        assertThat(reports.page(TASK, 0).reports())
                .as("the removed task's report is still read")
                .extracting(TaskReports.Report::text)
                .containsExactly("Of the removed task.", "Of the next task.");
        assertThat(reports.ofRecord(TASK, FIRST))
                .as("each record carries its own task's reports")
                .extracting(TaskReports.Report::seq)
                .containsExactly(1L);
        assertThat(reports.ofRecord(TASK, LATER))
                .extracting(TaskReports.Report::seq)
                .containsExactly(2L);
    }

    @Test
    void afterAnswersOnlyTheLaterOnes() throws Exception {
        final TaskReports reports = reports();
        for (int number = 1; number <= 3; number++) {
            reports.report(TASK, ID, FIRST, "status", "Report " + number + ".", 3);
        }
        assertThat(reports.page(TASK, 1).reports())
                .as("the ones after 1")
                .extracting(TaskReports.Report::seq)
                .containsExactly(2L, 3L);
        final TaskReports.Page none = reports.page(TASK, 3);
        assertThat(none.reports()).as("none after the last").isEmpty();
        assertThat(none.last()).as("the last number still told").isEqualTo(3);
        assertThat(none.more()).as("nothing left out").isFalse();
    }

    @Test
    void aPageCarriesAtMostOneMebibyteAndSaysThatMoreFollow() throws Exception {
        final TaskReports reports = reports();
        final String widest = WIDE.repeat(TaskReports.MOST_TEXT);
        for (int number = 1; number <= 3; number++) {
            reports.report(TASK, ID, FIRST, "status", widest, 3);
        }
        final TaskReports.Page first = reports.page(TASK, 0);
        assertThat(
                        Json.write(first.reports().stream().map(TaskReports.Report::asMap).toList())
                                .getBytes(StandardCharsets.UTF_8)
                                .length)
                .as("the reports as sent fit in 1 MiB")
                .isLessThanOrEqualTo(TaskReports.MOST_PAGE);
        assertThat(first.reports()).as("one widest report fits, two do not").hasSize(1);
        assertThat(first.more()).as("the cut is said").isTrue();
        assertThat(first.last()).as("the last number is the task's").isEqualTo(3);
        final TaskReports.Page next = reports.page(TASK, first.reports().getLast().seq());
        assertThat(next.reports())
                .as("the next page goes on where the first stopped")
                .extracting(TaskReports.Report::seq)
                .containsExactly(2L);
        assertThat(next.reports().getFirst().text()).as("a report is never cut").isEqualTo(widest);
    }

    @Test
    void theRecordIsAppendedToAndOnlyTheHostReadsIt() throws Exception {
        // Rewriting the whole record on each report would grow with every report a task sends.
        final TaskReports reports = reports();
        reports.report(TASK, ID, FIRST, "status", "One.", 2);
        final byte[] before = Files.readAllBytes(file());
        reports.report(TASK, ID, FIRST, "status", "Two.", 2);
        final byte[] after = Files.readAllBytes(file());
        assertThat(java.util.Arrays.copyOf(after, before.length))
                .as("what was kept stays as it was written")
                .isEqualTo(before);
        assertThat(Files.getPosixFilePermissions(file()))
                .as("the account's own")
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(file().startsWith(paths().messaging().mailbox(TASK)))
                .as("outside every task mount")
                .isFalse();
    }

    @Test
    void aLineAWriteLeftUnfinishedIsNeverReadAndItsNumberIsGivenToTheNext() throws Exception {
        // A report whose write broke off was never answered, so its number was never told.
        final TaskReports reports = reports();
        reports.report(TASK, ID, FIRST, "status", "Whole.", 3);
        Files.writeString(file(), "{\"seq\":2,\"kind\":\"st", StandardOpenOption.APPEND);
        assertThat(reports.page(TASK, 0).reports())
                .as("only the whole one")
                .extracting(TaskReports.Report::seq)
                .containsExactly(1L);
        assertThat(reports.last(TASK)).as("the row's number").isEqualTo(1);
        assertThat(reports.report(TASK, ID, FIRST, "status", "Next.", 3).seq())
                .as("the next report takes the number never told")
                .isEqualTo(2);
        assertThat(reports.page(TASK, 0).reports())
                .extracting(TaskReports.Report::text)
                .containsExactly("Whole.", "Next.");
    }

    @Test
    void aRecordTheHostDidNotWriteIsNeverNumberedOn() throws Exception {
        final TaskReports reports = reports();
        reports.report(TASK, ID, FIRST, "status", "One.", 3);
        Files.writeString(file(), "not json\n", StandardOpenOption.APPEND);
        assertThatThrownBy(() -> reports.report(TASK, ID, FIRST, "status", "Two.", 3))
                .as("an unreadable record is no empty one")
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> reports.page(TASK, 0))
                .as("nor read as one")
                .isInstanceOf(IOException.class);
    }

    @Test
    void reportsAtTheSameTimeAreEachNumberedOnceWithinTheBound() throws Exception {
        final TaskReports reports = reports();
        final List<Long> numbers = Collections.synchronizedList(new ArrayList<>());
        try (var pool = Executors.newFixedThreadPool(8)) {
            final List<Future<?>> calls = new ArrayList<>();
            for (int call = 0; call < 20; call++) {
                calls.add(
                        pool.submit(
                                () -> {
                                    try {
                                        numbers.add(
                                                reports.report(TASK, ID, FIRST, "status", "x", 12)
                                                        .seq());
                                    } catch (TaskReports.BoundReached refused) {
                                        // Past the bound: expected for eight of them.
                                    }
                                    return null;
                                }));
            }
            for (final Future<?> call : calls) {
                call.get();
            }
        }
        assertThat(numbers).as("twelve kept, each number once").hasSize(12).doesNotHaveDuplicates();
        assertThat(reports.last(TASK)).isEqualTo(12);
    }

    @Test
    void aKindIsOneLineOfOneTo64CharactersAndATextAtMost65536() {
        assertThat(TaskReports.validKind("finding")).as("a word").isTrue();
        final String face = new String(Character.toChars(0x1F600));
        assertThat(TaskReports.validKind(face.repeat(64))).as("64 code points").isTrue();
        assertThat(TaskReports.validKind(face.repeat(65))).as("65 code points").isFalse();
        assertThat(TaskReports.validKind("")).as("empty").isFalse();
        assertThat(TaskReports.validKind("a\nb")).as("two lines").isFalse();
        assertThat(TaskReports.validKind("a b")).as("a line separator").isFalse();
        assertThat(TaskReports.validKind("a\tb")).as("a tab").isFalse();
        assertThat(TaskReports.validKind("\ud800")).as("a lone surrogate").isFalse();
        assertThat(TaskReports.validText("It is.\r\nAnd so.")).as("line breaks").isTrue();
        assertThat(TaskReports.validText(WIDE.repeat(65_536))).as("65,536 code points").isTrue();
        assertThat(TaskReports.validText("x".repeat(65_537))).as("65,537").isFalse();
        assertThat(TaskReports.validText("")).as("nothing").isFalse();
        assertThat(TaskReports.validText("a\tb")).as("a tab").isFalse();
        assertThat(TaskReports.validText("a\u001bb")).as("an escape").isFalse();
    }

    @Test
    void aKindRefusesEveryFormatCharacterAndATextRefusesSeparatorsAndBidiControls() {
        // Either would let a task's words show in another order, or forge a line, on a screen.
        for (final int point : new int[] {0x200b, 0x200e, 0x202e, 0x2066, 0xfeff, 0xe0041}) {
            assertThat(TaskReports.validKind("a" + new String(Character.toChars(point)) + "b"))
                    .as("a kind holding format character U+%04X", point)
                    .isFalse();
        }
        for (final int point :
                new int[] {
                    0x2028, 0x2029, 0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2066, 0x2067, 0x2068,
                    0x2069
                }) {
            assertThat(TaskReports.validText("a" + new String(Character.toChars(point)) + "b"))
                    .as("a text holding U+%04X", point)
                    .isFalse();
        }
        assertThat(TaskReports.validText("a\u200bb"))
                .as("a zero-width space is kept, and escaped where it is shown")
                .isTrue();
    }

    @Test
    void theBoundIsKeptByTheTasksContainerWhateverItsRecordSays() throws Exception {
        // A record missing or changing under a removal must neither reset nor share a count.
        final TaskReports reports = reports();
        reports.report(TASK, ID, null, "status", "Without a record.", 1);
        assertThatThrownBy(() -> reports.report(TASK, ID, FIRST, "status", "With one now.", 1))
                .as("the same container is at its bound whatever record it names")
                .isInstanceOf(TaskReports.BoundReached.class);
        assertThat(reports.report(TASK, LATER_ID, null, "status", "Another container.", 1).seq())
                .as(
                        "another container of the name inherits no count, though its record is null too")
                .isEqualTo(2);
        assertThatThrownBy(() -> reports.report(TASK, "", null, "status", "Nobody's.", 1))
                .as("a report for no container is refused, never counted")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRowsNumberIsReadFromTheEndAcrossLinesLongerThanOneBlock() throws Exception {
        // The row reads the last line from the file's end in blocks; a line spans many of them.
        final TaskReports reports = reports();
        reports.report(TASK, ID, FIRST, "status", WIDE.repeat(4_000), 3);
        reports.report(TASK, ID, FIRST, "status", WIDE.repeat(4_000), 3);
        assertThat(reports.last(TASK)).as("two lines of about 48 KiB each").isEqualTo(2);
        Files.writeString(file(), "x".repeat(20_000), StandardOpenOption.APPEND);
        assertThat(reports.last(TASK))
                .as("a broken-off write longer than a block is passed over")
                .isEqualTo(2);
    }
}
