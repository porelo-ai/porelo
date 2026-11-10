package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskBuildsTest {

    private static final String TASK = "porelo-demo-shell-1";

    private static final String COMMIT = "c".repeat(40);

    @TempDir Path dir;

    private TaskPaths paths;

    private TaskBuilds builds;

    @BeforeEach
    void aTask() throws IOException {
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
        Sidecar.recordId(paths.containerState(TASK), "0123456789ab");
        builds = new TaskBuilds(paths);
    }

    @Test
    void aMissingCredentialKeptBeforeItNamedItsForgeReadsWithNone() throws IOException {
        // A record written before the field must still offer its remedy, never drop it.
        Files.writeString(
                paths.containerState(TASK).resolve(TaskBuilds.READER_FILE),
                Json.write(
                        Map.of(
                                "reader",
                                "host-forge",
                                "problem",
                                "missing",
                                "credential",
                                Map.of(
                                        "for",
                                        "builds",
                                        "credential",
                                        "host-builds",
                                        "storeCommand",
                                        List.of("porelo", "host", "forge-token"),
                                        "afterStore",
                                        "restart"))));

        assertThat(builds.view(TASK).credential())
                .as("an older record keeps its remedy and names no forge")
                .isNotNull()
                .extracting(MissingCredential::forge)
                .isEqualTo("");
    }

    @Test
    void aNewReaderOutcomeClearsAnEarlierMissingCredential() throws IOException {
        // A restarted reader must not keep offering token rotation for an earlier start's problem.
        Files.writeString(
                paths.containerState(TASK).resolve(TaskBuilds.READER_FILE),
                Json.write(
                        Map.of(
                                "reader",
                                "host-forge",
                                "problem",
                                "missing before restart",
                                "credential",
                                Map.of(
                                        "for",
                                        "builds",
                                        "credential",
                                        "host-builds",
                                        "storeCommand",
                                        List.of("porelo", "host", "forge-token"),
                                        "afterStore",
                                        "restart"))));
        builds.pullRequest(TASK, "https://forge.example/pull/1", "");
        assertThat(builds.view(TASK).credential())
                .as("a pull request update keeps the start's reason")
                .isNotNull();

        builds.reader(TASK, "host-forge", "");

        assertThat(builds.view(TASK).credential())
                .as("the next reader outcome replaces the reason")
                .isNull();
    }

    @Test
    void recordsWhatWasDeliveredAndReadsItBackWithTheLogsSizeAndHashButNotItsText()
            throws IOException {
        final TaskBuilds.Seen seen =
                new TaskBuilds.Seen(
                        COMMIT,
                        "failure",
                        List.of(
                                new TaskBuilds.Job(
                                        "Build / unit",
                                        "failure",
                                        new TaskBuilds.Log(
                                                "build-cccccccccccc-1.log", 33, "a".repeat(64))),
                                new TaskBuilds.Job("Build / docs", "success", null)),
                        "2026-10-06T12:00:00Z",
                        "",
                        "0123456789ab");

        builds.write(TASK, seen);

        assertThat(builds.record(TASK)).containsExactly(seen);
        assertThat(
                        Files.readString(
                                dir.resolve("state/porelo/builds/" + TASK + ".jsonl"),
                                StandardCharsets.UTF_8))
                .as("the record outlives the task, in the state directory")
                .contains("\"bytes\":33")
                .contains("build-cccccccccccc-1.log");
    }

    @Test
    void showsTheCurrentRunsBuildsOnlyEachWithItsNewestVerdictTheNewestPushFirst()
            throws IOException {
        builds.write(TASK, seen("1".repeat(40), "success", "ba9876543210"));
        builds.write(TASK, seen("2".repeat(40), "running", "0123456789ab"));
        builds.write(TASK, seen("3".repeat(40), "queued", "0123456789ab"));
        builds.write(TASK, seen("2".repeat(40), "failure", "0123456789ab"));

        assertThat(builds.current(TASK))
                .extracting(TaskBuilds.Seen::commit, TaskBuilds.Seen::verdict)
                .as(
                        "another run of the same name is not this one's; a push keeps its place when its verdict changes")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("3".repeat(40), "queued"),
                        org.assertj.core.groups.Tuple.tuple("2".repeat(40), "failure"));
    }

    @Test
    void saysWhichReaderFollowsTheBuildsAndWhatKeepsItFromDoingSo() throws IOException {
        assertThat(builds.view(TASK)).as("nothing follows them").isEqualTo(TaskBuilds.View.none());

        builds.reader(TASK, "github", "");
        assertThat(builds.view(TASK)).isEqualTo(new TaskBuilds.View("github", "", List.of()));

        builds.reader(TASK, "github", "no build reader for 'github' is installed");
        assertThat(builds.view(TASK).problem()).contains("is installed");
    }

    @Test
    void storedBuildFieldsReachTheDaemonAnswerWithTheApiBounds() throws IOException {
        // A stored adapter answer must not lose its trigger or inject lines through failed tests.
        final var line =
                new java.util.LinkedHashMap<String, Object>(
                        seen(COMMIT, "failure", "0123456789ab").asMap());
        line.put("run", "0123456789ab");
        line.put(
                "runs",
                List.of(
                        Map.of(
                                "name",
                                "Build",
                                "result",
                                "failure",
                                "event",
                                "PUSH\nforged",
                                "id",
                                "7".repeat(70))));
        line.put(
                "jobs",
                List.of(
                        Map.of(
                                "name",
                                "unit",
                                "result",
                                "failure",
                                "run",
                                "7".repeat(70),
                                "failedTests",
                                java.util.Collections.nCopies(55, "x".repeat(199) + "\nforged"),
                                "url",
                                "https://forge.example/jobs/7")));
        final Path record = dir.resolve("state/porelo/builds/" + TASK + ".jsonl");
        Files.createDirectories(record.getParent());
        Files.writeString(record, Json.write(line) + "\n");
        final Map<String, Object> answer = builds.current(TASK).getFirst().asMap();
        assertThat(answer.get("runs"))
                .as("bounded trigger and id reach Tasks1")
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "name",
                                        "Build",
                                        "result",
                                        "failure",
                                        "event",
                                        "push forged",
                                        "id",
                                        "7".repeat(64))));
        final Map<?, ?> job = (Map<?, ?>) ((List<?>) answer.get("jobs")).getFirst();
        assertThat(job.get("run")).as("the bounded run reference").isEqualTo("7".repeat(64));
        assertThat(job.get("failedTests"))
                .as("at most fifty single lines of two hundred code points")
                .isEqualTo(java.util.Collections.nCopies(50, "x".repeat(199) + " "));
        assertThat(job.get("url"))
                .as("the adapter's page")
                .isEqualTo("https://forge.example/jobs/7");
    }

    @Test
    void anOldRecordStillReadsAndRoundTripsWithoutOptionalFields() throws IOException {
        // Upgrading must preserve the builds recorded by readers that knew none of these fields.
        final TaskBuilds.Seen old =
                new TaskBuilds.Seen(
                        COMMIT,
                        "failure",
                        List.of(new TaskBuilds.Job("unit", "failure", null)),
                        "then",
                        "",
                        "0123456789ab",
                        List.of(new TaskBuilds.Run("Build", "failure")));
        builds.write(TASK, old);
        assertThat(builds.current(TASK)).as("the old record still reads").containsExactly(old);
        assertThat(builds.current(TASK).getFirst().asMap())
                .as("no optional fields are invented")
                .containsEntry("runs", List.of(Map.of("name", "Build", "result", "failure")))
                .containsEntry(
                        "jobs", List.of(Map.of("name", "unit", "result", "failure", "log", "")));
    }

    private static TaskBuilds.Seen seen(
            final String commit, final String verdict, final String run) {
        return new TaskBuilds.Seen(commit, verdict, List.of(), "2026-10-06T12:00:00Z", "", run);
    }
}
