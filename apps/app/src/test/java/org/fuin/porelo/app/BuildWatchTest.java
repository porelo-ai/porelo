package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildRefused;
import org.fuin.porelo.build.api.PullRequest;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildWatchTest {

    private static final String TASK = "porelo-demo-shell-1";

    private static final String RUN = "0123456789ab";

    private static final String BASE = "b".repeat(40);

    private static final String FIRST = "1".repeat(40);

    private static final String SECOND = "2".repeat(40);

    @TempDir Path dir;

    private TaskPaths paths;

    private TaskBuilds builds;

    private Instant now = Instant.parse("2026-10-06T12:00:00Z");

    /**
     * The forge: where the branch points and what each commit's build is; a refusal replaces every
     * answer.
     */
    private String head = BASE;

    private final Map<String, Build> forge = new HashMap<>();

    private BuildRefused refusal;

    /**
     * The forge's answer to opening the task's pull request: {@code null}, the default, for a
     * reader that opens none; a refusal of its own, so the builds go on being asked.
     */
    private PullRequest pullRequest;

    private static final PullRequest OPENED =
            new PullRequest("https://forge.example/owner/name/pulls/7", true);

    private BuildRefused pullRequestRefusal;

    /** The branches a pull request was asked to be opened from, in order. */
    private final List<String> opening = new ArrayList<>();

    private BuildWatch.NotNow notNow;

    /** What keeps the watch from asking, each time it changed; "" once it asks again. */
    private final List<String> troubles = new ArrayList<>();

    private final List<String> looked = new ArrayList<>();

    /** What the task was handed, in order, by name, with its content. */
    private final List<Map.Entry<String, String>> handed = new ArrayList<>();

    private final Map<String, String> files = new LinkedHashMap<>();

    /** Whether the next hand-in fails, as a full or gone task's does. */
    private boolean handInFails;

    @BeforeEach
    void aRunningTask() throws IOException {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        paths = new PoreloPaths(xdg, dir.resolve("bin")).tasks();
        Files.createDirectories(paths.containerState(TASK));
        Sidecar.recordId(paths.containerState(TASK), RUN);
        builds = new TaskBuilds(paths);
    }

    private BuildWatch watch() {
        return new BuildWatch(
                new BuildWatch.Forge() {
                    @Override
                    public String head(final String branch) {
                        assertThat(branch).isEqualTo(TASK);
                        refuse();
                        return head;
                    }

                    @Override
                    public Build build(final String commit) {
                        refuse();
                        looked.add(commit);
                        return forge.getOrDefault(
                                commit,
                                Build.unknown("no build of " + commit.substring(0, 12) + " yet"));
                    }

                    @Override
                    public PullRequest openPullRequest(final String branch) {
                        refuse();
                        opening.add(branch);
                        if (pullRequestRefusal != null) {
                            throw pullRequestRefusal;
                        }
                        final PullRequest answer = pullRequest;
                        if (answer != null) {
                            pullRequest = new PullRequest(answer.url(), false);
                        }
                        return answer;
                    }
                },
                (name, file) -> {
                    if (handInFails) {
                        throw new IOException("the task's files are full");
                    }
                    final byte[] content = Files.readAllBytes(file);
                    final String text = new String(content, StandardCharsets.UTF_8);
                    handed.add(Map.entry(name, text));
                    files.put(name, text);
                    return new HandIns.HandedFile(
                            name,
                            content.length,
                            HandIns.sha256Of(file),
                            now.toString(),
                            HandIns.PORELO,
                            RUN);
                },
                builds,
                TASK,
                RUN,
                TASK,
                paths.containerState(TASK).resolve("builds"),
                clock(),
                duration -> {
                    now = now.plus(duration);
                },
                troubles::add);
    }

    private void refuse() {
        if (refusal != null) {
            throw refusal;
        }
        if (notNow != null) {
            throw notNow;
        }
    }

    @Test
    void adapterMetadataReachesTheTasks1BuildAnswer() throws IOException {
        // Losing metadata on delivery leaves the daemon unable to distinguish triggers or name
        // failures.
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(
                FIRST,
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        "unit",
                                        "failure",
                                        null,
                                        "71",
                                        List.of("Suite.fails"),
                                        "https://forge.example/jobs/7")),
                        "",
                        List.of(new Build.Run("Build", "failure", "push", "71"))));
        watch.round();
        final Map<String, Object> answer = builds.current(TASK).getFirst().asMap();
        assertThat(answer.get("runs"))
                .as("the daemon's run fields come from the adapter")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "name", "Build", "result", "failure", "event", "push", "id",
                                        "71")));
        assertThat(answer.get("jobs"))
                .as("the daemon's job fields come from the adapter")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "name",
                                        "unit",
                                        "result",
                                        "failure",
                                        "log",
                                        "",
                                        "run",
                                        "71",
                                        "failedTests",
                                        List.of("Suite.fails"),
                                        "url",
                                        "https://forge.example/jobs/7")));
    }

    @Test
    void failedTestsArrivingAfterTheVerdictReachTheDaemonAndSurviveResume() throws Exception {
        // A later test report must not vanish just because the verdict and job names stayed the
        // same.
        final BuildWatch before = watch();
        before.round();
        head = FIRST;
        forge.put(
                FIRST,
                new Build(
                        Build.FAILURE,
                        List.of(new Build.Job("unit", "failure", null, "71", null, null)),
                        "",
                        List.of(new Build.Run("Build", "failure", "push", "71"))));
        before.round();
        final BuildWatch resumed = watch();
        resumed.run(() -> false);
        forge.put(
                FIRST,
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        "unit",
                                        "failure",
                                        null,
                                        "71",
                                        List.of("Suite.fails"),
                                        "https://forge.example/jobs/7")),
                        "",
                        List.of(new Build.Run("Build", "failure", "push", "71"))));
        later(BuildWatch.SETTLED);
        resumed.round();
        assertThat(builds.current(TASK).getFirst().asMap().get("jobs"))
                .as("new failure details replace the earlier record after resume")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "name",
                                        "unit",
                                        "result",
                                        "failure",
                                        "log",
                                        "",
                                        "run",
                                        "71",
                                        "failedTests",
                                        List.of("Suite.fails"),
                                        "url",
                                        "https://forge.example/jobs/7")));
    }

    @Test
    void followsOnlyWhatTheTaskPushesAndHandsInEachVerdictAsItChanges() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        assertThat(looked)
                .as("where the branch was before the task pushed is not the task's")
                .isEmpty();

        head = FIRST;
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        watch.round();
        watch.round();
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        later(BuildWatch.POLL);
        watch.round();
        watch.round();

        assertThat(handed)
                .extracting(Map.Entry::getKey)
                .as("running once, success once, nothing twice")
                .containsExactly("build-111111111111.txt", "build-111111111111.txt");
        assertThat(files.get("build-111111111111.txt"))
                .contains("commit: " + FIRST)
                .contains("verdict: success")
                .contains("since: 2026-10-06T12:00:20Z");
        assertThat(looked)
                .as("asked while running, twice, and at success; a finished one not every round")
                .hasSize(3);
        assertThat(builds.current(TASK))
                .singleElement()
                .satisfies(
                        seen -> {
                            assertThat(seen.verdict()).isEqualTo(Build.SUCCESS);
                            assertThat(seen.run()).isEqualTo(RUN);
                        });
    }

    @Test
    void aWorkflowThatStartsAfterAFinalVerdictIsFollowedAndReplacesTheFile() throws IOException {
        // On porelo-move-3 the Shared rules check passed first, and the Build started by hand
        // later was never told (findings 16 and 20).
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(
                FIRST,
                new Build(
                        Build.SUCCESS,
                        List.of(new Build.Job("Shared rules check", Build.SUCCESS)),
                        ""));
        watch.round();
        later(BuildWatch.SETTLED);
        forge.put(
                FIRST,
                new Build(
                        Build.RUNNING,
                        List.of(
                                new Build.Job("Shared rules check", Build.SUCCESS),
                                new Build.Job("Build and unit tests", Build.RUNNING)),
                        ""));
        watch.round();
        forge.put(
                FIRST,
                new Build(
                        Build.SUCCESS,
                        List.of(
                                new Build.Job("Shared rules check", Build.SUCCESS),
                                new Build.Job("Build and unit tests", Build.SUCCESS)),
                        ""));
        watch.round();

        assertThat(handed)
                .extracting(Map.Entry::getValue)
                .extracting(text -> text.lines().filter(l -> l.startsWith("verdict:")).findFirst())
                .as("success, running again, success again")
                .containsExactly(
                        java.util.Optional.of("verdict: success"),
                        java.util.Optional.of("verdict: running"),
                        java.util.Optional.of("verdict: success"));
        assertThat(files.get("build-111111111111.txt"))
                .contains("job: Build and unit tests - success");
    }

    // One line per workflow on the tile (move 3, finding 16). A run moving from queued to running
    // is no news for the agent: a rewritten file wakes it.
    @Test
    void eachRunIsWrittenDownAsItMovesButOnlyAChangedVerdictIsHandedIn() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(
                FIRST,
                new Build(
                        Build.RUNNING,
                        List.of(),
                        "",
                        List.of(
                                new Build.Run("Build", "queued"),
                                new Build.Run("Shared rules check", "queued"))));
        watch.round();
        forge.put(
                FIRST,
                new Build(
                        Build.RUNNING,
                        List.of(),
                        "",
                        List.of(
                                new Build.Run("Build", "in_progress"),
                                new Build.Run("Shared rules check", "success"))));
        watch.round();

        assertThat(handed).as("handed in once, at the first verdict").hasSize(1);
        assertThat(files.get("build-111111111111.txt"))
                .contains("run: Build - queued", "run: Shared rules check - queued");
        assertThat(builds.current(TASK))
                .singleElement()
                .satisfies(
                        seen -> {
                            assertThat(seen.runs())
                                    .containsExactly(
                                            new TaskBuilds.Run("Build", "in_progress"),
                                            new TaskBuilds.Run("Shared rules check", "success"));
                            assertThat(seen.asMap()).containsKey("runs");
                        });
    }

    // On porelo-move-3 the Shared rules check could fail while the Build still ran.
    @Test
    void aFailureWhileAnotherWorkflowRunsIsAskedAgainAtTheNextRound() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(
                FIRST,
                new Build(
                        Build.FAILURE,
                        List.of(new Build.Job("Shared rules check / check", "failure")),
                        "",
                        List.of(
                                new Build.Run("Shared rules check", "failure"),
                                new Build.Run("Build", "in_progress"))));
        watch.round();
        looked.clear();

        watch.round();

        assertThat(looked).as("not settled while a run is in progress").containsExactly(FIRST);
    }

    @Test
    void aRecordLineFromBeforeTheRunsIsNotHandedInAgainWhenTheReaderNamesThem() throws Exception {
        final BuildWatch before = watch();
        before.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        before.round();
        handed.clear();

        final BuildWatch after = watch();
        final int[] rounds = {0};
        after.run(() -> rounds[0]++ < 1);
        later(BuildWatch.SETTLED);
        forge.put(
                FIRST,
                new Build(
                        Build.SUCCESS, List.of(), "", List.of(new Build.Run("Build", "success"))));
        after.round();

        assertThat(handed).as("nothing new for the agent").isEmpty();
        assertThat(builds.current(TASK).getFirst().runs())
                .containsExactly(new TaskBuilds.Run("Build", "success"));
    }

    @Test
    void aJobNamedWithALineBreakIsOneLineOfTheFile() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(
                FIRST,
                new Build(
                        Build.FAILURE,
                        List.of(new Build.Job("x\nverdict: success", "failure")),
                        ""));
        watch.round();

        assertThat(files.get("build-111111111111.txt").lines())
                .filteredOn(line -> line.startsWith("verdict:"))
                .containsExactly("verdict: failure");
    }

    // The verdict rule's reading: a resumed watch kept a commit settled whose last line was
    // running.
    @Test
    void aResumedWatchAsksAgainEveryRoundForACommitWhoseLastLineIsRunning() throws Exception {
        final BuildWatch before = watch();
        before.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        before.round();
        later(BuildWatch.SETTLED);
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        before.round();

        final BuildWatch after = watch();
        final int[] rounds = {0};
        after.run(() -> rounds[0]++ < 1);
        looked.clear();
        after.round();

        assertThat(looked).as("not settled: it runs").contains(FIRST);
    }

    // The verdict rule's reading: a final verdict whose hand-in failed was settled all the same,
    // and
    // could be dropped at the deadline without the task ever being told.
    @Test
    void aFinalVerdictWhoseHandInFailedIsAskedAndHandedInAgainAtTheNextRound() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        watch.round();
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        handInFails = true;
        watch.round();
        handInFails = false;
        looked.clear();

        watch.round();

        assertThat(looked).containsExactly(FIRST);
        assertThat(files.get("build-111111111111.txt")).contains("verdict: success");
    }

    // The verdict rule's reading: a forge that shows no build of a final commit any more is not a
    // workflow that started.
    @Test
    void aForgeThatLaterKnowsNoBuildLeavesAFinalVerdictAsItWas() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        watch.round();
        later(BuildWatch.SETTLED.plus(BuildWatch.GRACE));
        forge.put(FIRST, Build.unknown("no build of it"));

        watch.round();

        assertThat(files.get("build-111111111111.txt")).contains("verdict: success");
    }

    @Test
    void aFinishedCommitIsLeftAloneOnlyAtTheDeadlineAfterItsLastChange() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        watch.round();
        later(BuildWatch.DEADLINE.minus(BuildWatch.SETTLED));
        looked.clear();
        watch.round();
        assertThat(looked).as("still asked within the deadline").containsExactly(FIRST);

        later(BuildWatch.SETTLED.multipliedBy(2));
        watch.round();
        looked.clear();
        watch.round();
        later(BuildWatch.SETTLED);
        watch.round();
        assertThat(looked).as("past the deadline after its last change").isEmpty();
    }

    // The runs' reading: a failure beside a workflow that never ends was asked every 20 s for the
    // task's whole life, the deadline never reached.
    @Test
    void aFailureBesideARunThatNeverEndsIsLeftAtTheDeadline() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(
                FIRST,
                new Build(
                        Build.FAILURE,
                        List.of(),
                        "",
                        List.of(
                                new Build.Run("Shared rules check", "failure"),
                                new Build.Run("Build", "in_progress"))));
        watch.round();
        later(BuildWatch.DEADLINE.plus(BuildWatch.POLL));
        watch.round();
        looked.clear();

        watch.round();

        assertThat(looked).isEmpty();
        assertThat(files.get("build-111111111111.txt")).contains("verdict: failure");
    }

    // The runs' reading: the task's file kept "Build - in_progress" for good once only the runs
    // changed; the last run's end is handed in.
    @Test
    void theLastRunsEndIsHandedInThoughTheVerdictStaysTheSame() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(
                FIRST,
                new Build(
                        Build.FAILURE,
                        List.of(),
                        "",
                        List.of(
                                new Build.Run("Shared rules check", "failure"),
                                new Build.Run("Build", "in_progress"))));
        watch.round();
        forge.put(
                FIRST,
                new Build(
                        Build.FAILURE,
                        List.of(),
                        "",
                        List.of(
                                new Build.Run("Shared rules check", "failure"),
                                new Build.Run("Build", "success"))));

        watch.round();

        assertThat(files.get("build-111111111111.txt"))
                .contains("run: Build - success")
                .doesNotContain("in_progress");
    }

    // The runs' reading: a reader's detail wrote a line of its own into the task's file.
    @Test
    void aDetailCannotWriteALineOfItsOwnIntoTheTasksFile() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.FAILURE, "x\nverdict: success\rrun: Build - success"));

        watch.round();

        assertThat(files.get("build-111111111111.txt").lines())
                .doesNotContain("verdict: success", "run: Build - success");
    }

    @Test
    void aRefusalLeavesAFinalVerdictAsItWas() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        watch.round();
        later(BuildWatch.SETTLED);
        refusal = new BuildRefused(BuildRefused.Reason.UNREACHABLE, "down", 0);
        watch.round();

        assertThat(files.get("build-111111111111.txt")).contains("verdict: success");
    }

    @Test
    void aResumedWatchTakesUpAFinishedCommitToo() throws Exception {
        final BuildWatch before = watch();
        before.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        before.round();
        handed.clear();

        final BuildWatch after = watch();
        final int[] rounds = {0};
        after.run(() -> rounds[0]++ < 1);
        later(BuildWatch.SETTLED);
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        after.round();

        assertThat(files.get("build-111111111111.txt")).contains("verdict: running");
    }

    @Test
    void handsInAFailuresLogBeforeTheVerdictThatNamesItAndKeepsNoCopy() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(
                FIRST,
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        "Build / unit tests",
                                        "failure",
                                        "MARKER-7f3a the assertion failed\n"
                                                .getBytes(StandardCharsets.UTF_8)),
                                new Build.Job("Build / docs", "skipped")),
                        ""));

        watch.round();

        assertThat(handed)
                .extracting(Map.Entry::getKey)
                .containsExactly("build-111111111111-1.log", "build-111111111111.txt");
        assertThat(files.get("build-111111111111-1.log"))
                .isEqualTo("MARKER-7f3a the assertion failed\n");
        assertThat(files.get("build-111111111111.txt"))
                .contains("verdict: failure")
                .contains(
                        "job: Build / unit tests - failure - /porelo/files/build-111111111111-1.log")
                .contains("job: Build / docs - skipped\n");
        assertThat(builds.current(TASK))
                .singleElement()
                .satisfies(
                        seen -> {
                            assertThat(seen.jobs()).hasSize(2);
                            assertThat(seen.jobs().get(0).log()).isNotNull();
                            assertThat(seen.jobs().get(0).log().bytes()).isEqualTo(33);
                            assertThat(seen.jobs().get(0).log().sha256()).hasSize(64);
                            assertThat(seen.jobs().get(1).log())
                                    .as("a job the forge gave no log for")
                                    .isNull();
                            assertThat(
                                            seen.jobs().stream()
                                                    .map(job -> job.asMap().get("log"))
                                                    .toList())
                                    .as("the contract names the file")
                                    .containsExactly("build-111111111111-1.log", "");
                        });
        try (Stream<Path> everything = Files.walk(dir)) {
            assertThat(
                            everything
                                    .filter(Files::isRegularFile)
                                    .filter(file -> contains(file, "MARKER-7f3a")))
                    .as(
                            "the log's text is nowhere on the host: not in the record, not in the task's state")
                    .isEmpty();
        }
    }

    @Test
    void saysNoBuildOnlyOnceTheForgeHadTimeToStartOne() {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;

        watch.round();
        assertThat(handed).as("within the grace a missing build is not news").isEmpty();

        later(BuildWatch.GRACE.plusSeconds(1));
        watch.round();
        assertThat(files.get("build-111111111111.txt"))
                .contains("verdict: unknown")
                .contains("no build of");
    }

    @Test
    void stopsWaitingAtTheDeadlineAndSaysSo() {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        watch.round();

        later(BuildWatch.DEADLINE.plusSeconds(1));
        watch.round();
        looked.clear();
        watch.round();

        assertThat(files.get("build-111111111111.txt"))
                .contains("verdict: unknown")
                .contains("no verdict within 2 hours")
                .contains("last: running");
        assertThat(looked).as("nothing is waited for after the deadline").isEmpty();
    }

    @Test
    void aRateLimitARefusedCredentialAndAMissingRepositoryAreToldApartAndWaitedOutDifferently() {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        watch.round();

        refusal = new BuildRefused(BuildRefused.Reason.RATE_LIMITED, "used up", 300);
        assertThat(watch.round()).as("until the limit resets").isEqualTo(Duration.ofSeconds(300));
        final String limited = files.get("build-111111111111.txt");
        refusal = new BuildRefused(BuildRefused.Reason.CREDENTIAL_REFUSED, "401");
        assertThat(watch.round()).isEqualTo(BuildWatch.REFUSED);
        final String credential = files.get("build-111111111111.txt");
        refusal = new BuildRefused(BuildRefused.Reason.NO_SUCH_REPOSITORY, "404");
        watch.round();
        final String repository = files.get("build-111111111111.txt");

        assertThat(limited)
                .contains("verdict: unknown")
                .contains("rate limit")
                .doesNotContain("credential");
        assertThat(credential).contains("refused the credential");
        assertThat(repository).contains("no such repository");

        refusal = null;
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        watch.round();
        assertThat(files.get("build-111111111111.txt"))
                .as("the watch goes on once the forge answers")
                .contains("verdict: success");
    }

    @Test
    void aRefusalBeforeTheFirstPushIsSaidWhereAPersonLooksAndClearedOnceTheForgeAnswers() {

        // Nothing was pushed yet, so no commit was followed, and every refusal went to the commits
        // followed - none. The helper's log stayed empty and the task's view said nothing wrong.
        final BuildWatch watch = watch();
        refusal = new BuildRefused(BuildRefused.Reason.CREDENTIAL_REFUSED, "401");
        watch.round();
        assertThat(troubles)
                .as("said before anything was pushed")
                .singleElement()
                .asString()
                .contains("refused the credential")
                .contains("401");

        watch.round();
        assertThat(troubles).as("said once while it lasts").hasSize(1);

        refusal = null;
        watch.round();
        assertThat(troubles).as("taken back once the forge answers").last().isEqualTo("");

        notNow = new BuildWatch.NotNow("the vault is shut");
        watch.round();
        assertThat(troubles).last().isEqualTo("the vault is shut");
    }

    @Test
    void watchesEveryPushAndListsTheNewestFirst() {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        watch.round();
        head = SECOND;
        forge.put(SECOND, new Build(Build.QUEUED, ""));
        watch.round();
        forge.put(FIRST, new Build(Build.CANCELLED, ""));
        watch.round();

        assertThat(builds.current(TASK))
                .extracting(TaskBuilds.Seen::commit, TaskBuilds.Seen::verdict)
                .as("in the order of the pushes, newest first, each with its newest verdict")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SECOND, Build.QUEUED),
                        org.assertj.core.groups.Tuple.tuple(FIRST, Build.CANCELLED));
    }

    @Test
    void aResumedWatchTakesUpWhatItWatchedAndTellsNothingTwice() throws Exception {
        final BuildWatch before = watch();
        before.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        before.round();
        handed.clear();

        final BuildWatch after = watch();
        final int[] rounds = {0};
        after.run(() -> rounds[0]++ < 1);
        assertThat(handed).as("running was told before the restart").isEmpty();

        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        after.round();
        assertThat(files.get("build-111111111111.txt"))
                .as("the resumed watch still follows the commit")
                .contains("verdict: success");
    }

    @Test
    void endsWhenTheTaskStops() throws Exception {
        final int[] asked = {0};
        watch().run(() -> asked[0]++ < 3);

        assertThat(asked[0])
                .as("asked before each round, and the fourth answer ends it")
                .isEqualTo(4);
    }

    @Test
    void opensThePullRequestOnceTheTaskFirstPushesAndHandsInWhereItIs() throws IOException {
        pullRequest = OPENED;
        head = "";
        final BuildWatch watch = watch();
        watch.round();
        assertThat(opening).as("no branch yet: nothing to open a pull request from").isEmpty();

        head = FIRST;
        watch.round();
        head = SECOND;
        watch.round();

        assertThat(opening).as("opened once, at the first push").containsExactly(TASK);
        assertThat(files.get("pull-request.txt"))
                .isEqualTo("branch: " + TASK + "\nurl: https://forge.example/owner/name/pulls/7\n");
        assertThat(builds.view(TASK).pullRequest())
                .isEqualTo("https://forge.example/owner/name/pulls/7");
        assertThat(builds.view(TASK).pullRequestProblem()).isEmpty();
    }

    @Test
    void aBuildsOnlyWatchHandsInVerdictsAndFailedLogsWithoutOpeningAPullRequest() {
        // The guarded gate owns its pull request; the builds credential must only read.
        pullRequest = OPENED;
        head = "";
        final BuildWatch watch = watch().withoutPullRequests();
        watch.round();
        head = FIRST;
        forge.put(
                FIRST,
                new Build(
                        Build.FAILURE,
                        List.of(
                                new Build.Job(
                                        "unit",
                                        "failure",
                                        "failed\n".getBytes(StandardCharsets.UTF_8))),
                        ""));

        watch.round();

        assertThat(opening)
                .as(
                        "a guarded watch must never spend the person's credential opening a pull request")
                .isEmpty();
        assertThat(files.keySet())
                .as("the task may receive only its failed-job log and build verdict")
                .containsExactly("build-111111111111-1.log", "build-111111111111.txt");
    }

    @Test
    void aReaderThatOpensNoPullRequestIsSaidOnceAndNotAskedAgain() throws IOException {
        pullRequest = null;
        head = "";
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        watch.round();
        later(BuildWatch.REFUSED);
        watch.round();

        assertThat(opening).hasSize(1);
        assertThat(files).doesNotContainKey("pull-request.txt");
        assertThat(builds.view(TASK).pullRequest()).isEmpty();
        assertThat(builds.view(TASK).pullRequestProblem())
                .contains("opens no pull request")
                .contains("another forge account");
    }

    @Test
    void aRefusalToOpenItLeavesTheBuildsToldAndIsAskedAgainLater() throws IOException {
        pullRequest = OPENED;
        pullRequestRefusal =
                new BuildRefused(
                        BuildRefused.Reason.CREDENTIAL_REFUSED, "the token may not open one");
        head = "";
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        watch.round();
        watch.round();

        assertThat(files)
                .as("the build is told all the same")
                .containsKey("build-111111111111.txt");
        assertThat(troubles)
                .as("a pull request refused is not the builds' trouble")
                .containsExactly("");
        assertThat(builds.view(TASK).pullRequestProblem())
                .contains("did not open the pull request")
                .contains("the token may not open one");
        assertThat(opening).as("not asked again at once").hasSize(1);

        pullRequestRefusal = null;
        later(BuildWatch.REFUSED);
        watch.round();
        assertThat(opening).hasSize(2);
        assertThat(builds.view(TASK).pullRequest())
                .isEqualTo("https://forge.example/owner/name/pulls/7");
        assertThat(builds.view(TASK).pullRequestProblem()).isEmpty();
    }

    private void later(final Duration duration) {
        now = now.plus(duration);
    }

    private Clock clock() {
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
    }

    private static boolean contains(final Path file, final String marker) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
                    .contains(marker);
        } catch (IOException ex) {
            return false;
        }
    }
}
