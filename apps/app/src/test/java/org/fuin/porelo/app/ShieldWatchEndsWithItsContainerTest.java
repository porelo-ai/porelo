package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A clearance watcher ends with the container whose process it was given. */
class ShieldWatchEndsWithItsContainerTest {

    @TempDir Path dir;

    @Test
    void aWatcherFollowsWhileItsContainerRunsAndEndsOnceItIsGone() throws Exception {
        final Process container = new ProcessBuilder("sleep", "60").start();
        try {
            final Path events = dir.resolve("events.jsonl");
            Files.writeString(events, "first\n", StandardCharsets.UTF_8);
            final List<String> handled = new CopyOnWriteArrayList<>();
            final CompletableFuture<Boolean> watcher =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return ShieldWatchCommand.followUntil(
                                            events,
                                            handled::add,
                                            ShieldWatchCommand.gone(container.pid()),
                                            new CountDownLatch(1));
                                } catch (Exception ex) {
                                    throw new IllegalStateException(ex);
                                }
                            });

            Thread.sleep(Duration.ofMillis(1500));
            assertThat(watcher).isNotDone();
            Files.writeString(
                    events,
                    "second\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            Thread.sleep(Duration.ofMillis(1500));

            container.destroy();
            container.waitFor(5, TimeUnit.SECONDS);

            assertThat(watcher.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(handled).containsExactly("first", "second");
        } finally {
            container.destroyForcibly();
        }
    }

    @Test
    void aProcessIsGoneOnceItEnded() throws Exception {
        final Process container = new ProcessBuilder("sleep", "60").start();
        final java.util.function.BooleanSupplier gone = ShieldWatchCommand.gone(container.pid());
        assertThat(gone.getAsBoolean()).isFalse();
        container.destroy();
        container.waitFor(5, TimeUnit.SECONDS);
        assertThat(gone.getAsBoolean()).isTrue();
    }

    @Test
    void aProcessThatTookTheIdLaterIsNotTheContainer() throws Exception {
        final Process other = new ProcessBuilder("sleep", "60").start();
        try {
            assertThat(
                            ShieldWatchCommand.gone(other.pid(), Optional.of(Instant.EPOCH))
                                    .getAsBoolean())
                    .isTrue();
            assertThat(
                            ShieldWatchCommand.gone(
                                            other.pid(), other.toHandle().info().startInstant())
                                    .getAsBoolean())
                    .isFalse();
        } finally {
            other.destroyForcibly();
        }
    }

    @Test
    void anIdNoProcessHasIsGone() {
        assertThat(ShieldWatchCommand.gone(Long.MAX_VALUE).getAsBoolean()).isTrue();
    }
}
