package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostPoolWatchProgramsTest {
    @TempDir Path directory;

    /** Harmless child whose second line waits for the parent's handling of its first. */
    public static final class EventChild {
        /** Writes a synthetic event, with a deadline if the parent fails to drain it. */
        public static void main(String[] args) throws Exception {
            System.out.println("event 'lifecycle' for domain 'pool-1': Stopped Shutdown");
            System.err.println("a warning is not a lifecycle event");
            System.out.flush();
            final long until = System.nanoTime() + 5_000_000_000L;
            while (!Files.exists(Path.of(args[0]))) {
                if (System.nanoTime() >= until) System.exit(1);
                Thread.sleep(10);
            }
            System.out.println("event loop timed out");
        }
    }

    @Test
    void theWatchStartsBeforeTheCensusAndDeliversEventsBeforeItExits() throws Exception {
        final Path handled = directory.resolve("handled");
        final AtomicBoolean scanned = new AtomicBoolean();
        final List<String> lines = new ArrayList<>();
        final var programs =
                new HostPrograms(
                        Duration.ofSeconds(10),
                        65536,
                        name -> Path.of(System.getProperty("java.home"), "bin", "java"));
        final var result =
                programs.watch(
                        List.of(
                                "java",
                                "-XX:-UsePerfData",
                                "-Djava.io.tmpdir=" + directory.toAbsolutePath(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                EventChild.class.getName(),
                                handled.toAbsolutePath().toString()),
                        Map.of(),
                        () -> scanned.set(true),
                        line -> {
                            assertThat(scanned.get())
                                    .as("queued lifecycle events follow the initial census")
                                    .isTrue();
                            lines.add(line);
                            try {
                                Files.writeString(handled, "handled");
                            } catch (java.io.IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                        });
        assertThat(result.exitCode())
                .as("the child only exits successfully after the first callback")
                .isZero();
        assertThat(result.error()).isEqualTo("a warning is not a lifecycle event\n");
        assertThat(lines)
                .as("both event and timeout lines are delivered while the pipe is open")
                .containsExactly(
                        "event 'lifecycle' for domain 'pool-1': Stopped Shutdown",
                        "event loop timed out");
    }
}
