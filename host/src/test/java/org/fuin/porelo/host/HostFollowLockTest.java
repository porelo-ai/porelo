package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostFollowLockTest {
    @TempDir Path directory;

    // Progress is live only while the writer holds its stable inode.
    @Test
    void aProbeSeesTheExclusiveWriterAndItsRelease() throws Exception {
        final Path path = Files.writeString(directory.resolve("follow.lock"), "");
        assertThat(HostFollowLock.live(path)).as("a free file is no live run").isFalse();
        try (var writer = HostFollowLock.acquire(path)) {
            assertThat(HostFollowLock.live(path))
                    .as("an exclusive writer prevents a shared probe")
                    .isTrue();
        }
        assertThat(HostFollowLock.live(path)).as("closing releases the writer").isFalse();
    }

    // A repeated cleanup cannot close a descriptor the kernel has since reused.
    @Test
    void closingTwiceDoesNotCloseAnotherLock() throws Exception {
        final Path path = Files.writeString(directory.resolve("follow.lock"), "");
        final var first = HostFollowLock.acquire(path);
        first.close();
        try (var second = HostFollowLock.acquire(path)) {
            assertThatCode(first::close)
                    .as("cleanup closes its descriptor only once")
                    .doesNotThrowAnyException();
            assertThat(HostFollowLock.live(path)).as("the second holder retains its lock").isTrue();
        }
    }

    // Simulated errno answers exercise retries without a process-wide signal handler.
    @Test
    void interruptedAttemptsRetryUntilTheKernelGivesAnAnswer() throws Exception {
        final var attempts = new java.util.concurrent.atomic.AtomicInteger();
        assertThat(HostFollowLock.retryInterrupted(() -> attempts.incrementAndGet() < 3 ? 4 : 0))
                .isZero();
        assertThat(attempts.get()).as("both interrupted calls were retried").isEqualTo(3);
        assertThat(HostFollowLock.retryInterrupted(() -> 11))
                .as("contention is returned without retry")
                .isEqualTo(11);
    }

    // A missing or substituted inode cannot vouch for a running follow.
    @Test
    void missingFilesAndSymlinksAreRefusedWithoutCreatingAnything() throws Exception {
        final Path path = directory.resolve("follow.lock");
        assertThatThrownBy(() -> HostFollowLock.live(path)).isInstanceOf(java.io.IOException.class);
        assertThat(path).as("a probe never creates a lock").doesNotExist();
        final Path target = Files.writeString(directory.resolve("target"), "");
        Files.createSymbolicLink(path, target);
        assertThatThrownBy(() -> HostFollowLock.live(path)).isInstanceOf(java.io.IOException.class);
    }
}
