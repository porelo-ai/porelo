package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.fuin.porelo.wire.Waiting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for what a task says about itself beyond its container's state.
 *
 * <p>The distinction being checked is the one that costs an afternoon: a task blocked on a question
 * nobody saw and a task grinding through a build are both {@code running: true}.
 */
class TaskActivityTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path root;

    private PoreloContext context(Path dir) {
        root = dir;
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** A task the runtime reports, with a state directory and a sidecar. */
    private Path task(String container, String status, String started, String exited)
            throws IOException {
        runner.answering("ps", container + "\t" + status + "\t" + started + "\t" + exited + "\n");
        final Path state = root.resolve("run/porelo").resolve(container);
        Files.createDirectories(state);
        new Sidecar(
                        Sidecar.VERSION,
                        "uc",
                        "guarded",
                        state.resolve("r.nft").toString(),
                        state.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        state.toString())
                .writeTo(state.resolve("sidecar.json"));
        return state;
    }

    private TaskInventory.Task only(PoreloContext context) {
        return new TaskInventory(context).tasks().getFirst();
    }

    @Test
    void aTaskSaysWhetherItsCommitsAreSignedByACertifiedKey(@TempDir Path dir) throws IOException {
        // A gate that judges nothing is a choice a person must be able to see.
        final PoreloContext context = context(dir);
        final Path state = task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");

        assertThat(only(context).asMap()).containsEntry("certified", false);

        Files.writeString(state.resolve(TaskState.CERTIFIERS), "task:uc:* cert-authority k\n");

        assertThat(only(context).certified()).isTrue();
        assertThat(only(context).asMap()).containsEntry("certified", true);
    }

    @Test
    void anEndedRunCountsTheMessagesWaitingForItsNextRun(@TempDir Path dir) throws IOException {
        // Hiding an ended run's inbox loses the cue that a person must start it again.
        final PoreloContext context = context(dir);
        final String container = "porelo-uc-shell-1";
        final Path state = task(container, "Up 4 minutes", "1788500000", "0");
        final Instant wrote = Instant.parse("2026-10-01T10:00:00Z");
        new TaskProfile(
                        TaskProfile.VERSION,
                        "example",
                        TaskMode.UNATTENDED,
                        "answer",
                        null,
                        wrote.toString(),
                        "prompt")
                .writeTo(state);
        final Path log = Files.writeString(state.resolve("task.log"), "finished\n");
        Files.setLastModifiedTime(log, FileTime.from(wrote));
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        mailbox.create();
        Files.writeString(mailbox.inboxNew().resolve("one.json"), "{}");
        Files.writeString(mailbox.inboxNew().resolve("two.json"), "{}");
        Files.writeString(mailbox.inboxNew().resolve("unfinished.tmp"), "{}");
        Files.createDirectory(mailbox.inboxNew().resolve("directory.json"));
        Files.writeString(mailbox.inboxCur().resolve("read.json"), "{}");
        final AgentEnding ending =
                new AgentEnding(
                        runner,
                        java.time.Clock.fixed(wrote.plusSeconds(120), java.time.ZoneOffset.UTC),
                        (agent, lines) ->
                                new org.fuin.porelo.agent.api.AgentEnd(true, "", "agent", null));
        final TaskInventory inventory = new TaskInventory(context, null, ending);
        final TaskInventory.Task ended = inventory.tasks().getFirst();

        assertThat(ended.activity())
                .as("the fixture's unattended agent has ended")
                .isEqualTo(TaskInventory.Activity.ENDED);
        assertThat(ended.asMap().get("inboxWaiting"))
                .as("only new JSON messages wait for the ended agent's next run")
                .isEqualTo(2);
        assertThat(only(context).asMap().get("inboxWaiting"))
                .as("a running task counts the same host-side inbox")
                .isEqualTo(2);
        task(container, "Exited (0) 1 minute ago", "1788500000", "1788509999");
        final TaskInventory.Task stopped = inventory.tasks().getFirst();
        assertThat(stopped.activity())
                .as("the fixture's container is now stopped")
                .isEqualTo(TaskInventory.Activity.DEAD);
        assertThat(stopped.asMap().get("inboxWaiting"))
                .as("stopping the container does not hide its unread messages")
                .isEqualTo(2);
        assertThat(stopped.asMap().get("lastWrote"))
                .as("a stopped task keeps its last log write time")
                .isEqualTo(wrote.toString());
    }

    @Test
    void aTaskSaysWhenItsAgentLastWrote(@TempDir Path dir) throws IOException {
        // A client must not guess the last output time from the container's age.
        final PoreloContext context = context(dir);
        final Path state = task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        assertThat(only(context).asMap().get("lastWrote"))
                .as("without a log there is no output time")
                .isEqualTo("");
        assertThat(only(context).asMap().get("inboxWaiting"))
                .as("without a mailbox there are no waiting messages")
                .isEqualTo(0);
        final Instant wrote = Instant.parse("2026-10-01T10:00:00Z");
        final Path log = Files.writeString(state.resolve("task.log"), "output\n");
        Files.setLastModifiedTime(log, FileTime.from(wrote));
        assertThat(only(context).asMap().get("lastWrote"))
                .as("the file's modification time crosses the wire as an instant")
                .isEqualTo(wrote.toString());
    }

    @Test
    void aStartInProgressIsVisibleToAnotherWindowUntilItEnds(@TempDir Path dir) throws IOException {
        // A second window must not hide a task's active start behind an empty phase.
        final PoreloContext context = context(dir);
        final Path state = task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        final Path phase = state.resolve("start-step.json");
        final String text = "Preparing the image";
        Files.writeString(
                phase,
                org.fuin.porelo.wire.Json.write(
                        java.util.Map.of(
                                "step",
                                "IMAGE",
                                "state",
                                "STARTED",
                                "text",
                                text,
                                "waitingOn",
                                "",
                                "cause",
                                "",
                                "pid",
                                ProcessHandle.current().pid(),
                                "startedAt",
                                ProcessHandle.current()
                                        .info()
                                        .startInstant()
                                        .orElseThrow()
                                        .toString())));
        assertThat(only(context).asMap())
                .as("a listed task must show the start step another window is waiting on")
                .containsEntry("phase", text);
        Files.delete(phase);
        assertThat(only(context).asMap())
                .as("a finished start must not leave a stale phase")
                .containsEntry("phase", "");
        assertThat(TaskInventory.isLog("start-step.json"))
                .as("progress metadata must not become a task log")
                .isFalse();
    }

    @Test
    void saysWhichAgentAndModeAndBranchItRunsWith(@TempDir Path dir) throws IOException {

        // None of this can be recovered from a running container: two tasks in the same project
        // under the same class were indistinguishable.
        final PoreloContext context = context(dir);
        final Path state = task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        new TaskProfile(
                        TaskProfile.VERSION,
                        "example",
                        TaskMode.UNATTENDED,
                        "fix the parser",
                        "refs/porelo/incoming/shell",
                        "2026-09-07T10:00:00Z",
                        "prompt")
                .writeTo(state);

        assertThat(only(context))
                .satisfies(
                        task -> {
                            assertThat(task.agent()).isEqualTo("example");
                            // The wire spelling, which is the enum's own name - not the on-disk
                            // lower case.
                            assertThat(task.mode()).isEqualTo("UNATTENDED");
                            assertThat(task.prompt()).isEqualTo("fix the parser");
                            assertThat(task.branch()).isEqualTo("refs/porelo/incoming/shell");
                            assertThat(task.clearance()).isEqualTo("prompt");
                        });
    }

    @Test
    void saysItsWorkWaitsAtTheGateOfTheRepositoryItWorksOn(@TempDir Path dir) throws IOException {

        // A task on a repository other than its project's own - every task of 'default' - pushes to
        // that repository's mirror; asking the project's mirror said nothing of its waited.
        final PoreloContext context = context(dir);
        Files.createDirectories(dir.resolve("data/porelo/mirrors/default/app.git"));
        runner.answering("for-each-ref", "refs/porelo/incoming/app\n");
        runner.answering(
                "ps",
                "porelo-default-app\tUp 4 minutes\t1788500000\t0\tdefault\tguarded\tapp\t\t\n");
        final Path state = root.resolve("run/porelo/porelo-default-app");
        Files.createDirectories(state);
        new TaskProfile(
                        TaskProfile.VERSION,
                        "example",
                        TaskMode.UNATTENDED,
                        "fix the parser",
                        "refs/porelo/incoming/app",
                        "2026-09-07T10:00:00Z",
                        "prompt")
                .writeTo(state);

        assertThat(only(context).waiting())
                .as("its push waits in the mirror of 'app'")
                .isEqualTo(1);
    }

    @Test
    void saysWhenNothingIsEnforcingItsEgress(@TempDir Path dir) throws IOException {

        // The most consequential state a task can be in - nothing asks and nothing is refused - and
        // until it was recorded nothing an interface listed could mark it.
        final PoreloContext context = context(dir);
        final Path state = task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        new TaskProfile(
                        TaskProfile.VERSION,
                        "example",
                        TaskMode.UNATTENDED,
                        "go",
                        null,
                        "2026-09-07T10:00:00Z",
                        "off")
                .writeTo(state);

        assertThat(only(context).clearance()).isEqualTo("off");
    }

    @Test
    void saysWhenItsStateBeganAsATimestamp(@TempDir Path dir) throws IOException {

        // "Up 4 minutes" is for a person and must not be parsed, which leaves "idle for forty
        // minutes" answerable only by arithmetic on this.
        final PoreloContext context = context(dir);
        task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");

        assertThat(only(context).since()).isEqualTo(Instant.ofEpochSecond(1788500000).toString());
    }

    @Test
    void aStoppedTaskSaysWhenItStoppedRatherThanWhenItStarted(@TempDir Path dir)
            throws IOException {

        // The state that matters is the current one.
        final PoreloContext context = context(dir);
        task("porelo-uc-shell-1", "Exited (0) 3 minutes ago", "1788500000", "1788509999");

        assertThat(only(context).since()).isEqualTo(Instant.ofEpochSecond(1788509999).toString());
    }

    @Test
    void saysNothingRatherThanTheYearOneForAContainerThatNeverStarted(@TempDir Path dir)
            throws IOException {

        // Measured on a real machine: a container created and never started answers -62135596800,
        // Go's zero time, which podman renders as "Exited (0) 292 years ago".
        final PoreloContext context = context(dir);
        task("porelo-uc-shell-1", "Exited (0) 292 years ago", "1788460208", "-62135596800");

        assertThat(only(context).since()).isEmpty();
    }

    @Test
    void aTaskThatIsAskedSomethingSaysItIsWaiting(@TempDir Path dir) throws IOException {

        // Said by the thing that asked, not inferred from how long it has been quiet.
        final PoreloContext context = context(dir);
        final Path state = task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        new Waiting(state).asking("api.example.test:443");

        assertThat(only(context))
                .satisfies(
                        task -> {
                            assertThat(task.activity()).isEqualTo(TaskInventory.Activity.WAITING);
                            assertThat(task.waitingFor()).isEqualTo("api.example.test:443");
                        });
    }

    @Test
    void stopsSayingItIsWaitingOnceTheQuestionIsAnswered(@TempDir Path dir) throws IOException {

        // A marker left behind would make a working task look stuck, which is the same mistake in
        // the other direction.
        final PoreloContext context = context(dir);
        final Path state = task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        final Waiting waiting = new Waiting(state);
        waiting.asking("api.example.test:443");
        waiting.answered();

        assertThat(only(context).activity()).isNotEqualTo(TaskInventory.Activity.WAITING);
        assertThat(only(context).waitingFor()).isNull();
    }

    @Test
    void aTaskProducingOutputIsWorking(@TempDir Path dir) throws IOException {

        final PoreloContext context = context(dir);
        final Path state = task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        Files.writeString(state.resolve("task.log"), "the agent said something\n");

        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.WORKING);
    }

    @Test
    void aTaskWhoseOutputHasStoppedIsIdleAndNotWaiting(@TempDir Path dir) throws IOException {

        // Quiet is not waiting. An interface that showed this as "waiting for you" would send
        // somebody to answer a question nobody asked.
        final PoreloContext context = context(dir);
        final Path state = task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        final Path log = Files.writeString(state.resolve("task.log"), "quiet for a while\n");
        Files.setLastModifiedTime(log, FileTime.from(Instant.now().minusSeconds(3600)));

        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.IDLE);
    }

    @Test
    void aTaskNothingCanSeeIntoSaysSoRatherThanGuessing(@TempDir Path dir) throws IOException {

        // An attached session writes to the operator's terminal, not to a file this can read. A
        // state that is silently wrong is worse than one that admits it does not know.
        final PoreloContext context = context(dir);
        task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");

        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.UNKNOWN);
    }

    @Test
    void aTaskWhoseContainerIsGoneIsDeadRatherThanIdle(@TempDir Path dir) throws IOException {

        final PoreloContext context = context(dir);
        final Path state =
                task("porelo-uc-shell-1", "Exited (137) 1 hour ago", "1788500000", "1788509999");
        // Even with a marker left behind by whatever was asking when it died.
        new Waiting(state).asking("api.example.test:443");

        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.DEAD);
    }

    @Test
    void aTaskFromAnOlderPoreloStillDescribesItself(@TempDir Path dir) throws IOException {

        // No profile file at all. Every added field is absent, and nothing throws: an interface
        // that could not list a running task because it predates a release is worse than one that
        // shows less about it.
        final PoreloContext context = context(dir);
        task("porelo-uc-shell-1", "Up 4 minutes", "1788500000", "0");

        assertThat(only(context))
                .satisfies(
                        task -> {
                            assertThat(task.agent()).isNull();
                            assertThat(task.mode()).isNull();
                            assertThat(task.name()).isEqualTo("porelo-uc-shell-1");
                            assertThat(task.running()).isTrue();
                        });
    }
}
