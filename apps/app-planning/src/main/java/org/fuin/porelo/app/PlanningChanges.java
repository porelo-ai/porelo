package org.fuin.porelo.app;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * Shared project invalidations; all state transitions are serialized, and clients drain private
 * mailboxes.
 */
public final class PlanningChanges implements AutoCloseable {
    /** Adapters without a watch are read at this interval, without fetching. */
    public static final Duration POLL_INTERVAL = Duration.ofMinutes(3);

    /**
     * A broken adapter stream is reopened after this delay, doubling up to the polling interval.
     */
    public static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    /** A project's board and its independent invalidation stream; close cancels the stream too. */
    public interface Source extends AutoCloseable {
        /** Reads the complete enriched board, or throws its named Tasks1 refusal. */
        Map<String, Object> board();

        /** Whether the adapter advertises watch. */
        boolean hasWatch();

        /** Opens a stream; failure calls failed, and normal ending calls ended. */
        void watch(Consumer<String> changed, Consumer<RuntimeException> failed, Runnable ended);

        /** Explicitly fetches, returning the adapter's revision or its named refusal. */
        String refresh();

        /** Cancels the stream and releases the adapter. */
        @Override
        void close();
    }

    /** A cancellable delayed action. */
    @FunctionalInterface
    public interface Cancellation {
        /** Prevents a pending action from running. */
        void cancel();
    }

    /**
     * Supplies time independently of subscription logic; callbacks must not run before schedule
     * returns.
     */
    public interface Scheduler extends AutoCloseable {
        /** Runs once after the delay. */
        Cancellation schedule(Duration delay, Runnable action);

        /** Releases idle resources; a subsequent schedule may acquire them again. */
        @Override
        default void close() {}
    }

    private final Function<String, Source> sources;
    private final Scheduler scheduler;
    private final Map<String, Project> projects = new LinkedHashMap<>();
    private boolean closed;

    /** Uses caller-supplied sources and time; shutdown closes sources concurrently. */
    public PlanningChanges(Function<String, Source> sources, Scheduler scheduler) {
        this.sources = sources;
        this.scheduler = scheduler;
    }

    /** Connects installed adapter reads to platform threads and a cancellable timer. */
    public static PlanningChanges installed(
            Function<String, PlanningReads> reads,
            Function<String, PlanningReadSnapshot> snapshots) {
        return new PlanningChanges(
                project ->
                        new PlanningChangeSource(
                                reads.apply(project), () -> snapshots.apply(project)),
                new PlanningChangeSource.Timer());
    }

    /** Reads once without subscribing; an existing project uses its already running adapter. */
    public Map<String, Object> board(String project) {
        final var active = acquire(project);
        try {
            return source(active).board();
        } finally {
            release(active);
        }
    }

    /** Fetches through the same adapter as existing watchers, if any. */
    public String refresh(String project) {
        final var active = acquire(project);
        try {
            final String revision = source(active).refresh();
            final boolean changed;
            synchronized (this) {
                changed =
                        live(active)
                                && !active.watchers.isEmpty()
                                && !revision.equals(active.board.get("revision"));
            }
            if (changed) reread(active);
            return revision;
        } finally {
            release(active);
        }
    }

    /** Delivers the initial board, opening one adapter subscription with the first watcher. */
    public Subscription watch(String project) {
        final var active = acquire(project);
        try {
            final boolean reread;
            synchronized (this) {
                reread =
                        active.board.isEmpty() || (!active.watchers.isEmpty() && !active.streaming);
            }
            if (reread) reread(active);
            final Subscription subscription;
            final boolean first;
            synchronized (this) {
                requireLive(active);
                subscription = new Subscription(active, active.board);
                active.watchers.add(subscription);
                first = active.watchers.size() == 1;
            }
            if (first) start(active);
            return subscription;
        } finally {
            release(active);
        }
    }

    /** Rereads local work even when the adapter revision is unchanged. */
    public void signal(String project) {
        final Project active;
        synchronized (this) {
            final var candidate = projects.get(project);
            active = candidate != null && !candidate.watchers.isEmpty() ? candidate : null;
        }
        if (active != null) reread(active);
    }

    private Project acquire(String name) {
        final Project project;
        final boolean created;
        synchronized (this) {
            requireOpen();
            final var existing = projects.get(name);
            created = existing == null;
            project = existing == null ? new Project(name) : existing;
            projects.put(name, project);
            project.users++;
        }
        try {
            if (created) initialize(project);
            else project.initialized.join();
            return project;
        } catch (CompletionException ex) {
            release(project);
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw ex;
        } catch (RuntimeException ex) {
            release(project);
            throw ex;
        }
    }

    private void initialize(Project project) {
        try {
            final Source source = sources.apply(project.name);
            final boolean accepted;
            synchronized (this) {
                accepted = live(project);
                if (accepted) project.source = source;
            }
            if (!accepted) {
                source.close();
                throw closing();
            }
            synchronized (this) {
                requireLive(project);
                project.initialized.complete(true);
            }
        } catch (RuntimeException ex) {
            project.initialized.completeExceptionally(ex);
            refuse(project, ex);
            throw ex;
        }
    }

    private synchronized Source source(Project project) {
        requireLive(project);
        return java.util.Objects.requireNonNull(
                project.source, "An initialized project has its source");
    }

    private void release(Project project) {
        final Source closing;
        synchronized (this) {
            project.users--;
            closing = project.users == 0 && project.watchers.isEmpty() ? detach(project) : null;
        }
        if (closing != null) closing.close();
    }

    private void start(Project project) {
        final long generation;
        synchronized (this) {
            if (!live(project)) return;
            generation = ++project.generation;
        }
        try {
            final var source = source(project);
            final boolean watch = source.hasWatch();
            synchronized (this) {
                if (!current(project, generation)) return;
                project.streaming = watch;
                if (!watch) {
                    project.pending =
                            scheduler.schedule(POLL_INTERVAL, () -> poll(project, generation));
                    return;
                }
            }
            source.watch(
                    revision -> changed(project, generation, revision),
                    error -> failed(project, generation, error),
                    () -> ended(project, generation));
        } catch (RuntimeException ex) {
            failed(project, generation, ex);
        }
    }

    private void changed(Project project, long generation, String revision) {
        final boolean changed;
        synchronized (this) {
            if (!current(project, generation)) return;
            project.backoff = RETRY_DELAY;
            changed = !revision.equals(project.board.get("revision"));
        }
        if (changed) reread(project);
    }

    private void failed(Project project, long generation, RuntimeException error) {
        synchronized (this) {
            if (!current(project, generation)) return;
            if (!(error instanceof VarlinkException)) {
                retry(project);
                return;
            }
        }
        refuse(project, error, generation, 0);
    }

    private synchronized void ended(Project project, long generation) {
        if (current(project, generation)) retry(project);
    }

    private void retry(Project project) {
        project.streaming = false;
        final long generation = ++project.generation;
        if (project.pending != null) project.pending.cancel();
        project.pending = scheduler.schedule(project.backoff, () -> reopen(project, generation));
        project.backoff =
                project.backoff.multipliedBy(2).compareTo(POLL_INTERVAL) > 0
                        ? POLL_INTERVAL
                        : project.backoff.multipliedBy(2);
    }

    private void reopen(Project project, long generation) {
        synchronized (this) {
            if (!current(project, generation)) return;
            project.pending = null;
        }
        reread(project);
        start(project);
    }

    private void poll(Project project, long generation) {
        synchronized (this) {
            if (!current(project, generation)) return;
            project.pending = null;
        }
        reread(project);
        start(project);
    }

    private void reread(Project project) {
        final long reading;
        synchronized (this) {
            if (!live(project)) return;
            reading = ++project.reading;
        }
        try {
            final var board = source(project).board();
            synchronized (this) {
                if (!live(project) || reading != project.reading || board.equals(project.board))
                    return;
                project.board = board;
                for (var watcher : project.watchers) watcher.offer(new Board(board));
            }
        } catch (RuntimeException ex) {
            refuse(project, ex, 0, reading);
        }
    }

    private void refuse(Project project, RuntimeException error) {
        refuse(project, error, 0, 0);
    }

    private void refuse(Project project, RuntimeException error, long generation, long reading) {
        final Source closing;
        final var refusal =
                error instanceof VarlinkException named && named.getErrorName() != null
                        ? named
                        : new VarlinkException(
                                TasksContract.INTERFACE + ".Failed",
                                Map.of(
                                        "message",
                                        "Cannot read the planning board; repair the adapter and reconnect"));
        synchronized (this) {
            if (!live(project)
                    || (generation != 0 && generation != project.generation)
                    || (reading != 0 && reading != project.reading)) return;
            project.refusal = refusal;
            for (var watcher : project.watchers) watcher.offer(new Refused(refusal));
            closing = detach(project);
        }
        if (closing != null) closing.close();
    }

    private boolean live(Project project) {
        return projects.get(project.name) == project;
    }

    private boolean current(Project project, long generation) {
        return live(project) && generation == project.generation;
    }

    private @Nullable Source detach(Project project) {
        if (!projects.remove(project.name, project)) return null;
        if (project.pending != null) project.pending.cancel();
        if (projects.values().stream().allMatch(active -> active.watchers.isEmpty()))
            scheduler.close();
        return project.source;
    }

    private void requireLive(Project project) {
        if (!live(project)) throw project.refusal == null ? closing() : project.refusal;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Planning changes is closed");
    }

    private static VarlinkException closing() {
        return new VarlinkException(
                TasksContract.INTERFACE + ".Unreachable",
                Map.of(
                        "detail",
                        "The board service is closing; reconnect to the daemon",
                        "retryAfter",
                        0));
    }

    /** Cancels every adapter stream and wakes every waiting client. */
    @Override
    public void close() {
        final var sources = new ArrayList<Source>();
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (var project : new ArrayList<>(projects.values())) {
                final var refusal = closing();
                project.refusal = refusal;
                project.initialized.completeExceptionally(refusal);
                for (var watcher : project.watchers) watcher.offer(new Refused(refusal));
                final var source = detach(project);
                if (source != null) sources.add(source);
            }
            scheduler.close();
        }
        // Each adapter must receive cancellation even if another takes time to close.
        try (var executor =
                java.util.concurrent.Executors.newThreadPerTaskExecutor(
                        Thread.ofPlatform().daemon().name("porelo-planning-close-", 0).factory())) {
            CompletableFuture.allOf(
                            sources.stream()
                                    .map(
                                            source ->
                                                    CompletableFuture.runAsync(
                                                            source::close, executor))
                                    .toArray(CompletableFuture<?>[]::new))
                    .join();
        }
    }

    private static final class Project {
        final String name;
        final CompletableFuture<Boolean> initialized = new CompletableFuture<>();
        final ArrayList<Subscription> watchers = new ArrayList<>();
        @Nullable Source source;
        Map<String, Object> board = Map.of();
        @Nullable Cancellation pending;
        @Nullable VarlinkException refusal;
        Duration backoff = RETRY_DELAY;
        boolean streaming;
        int users;
        long generation;
        long reading;

        Project(String name) {
            this.name = name;
        }
    }

    private sealed interface Update permits Board, Refused, End {}

    private record Board(Map<String, Object> fields) implements Update {}

    private record Refused(VarlinkException error) implements Update {}

    private record End() implements Update {}

    /** An initial board plus a bounded latest update, never a callback into a client's socket. */
    public final class Subscription implements AutoCloseable {
        private final Project project;
        private final ArrayBlockingQueue<Update> pending = new ArrayBlockingQueue<>(1);
        private final AtomicReference<@Nullable Map<String, Object>> initial;
        private volatile boolean finished;

        private Subscription(Project project, Map<String, Object> initial) {
            this.project = project;
            this.initial = new AtomicReference<>(initial);
        }

        private void offer(Update update) {
            if (!pending.offer(update)) {
                pending.clear();
                pending.offer(update);
            }
        }

        /** Waits at most the bound; a named refusal terminates this subscription. */
        public @Nullable Map<String, Object> next(long timeoutMillis) throws InterruptedException {
            if (finished) return null;
            final var first = initial.getAndSet(null);
            if (first != null) return finished ? null : first;
            final Update update = pending.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (finished || update == null) return null;
            return switch (update) {
                case Board board -> board.fields();
                case Refused refused -> {
                    close();
                    throw refused.error();
                }
                case End ignored -> {
                    close();
                    yield null;
                }
            };
        }

        /** Drops this watcher immediately; the last watcher also cancels the adapter and timer. */
        @Override
        public void close() {
            final Source closing;
            synchronized (PlanningChanges.this) {
                if (finished) return;
                finished = true;
                initial.set(null);
                pending.clear();
                pending.offer(new End());
                project.watchers.remove(this);
                // A read still running on the adapter closes it at its release instead.
                closing = project.watchers.isEmpty() && project.users == 0 ? detach(project) : null;
            }
            if (closing != null) closing.close();
        }
    }
}
