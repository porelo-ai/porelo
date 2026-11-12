package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.fuin.porelo.wire.Waiting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A task's review signal comes from the same waiting list as its gate. */
class TaskInventoryWaitingTest {
    @TempDir Path dir;
    private final FakeCommandRunner runner = new FakeCommandRunner();
    private PoreloContext context;

    @BeforeEach
    void machine() throws IOException {
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
        Files.createDirectories(GateSupport.mirror(context, "demo", "app"));
        Files.createDirectories(GateSupport.mirror(context, "demo", null));
    }

    @Test
    void anOnlineTasksPassedPushAsksNobodyForReview() throws IOException {
        // A passed push must not send a person to approve work that the gate does not hold.
        task("fix", "online");
        runner.answering("ps", row("fix", "online", true));
        runner.answering("for-each-ref", "refs/porelo/incoming/fix\n");

        final TaskInventory.Task task = inventory().tasks().getFirst();

        assertThat(task.waiting()).as("a retained online ref is already passed on").isZero();
        assertThat(task.waitingFor()).as("no question waits for a person").isNull();
        assertThat(task.asMap())
                .as("the daemon sends these same review signals")
                .containsEntry("waiting", 0)
                .containsEntry("waitingFor", "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"guarded", "offline"})
    void aWaitingPushAsksForReviewUntilTheGateNoLongerListsIt(String securityClass)
            throws IOException {
        // A false review signal can strand held work or waste a person's review.
        task("fix", securityClass);
        runner.answering("ps", row("fix", securityClass, true));
        runner.answering("for-each-ref", "refs/porelo/incoming/fix\n");
        final TaskInventory inventory = inventory();
        final TaskInventory.Task before = inventory.tasks().getFirst();

        assertThat(before.waiting()).as("the gate holds this task's work").isEqualTo(1);
        assertThat(before.waitingFor())
                .as("the held commit supplies the waiting reason")
                .isNotBlank();
        assertThat(before.activity())
                .as("the running task needs a person's review")
                .isEqualTo(TaskInventory.Activity.WAITING);

        runner.answering("for-each-ref", "");
        final TaskInventory.Task after = inventory.tasks().getFirst();
        assertThat(after.waiting()).as("the gate no longer lists the work").isZero();
        assertThat(after.waitingFor()).as("approval ends the review question").isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void tasksSharingAMirrorKeepTheirOwnGateMode(boolean onlineFirst) throws IOException {
        // A cached list for another class must neither hide a review nor invent one.
        task("passed", "online");
        task("held", "guarded");
        final String online = row("passed", "online", true);
        final String guarded = row("held", "guarded", true);
        runner.answering("ps", onlineFirst ? online + guarded : guarded + online);
        runner.answering(
                "for-each-ref", "refs/porelo/incoming/passed\nrefs/porelo/incoming/held\n");

        final List<TaskInventory.Task> tasks = inventory().tasks();

        assertThat(tasks)
                .filteredOn(task -> task.securityClass().equals("online"))
                .extracting(TaskInventory.Task::waiting)
                .as("online work went on")
                .containsExactly(0);
        assertThat(tasks)
                .filteredOn(task -> task.securityClass().equals("guarded"))
                .extracting(TaskInventory.Task::waiting)
                .as("guarded work stays held")
                .containsExactly(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"online", "guarded"})
    void aClearanceQuestionStillNeedsAnAnswer(String securityClass) throws IOException {
        // Correcting review signals must not hide a live request to widen network access.
        final Path state = task("fix", securityClass);
        runner.answering("ps", row("fix", securityClass, true));
        runner.answering("for-each-ref", "refs/porelo/incoming/fix\n");
        new Waiting(state).asking("api.example.org:443");

        assertThat(inventory().tasks().getFirst().waitingFor())
                .as("the clearance question is independent of the push")
                .isEqualTo("api.example.org:443");
    }

    @Test
    void aStoppedOnlineTaskUsesItsRecordedClassWhenLabelsAreAbsent() throws IOException {
        // An older stopped container must not revive a review request for work already forwarded.
        task("fix", "online");
        runner.answering(
                "ps", "porelo-demo-fix\tExited (0) 1 minute ago\t1788500000\t1788500100\n");
        runner.answering("for-each-ref", "refs/porelo/incoming/fix\n");

        final TaskInventory.Task task = inventory().tasks().getFirst();

        assertThat(task.waiting()).as("the sidecar keeps its online class").isZero();
        assertThat(task.waitingFor()).as("a stopped task asks no question").isNull();
        assertThat(task.activity())
                .as("its container is stopped")
                .isEqualTo(TaskInventory.Activity.DEAD);
    }

    @Test
    void aRowSaysWhereItsTasksWorkLandedAndNothingWhileItWaits() throws IOException {
        // A reader's starter needs the ref to name before it starts the reader.
        task("fix", "guarded");
        task("held", "guarded");
        runner.answering("ps", row("fix", "guarded", false) + row("held", "guarded", true));
        runner.answering("for-each-ref", "refs/porelo/incoming/held\n");
        final String commit = "0123456789abcdef0123456789abcdef01234567";
        final Path ref =
                GateSupport.mirror(context, "demo", "app")
                        .resolve(org.fuin.porelo.gate.GitGate.LANDED + "fix/porelo/fix");
        Files.createDirectories(ref.getParent());
        Files.writeString(ref, commit + "\n");
        final TaskInventory inventory = inventory();

        final List<java.util.Map<String, Object>> rows = inventory.rows(inventory.tasks());

        assertThat(rows.getFirst())
                .as("the branch, the ref a reader fetches, the commit")
                .containsEntry(
                        "landed",
                        List.of(
                                java.util.Map.of(
                                        "branch", "porelo/fix",
                                        "ref", "refs/porelo/landed/fix/porelo/fix",
                                        "remote", "origin/landed/fix/porelo/fix",
                                        "commit", commit)));
        assertThat(rows.getLast())
                .as("work at the gate has landed nowhere")
                .containsEntry("landed", List.of());
    }

    private TaskInventory inventory() {
        return new TaskInventory(context, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"online", "guarded"})
    void aTaskWhoseNameEndsInRescuedDoesNotBelongToAnotherTask(String securityClass)
            throws IOException {
        // A suffix match can ask a person to review a different task's work.
        task("fix", securityClass);
        task("fix-rescued", "guarded");
        runner.answering(
                "ps", row("fix", securityClass, true) + row("fix-rescued", "guarded", true));
        runner.answering("for-each-ref", "refs/porelo/incoming/fix-rescued\n");

        final List<TaskInventory.Task> tasks = inventory().tasks();

        assertThat(tasks.getFirst().waiting()).as("fix has no pending ref of its own").isZero();
        assertThat(tasks.getFirst().waitingFor())
                .as("the other task's ref asks nothing of fix")
                .isNull();
        assertThat(tasks.getLast().waiting())
                .as("the exact recorded ref still needs review")
                .isEqualTo(1);
        assertThat(tasks.getLast().waitingFor())
                .as("the owning task keeps its review question")
                .isNotBlank();
    }

    private Path task(String name, String securityClass) throws IOException {
        final Path state = context.paths().tasks().containerState("porelo-demo-" + name);
        Files.createDirectories(state);
        new Sidecar(
                        Sidecar.VERSION,
                        "demo",
                        securityClass,
                        state.resolve("r.nft").toString(),
                        state.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        state.toString())
                .writeTo(state.resolve("sidecar.json"));
        new TaskProfile(
                        TaskProfile.VERSION,
                        "example",
                        TaskMode.UNATTENDED,
                        "fix the parser",
                        "refs/porelo/incoming/" + name,
                        "2026-09-07T10:00:00Z",
                        "prompt")
                .writeTo(state);
        return state;
    }

    private static String row(String name, String securityClass, boolean running) {
        return "porelo-demo-"
                + name
                + "\t"
                + (running ? "Up 4 minutes" : "Exited (0) 1 minute ago")
                + "\t1788500000\t0\tdemo\t"
                + securityClass
                + "\tapp\t\t\n";
    }
}
