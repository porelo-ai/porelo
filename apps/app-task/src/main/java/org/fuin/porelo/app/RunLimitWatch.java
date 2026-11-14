package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.fuin.porelo.runtime.ContainerSummary;

/**
 * Ends an unattended run at its time limit when nothing waits on it any more.
 *
 * <p><strong>The limit was only the wait in the process that started the run.</strong> A run
 * started through {@code porelod} waits in the daemon, and the agent goes on running in its
 * container when the daemon restarts: nothing then remembered that the run had a limit. So the run
 * keeps its deadline and its waiter in the task's state ({@link RunLimit#started}), and this pass
 * reads them, at the daemon's start and once a minute: a run whose deadline passed, that is not
 * closed and whose waiter is gone is ended as its own wait would have ended it. A run whose waiter
 * still runs is left to it, so a limit is never ended twice.
 *
 * <p>Only the confirmed stop closes the run ({@link TaskControl} closes it as it stops a running
 * task): one closed before it, whose stop then failed, was never tried again and ran on unbounded.
 * A stop that fails is tried again at the next pass, and said once.
 */
public final class RunLimitWatch implements AutoCloseable {

    /** How often it looks while the daemon runs. */
    public static final Duration INTERVAL = Duration.ofMinutes(1);

    /** Ends one task's run at its limit. */
    @FunctionalInterface
    interface Ending {
        /**
         * Ends the run.
         *
         * @param container The task.
         * @param minutes The limit that passed.
         * @return Whether the stop went through.
         */
        boolean end(String container, int minutes);
    }

    private final PoreloContext context;

    private final Duration every;

    private final Consumer<String> log;

    private final Supplier<Instant> clock;

    private final Predicate<RunLimit.Waiter> alive;

    private final Ending ending;

    private volatile boolean running = true;

    /** The tasks whose stop failed, said once until it goes through. */
    private final java.util.Set<String> failing = new java.util.HashSet<>();

    /**
     * Constructor.
     *
     * @param context The account.
     * @param every How often to look.
     * @param log Where each run it ended, and each it could not, is said, one line each.
     */
    public RunLimitWatch(
            final PoreloContext context, final Duration every, final Consumer<String> log) {
        this(context, every, log, Instant::now, RunLimit.Waiter::alive, null);
    }

    RunLimitWatch(
            final PoreloContext context,
            final Duration every,
            final Consumer<String> log,
            final Supplier<Instant> clock,
            final Predicate<RunLimit.Waiter> alive,
            final @org.jspecify.annotations.Nullable Ending ending) {
        this.context = context;
        this.every = every;
        this.log = log;
        this.clock = clock;
        this.alive = alive;
        this.ending = ending == null ? this::endAtLimit : ending;
    }

    /**
     * Looks once at every running task.
     *
     * @return The tasks whose run it ended.
     */
    List<String> passOnce() {
        final List<String> ended = new ArrayList<>();
        final List<String> tasks;
        try {
            tasks = runningTasks();
        } catch (RuntimeException ex) {
            log.accept(
                    "could not list the running tasks for their time limits: " + ex.getMessage());
            return ended;
        }
        final Instant now = clock.get();
        for (final String container : tasks) {
            final java.nio.file.Path state = context.paths().tasks().containerState(container);
            final Integer minutes = RunLimit.due(state, now, alive);
            if (minutes == null) {
                continue;
            }
            boolean stopped;
            String why = "its stop did not go through";
            try {
                stopped = ending.end(container, minutes);
            } catch (RuntimeException ex) {
                stopped = false;
                why = String.valueOf(ex.getMessage());
            }
            if (stopped) {
                failing.remove(container);
                log.accept(
                        container
                                + ": ended at its time limit of "
                                + minutes
                                + " minutes, which nothing waited on any more");
                ended.add(container);
            } else if (failing.add(container)) {
                log.accept(
                        container
                                + ": could not end at its time limit, tried again each pass: "
                                + why);
            }
        }
        return ended;
    }

    private boolean endAtLimit(final String container, final int minutes) {
        final StringWriter said = new StringWriter();
        final boolean stopped;
        try (PrintWriter err = new PrintWriter(said)) {
            stopped = TaskLaunch.endAtLimit(context, container, minutes, err);
        }
        if (stopped || !failing.contains(container)) {
            // A stop that fails again says nothing new.
            said.toString().lines().forEach(line -> log.accept(container + ": " + line));
        }
        return stopped;
    }

    /** Starts looking, on a thread of its own: at once, then every interval. */
    public void start() {
        if (every.isZero() || every.isNegative()) {
            return;
        }
        BackgroundPass.start(
                "porelo-run-limit",
                () -> {
                    while (running) {
                        passOnce();
                        try {
                            Thread.sleep(every.toMillis());
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                });
    }

    @Override
    public void close() {
        running = false;
    }

    private List<String> runningTasks() {
        final List<String> names = new ArrayList<>();
        for (final ContainerSummary summary : context.podman().taskCandidates()) {
            if (summary.running()) {
                names.add(summary.name());
            }
        }
        return names;
    }
}
