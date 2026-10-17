package org.fuin.porelo.legs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Tests how a long phase of a leg reaches the job's log: as it is written, and with a line of its
 * own when it is quiet.
 */
class StreamedTest {

    @Test
    void aLineReachesTheLogBeforeTheCommandEnds() throws Exception {

        // The build's output reached the job's log only when the build ended: 29 minutes of nothing
        // after '-- building --', and nobody could tell a slow build from a hung one (2026-10-17).
        final PipedInputStream out = new PipedInputStream();
        final PipedInputStream err = new PipedInputStream();
        final PipedOutputStream toOut = new PipedOutputStream(out);
        final PipedOutputStream toErr = new PipedOutputStream(err);
        final ByteArrayOutputStream log = new ByteArrayOutputStream();
        final Streamed.Progress progress =
                new Streamed.Progress(
                        "building",
                        new PrintStream(log, true, StandardCharsets.UTF_8),
                        System::currentTimeMillis,
                        Duration.ofMinutes(1));

        final Thread pump =
                Thread.ofPlatform().daemon().start(() -> Streamed.pump(out, err, progress));
        toOut.write("[INFO] Building porelo-core\n".getBytes(StandardCharsets.UTF_8));
        toErr.write("warning: on the side\n".getBytes(StandardCharsets.UTF_8));
        toOut.flush();
        toErr.flush();

        // Both streams still open: the command has not ended.
        assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> {
                    while (!log.toString(StandardCharsets.UTF_8).contains("warning: on the side")
                            || !log.toString(StandardCharsets.UTF_8)
                                    .contains("[INFO] Building porelo-core")) {
                        Thread.sleep(20);
                    }
                });
        toOut.close();
        toErr.close();
        pump.join(5_000);
        assertThat(pump.isAlive()).isFalse();
    }

    @Test
    void aQuietPhaseSaysWhatItIsAndHowLongItHasTakenOncePerMinute() {
        final AtomicLong now = new AtomicLong(1_000_000);
        final ByteArrayOutputStream log = new ByteArrayOutputStream();
        final Streamed.Progress progress =
                new Streamed.Progress(
                        "building",
                        new PrintStream(log, true, StandardCharsets.UTF_8),
                        now::get,
                        Duration.ofMinutes(1));

        now.addAndGet(Duration.ofSeconds(59).toMillis());
        progress.tick();
        assertThat(log.toString(StandardCharsets.UTF_8)).isEmpty();

        now.addAndGet(Duration.ofSeconds(1).toMillis());
        progress.tick();
        progress.tick();
        assertThat(log.toString(StandardCharsets.UTF_8))
                .isEqualTo("  ... building, 1 min so far\n");

        // A line of the command's own resets the quiet, not the clock of the phase.
        progress.line("[INFO] Building porelo-app");
        now.addAndGet(Duration.ofMinutes(2).toMillis());
        progress.tick();
        assertThat(log.toString(StandardCharsets.UTF_8))
                .endsWith("[INFO] Building porelo-app\n  ... building, 3 min so far\n");
    }
}
