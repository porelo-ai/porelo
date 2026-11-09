package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostConfigurationLockTest {
    @TempDir Path directory;

    private int tryFlock(Path file, boolean shared) throws Exception {
        final Process process =
                new ProcessBuilder(
                                "/usr/bin/flock",
                                shared ? "--shared" : "--exclusive",
                                "--nonblock",
                                file.toString(),
                                "/usr/bin/true")
                        .redirectErrorStream(true)
                        .start();
        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS))
                    .as("the independent flock probe is bounded")
                    .isTrue();
            return process.exitValue();
        } finally {
            process.destroyForcibly();
            process.getInputStream().close();
            process.getOutputStream().close();
            process.getErrorStream().close();
        }
    }

    @Test
    void aSharedLockAllowsReadersAndExcludesPythonCompatibleWriters() throws Exception {
        final Path file = Files.writeString(directory.resolve("forge.conf"), "host=workshop\n");
        try (var lock = HostConfigurationLock.acquire(file, true)) {
            assertThat(tryFlock(file, true))
                    .as("Linux flock readers share the host status lock")
                    .isZero();
            assertThat(tryFlock(file, false))
                    .as("a writer cannot change state during the shared read")
                    .isEqualTo(1);
        }
        assertThat(tryFlock(file, false)).as("closing the lock releases it").isZero();
    }

    @Test
    void anExclusiveLockExcludesOtherReadersAndWriters() throws Exception {
        final Path file = Files.writeString(directory.resolve("forge.conf"), "host=workshop\n");
        try (var lock = HostConfigurationLock.acquire(file, false)) {
            assertThat(tryFlock(file, true))
                    .as("task show retains Python's exclusive locking")
                    .isEqualTo(1);
            assertThat(tryFlock(file, false)).as("another writer waits too").isEqualTo(1);
        }
        assertThat(tryFlock(file, true)).as("the read lock is available again on close").isZero();
    }

    @Test
    void theLockNeverCreatesAMissingConfiguration() {
        final Path missing = directory.resolve("forge.conf");
        assertThatThrownBy(() -> HostConfigurationLock.acquire(missing, true))
                .as("root's mutex must already exist")
                .isInstanceOf(IOException.class);
        assertThat(missing).as("an invalid host read leaves no new file").doesNotExist();
    }
}
