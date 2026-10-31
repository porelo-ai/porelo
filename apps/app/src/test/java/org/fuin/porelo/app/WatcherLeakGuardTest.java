package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A watcher a test leaves running outlives the test JVM and never exits, so the guard must see one,
 * stop it, and leave alone what is no watcher or ran before the test.
 */
class WatcherLeakGuardTest {

    private static final Duration SHORT = Duration.ofMillis(300);

    /** A process whose command line reads as a watcher's, running until it is stopped. */
    private static Process watcherLike(final String seconds) throws IOException {
        return new ProcessBuilder(
                        "sh",
                        "-c",
                        "sleep " + seconds + "; :",
                        "porelo",
                        "shield",
                        "watch",
                        "--project",
                        "uc")
                .start();
    }

    @Test
    void aWatcherLeftRunningIsNamedAndStopped() throws IOException {
        final Process watcher = watcherLike("60");
        try {
            final WatcherLeakGuard guard =
                    new WatcherLeakGuard(() -> Stream.of(watcher.toHandle()), SHORT);

            final List<String> left = guard.leftOver(Set.of());

            assertThat(left)
                    .as("the watcher still running after the test is named by its pid")
                    .singleElement()
                    .asString()
                    .startsWith(watcher.pid() + " ")
                    .contains(" shield watch ");
            assertThat(watcher.toHandle().onExit())
                    .as("and stopped, so it does not outlive the JVM")
                    .succeedsWithin(Duration.ofSeconds(5));
        } finally {
            watcher.destroyForcibly();
        }
    }

    @Test
    void aWatcherThatExitsWithinThePatienceIsNoLeak() throws IOException {
        final Process watcher = watcherLike("0.1");
        try {
            final WatcherLeakGuard guard =
                    new WatcherLeakGuard(
                            () -> Stream.of(watcher.toHandle()), Duration.ofSeconds(5));

            assertThat(guard.leftOver(Set.of()))
                    .as("a watcher the test stopped may take a moment to exit")
                    .isEmpty();
        } finally {
            watcher.destroyForcibly();
        }
    }

    @Test
    void aWatcherRunningBeforeTheTestAndAnyOtherProcessAreLeftAlone() throws IOException {
        final Process earlier = watcherLike("60");
        final Process other = new ProcessBuilder("sh", "-c", "sleep 60; :").start();
        try {
            final WatcherLeakGuard guard =
                    new WatcherLeakGuard(
                            () -> Stream.of(earlier.toHandle(), other.toHandle()), SHORT);

            assertThat(guard.leftOver(Set.of(earlier.pid())))
                    .as("only a watcher this test started is its leak")
                    .isEmpty();
            assertThat(earlier.isAlive() && other.isAlive())
                    .as("and nothing the test did not start is stopped")
                    .isTrue();
        } finally {
            earlier.destroyForcibly();
            other.destroyForcibly();
        }
    }
}
