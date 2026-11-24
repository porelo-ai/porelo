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
