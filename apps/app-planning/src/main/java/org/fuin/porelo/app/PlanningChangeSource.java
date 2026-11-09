package org.fuin.porelo.app;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** The installed adapter and timer edges of the otherwise synchronous subscription logic. */
final class PlanningChangeSource implements PlanningChanges.Source {
    private final PlanningReads reads;
    private final Supplier<PlanningReadSnapshot> snapshots;
    private volatile boolean closed;

    PlanningChangeSource(PlanningReads reads, Supplier<PlanningReadSnapshot> snapshots) {
        this.reads = reads;
        this.snapshots = snapshots;
    }

    @Override
    public Map<String, Object> board() {
        return reads.board(snapshots.get());
    }

    @Override
    public boolean hasWatch() {
        return reads.hasWatch();
    }

    @Override
    public String refresh() {
        return reads.refresh();
    }

    @Override
    public void watch(Consumer<String> changed, Consumer<RuntimeException> failed, Runnable ended) {
        if (closed) return;
        Thread.ofPlatform()
                .daemon()
                .name("porelo-planning-changes")
                .start(
                        () -> {
                            if (closed) return;
                            try {
                                reads.watch(
                                        revision -> {
                                            if (!closed) changed.accept(revision);
                                        });
                            } catch (RuntimeException ex) {
                                if (!closed) failed.accept(ex);
                                return;
                            }
                            if (!closed) ended.run();
                        });
    }

    @Override
    public void close() {
        closed = true;
        reads.close();
    }

    /** Lazy, reusable after becoming idle, and free of executor threads with no watchers. */
    static final class Timer implements PlanningChanges.Scheduler {
        private final java.util.concurrent.ThreadFactory threads;
        private final java.util.Set<Thread> actions = new java.util.HashSet<>();
        private @Nullable ScheduledThreadPoolExecutor executor;
        private long generation;

        Timer() {
            this(Thread.ofPlatform().daemon().name("porelo-planning-timer-", 0).factory());
        }

        Timer(java.util.concurrent.ThreadFactory threads) {
            this.threads = threads;
        }

        @Override
        public synchronized PlanningChanges.Cancellation schedule(Duration delay, Runnable action) {
            var current = executor;
            if (current == null) {
                current = new ScheduledThreadPoolExecutor(1, threads);
                current.setRemoveOnCancelPolicy(true);
                executor = current;
            }
            final long scheduled = generation;
            final var future =
                    current.schedule(
                            () -> dispatch(scheduled, action),
                            delay.toMillis(),
                            TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        private synchronized void dispatch(long scheduled, Runnable action) {
            if (executor == null || scheduled != generation) return;
            final Thread worker =
                    threads.newThread(
                            () -> {
                                try {
                                    action.run();
                                } finally {
                                    synchronized (Timer.this) {
                                        actions.remove(Thread.currentThread());
                                    }
                                }
                            });
            actions.add(worker);
            worker.start();
        }

        @Override
        public synchronized void close() {
            generation++;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            for (var thread : actions) {
                if (thread != Thread.currentThread()) thread.interrupt();
            }
        }
    }
}
