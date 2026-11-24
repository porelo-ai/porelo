package org.fuin.porelo.daemon;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Releases a server's kept readings once their keep time has passed, on a timer.
 *
 * <p>A reading was released only when a later call of its kind looked, so after a burst of large
 * readings that no call followed, up to their bound stayed held for the daemon's life. Each kind
 * releases under the lock its callers take, so a reading inside its keep time, which a caller may
 * still page through, stays; one already handed out is the caller's until it is done with it.
 *
 * <p>Every sweep of the process runs on one platform thread, so a task's socket adds a timer entry
 * and no thread; a sweep ends when the server that started it closes.
 */
final class ReadingSweep implements AutoCloseable {

    /** How often the kept readings are swept. */
    static final Duration EVERY = Duration.ofMinutes(1);

    /** The one thread every sweep runs on; an ended sweep leaves no entry in it. */
    private static final ScheduledThreadPoolExecutor TIMER = timer();

    /** One kind's release, and whether its failure was already written. */
    private record Release(Runnable run, AtomicBoolean told) {}

    private final List<Release> releases = new CopyOnWriteArrayList<>();
    private final java.io.PrintStream errors;
    private final ScheduledFuture<?> scheduled;
    private boolean closed;

    /**
     * Starts sweeping.
     *
     * @param every How often to sweep.
     */
    ReadingSweep(final Duration every) {
        this(every, System.err);
    }

    /**
     * Starts sweeping, writing a failed release where given.
     *
     * @param every How often to sweep.
     * @param errors Where a kind whose release failed is named, once.
     */
    ReadingSweep(final Duration every, final java.io.PrintStream errors) {
        this.errors = errors;
        final long millis = Math.max(1, every.toMillis());
        this.scheduled =
                TIMER.scheduleWithFixedDelay(this::sweep, millis, millis, TimeUnit.MILLISECONDS);
    }

    private static ScheduledThreadPoolExecutor timer() {
        final ScheduledThreadPoolExecutor timer =
                new ScheduledThreadPoolExecutor(
                        1, Thread.ofPlatform().daemon().name("porelod-reading-sweep").factory());
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    /**
     * Adds what releases one kind of reading.
     *
     * @param release Releases the readings of that kind whose keep time has passed.
     * @throws IllegalStateException When this sweep has ended: what is added would never run.
     */
    synchronized void add(final Runnable release) {
        if (closed) {
            throw new IllegalStateException("the reading sweep has ended");
        }
        releases.add(new Release(release, new AtomicBoolean()));
    }

    /**
     * Releases every kind once; one that fails keeps neither the others nor the next sweep, and is
     * named once by the class of what it threw, never its message, which may carry what it held.
     */
    void sweep() {
        for (final Release release : releases) {
            try {
                release.run().run();
            } catch (final RuntimeException ex) {
                // The next sweep tries it again: a timer entry that throws is never run again.
                if (release.told().compareAndSet(false, true)) {
                    errors.println(
                            "porelod: releasing kept readings failed: " + ex.getClass().getName());
                    errors.flush();
                }
            }
        }
    }

    /**
     * Returns whether this sweep has ended, so its timer runs it no more.
     *
     * @return Whether it has ended.
     */
    boolean ended() {
        return scheduled.isDone();
    }

    @Override
    public synchronized void close() {
        closed = true;
        scheduled.cancel(false);
        releases.clear();
    }
}
