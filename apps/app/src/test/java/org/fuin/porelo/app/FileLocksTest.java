package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prevents simultaneous writers after a waiting process's lock file is unlinked. */
class FileLocksTest {

    @Test
    void waitingProcessesRetryAnUnlinkedLockBeforeEntering(@TempDir Path dir) throws Exception {
        final Path lock = dir.resolve("clone.lock");
        final Path events = Files.createFile(dir.resolve("events"));
        final List<Process> children = new ArrayList<>();
        try {
            final Process waiter =
                    FileLocks.holding(
                            lock,
                            () -> {
                                final long oldInode = (long) Files.getAttribute(lock, "unix:ino");
                                final Process queued = start(dir, "waiter");
                                children.add(queued);
                                await(
                                        () -> blocked(queued, oldInode),
                                        "the second process is waiting on the original inode");
                                // Unlink while held, as cleanup must: a queued opener must recheck
                                // its identity.
                                Files.delete(lock);
                                final Process third = start(dir, "third");
                                children.add(third);
                                await(
                                        () -> Files.exists(dir.resolve("third.begin")),
                                        "the third process holds the replacement inode");
                                return queued;
                            });
            final long replacementInode = (long) Files.getAttribute(lock, "unix:ino");
            await(
                    () ->
                            Files.exists(dir.resolve("waiter.begin"))
                                    || blocked(waiter, replacementInode),
                    "the waiter enters or retries on the replacement inode");
            Files.createFile(dir.resolve("release"));
            for (final Process child : children) {
                assertThat(child.waitFor(10, TimeUnit.SECONDS)).as("the child finishes").isTrue();
                assertThat(child.exitValue())
                        .as(
                                "the child succeeds: "
                                        + Files.readString(dir.resolve(child.pid() + ".log")))
                        .isZero();
            }
            assertThat(Files.readAllLines(events))
                    .as("process critical sections never interleave")
                    .containsExactly("third begin", "third end", "waiter begin", "waiter end");
        } finally {
            for (final Process child : children) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static Process start(Path dir, String role) throws IOException {
        final Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                Locker.class.getName(),
                                dir.toString(),
                                role)
                        .redirectErrorStream(true)
                        .redirectOutput(dir.resolve(role + ".log").toFile())
                        .start();
        Files.createSymbolicLink(dir.resolve(process.pid() + ".log"), Path.of(role + ".log"));
        return process;
    }

    private static boolean blocked(Process process, long inode) {
        try (var input = Files.newInputStream(Path.of("/proc/locks"))) {
            final String locks =
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return locks.lines()
                    .anyMatch(
                            line ->
                                    line.contains("->")
                                            && line.matches(
                                                    ".*\\s"
                                                            + process.pid()
                                                            + "\\s.*:"
                                                            + inode
                                                            + "\\s.*"));
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static void await(BooleanSupplier ready, String reason) throws IOException {
        final long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new IOException("Timed out: " + reason);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException(ex);
            }
        }
    }

    /** Runs in a separate JVM so a pending file lock really waits in the kernel. */
    public static final class Locker {
        /**
         * Enters one critical section, keeping the third process there until the test releases it.
         */
        public static void main(String[] args) throws IOException {
            final Path dir = Path.of(args[0]);
            final String role = args[1];
            FileLocks.holding(
                    dir.resolve("clone.lock"),
                    () -> {
                        Files.writeString(
                                dir.resolve("events"),
                                role + " begin\n",
                                StandardOpenOption.APPEND);
                        Files.createFile(dir.resolve(role + ".begin"));
                        await(
                                () -> Files.exists(dir.resolve("release")),
                                "the parent releases the critical section");
                        Files.writeString(
                                dir.resolve("events"), role + " end\n", StandardOpenOption.APPEND);
                        return true;
                    });
        }
    }
}
