package org.fuin.porelo.app;

import java.util.concurrent.atomic.AtomicReference;

/** Lets a test land a removal exactly while another thread waits for a file's lock. */
final class Races {

    private Races() {}

    /**
     * Returns once the thread, started meanwhile, waits for {@link FileLocks}' lock: it is past
     * whatever it checked before taking the lock, and has not made anything for it yet.
     *
     * @param thread The thread, set once it is made.
     */
    static void waitsAtTheLock(final AtomicReference<Thread> thread) {
        final long until = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < until) {
            final Thread waiting = thread.get();
            if (waiting != null && atTheLock(waiting)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the thread never came to the lock");
    }

    private static boolean atTheLock(final Thread thread) {
        if (thread.getState() != Thread.State.WAITING) {
            return false;
        }
        for (final StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals(FileLocks.class.getName())) {
                return true;
            }
        }
        return false;
    }
}
