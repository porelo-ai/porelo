package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link TaskRoomSweep}: when it sweeps, and that a shut vault asks nothing. */
class TaskRoomSweepTest {

    @TempDir Path dir;

    private final FakeCommandRunner runner = new TransportTestRunner();

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-11-13T09:00:00Z"));

    private final AtomicBoolean open = new AtomicBoolean();

    private final List<String> said = new ArrayList<>();

    private TaskRoomSweep sweep() {
        return new TaskRoomSweep(context(), Duration.ofMinutes(1), open::get, said::add, now::get);
    }

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

    @Test
    void sweepsAtTheStartOnceAMinuteAndWhenTheVaultOpens() {
        // The start covers a machine that was off; the opening covers the sweeps a locked vault
        // could not do.
        final TaskRoomSweep sweep = sweep();

        assertThat(sweep.due()).as("at the start").isTrue();
        now.set(now.get().plusSeconds(10));
        assertThat(sweep.due()).as("ten seconds on").isFalse();
        open.set(true);
        assertThat(sweep.due()).as("the vault opened").isTrue();
        now.set(now.get().plusSeconds(30));
        assertThat(sweep.due()).as("open still, half a minute on").isFalse();
        now.set(now.get().plusSeconds(31));
        assertThat(sweep.due()).as("a minute after the last").isTrue();
    }

    @Test
    void aShutVaultAsksNoTransportAndNoContainerList() {
        assertThat(sweep().sweepOnce()).isEmpty();
        assertThat(runner.invocations()).as("nothing run").isEmpty();
        assertThat(said).isEmpty();
    }

    @Test
    void aRunningTaskWhoseRecordNamesAnotherIdIsHereAllTheSame() throws Exception {
        // Read through the verified list, a running task with a lost or stale record was taken for
        // ended and retired at every sweep.
        LegacyTaskFixture.record(context(), "porelo-p-unit", "p", "b".repeat(64));
        runner.answering(
                "podman ps",
                "porelo-p-unit\tUp 4 minutes\t1700000000\t0\tp\tguarded\tp\t\t"
                        + "a".repeat(64)
                        + "\tunit\n");

        // As the daemon's context does: a task only by the id Porelo recorded.
        final TaskRoomSweep sweep =
                new TaskRoomSweep(
                        context().recognizingTasks(),
                        Duration.ofMinutes(1),
                        open::get,
                        said::add,
                        now::get);

        assertThat(sweep.running()).containsExactly("porelo-p-unit");
    }
}
