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

    /** The verdict recorded for a push that nothing judged, as its task has no certificate. */
    static final String ACCEPTED_UNSIGNED = "accepted, unsigned";

    private ArrivalRecords() {}

    /**
     * Returns a judge for a task that has no certificate, so nothing can judge its commits: it
     * accepts every push, as the gate always did for such a task, and records each one as {@code
     * accepted, unsigned} - never a bare {@code accepted}, which reads as judged.
     *
     * @param project The project.
     * @param task The task's container.
     * @param recorder Where each arrival is recorded.
     * @param clock Says when a push arrives.
     * @return A judge that accepts.
     */
    static GitHttpServer.Arrivals unjudged(
            String project, String task, Recorder recorder, Clock clock) {
        return (old, commit) -> {
            recorder.record(
                    EventLog.ARRIVED,
                    project,
                    task,
                    old + ".." + commit + " arrived " + clock.instant() + ": " + ACCEPTED_UNSIGNED);
            return null;
        };
    }

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
