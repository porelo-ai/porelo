package org.fuin.porelo.app;

import java.time.Clock;
import org.fuin.porelo.gate.GitHttpServer;

/**
 * Records each push's arrival at a task's gate, with its verdict: a task's commits are judged by
 * the time they arrive, so that time and what was decided are kept where a person can read them
 * back.
 */
final class ArrivalRecords {

    /** Where a record goes. */
    @FunctionalInterface
    interface Recorder {

        /**
         * Records one event.
         *
         * @param kind The event's kind.
         * @param project The project.
         * @param task The task's container.
         * @param detail What happened.
         */
        void record(String kind, String project, String task, String detail);
    }

    private ArrivalRecords() {}

    /**
     * Wraps a judge so that every verdict is recorded.
     *
     * @param judge What decides.
     * @param project The project.
     * @param task The task's container.
     * @param recorder Where each arrival is recorded.
     * @param clock Says when a push arrives.
     * @return A judge answering as {@code judge} does.
     */
    static GitHttpServer.Arrivals recording(
            GitHttpServer.Arrivals judge,
            String project,
            String task,
            Recorder recorder,
            Clock clock) {
        return (old, commit) -> {
            final String arrived = clock.instant().toString();
            final String verdict = judge.judge(old, commit);
            recorder.record(
                    EventLog.ARRIVED,
                    project,
                    task,
                    old
                            + ".."
                            + commit
                            + " arrived "
                            + arrived
                            + ": "
                            + (verdict == null ? "accepted" : "refused, " + verdict));
            return verdict;
        };
    }
}
