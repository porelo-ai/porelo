package org.fuin.porelo.app;

import java.time.Clock;
import org.fuin.porelo.gate.GitHttpServer;
import org.jspecify.annotations.Nullable;

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
     * Returns a judge for a task that has no certificate, so nothing can judge its signatures: it
     * accepts every push the issue judge accepts, as the gate always did for such a task, and
     * records each one as {@code accepted, unsigned} - never a bare {@code accepted}, which reads
     * as judged; a push the issue judge refuses is recorded as refused.
     *
     * @param byIssue The judge of the task's issue trailers, or {@code null} for a task given no
     *     work.
     * @param project The project.
     * @param task The task's container.
     * @param recorder Where each arrival is recorded.
     * @param clock Says when a push arrives.
     * @return A judge that accepts.
     */
    static GitHttpServer.Arrivals unjudged(
            GitHttpServer.@Nullable Arrivals byIssue,
            String project,
            String task,
            Recorder recorder,
            Clock clock) {
        return (old, commit) -> {
            final String arrived = clock.instant().toString();
            final String verdict = byIssue == null ? null : byIssue.judge(old, commit);
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
                            + (verdict == null ? ACCEPTED_UNSIGNED : "refused, " + verdict));
            return verdict;
        };
    }

    /**
     * Returns a judge that asks the issue judge first and the other only when it accepts, so both
     * must hold.
     *
     * @param byIssue The judge of the task's issue trailers, or {@code null} for none.
     * @param then The judge asked after it.
     * @return The two in order.
     */
    static GitHttpServer.Arrivals inOrder(
            GitHttpServer.@Nullable Arrivals byIssue, GitHttpServer.Arrivals then) {
        if (byIssue == null) {
            return then;
        }
        return (old, commit) -> {
            final String first = byIssue.judge(old, commit);
            return first != null ? first : then.judge(old, commit);
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
