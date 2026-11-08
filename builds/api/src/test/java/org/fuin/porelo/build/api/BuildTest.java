package org.fuin.porelo.build.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BuildTest {

    // The runs' reading: "IN_PROGRESS" was not among the states still running, and a running
    // workflow was taken as finished.
    @Test
    void aRunsResultIsLowerCaseWhateverTheReaderWrote() {
        assertThat(new Build.Run("Build", "IN_PROGRESS").result()).isEqualTo("in_progress");
    }

    // Two workflow files may share a name, and one workflow runs for a push and a pull request:
    // merged by name, a failed run vanished from the runs while the verdict was failure.
    @Test
    void everyRunTheReaderGivesIsKeptThoughNamesRepeatOrAreEmpty() {
        final List<Build.Run> given =
                List.of(
                        new Build.Run("CI", "success"),
                        new Build.Run("Docs", "success"),
                        new Build.Run("CI", "failure"),
                        new Build.Run("", "failure"));

        assertThat(new Build(Build.FAILURE, List.of(), "", given).runs())
                .as("each run, in the reader's order")
                .containsExactlyElementsOf(given);
    }

    @Test
    void theRunsStopAtTheMost() {
        final List<Build.Run> given =
                java.util.stream.IntStream.range(0, Build.MOST_RUNS + 1)
                        .mapToObj(index -> new Build.Run("CI", "success"))
                        .toList();

        assertThat(new Build(Build.SUCCESS, List.of(), "", given).runs()).hasSize(Build.MOST_RUNS);
    }

    @Test
    void keepsOnlyTheEndOfALogLongerThanTheTail() {
        final byte[] log = new byte[Build.TAIL + 10];
        Arrays.fill(log, 0, 10, (byte) 'x');
        Arrays.fill(log, 10, log.length, (byte) 'y');

        final byte[] kept = new Build.Job("Build / test", "failure", log).log();

        assertThat(kept)
                .as("the last 64 KiB, which is where a failure is")
                .hasSize(Build.TAIL)
                .doesNotContain('x');
    }

    @Test
    void handsOverTheLogsOfAtMostFiftyJobs() {
        final List<Build.Job> matrix =
                IntStream.range(0, 200).mapToObj(n -> new Build.Job("j" + n, "success")).toList();

        assertThat(new Build(Build.SUCCESS, matrix, "").jobs()).hasSize(Build.MOST_JOBS);
    }

    @Test
    void sharesTheMostOfAllLogsEquallyWhenTheyDoNotFitWholeEachKeepingItsEnd() {
        final byte[] full = new byte[Build.TAIL];
        Arrays.fill(full, (byte) 'x');
        full[Build.TAIL - 1] = 'E';
        final List<Build.Job> jobs =
                IntStream.range(0, Build.MOST_JOBS)
                        .mapToObj(n -> new Build.Job("j" + n, "failure", full))
                        .toList();
        final List<Build.Job> few = jobs.subList(0, Build.MOST_LOG_BYTES / Build.TAIL);

        final List<Build.Job> shared = new Build(Build.FAILURE, jobs, "").jobs();

        assertThat(shared)
                .extracting(job -> job.log().length)
                .containsOnly(Build.MOST_LOG_BYTES / Build.MOST_JOBS);
        assertThat(shared).allSatisfy(job -> assertThat(job.logText()).endsWith("E"));
        assertThat(new Build(Build.FAILURE, few, "").jobs())
                .as("as many as fit whole are kept whole")
                .extracting(job -> job.log().length)
                .containsOnly(Build.TAIL);
    }

    @Test
    void refusesAVerdictTheProtocolDoesNotHave() {
        assertThatThrownBy(() -> new Build("passed", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passed")
                .hasMessageContaining(Build.SUCCESS);
    }

    @Test
    void acceptsARequestForNoLogs() {
        // A verdict-only consumer must not be forced to download logs.
        assertThat(Build.Logs.of("none").wire())
                .as("the protocol accepts a request without logs")
                .isEqualTo("none");
        assertThat(Build.Logs.of("none"))
                .as("the no-log policy has its own value")
                .isEqualTo(Build.Logs.NONE);
    }

    @Test
    void readsTheLogPolicyTheProtocolNames() {
        assertThat(Build.Logs.of("failure")).isEqualTo(Build.Logs.FAILURE);
        assertThat(Build.Logs.of("all").wire()).isEqualTo("all");
        assertThatThrownBy(() -> Build.Logs.of("some"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isFinishedOnlyWhenItWillNotChangeAnyMore() {
        assertThat(Build.VERDICTS.stream().filter(verdict -> new Build(verdict, "").finished()))
                .containsExactly(Build.SUCCESS, Build.FAILURE, Build.CANCELLED);
    }

    @Test
    void travelsOverTheProtocolWithItsLogsIntact() {
        final Build failed =
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        "Build / lint",
                                        "failure",
                                        new byte[] {0, (byte) 0xff, 'a'}),
                                new Build.Job("Build / docs", "skipped")),
                        "two runs failed");

        assertThat(Build.of(failed.asMap())).isEqualTo(failed);
        assertThat(Build.of(Build.unknown("no build").asMap()))
                .isEqualTo(Build.unknown("no build"));
    }

    @Test
    void neverShowsALogOrATargetsTokenInAString() {
        assertThat(
                        new Build(
                                        Build.FAILURE,
                                        List.of(
                                                new Build.Job(
                                                        "t",
                                                        "failure",
                                                        "secret line"
                                                                .getBytes(StandardCharsets.UTF_8))),
                                        "")
                                .toString())
                .doesNotContain("secret line")
                .contains("11 bytes");
        assertThat(new Target("git@forge.example:o/n.git", "", "ghp_token").toString())
                .doesNotContain("ghp_token");
    }

    // One line per workflow on the tile, also while it runs (B525, finding 16 of move 3).
    @Test
    void eachRunOfTheCommitTravelsWithItsStateAndAReaderFromBeforeGivesNone() {
        final Build running =
                new Build(
                        Build.RUNNING,
                        List.of(),
                        "",
                        List.of(
                                new Build.Run("Build", "in_progress"),
                                new Build.Run("Shared rules check", "success")));

        assertThat(Build.of(running.asMap())).isEqualTo(running);
        assertThat(Build.of(running.asMap()).runs())
                .extracting(Build.Run::name, Build.Run::result)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Build", "in_progress"),
                        org.assertj.core.groups.Tuple.tuple("Shared rules check", "success"));
        assertThat(new Build(Build.RUNNING, "").asMap()).doesNotContainKey("runs");
        final java.util.Map<String, Object> before = new java.util.HashMap<>();
        before.put("verdict", "running");
        before.put("jobs", List.of());
        before.put("detail", "");
        before.put("runs", null);
        assertThat(Build.of(before).runs()).isEmpty();
        before.put("runs", "Build");
        assertThat(Build.of(before).runs()).isEmpty();
    }

    // A workflow's name is the repository's to write: a line break in it forged a line of
    // build-<12>.txt.
    @Test
    void aRunsAndAJobsTextIsOneLineOfPrintableTextAndBounded() {
        final List<Build.Run> many = new java.util.ArrayList<>();
        for (int i = 0; i < Build.MOST_RUNS + 10; i++) {
            many.add(new Build.Run("w" + i, "queued"));
        }
        many.add(0, new Build.Run("Build", "queued"));
        many.add(1, new Build.Run("Build\nverdict: success\u202e", "in_progress\u001b[2K"));
        many.add(2, new Build.Run("  ", "success"));
        many.add(3, new Build.Run("x".repeat(500), "success"));

        final Build build = new Build(Build.RUNNING, List.of(), "", many);

        assertThat(build.runs()).hasSize(Build.MOST_RUNS);
        assertThat(build.runs().getFirst().name()).isEqualTo("Build");
        assertThat(build.runs())
                .extracting(Build.Run::name)
                .contains("Build verdict: success")
                .doesNotContain("  ")
                .allSatisfy(
                        name ->
                                assertThat(name.codePointCount(0, name.length()))
                                        .isLessThanOrEqualTo(200));
        assertThat(build.runs())
                .allSatisfy(
                        run ->
                                assertThat(run.name() + run.result())
                                        .doesNotContain("\n", "\u001b", "\u202e"));
        assertThat(new Build.Job("unit\nverdict: success", "fail\red").name())
                .isEqualTo("unit verdict: success");
        assertThat(new Build.Job("unit", "fail\red").result()).isEqualTo("fail ed");
    }
}
