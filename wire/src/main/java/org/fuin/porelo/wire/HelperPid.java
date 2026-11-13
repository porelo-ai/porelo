package org.fuin.porelo.wire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/**
 * A helper's process id, written so that whoever finds it later can tell it is still the same
 * process.
 *
 * <p><strong>A number alone is not an identity.</strong> Process ids are reused. A helper that
 * exits leaves its file behind until something cleans up, and by then the number may belong to
 * anything the same user is running - an editor, an ssh agent, another task's helper. Signalling on
 * the strength of that number is how a tidy-up kills something nobody asked it to touch. Nothing
 * here crosses a user boundary; the operating system still refuses another user's process. It is an
 * avoidable way to take out the operator's own.
 *
 * <p>So the file records the id <em>and</em> the process's start time, and nothing is signalled
 * until both agree with the process that holds the id now. Start time is what makes the pair
 * unique: the kernel will reuse an id, but not at the same instant.
 *
 * <p><strong>A file that cannot be verified is not signalled.</strong> One written by an older
 * build carries no start time, so there is nothing to compare - and the safe answer to "is this
 * still the helper" is no. The file is removed and the process left alone: an orphaned helper is
 * visible and stoppable by name, whereas a process killed by mistake is gone.
 */
public final class HelperPid {

    private HelperPid() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Writes this process's identity, owner-only.
     *
     * @param file Where to write it.
     * @throws IOException If it cannot be written.
     */
    public static void record(Path file) throws IOException {
        record(file, ProcessHandle.current());
    }

    /**
     * Writes a process's identity, owner-only.
     *
     * @param file Where to write it.
     * @param process Whose identity to write.
     * @throws IOException If it cannot be written.
     */
    public static void record(Path file, ProcessHandle process) throws IOException {
        final String started =
                process.info()
                        .startInstant()
                        .map(instant -> String.valueOf(instant.toEpochMilli()))
                        .orElse("");
        Files.writeString(file, process.pid() + " " + started, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(
                    file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ex) {
            // A filesystem without posix permissions. The directory above is owner-only anyway.
        }
    }

    /**
     * Stops a helper and every process it started, those first.
     *
     * <p>A helper's own processes are not ended with it: a signal goes to one process, and a build
     * watch stopped through its pid file left the build reader it had started running for hours
     * after its task was removed.
     *
     * @param helper The helper, as {@link #verified} found it.
     */
    public static void stop(final ProcessHandle helper) {
        // Taken before anything is signalled: a child whose parent is gone is no longer its
        // descendant.
        final java.util.List<ProcessHandle> started = helper.descendants().toList();
        started.forEach(ProcessHandle::destroy);
        helper.destroy();
    }

    /**
     * Ends a helper and every process it started, as {@link #stop} does, and kills whatever of them
     * still runs once the grace has passed.
     *
     * <p>Signalled is not yet gone: a helper that ignores {@code SIGTERM}, or is stuck before it
     * handles it, ran on after a start that gave up on it answered, and one that never recorded its
     * pid was then named by nothing that cleans up.
     *
     * @param helper The helper.
     * @param grace How long it has to go once asked.
     * @return Whether it and everything it started are gone.
     */
    public static boolean end(final ProcessHandle helper, final java.time.Duration grace) {
        // Taken before anything is signalled: a child whose parent is gone is no longer its
        // descendant, and could not be killed below.
        final java.util.List<ProcessHandle> all =
                new java.util.ArrayList<>(helper.descendants().toList());
        all.add(helper);
        all.forEach(ProcessHandle::destroy);
        if (gone(all, grace)) {
            return true;
        }
        all.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        return gone(all, KILLED);
    }

    /** How long a killed helper has to be gone; the kernel does not refuse a kill. */
    private static final java.time.Duration KILLED = java.time.Duration.ofSeconds(2);

    private static boolean gone(
            final java.util.List<ProcessHandle> processes, final java.time.Duration within) {
        final long until = System.nanoTime() + within.toNanos();
        for (final ProcessHandle process : processes) {
            final long left = until - System.nanoTime();
            try {
                process.onExit().get(Math.max(left, 0), java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException
                    | java.util.concurrent.ExecutionException ex) {
                // Still there, or not to be waited for: asked below.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return processes.stream().noneMatch(ProcessHandle::isAlive);
    }

    /**
     * Waits until a helper has recorded itself and still runs, or the patience runs out.
     *
     * <p>A helper that was started is not yet one that came up: until its record is there, nothing
     * that cleans up can find it, and a start that went on without it answered success for a task
     * that had no gate.
     *
     * <p>Measured on the monotonic clock: a wall clock set back while a helper starts made the wait
     * as much longer.
     *
     * @param file The record the helper writes.
     * @param deadline When to give up, as {@link System#nanoTime} reads it.
     * @return The helper, or empty when it did not record itself in time, or is gone again.
     */
    public static Optional<ProcessHandle> await(Path file, long deadline) {
        while (true) {
            final Optional<ProcessHandle> found = verified(file);
            if (found.isPresent() || System.nanoTime() - deadline >= 0) {
                return found;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return verified(file);
            }
        }
    }

    /**
     * Returns the deadline {@link #await} takes, a patience from now.
     *
     * @param patience How long to wait.
     * @return The deadline, as {@link System#nanoTime} reads it.
     */
    public static long deadline(final java.time.Duration patience) {
        return System.nanoTime() + patience.toNanos();
    }

    /**
     * Returns the process a record names, when it is still that process.
     *
     * @param file The record.
     * @return The handle, or empty when the process is gone, the record is unreadable, or the id
     *     now belongs to something else.
     */
    public static Optional<ProcessHandle> verified(Path file) {
        final String[] parts;
        try {
            parts = Files.readString(file, StandardCharsets.UTF_8).strip().split("\\s+");
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
        if (parts.length < 2 || parts[1].isBlank()) {
            // No start time: written by an older build, or by something else entirely. Nothing to
            // compare against, so nothing is signalled.
            return Optional.empty();
        }
        final long pid;
        final long started;
        try {
            pid = Long.parseLong(parts[0]);
            started = Long.parseLong(parts[1]);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        return ProcessHandle.of(pid)
                .filter(
                        handle ->
                                handle.info()
                                        .startInstant()
                                        .map(instant -> instant.toEpochMilli() == started)
                                        .orElse(false));
    }
}
