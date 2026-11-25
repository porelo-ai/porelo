package org.fuin.porelo.app;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.fuin.porelo.gate.review.CurrentRuns;
import org.fuin.porelo.gate.review.Run;

/**
 * The run each task is in now, kept in memory for the daemon's life, safe across threads.
 *
 * <p>A run begun for a task that is already in one replaces it: the start that begins it has
 * already lapsed every undecided item of the earlier run at its place ({@code
 * ReviewHolds.startRun}), so the earlier run has nothing left to answer for. A daemon that restarts
 * knows no run; a task's submission is then refused until its next start.
 */
public final class HeldRuns implements CurrentRuns {

    private final ConcurrentMap<String, Run> runs = new ConcurrentHashMap<>();

    @Override
    public Optional<Run> of(final String task) {
        return Optional.ofNullable(runs.get(Objects.requireNonNull(task, "task")));
    }

    @Override
    public void begin(final String task, final Run run) {
        runs.put(Objects.requireNonNull(task, "task"), Objects.requireNonNull(run, "run"));
    }

    @Override
    public void end(final String task) {
        runs.remove(Objects.requireNonNull(task, "task"));
    }
}
