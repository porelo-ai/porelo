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
    void aLockFileThatIsNotThereIsFree(@TempDir Path dir) {
        // Read as held, a sign-in that never ran kept its leftover container from every sweep.
        assertThat(FileLocks.free(dir.resolve("logins").resolve("gone.lock")))
                .as("nobody can hold a lock file that does not exist")
                .isTrue();
    }

    @Test
    void aProbeNeverDropsTheLockAnotherThreadOfThisProcessHolds(@TempDir Path dir)
            throws Exception {
        // A probe's channel closing drops every lock this process holds on the file: a sign-in
        // in the daemon lost its lock to a listing, and a terminal's login then took it.
        final Path lock = dir.resolve("logins").resolve("example.lock");
        final java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean();
        final Thread probing =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    while (!stop.get()) {
                                        FileLocks.free(lock);
                                    }
                                });
        int dropped = 0;
        int holds = 0;
        try {
            final long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() - until < 0) {
                final java.util.Optional<Boolean> kept =
                        FileLocks.holdingIfFree(
                                lock,
                                () -> {
                                    Thread.onSpinWait();
                                    return heldHere(lock);
                                });
                if (kept.isPresent()) {
                    holds++;
                }
                if (kept.isPresent() && !kept.get()) {
                    dropped++;
                }
            }
        } finally {
            stop.set(true);
            probing.join();
        }
        assertThat(holds).as("the lock was taken beside the probes").isPositive();
        assertThat(dropped).as("holds whose lock the kernel no longer had").isZero();
    }

    @Test
    void aReadOfTheKernelsListThatMissesTheLineOnceStillReadsTheLockAsHeld() throws IOException {
        // The kernel writes /proc/locks in pieces while every process takes and drops locks; one
        // read during a busy build missed a held lock and failed the probe test above.
        final String line = "1: POSIX  ADVISORY  WRITE 4242 00:2a:77 0 EOF";
        final java.util.Iterator<List<String>> reads =
                List.of(List.<String>of(), List.of(line), List.of(line)).iterator();
        assertThat(heldIn(reads::next, "4242", 77))
                .as("a lock missing from one read but listed by the next two is held")
                .isTrue();
        final java.util.Iterator<List<String>> none =
                List.of(List.<String>of(), List.<String>of()).iterator();
        assertThat(heldIn(none::next, "4242", 77))
                .as("a lock two reads in a row do not list is not held")
                .isFalse();
    }

    /** Says whether the kernel lists a lock of this process on the file. */
    private static boolean heldHere(final Path lock) throws IOException {
        final long inode = (long) Files.getAttribute(lock, "unix:ino");
        final String pid = Long.toString(ProcessHandle.current().pid());
        return heldIn(() -> Files.readAllLines(Path.of("/proc/locks")), pid, inode);
    }

    /** One read of the kernel's lock list. */
    private interface LockList {
        List<String> read() throws IOException;
    }

    /**
     * Reads the list until two reads in a row agree, at most five times, and answers what they
     * agree on; with no agreement, whether any read listed the lock.
     */
    private static boolean heldIn(final LockList list, final String pid, final long inode)
            throws IOException {
        Boolean last = null;
        boolean seen = false;
        for (int read = 0; read < 5; read++) {
            final boolean now = listed(list.read(), pid, inode);
            seen |= now;
            if (last != null && last == now) {
                return now;
            }
            last = now;
        }
        return seen;
    }

    private static boolean listed(final List<String> lines, final String pid, final long inode) {
        for (final String line : lines) {
            final String[] fields = line.trim().split("\\s+");
            if (fields.length > 5 && fields[4].equals(pid) && fields[5].endsWith(":" + inode)) {
                return true;
            }
        }
        return false;
    }

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
