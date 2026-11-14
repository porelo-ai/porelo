package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link RunLimitWatch}: which runs it ends at their limit, and which it leaves. */
class RunLimitWatchTest {

    private static final String TASK = "porelo-p-unit";

    private static final Instant START = Instant.parse("2026-11-14T09:00:00Z");

    private static final RunLimit.Waiter GONE = new RunLimit.Waiter(4242, "2026-11-14T08:59:00Z");

    @TempDir Path dir;

    private final FakeCommandRunner runner = new TransportTestRunner();

    private final List<String> ended = new ArrayList<>();

    private final List<String> said = new ArrayList<>();

    private final List<RunLimit.Waiter> asked = new ArrayList<>();

    private boolean stops = true;

    private PoreloContext context() {
        return new PoreloContext(
                runner,
                new PoreloPaths(
                        org.fuin.porelo.core.config.XdgPaths.of(
                                name ->
                                        switch (name) {
                                            case "XDG_CONFIG_HOME" ->
                                                    dir.resolve("config").toString();
                                            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                            case "XDG_STATE_HOME" ->
                                                    dir.resolve("state").toString();
                                            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                            default -> null;
                                        },
                                dir),
                        dir.resolve("bin")),
                args -> 0);
    }

    private Path state() {
        return context().paths().tasks().containerState(TASK);
    }

    private RunLimitWatch watch(final Instant now, final boolean waiterAlive) throws Exception {
        LegacyTaskFixture.record(context(), TASK, "p", "a".repeat(64));
        runner.answering(
                "podman ps",
                TASK
                        + "\tUp 4 minutes\t1700000000\t0\tp\tguarded\tp\t\t"
                        + "a".repeat(64)
                        + "\tunit\n");
        return new RunLimitWatch(
                context().recognizingTasks(),
                Duration.ofMinutes(1),
                said::add,
                () -> now,
                waiter -> {
                    asked.add(waiter);
                    return waiterAlive;
                },
                (container, minutes) -> {
                    ended.add(container + " " + minutes);
                    if (stops) {
                        // As the confirmed stop does.
                        try {
                            RunLimit.closed(state());
                        } catch (java.io.IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    }
                    return stops;
                });
    }

    @Test
    void aRunPastItsDeadlineThatNothingWaitsOnIsEndedAtItsLimit() throws Exception {
        // porelod restarted during the run: the agent went on, and nothing remembered its limit.
        RunLimit.started(state(), 1, START, GONE);

        final RunLimitWatch watch = watch(START.plusSeconds(61), false);

        assertThat(watch.passOnce()).as("the run it ended").containsExactly(TASK);
        assertThat(asked).as("the waiter the run recorded").containsExactly(GONE);
        assertThat(ended).as("ended as its own wait would").containsExactly(TASK + " 1");
        assertThat(watch.passOnce()).as("once, not at every pass").isEmpty();
        assertThat(ended).hasSize(1);
    }

    @Test
    void aRunWhoseWaiterStillRunsIsLeftToIt() throws Exception {
        RunLimit.started(state(), 1, START, GONE);

        assertThat(watch(START.plusSeconds(61), true).passOnce()).as("its wait ends it").isEmpty();
        assertThat(ended).isEmpty();
    }

    @Test
    void aRunBeforeItsDeadlineIsLeft() throws Exception {
        RunLimit.started(state(), 1, START, GONE);

        assertThat(watch(START.plusSeconds(59), false).passOnce()).isEmpty();
        assertThat(ended).isEmpty();
    }

    @Test
    void aClosedRunIsLeft() throws Exception {
        RunLimit.started(state(), 1, START, GONE);
        RunLimit.closed(state());

        assertThat(watch(START.plusSeconds(61), false).passOnce()).isEmpty();
        assertThat(ended).isEmpty();
    }

    @Test
    void aRecordWithoutADeadlineIsNoDeadline() throws Exception {
        // As every run recorded before the deadline was kept.
        java.nio.file.Files.createDirectories(state());
        java.nio.file.Files.writeString(state().resolve(RunLimit.GIVEN), "{\"minutes\":1}");

        assertThat(watch(START.plusSeconds(3600), false).passOnce()).isEmpty();
        assertThat(ended).isEmpty();
    }

    @Test
    void aRunWhoseStopFailedIsTriedAgainAtTheNextPassAndSaidOnce() throws Exception {
        // Closed before its stop, a run whose stop failed was never tried again and ran on.
        RunLimit.started(state(), 1, START, GONE);
        stops = false;
        final RunLimitWatch watch = watch(START.plusSeconds(61), false);

        assertThat(watch.passOnce()).as("not ended").isEmpty();
        assertThat(watch.passOnce()).isEmpty();
        assertThat(ended).as("tried at each pass").hasSize(2);
        assertThat(said).as("said once").hasSize(1);
        assertThat(said.getFirst()).contains("could not end at its time limit");

        stops = true;
        assertThat(watch.passOnce()).as("ended once its stop goes through").containsExactly(TASK);
        assertThat(watch.passOnce()).isEmpty();
    }

    @Test
    void aRunThisProcessStillWaitsOnIsLeftAndOneItLetGoIsDue() throws Exception {
        // The waiter is a process, and the daemon outlives each run it waited on.
        RunLimit.started(state(), 1);
        final java.time.Instant later = Instant.now().plusSeconds(61);

        assertThat(RunLimit.due(state(), later, waiter -> true))
                .as("waited on in this process")
                .isNull();
        RunLimit.released(state());
        assertThat(RunLimit.due(state(), later, waiter -> true))
                .as("this process waits on it no more, whatever its pid says")
                .isEqualTo(1);
    }

    @Test
    void closingTheRunOfARemovedTaskMakesNoStateAgain() throws Exception {
        // Made again, a removed task's state refused every later start of its name.
        RunLimit.started(state(), 1, START, GONE);
        try (java.util.stream.Stream<Path> paths = java.nio.file.Files.walk(state())) {
            for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.delete(path);
            }
        }

        RunLimit.closed(state());

        assertThat(state()).doesNotExist();
    }

    @Test
    void thisProcessIsAliveAndAnotherStartOfItsPidIsNot() {
        final RunLimit.Waiter self = RunLimit.Waiter.current();

        assertThat(self.alive()).as("this process").isTrue();
        assertThat(new RunLimit.Waiter(self.pid(), "1970-01-01T00:00:00Z").alive())
                .as("its pid, started at another time")
                .isFalse();
    }
}
