package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sweep releases kept readings on its timer, so heap held after a burst is given back without a
 * later call, and it ends with the daemon that started it.
 */
class ReadingSweepTest {

    private static final Duration KEPT = Duration.ofMinutes(5);

    /** Short, so the timer fires within the test; the clock decides what has expired. */
    private static final Duration OFTEN = Duration.ofMillis(10);

    @TempDir Path dir;

    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(start);
    private final KeptReadings<String, String> readings =
            new KeptReadings<>(now::get, KEPT, 4, 64L * 1024 * 1024);

    @Test
    void theTimerReleasesAReadingNoLaterCallAsksFor() throws Exception {
        readings.keep("burst", "records", 64L * 1024 * 1024);
        now.set(start.plus(KEPT));
        final CountDownLatch swept = new CountDownLatch(1);

        try (ReadingSweep sweep = new ReadingSweep(OFTEN)) {
            sweep.add(readings::release);
            sweep.add(swept::countDown);

            assertThat(swept.await(30, TimeUnit.SECONDS)).as("the timer swept").isTrue();
        }

        assertThat(readings.bytes()).as("released with no call of its kind").isZero();
    }

    @Test
    void aReleaseThatFailsDoesNotEndTheSweep() throws Exception {
        final CountDownLatch swept = new CountDownLatch(2);

        try (ReadingSweep sweep = new ReadingSweep(OFTEN)) {
            sweep.add(
                    () -> {
                        throw new IllegalStateException("one kind failed");
                    });
            sweep.add(swept::countDown);

            assertThat(swept.await(30, TimeUnit.SECONDS))
                    .as("the sweep runs again after a failure")
                    .isTrue();
        }
    }

    @Test
    void aReleaseThatFailsIsNamedOnceByItsClassAlone() throws Exception {
        final java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();
        final CountDownLatch swept = new CountDownLatch(3);

        try (ReadingSweep sweep =
                new ReadingSweep(
                        OFTEN,
                        new java.io.PrintStream(
                                written, true, java.nio.charset.StandardCharsets.UTF_8))) {
            sweep.add(
                    () -> {
                        throw new IllegalStateException("secret-looking detail");
                    });
            sweep.add(swept::countDown);

            assertThat(swept.await(30, TimeUnit.SECONDS)).as("swept three times").isTrue();
        }

        final String text = written.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text.lines().toList())
                .as("one line for a release that failed on every sweep")
                .hasSize(1)
                .first()
                .asString()
                .contains(IllegalStateException.class.getName());
        assertThat(text).as("its message is not written").doesNotContain("secret-looking");
    }

    @Test
    void nothingIsAddedToAnEndedSweep() {
        final ReadingSweep sweep = new ReadingSweep(OFTEN);
        sweep.close();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sweep.add(() -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closingEndsTheSweep() {
        final ReadingSweep sweep = new ReadingSweep(OFTEN);

        sweep.close();

        assertThat(sweep.ended()).as("its timer runs it no more").isTrue();
    }

    @Test
    void theDaemonsStopEndsItsSweep() throws Exception {
        final ReadingSweep sweep = new ReadingSweep(OFTEN);
        final VarlinkServer server =
                PoreloDaemon.serving(
                        context(),
                        dir.resolve("porelo.sock"),
                        org.fuin.porelo.core.process.SocketPeer::read,
                        null,
                        sweep);

        server.close();

        assertThat(sweep.ended()).as("the server's close ends the sweep").isTrue();
    }

    @Test
    void aServerThatCannotBeMadeLeavesItsSweepEnded() throws Exception {
        // A socket whose directory is a file cannot be bound, so the server is never made.
        final Path file = java.nio.file.Files.writeString(dir.resolve("taken"), "");
        final ReadingSweep sweep = new ReadingSweep(OFTEN);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                PoreloDaemon.serving(
                                        context(),
                                        file.resolve("porelo.sock"),
                                        org.fuin.porelo.core.process.SocketPeer::read,
                                        null,
                                        sweep))
                .isInstanceOf(RuntimeException.class);

        assertThat(sweep.ended()).as("no sweep is left on the timer").isTrue();
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }
}
