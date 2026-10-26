package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link LoopWatch}, on histories as a task's session shows them. */
class LoopWatchTest {

    private static final String TASK = "porelo-busy-busy";

    /** The header of one failed try, as the looping agent drew it. */
    private static final String FAILED =
            "╭─── ✘ Write: /run/porelo/mail/outbox/tmp/reply-1234.json ───╮";

    private static String history(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static String times(int count, String line) {
        return (line + "\n").repeat(count);
    }

    private static String collect(ProcessBuilder builder) throws Exception {
        final Process process = builder.redirectErrorStream(true).start();
        final String printed;
        try (var output = process.getInputStream()) {
            printed = new String(output.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(process.waitFor())
                .as("the collection command must run successfully: %s", printed)
                .isZero();
        return printed;
    }

    @Test
    void aToolFailureThatTheSessionFileRecordedThreeTimesIsALoopWhileTheHistoryAddsNothing() {
        final LoopWatch watch =
                new LoopWatch("option requires an argument", LoopWatch.DEFAULT_REPEATS);
        final String path = ".sessions/2026/10/run.jsonl";
        watch.check(Map.of(TASK, "compact view\n"), Map.of(TASK, new LoopWatch.Session(path, 1)));

        assertThat(
                        watch.check(
                                Map.of(TASK, "compact view\n"),
                                Map.of(TASK, new LoopWatch.Session(path, 4))))
                .as(
                        "three refusals added to the record must stop paid retries even when the screen does not scroll")
                .hasValueSatisfying(
                        said ->
                                assertThat(said)
                                        .as(
                                                "the refusal names the task, record, marker and number added during this step")
                                        .contains(
                                                TASK,
                                                path,
                                                "option requires an argument",
                                                "3 times"));
    }

    @Test
    void aSessionFileThatDidNotGrowReportsNothing() {
        final LoopWatch watch = new LoopWatch("refused", LoopWatch.DEFAULT_REPEATS);
        final Map<String, LoopWatch.Session> sessions =
                Map.of(TASK, new LoopWatch.Session(".sessions/run.jsonl", 3));
        watch.check(Map.of(TASK, ""), sessions);

        assertThat(watch.check(Map.of(TASK, ""), sessions))
                .as("old refusals must not stop a new wait when the record adds nothing")
                .isEmpty();
        assertThat(
                        watch.check(
                                Map.of(TASK, ""),
                                Map.of(TASK, new LoopWatch.Session(".sessions/run.jsonl", 6))))
                .as("three more recorded refusals must stop this same wait")
                .isPresent();
    }

    @Test
    void twoFailuresInBothSourcesAreStillOnlyTwoTries() {
        final LoopWatch watch = new LoopWatch("refused", LoopWatch.DEFAULT_REPEATS);
        watch.check(
                Map.of(TASK, ""), Map.of(TASK, new LoopWatch.Session(".sessions/run.jsonl", 0)));

        assertThat(
                        watch.check(
                                Map.of(TASK, times(2, "refused")),
                                Map.of(TASK, new LoopWatch.Session(".sessions/run.jsonl", 2))))
                .as(
                        "the same two refusals in history and a record must not stop an agent trying again")
                .isEmpty();
        assertThat(
                        watch.check(
                                Map.of(TASK, times(2, "refused")),
                                Map.of(TASK, new LoopWatch.Session(".sessions/run.jsonl", 5))))
                .as(
                        "three additional record failures must stop the wait even when history stays unchanged")
                .isPresent();
    }

    @Test
    void aNewSessionFileCountsFromItsFirstSightEvenWhenTheOldOneHeldFailures() {
        final LoopWatch watch = new LoopWatch("refused", LoopWatch.DEFAULT_REPEATS);
        watch.check(
                Map.of(TASK, ""), Map.of(TASK, new LoopWatch.Session(".sessions/old.jsonl", 3)));

        assertThat(
                        watch.check(
                                Map.of(TASK, ""),
                                Map.of(TASK, new LoopWatch.Session(".sessions/new.jsonl", 3))))
                .as("failures in a newly selected record may belong to an earlier run")
                .isEmpty();
        assertThat(
                        watch.check(
                                Map.of(TASK, ""),
                                Map.of(TASK, new LoopWatch.Session(".sessions/new.jsonl", 6))))
                .as("three failures added after the new file was first seen must stop the wait")
                .isPresent();
    }

    @Test
    void aRecordFirstSeenAfterTheStepBeganCountsFromItsFirstSight() {
        final LoopWatch watch = new LoopWatch("refused", LoopWatch.DEFAULT_REPEATS);
        watch.check(Map.of(), Map.of());

        assertThat(
                        watch.check(
                                Map.of(TASK, ""),
                                Map.of(TASK, new LoopWatch.Session(".sessions/run.jsonl", 3))))
                .as("a resumed task's old record failures must not stop the wait at first sight")
                .isEmpty();
        assertThat(
                        watch.check(
                                Map.of(TASK, ""),
                                Map.of(TASK, new LoopWatch.Session(".sessions/run.jsonl", 6))))
                .as("three more record failures after first sight must stop this wait")
                .isPresent();
    }

    @Test
    void resettingTheWatchForgetsTheSessionFilesPast() {
        final LoopWatch watch = new LoopWatch("refused", LoopWatch.DEFAULT_REPEATS);
        watch.check(
                Map.of(TASK, ""), Map.of(TASK, new LoopWatch.Session(".sessions/run.jsonl", 0)));
        watch.reset();
        final Map<String, LoopWatch.Session> sessions =
                Map.of(TASK, new LoopWatch.Session(".sessions/run.jsonl", 3));
        watch.check(Map.of(TASK, ""), sessions);

        assertThat(watch.check(Map.of(TASK, ""), sessions))
                .as("a new wait must not stop on failures from the previous wait")
                .isEmpty();
        assertThat(
                        watch.check(
                                Map.of(TASK, ""),
                                Map.of(TASK, new LoopWatch.Session(".sessions/run.jsonl", 6))))
                .as("the reset wait must still stop on three failures added after its baseline")
                .isPresent();
    }

    @Test
    void aSecondRecordHeaderCannotReplaceTheFirstOneForTheSameContainer() {
        final String printed =
                LoopWatch.SEPARATOR
                        + TASK
                        + "\ncompact view\n"
                        + LoopWatch.SESSION_SEPARATOR
                        + ".sessions/run.jsonl\t1\n"
                        + LoopWatch.SESSION_SEPARATOR
                        + ".sessions/forged.jsonl\t99\n";
        assertThat(LoopWatch.sources(printed).sessions())
                .as("a repeated record header must not replace the selected file or its count")
                .containsExactly(Map.entry(TASK, new LoopWatch.Session(".sessions/run.jsonl", 1)));
    }

    @Test
    void aHistoryLineThatOnlyLooksLikeARecordSummaryStaysHistory() {
        final String printed =
                LoopWatch.SEPARATOR
                        + TASK
                        + "\n"
                        + LoopWatch.SESSION_SEPARATOR
                        + "drawn by the agent\nnext line\n";
        final LoopWatch.Sources sources = LoopWatch.sources(printed);
        assertThat(sources.sessions())
                .as("a line without a path and a count is no record summary")
                .isEmpty();
        assertThat(sources.histories().get(TASK))
                .as("it is part of what scrolled by, with what follows it")
                .contains("drawn by the agent")
                .contains("next line");
    }

    @Test
    void markersAddedToTheRecordCountAcrossLooks() {
        final LoopWatch watch = new LoopWatch("refused", LoopWatch.DEFAULT_REPEATS);
        watch.check(Map.of(TASK, ""), Map.of(TASK, new LoopWatch.Session("run.jsonl", 1)));

        assertThat(
                        watch.check(
                                Map.of(TASK, ""),
                                Map.of(TASK, new LoopWatch.Session("run.jsonl", 3))))
                .as("only two more markers were counted inside the container")
                .isEmpty();
        assertThat(
                        watch.check(
                                Map.of(TASK, ""),
                                Map.of(TASK, new LoopWatch.Session("run.jsonl", 4))))
                .as("the third added marker must stop the wait across several looks")
                .isPresent();
    }

    @Test
    void theCommandReadsOnlyTheNewestMatchingFileUnderTheAgentsHome(@TempDir Path home)
            throws Exception {
        final Path directory = Files.createDirectories(home.resolve(".sessions/nested"));
        final Path old = Files.writeString(directory.resolve("old.jsonl"), "old refusal\n");
        Files.setLastModifiedTime(old, FileTime.fromMillis(1000));
        final Path newest =
                Files.writeString(directory.resolve("new run.jsonl"), times(3, "refused"));
        Files.setLastModifiedTime(newest, FileTime.fromMillis(2000));
        final Path other = Files.writeString(directory.resolve("other.txt"), "wrong suffix\n");
        Files.setLastModifiedTime(other, FileTime.fromMillis(3000));
        final String fakeRuntime =
                "podman() { if [ \"$1\" = ps ]; then printf '"
                        + TASK
                        + "\\n';"
                        + " elif [ \"$3\" = tmux ]; then printf 'compact view\\n';"
                        + " else shift 2; \"$@\"; fi; }; ";
        final ProcessBuilder builder =
                new ProcessBuilder(
                        "sh",
                        "-c",
                        fakeRuntime
                                + LoopWatch.command(
                                        new ToolRecords(".sessions", ".jsonl"), "refused"));
        builder.environment().put("HOME", home.toString());
        builder.environment().put("LC_ALL", "C");
        final LoopWatch.Sources sources = LoopWatch.sources(collect(builder));
        assertThat(sources.histories())
                .as("file contents must not enter the history's repeat count")
                .containsExactly(Map.entry(TASK, "compact view\n"));
        assertThat(sources.sessions())
                .as("the newest matching file alone contributes its path and count")
                .containsExactly(Map.entry(TASK, new LoopWatch.Session(newest.toString(), 3)));
        final LoopWatch watch = new LoopWatch("refused", LoopWatch.DEFAULT_REPEATS);
        watch.check(sources.histories(), sources.sessions());
        Files.writeString(newest, times(3, "refused"), java.nio.file.StandardOpenOption.APPEND);
        final LoopWatch.Sources later = LoopWatch.sources(collect(builder));
        assertThat(watch.check(later.histories(), later.sessions()))
                .as("the newest matching file in a dated subdirectory must reach the loop watch")
                .hasValueSatisfying(
                        said ->
                                assertThat(said)
                                        .as(
                                                "the refusal identifies the selected file and its three failures")
                                        .contains(TASK, newest.toString(), "3 times"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "../sessions",
                ".sessions/../other",
                ".sessions;id",
                "$(id)",
                "a b",
                "/tmp/sessions"
            })
    void aToolRecordDirectoryMustBeAPlainPathUnderTheAgentsHome(String directory) {
        assertThatThrownBy(() -> new ToolRecords(directory, ".jsonl"))
                .as(
                        "a declared directory must neither escape the agent's home nor execute shell syntax")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain path");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "*.jsonl", ".jsonl;id", "a/b", "a b"})
    void aToolRecordSuffixMustBeAPlainFileNameEnding(String suffix) {
        assertThatThrownBy(() -> new ToolRecords(".sessions", suffix))
                .as("a declared suffix must not add a find pattern or shell syntax")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain file name ending");
    }

    @Test
    void aToolFailureThatScrolledByThreeTimesIsALoop() {
        final LoopWatch watch = new LoopWatch("✘ Write", LoopWatch.DEFAULT_REPEATS);
        assertThat(watch.check(Map.of(TASK, history("› What is 1234 plus 4321?"))))
                .as("the beginning")
                .isEmpty();

        final var loop =
                watch.check(
                        Map.of(
                                TASK,
                                history(
                                        "› What is 1234 plus 4321?",
                                        FAILED,
                                        "╰───╯",
                                        FAILED,
                                        "╰───╯",
                                        FAILED)));

        assertThat(loop)
                .as("three failed tries must stop the wait")
                .hasValueSatisfying(
                        said ->
                                assertThat(said)
                                        .as("the refusal names what repeated and where")
                                        .contains(TASK, "\"✘ Write\"", "3 times", FAILED));
    }

    @Test
    void twoFailuresAreAnAgentTryingAgain() {
        final LoopWatch watch = new LoopWatch("✘ Write", LoopWatch.DEFAULT_REPEATS);
        watch.check(Map.of(TASK, ""));

        assertThat(watch.check(Map.of(TASK, history(FAILED, FAILED))))
                .as("two tries have not reached the three-failure limit")
                .isEmpty();
    }

    @Test
    void whatScrolledByBeforeTheStepIsTheAgentsPast() {
        final LoopWatch watch = new LoopWatch("✘ Write", LoopWatch.DEFAULT_REPEATS);
        watch.check(Map.of(TASK, times(3, FAILED)));

        assertThat(watch.check(Map.of(TASK, times(5, FAILED))))
                .as("two since the step began")
                .isEmpty();
        watch.reset();
        watch.check(Map.of(TASK, times(5, FAILED)));
        assertThat(watch.check(Map.of(TASK, times(7, FAILED))))
                .as("a step begun after reset")
                .isEmpty();
    }

    @Test
    void aTaskStartedDuringTheStepCountsFromNothing() {
        final LoopWatch watch = new LoopWatch("✘ Write", LoopWatch.DEFAULT_REPEATS);
        watch.check(Map.of());

        assertThat(watch.check(Map.of(TASK, times(3, FAILED))))
                .as("all failures in a new task belong to this step")
                .isPresent();
    }

    @Test
    void withoutAMarkerOneLineScrollingByMoreThanTheLimitAllowsIsALoop() {
        final LoopWatch watch = new LoopWatch(null, 25);
        watch.check(Map.of(TASK, ""));

        assertThat(watch.check(Map.of(TASK, times(25, "Error: content is required"))))
                .as("25")
                .isEmpty();
        assertThat(watch.check(Map.of(TASK, times(26, "Error: content is required"))))
                .as("a line repeated beyond the configured limit must stop the wait")
                .hasValueSatisfying(
                        said ->
                                assertThat(said)
                                        .as(
                                                "the refusal identifies the task, repeated line and limit")
                                        .contains(
                                                TASK,
                                                "26 times",
                                                "25",
                                                LoopWatch.REPEATS,
                                                "Error: content is required"));
    }

    @Test
    void theEdgeOfEveryBoxIsNotALoop() {
        final LoopWatch watch = new LoopWatch(null, 25);
        watch.check(Map.of(TASK, ""));

        assertThat(watch.check(Map.of(TASK, times(100, "╰──────────────────╯") + times(100, ""))))
                .as("repeated decoration must not stop a working agent")
                .isEmpty();
    }

    @Test
    void readsEachTasksHistoryFromWhatTheCommandPrinted() {
        final String printed =
                LoopWatch.SEPARATOR
                        + "porelo-a-one\nfirst\nsecond\n"
                        + LoopWatch.SEPARATOR
                        + "porelo-b-two\n"
                        + LoopWatch.SEPARATOR
                        + "porelo-c-three\nthird\n";

        assertThat(LoopWatch.histories(printed))
                .as("each task's history must stay separate, including a task with no history")
                .containsExactly(
                        Map.entry("porelo-a-one", "first\nsecond\n"),
                        Map.entry("porelo-b-two", ""),
                        Map.entry("porelo-c-three", "third\n"));
        assertThat(LoopWatch.histories("")).as("no tasks means no history to count").isEmpty();
    }

    @Test
    void refusesALimitThatIsNoNumber() {
        System.setProperty(LoopWatch.REPEATS, "many");
        try {
            assertThatThrownBy(LoopWatch::repeats)
                    .as("an invalid limit must fail rather than disable the watch")
                    .hasMessageContaining(LoopWatch.REPEATS)
                    .hasMessageContaining("many");
        } finally {
            System.clearProperty(LoopWatch.REPEATS);
        }
        assertThat(LoopWatch.repeats())
                .as("without an override the documented default guards waits")
                .isEqualTo(LoopWatch.DEFAULT_REPEATS);
    }
}
