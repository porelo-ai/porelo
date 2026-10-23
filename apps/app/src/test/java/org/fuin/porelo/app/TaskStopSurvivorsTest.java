package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against continuing shutdown while a task container survives. */
class TaskStopSurvivorsTest {
    private static final String CONTAINER = "porelo-uc-shell-1";
    private final StoppingCommandRunner runner = new StoppingCommandRunner();

    private PoreloContext context(Path dir) throws IOException {
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
        runner.answering("ps", CONTAINER + "\tUp 4 minutes\t\t\tuc\tguarded\t\t\ta1b2c3d4e5f6\n");
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0)
                        .recognizingTasks();
        Sidecar.recordId(context.paths().tasks().containerState(CONTAINER), "a1b2c3d4e5f6");
        new TaskState(context).save(CONTAINER);
        return context;
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void aStopIsCleanOnlyWhenTheRuntimeConfirmsIt(boolean purge, boolean fails, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        if (fails) {
            runner.failing("podman stop", 125, "runtime refused");
            if (purge) runner.failing("podman rm", 125, "runtime refused");
        }
        final TaskControl control = new TaskControl(context);
        final TaskControl.Stopped result =
                purge ? control.remove(CONTAINER, false, true) : control.stop(CONTAINER);
        assertSoftly(
                softly -> {
                    softly.assertThat(result.clean())
                            .as("a surviving container prevents a clean stop")
                            .isEqualTo(!fails);
                    softly.assertThat(result.removed())
                            .as("only a confirmed purge removes records")
                            .isEqualTo(purge && !fails);
                    softly.assertThat(
                                    Files.isDirectory(
                                            context.paths().tasks().containerState(CONTAINER)))
                            .as("runtime identity survives a failed purge")
                            .isEqualTo(!purge || fails);
                    softly.assertThat(
                                    Files.isRegularFile(
                                            context.paths()
                                                    .tasks()
                                                    .taskRecord(CONTAINER)
                                                    .resolve(Sidecar.RECORDED_ID)))
                            .as("durable identity is checked independently")
                            .isEqualTo(!purge || fails);
                    softly.assertThat(context.recordedId(CONTAINER))
                            .as("durable identity survives a failed purge")
                            .isEqualTo(purge && !fails ? null : "a1b2c3d4e5f6");
                    if (fails) {
                        softly.assertThat(result.surviving())
                                .as("the survivor identifies the container")
                                .anyMatch(value -> value.contains("container " + CONTAINER));
                    } else {
                        softly.assertThat(result.surviving())
                                .as("a confirmed stop has no survivors")
                                .isEmpty();
                    }
                });
    }

    @ParameterizedTest
    @CsvSource({"stop,false", "stop,true", "all,false", "all,true", "panic,false", "panic,true"})
    void shutdownCommandsFailAndNameAContainerThatSurvives(
            String verb, boolean fails, @TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        if (fails) {
            runner.failing("podman stop", 125, "runtime refused");
        }
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final picocli.CommandLine cli =
                PoreloCli.commandLine(context)
                        .setOut(new PrintWriter(out))
                        .setErr(new PrintWriter(err));
        final String[] arguments =
                switch (verb) {
                    case "stop" -> new String[] {"task", "stop", CONTAINER};
                    case "all" -> new String[] {"task", "stop", "--all"};
                    default -> new String[] {"panic"};
                };
        final int exit = cli.execute(arguments);
        assertSoftly(
                softly -> {
                    softly.assertThat(exit)
                            .as("shutdown must fail while its container runs")
                            .isEqualTo(fails ? 70 : 0);
                    if (fails) {
                        softly.assertThat(err.toString())
                                .as("stderr names the surviving container")
                                .contains("container " + CONTAINER);
                    } else {
                        softly.assertThat(err.toString())
                                .as("a confirmed shutdown has no error")
                                .isEmpty();
                    }
                    softly.assertThat(out.toString())
                            .as("a container survivor is not a negative helper count")
                            .doesNotContain("helpers   -1");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "stopping", "paused", "restarting", "unknown", "error"})
    void anUnconfirmedStopByIdFailsAndNamesTheSurvivor(String state, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        runner.failing("podman stop", 125, "runtime refused");
        // Even a rename that hides the inventory row cannot hide the recorded container.
        runner.answering("ps", "renamed\tUp 4 minutes\t\t\tuc\tguarded\t\t\ta1b2c3d4e5f6\n");
        if (state.equals("error"))
            runner.failing("{{.State.Status}} a1b2c3d4e5f6", 125, "inspect failed");
        else runner.answering("{{.State.Status}} a1b2c3d4e5f6", state);
        final TaskControl.Stopped result = new TaskControl(context).stop(CONTAINER);
        assertThat(runner.lines())
                .as("inspection targets the captured recorded identity")
                .contains("podman container inspect --format {{.State.Status}} a1b2c3d4e5f6");
        assertThat(result.clean()).as("only a positively terminal state is clean").isFalse();
        assertThat(result.surviving())
                .as("the recorded container is named despite its rename")
                .anyMatch(value -> value.contains(CONTAINER));
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final int exit =
                PoreloCli.commandLine(context)
                        .setOut(new PrintWriter(out))
                        .setErr(new PrintWriter(err))
                        .execute("task", "stop", CONTAINER);
        assertThat(exit).as("unconfirmed stop fails").isEqualTo(70);
        assertThat(err.toString())
                .as("stderr names the survivor")
                .contains("porelo: still running: container " + CONTAINER);
        assertThat(out.toString())
                .as("stdout keeps helper counts separate")
                .contains("helpers   0 of 0 stopped")
                .doesNotContain("still running");
    }

    @ParameterizedTest
    @CsvSource({
        "stopping,false",
        "paused,false",
        "renamed,false",
        "error,false",
        "stopping,true",
        "paused,true",
        "renamed,true",
        "error,true"
    })
    void panicSelectsEveryUnconfirmedRecordedIdentity(
            String state, boolean fails, @TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final String name = state.equals("renamed") ? "renamed" : CONTAINER;
        runner.answering(
                "ps",
                name
                        + "\t"
                        + (state.equals("renamed") ? "Up 4 minutes" : state)
                        + "\t\t\tuc\tguarded\t\t\ta1b2c3d4e5f6\n");
        runner.initially(state.equals("renamed") ? "running" : state);
        if (state.equals("error")) {
            runner.failing("{{.State.Status}} a1b2c3d4e5f6", 125, "inspect failed");
        } else if (fails) {
            runner.answering(
                    "{{.State.Status}} a1b2c3d4e5f6", state.equals("renamed") ? "running" : state);
        }
        if (fails) runner.failing("podman stop", 125, "runtime refused");
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final int exit =
                PoreloCli.commandLine(context)
                        .setOut(new PrintWriter(out))
                        .setErr(new PrintWriter(err))
                        .execute("panic");
        assertThat(runner.lines())
                .as("panic selects the recorded id regardless of inventory state or name")
                .contains("podman stop --time 5 a1b2c3d4e5f6");
        assertThat(exit)
                .as("only confirmed stops succeed")
                .isEqualTo(fails || state.equals("error") ? 70 : 0);
        assertThat(out.toString())
                .as("unconfirmed identities cannot mean nothing is running")
                .doesNotContain("nothing is running");
        if (fails || state.equals("error")) {
            assertThat(err.toString()).as("unconfirmed identity is named").contains(CONTAINER);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"exited", "created", "absent"})
    void panicSkipsOnlyConfirmedTerminalOrAbsentIdentities(String state, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        runner.answering("ps", state.equals("absent") ? "" : "a1b2c3d4e5f6\t" + state + "\n");
        final StringWriter out = new StringWriter();
        final int exit =
                PoreloCli.commandLine(context).setOut(new PrintWriter(out)).execute("panic");
        assertThat(exit).as("confirmed inactive tasks need no stop").isZero();
        assertThat(out.toString())
                .as("absence is claimed only after direct confirmation")
                .contains("nothing is running");
        assertThat(runner.lines())
                .as("selection uses one bulk identity inventory")
                .containsExactly("podman ps --all --no-trunc --format {{.ID}}\t{{.State}}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"exited", "stopped", "created"})
    void aTerminalStateConfirmsAStop(String state, @TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        runner.answering("{{.State.Status}} a1b2c3d4e5f6", state);
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final int exit =
                PoreloCli.commandLine(context)
                        .setOut(new PrintWriter(out))
                        .setErr(new PrintWriter(err))
                        .execute("task", "stop", CONTAINER);
        assertThat(exit).as("confirmed terminal state succeeds").isZero();
        assertThat(out.toString())
                .as("helper census is exact")
                .contains("helpers   0 of 0 stopped")
                .doesNotContain("still running");
        assertThat(err.toString())
                .as("a confirmed stop has no survivors")
                .doesNotContain("still running");
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "stopping", "paused", "exited", "error"})
    void aFailedPurgeKeepsRecordsAndDoesNotClaimRemoval(String state, @TempDir Path dir)
            throws IOException {
        final PoreloContext context =
                context(dir).asking(prompt -> "fixture-passphrase".toCharArray());
        project(dir);
        Files.writeString(
                dir.resolve("project.yml"),
                "project:\n  name: uc\n  security_class: guarded\nimage:\n  base_image: ubuntu:24.04\nmail:\n  transports:\n    room: {}\n  peers:\n    reviewer: { address: 'room:', trust: vouched }\n");
        assertThat(GateSupport.byName(context, "uc").mail().conversations())
                .as("the fixture project declares the task's conversation")
                .contains("room");
        final Path adapters =
                context.paths().messaging().transportDirectory().locations().getFirst();
        Files.createDirectories(adapters);
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        runner.answering("describe", "{\"scheme\":\"room\",\"lifecycle\":[\"retire\"]}");
        runner.answering(" retire ", "{}");
        Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(
                        java.util.Map.of(
                                VaultNames.TASK_PREFIX + CONTAINER + "/route/service",
                                        org.fuin.porelo.vault.VaultEntry.of("fixture-route"),
                                VaultNames.TRANSPORT_PREFIX + "room/task/" + CONTAINER,
                                        org.fuin.porelo.vault.VaultEntry.of(
                                                "{\"TASK\":\"fixture-account\"}")),
                        "fixture-passphrase".toCharArray());
        context.openerAsking(false, new PrintWriter(new StringWriter()));
        final TaskGuide guide = new TaskGuide(context.paths(), CONTAINER);
        Files.createDirectories(guide.directory());
        Files.writeString(guide.file(), "guide to keep");
        runner.failing("podman stop", 125, "runtime refused");
        runner.failing("podman rm", 125, "runtime refused");
        if (state.equals("error"))
            runner.failing("{{.State.Status}} a1b2c3d4e5f6", 125, "inspect failed");
        else runner.answering("{{.State.Status}} a1b2c3d4e5f6", state);
        new TaskSession(context).record(CONTAINER, "session-to-keep");
        final Path mailbox = context.paths().messaging().mailbox(CONTAINER);
        Files.createDirectories(mailbox);
        Files.writeString(mailbox.resolve("sentinel"), "message to keep");
        final Path journal = context.paths().tasks().clearanceJournal(CONTAINER);
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "decision to keep");
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final int exit =
                PoreloCli.commandLine(context)
                        .setOut(new PrintWriter(out))
                        .setErr(new PrintWriter(err))
                        .execute("task", "remove", CONTAINER, "--force");
        assertThat(exit).as("failed removal fails the command").isEqualTo(70);
        assertThat(err.toString())
                .as("stderr names the surviving container")
                .contains("porelo: still running: container " + CONTAINER);
        assertThat(out.toString())
                .as("a failed purge is never reported removed")
                .doesNotContain("removed");
        assertThat(Files.isDirectory(context.paths().tasks().containerState(CONTAINER)))
                .as("state is kept for retry")
                .isTrue();
        assertThat(context.paths().tasks().taskRecord(CONTAINER).resolve(Sidecar.RECORDED_ID))
                .as("durable record survives independently of the runtime record")
                .hasContent("a1b2c3d4e5f6\n");
        assertThat(new TaskSession(context).recorded(CONTAINER))
                .as("session survives the failed purge")
                .contains("session-to-keep");
        assertThat(mailbox.resolve("sentinel"))
                .as("mailbox survives the failed purge")
                .hasContent("message to keep");
        assertThat(journal)
                .as("journal is not closed before confirmed removal")
                .hasContent("decision to keep");
        assertThat(guide.file())
                .as("the surviving task keeps its guide")
                .hasContent("guide to keep");
        assertThat(new TaskSecrets(context).read(CONTAINER).orElseThrow().routes())
                .as("route credentials survive for retry")
                .containsKey("service");
        assertThat(
                        new TransportLifecycle(
                                        context, context.paths().messaging().transportDirectory())
                                .secrets("room", "task/" + CONTAINER))
                .as("conversation credentials survive for retry")
                .containsKey("TASK");
        assertThat(runner.lines())
                .as("a surviving task is never retired from its conversation")
                .noneMatch(line -> line.contains(" retire "));
        PoreloContext.forgetAsked();
        assertThat(context.recordedId(CONTAINER))
                .as("durable identity is kept for retry")
                .isEqualTo("a1b2c3d4e5f6");
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "paused", "stopping", "restarting"})
    void removalRefusesAnActiveRecordedIdentityDespiteARename(String state, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        runner.answering("ps", "renamed\tPaused\t\t\tuc\tguarded\t\t\ta1b2c3d4e5f6\n");
        runner.initially(state);
        runner.answering("git status --porcelain", "0 0");
        new TaskControl(context).remove(CONTAINER, false, false);
        assertThat(runner.lines())
                .as("unforced removal cannot destroy an active recorded container")
                .noneMatch(line -> line.startsWith("podman rm "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "paused", "stopping", "restarting"})
    void stoppingARenamedActiveIdentitySavesItsFreshCensus(String state, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        runner.answering("ps", "renamed\tPaused\t\t\tuc\tguarded\t\t\ta1b2c3d4e5f6\n");
        runner.initially(state);
        runner.answering("git status --porcelain", "2 3");
        new TaskControl(context).stop(CONTAINER);
        assertThat(UnhandedWork.read(context.paths().tasks().containerState(CONTAINER)))
                .as("the census is taken from the recorded identity before stopping")
                .extracting(
                        UnhandedWork.Held::readable,
                        UnhandedWork.Held::changedFiles,
                        UnhandedWork.Held::unpushedCommits)
                .containsExactly(true, 2, 3);
        assertThat(UnhandedWork.read(context.paths().tasks().taskRecord(CONTAINER)).changedFiles())
                .as("the measured work survives a reboot")
                .isEqualTo(2);
    }

    @Test
    void removalRefusesAnUnmeasuredStoppedIdentityDespiteARename(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        runner.answering("ps", "");
        runner.initially("exited");
        runner.afterStop("exited");
        assertThat(new TaskControl(context).remove(CONTAINER, false, false).outcome())
                .as("an existing identity needs a census even without an inventory name")
                .isEqualTo(TaskControl.Outcome.NOTHING_KNOWS);
        assertThat(runner.lines())
                .as("unknown work cannot be discarded")
                .noneMatch(line -> line.startsWith("podman rm "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "exited"})
    void aFailedStopInvalidatesBothWorkNotesWithoutSavingLiveState(
            String initial, @TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        runner.initially(initial);
        runner.afterStop("running");
        runner.answering("git status --porcelain", "0 0");
        final Path runtime = context.paths().tasks().containerState(CONTAINER);
        final Path durable = context.paths().tasks().taskRecord(CONTAINER);
        UnhandedWork.note(runtime, new UnhandedWork.Held(true, 0, 0, null));
        new TaskState(context).save(CONTAINER);
        Files.writeString(runtime.resolve("task.json"), "live state must not be saved");
        runner.failing("podman stop", 125, "refused");
        new TaskControl(context).stop(CONTAINER);
        assertThat(runtime.resolve(UnhandedWork.FILE))
                .as("a failed stop invalidates the runtime census")
                .doesNotExist();
        assertThat(durable.resolve(UnhandedWork.FILE))
                .as("a reboot cannot restore an old clean census")
                .doesNotExist();
        assertThat(durable.resolve("task.json"))
                .as("live state is not saved as stopped")
                .doesNotExist();
        runner.initially("exited");
        runner.afterStop("exited");
        assertThat(new TaskControl(context).remove(CONTAINER, false, false).outcome())
                .as("later removal must refuse the unknown work")
                .isEqualTo(TaskControl.Outcome.NOTHING_KNOWS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryDoesNotClaimOrAcceptAnUnconfirmedRemoval(boolean fails, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        if (fails) runner.failing("podman rm", 125, "refused");
        final StringWriter output = new StringWriter();
        final boolean recovered =
                TaskResumeCommand.recover(
                        CONTAINER,
                        new Offer(
                                question -> "y", false, false, new PrintWriter(new StringWriter())),
                        context,
                        dir,
                        new PrintWriter(output));
        assertThat(recovered).as("only confirmed removal completes recovery").isEqualTo(!fails);
        if (fails) {
            assertThat(output.toString())
                    .as("recovery names the survivor without claiming removal")
                    .contains("container " + CONTAINER)
                    .doesNotContain("removed   ");
        } else {
            assertThat(output.toString())
                    .as("confirmed removal can be reported")
                    .contains("removed   " + CONTAINER);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedInventoryFailsPanicEvenForAPreview(boolean preview, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        runner.failing("ps", 125, "inventory unavailable");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TaskPanic(context).panic(preview))
                .as("an unreadable inventory cannot imply that historical tasks run")
                .isInstanceOf(org.fuin.porelo.core.process.CommandException.class);
        assertThat(runner.lines())
                .as("failed selection never stops anything")
                .noneMatch(line -> line.startsWith("podman stop "));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void panicUsesOneInventoryAndPreservesItsOrder(boolean preview, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final String other = "porelo-uc-z-last";
        Sidecar.recordId(context.paths().tasks().containerState(other), "bbbbbbbbbbbb");
        runner.answering("ps", "bbbbbbbbbbbb\tpaused\na1b2c3d4e5f6\tstopping\nforeign\trunning\n");
        assertThat(new TaskPanic(context).panic(preview).tasks())
                .as("only recorded identities, in runtime order")
                .extracting(TaskPanic.Stopped::name)
                .containsExactly(other, CONTAINER);
        assertThat(runner.lines().stream().filter(line -> line.startsWith("podman ps ")))
                .as("selection makes one bulk query")
                .containsExactly("podman ps --all --no-trunc --format {{.ID}}\t{{.State}}");
        assertThat(runner.lines().stream().filter(line -> line.contains("{{.State.Status}}")))
                .as("inspection is only the verification after each stop")
                .hasSize(preview ? 0 : 2);
        if (!preview)
            assertThat(runner.lines().indexOf("podman stop --time 5 bbbbbbbbbbbb"))
                    .as("there is no pre-stop inspection")
                    .isLessThan(
                            runner.lines()
                                    .indexOf(
                                            "podman container inspect --format {{.State.Status}} bbbbbbbbbbbb"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"paused", "stopping", "running"})
    void vaultClearingKeepsALockedVaultForARenamedActiveIdentity(String state, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        runner.answering("ps", "a1b2c3d4e5f6\t" + state + "\n");
        Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(
                        java.util.Map.of(
                                VaultNames.TASK_PREFIX + CONTAINER + "/route/service",
                                org.fuin.porelo.vault.VaultEntry.of("fixture-token")),
                        "fixture-passphrase".toCharArray());
        new VaultClearing(context).clear(false, true);
        assertThat(context.vault().path())
                .as("force cannot clear past a recorded active task holding a token")
                .exists();
    }

    private void project(Path dir) throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, "project:\n  name: uc\n  security_class: guarded\n");
        final Path registry = dir.resolve("data/porelo/projects");
        Files.createDirectories(registry);
        Files.writeString(registry.resolve("uc"), file + "\n");
        Files.createDirectories(dir.resolve("data/porelo/mirrors/uc.git/objects"));
    }

    @Test
    void projectDeletionReportsAFailedPurgeAndKeepsTheMirror(@TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        project(dir);
        runner.failing("podman stop", 125, "runtime refused");
        runner.failing("podman rm", 125, "runtime refused");
        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", false, true);
        assertThat(result.outcome())
                .as("failed task purge prevents project deletion")
                .isEqualTo(ProjectDeletion.Outcome.FAILED);
        assertThat(result.detail())
                .as("project deletion names the failed task")
                .contains(CONTAINER);
        assertThat(dir.resolve("data/porelo/mirrors/uc.git"))
                .as("work at the gate is kept")
                .isDirectory();
    }

    @Test
    void clearingReportsAFailedPurge(@TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir);
        project(dir);
        runner.failing("podman stop", 125, "runtime refused");
        runner.failing("podman rm", 125, "runtime refused");
        final Clearing.Result result = new Clearing(context).project("uc", false, true);
        assertThat(result.items())
                .as("clearing reports the failed removal")
                .anyMatch(item -> item.status() == Clearing.Status.NOT_REMOVED);
        assertThat(context.recordedId(CONTAINER))
                .as("clearing keeps the identity for retry")
                .isEqualTo("a1b2c3d4e5f6");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accountClearingKeepsTransportsAfterAFailedPurge(boolean builtIn, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        if (builtIn) {
            runner.answering(
                    "ps", CONTAINER + "\tUp 4 minutes\t\t\tdefault\tguarded\t\t\ta1b2c3d4e5f6\n");
        } else {
            project(dir);
        }
        runner.failing("podman stop", 125, "runtime refused");
        runner.failing("podman rm", 125, "runtime refused");
        final Path files =
                context.paths().xdg().state().resolve("transport/room/conversation.json");
        Files.createDirectories(files.getParent());
        Files.writeString(files, "conversation to keep");
        final StringWriter out = new StringWriter();
        final int exit =
                PoreloCli.commandLine(context)
                        .setOut(new PrintWriter(out))
                        .setErr(new PrintWriter(new StringWriter()))
                        .execute("clear", "--yes", "--force");
        assertThat(files)
                .as("a surviving task keeps its transport's local files")
                .hasContent("conversation to keep");
        assertThat(exit).as("failed account clearing fails the command").isNotZero();
        assertThat(out.toString())
                .as("the skipped cleanup explains why")
                .contains("transport")
                .contains("removal failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"remove", "unfollow"})
    void anInteractiveStopNamesSurvivorsAndFails(String verb, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        project(dir);
        runner.answering("git status --porcelain", "0 0");
        runner.failing("podman stop", 125, "runtime refused");
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final picocli.CommandLine cli;
        final String[] args;
        if (verb.equals("unfollow")) {
            final ProjectUnfollowCommand command = new ProjectUnfollowCommand();
            command.setContext(context);
            command.asker = question -> "y";
            cli = new picocli.CommandLine(command);
            args = new String[] {"uc"};
        } else {
            cli = PoreloCli.commandLine(context);
            args = new String[] {"task", "remove", CONTAINER, "--yes"};
        }
        final int exit =
                cli.setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(args);
        assertThat(err.toString())
                .as("every interactive caller names the survivor")
                .contains("porelo: still running: container " + CONTAINER);
        assertThat(exit).as("a failed interactive stop fails its caller").isEqualTo(70);
        assertThat(out.toString())
                .as("a failed interactive stop cannot claim removal")
                .doesNotContain("removed   ");
    }
}
