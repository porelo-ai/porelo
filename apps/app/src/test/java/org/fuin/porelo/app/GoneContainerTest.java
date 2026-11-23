package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task whose container was removed outside Porelo, by {@code podman rm} or a forced removal of
 * its image, keeps its name taken until Porelo clears what it holds for it.
 */
class GoneContainerTest {

    private static final String CONTAINER = "porelo-p_c";

    private static final String ID = "a1b2c3d4e5f6";

    @TempDir private Path dir;

    private PoreloContext context(final FakeCommandRunner runner) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0)
                .recognizingTasks();
    }

    /** Podman as it answers once the container is gone: no row, and no such container by id. */
    private static FakeCommandRunner podmanWithoutTheContainer() {
        return new FakeCommandRunner()
                .answering("{{.Names}}", "")
                .answering("ps", "")
                .failing("inspect", 125, "Error: no such container " + ID + "\n");
    }

    /** What a task that ran leaves, its container since removed outside Porelo. */
    private static Path ranAndRemovedOutside(
            final PoreloContext context, final FakeCommandRunner runner) throws IOException {
        // As a start leaves it: the name's claim, and the id in its runtime folder and record.
        try (var claim = new TaskReservations(context).reserve("p", "c", CONTAINER, "first")) {
            final TaskRunner started = new TaskRunner(runner, context.paths());
            started.reserving(claim);
            started.recordCreated(CONTAINER, ID);
        }
        final Path runtime = context.paths().tasks().containerState(CONTAINER);
        // The sidecar a start writes in both folders, naming the runtime folder as its state.
        for (final Path folder :
                java.util.List.of(runtime, context.paths().tasks().taskRecord(CONTAINER))) {
            new Sidecar(
                            Sidecar.VERSION,
                            "p",
                            "guarded",
                            runtime.resolve("ruleset.nft").toString(),
                            runtime.resolve("dns.conf").toString(),
                            "/usr/bin/porelo",
                            runtime.toString(),
                            "c",
                            null,
                            null,
                            org.fuin.porelo.wire.LimitFactors.DEFAULT)
                    .writeTo(folder.resolve("sidecar.json"));
        }
        assertThat(Sidecar.recordedId(runtime)).as("the fixture records the id").isEqualTo(ID);
        assertThat(new TaskIdentities(context).retained(CONTAINER))
                .as("the fixture is a task Porelo knows, as one that ran is")
                .isInstanceOf(TaskIdentities.Found.class);
        // As found after the container went: the name's claim keeps the id, no folder does.
        for (final Path folder :
                java.util.List.of(runtime, context.paths().tasks().taskRecord(CONTAINER))) {
            Files.delete(folder.resolve(Sidecar.RECORDED_ID));
        }
        return runtime;
    }

    @Test
    void aTaskWhoseContainerWasRemovedOutsidePoreloIsRemovedWithWhatPoreloKeptForIt()
            throws IOException {
        final FakeCommandRunner runner = podmanWithoutTheContainer();
        final PoreloContext context = context(runner);
        final Path runtime = ranAndRemovedOutside(context, runner);

        final TaskControl.Stopped removed =
                new TaskControl(context).remove(CONTAINER, false, false);

        assertThat(removed.outcome())
                .as("a task Porelo recorded is a task, though its container is gone")
                .isNotEqualTo(TaskControl.Outcome.NOT_A_TASK);
        assertThat(Files.exists(runtime))
                .as("its runtime folder is cleared, so the name is free again")
                .isFalse();
        assertThat(Files.exists(context.paths().tasks().taskRecord(CONTAINER)))
                .as("its saved record is cleared too")
                .isFalse();
        assertThat(new TaskReservations(context).protectedNames())
                .as("the name is retired, so a start may take it again")
                .doesNotContain(CONTAINER);
        assertThat(runner.lines())
                .as("nothing is removed by its name, which another container may hold now")
                .noneMatch(line -> line.contains(" rm ") && line.contains(CONTAINER));
    }

    @Test
    void aTaskWhoseContainerWasRemovedOutsidePoreloIsRemovedByItsTaskName() throws IOException {
        final FakeCommandRunner runner = podmanWithoutTheContainer();
        final PoreloContext context = context(runner);
        final Path runtime = ranAndRemovedOutside(context, runner);
        final var cli = PoreloCli.commandLine(context);
        final java.io.StringWriter said = new java.io.StringWriter();
        cli.setOut(new java.io.PrintWriter(said, true));
        cli.setErr(new java.io.PrintWriter(said, true));

        assertThat(cli.execute("task", "remove", "c", "--no-input"))
                .as("the task is found by its name, as a person gives it: %s", said)
                .isZero();
        assertThat(Files.exists(runtime)).as("its runtime folder is cleared").isFalse();
        assertThat(new TaskReservations(context).protectedNames())
                .as("the name is free again")
                .doesNotContain(CONTAINER);
    }

    @Test
    void workTheRecordOfAGoneContainerHoldsIsClearedOnlyWhenForced() throws IOException {
        final FakeCommandRunner runner = podmanWithoutTheContainer();
        final PoreloContext context = context(runner);
        final Path runtime = ranAndRemovedOutside(context, runner);
        UnhandedWork.note(runtime, UnhandedWork.census("2 1"));
        final TaskControl control = new TaskControl(context);

        assertThat(control.remove(CONTAINER, false, false).outcome())
                .as("what the record says it held is said before it is cleared")
                .isEqualTo(TaskControl.Outcome.HOLDS_WORK);
        assertThat(Files.exists(runtime)).as("nothing is cleared unforced").isTrue();
        control.remove(CONTAINER, false, true);
        assertThat(Files.exists(runtime)).as("forced, the record is cleared").isFalse();
    }

    @Test
    void aContainerOfTheNameWithAnotherIdIsNeitherTakenForTheTaskNorTouched() throws IOException {
        final FakeCommandRunner runner = podmanWithoutTheContainer();
        final PoreloContext context = context(runner);
        final Path runtime = ranAndRemovedOutside(context, runner);
        // Made since by something else under the same name, as podman lists it by name and in rows.
        runner.answering("{{.Names}}", CONTAINER + "\n");
        runner.answering(
                "ps", CONTAINER + "\tUp 2 minutes\t\t\tp\tguarded\tc\t\t" + "f".repeat(64) + "\n");

        assertThat(new TaskReservations(context).goneId(CONTAINER))
                .as("a container holds the name, so the task is not gone")
                .isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TaskControl(context).remove(CONTAINER, false, true))
                .as("the removal is refused, since a container holds the name")
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);

        assertThat(runner.lines())
                .as("the other container is neither removed nor stopped")
                .noneMatch(line -> line.contains(" rm ") || line.contains(" stop "));
        assertThat(Files.exists(runtime)).as("the task's record stays").isTrue();
    }

    @Test
    void aTaskWhoseContainerWasRemovedOutsidePoreloIsListed() throws IOException {
        final FakeCommandRunner runner = podmanWithoutTheContainer();
        final PoreloContext context = context(runner);
        ranAndRemovedOutside(context, runner);

        assertThat(new TaskInventory(context).gone())
                .as("its name stays taken, so the list names it with what clears it")
                .containsExactly(new TaskInventory.Gone(CONTAINER, "p", "c"));
    }

    @Test
    void aRecordThatCannotBeTrustedKeepsTheListWorkingAndTheNameProtected() throws IOException {
        final FakeCommandRunner runner = podmanWithoutTheContainer();
        final PoreloContext context = context(runner);
        final Path runtime = ranAndRemovedOutside(context, runner);
        Files.createSymbolicLink(runtime.resolve(Sidecar.RECORDED_ID), dir.resolve("elsewhere"));

        assertThat(new TaskInventory(context).gone())
                .as("one untrusted record neither breaks the list nor counts as gone")
                .isEmpty();
        assertThat(new TaskReservations(context).goneId(CONTAINER))
                .as("its name stays protected")
                .isNull();
    }

    @Test
    void aRecordThatCannotBeReadKeepsTheListWorkingAndTheNameProtected() throws IOException {
        final FakeCommandRunner runner = podmanWithoutTheContainer();
        final PoreloContext context = context(runner);
        ranAndRemovedOutside(context, runner);
        // A saved record its owner cannot read: reading it fails, not as "absent".
        final Path record = context.paths().tasks().taskRecord(CONTAINER);
        Files.setPosixFilePermissions(
                record, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        try {
            assertThat(new TaskInventory(context).gone())
                    .as("one unreadable record neither breaks the list nor counts as gone")
                    .isEmpty();
        } finally {
            Files.setPosixFilePermissions(
                    record, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aStartOfTheNameOfATaskWhoseContainerIsGoneNamesTheRemovalThatFreesIt() throws IOException {
        final FakeCommandRunner runner = podmanWithoutTheContainer();
        final PoreloContext context = context(runner);
        ranAndRemovedOutside(context, runner);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TaskReservations(context).start("p", "c", CONTAINER))
                .as("the start is refused, since the record may hold work, and says what frees it")
                .isInstanceOf(TaskReservations.ContainerNameTaken.class)
                .hasMessageContaining("porelo task remove " + CONTAINER);
    }
}
