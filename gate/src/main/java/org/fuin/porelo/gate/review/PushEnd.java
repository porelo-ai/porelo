package org.fuin.porelo.gate.review;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Where a task's last push stands at the gate: waiting since it was taken, or how it stopped
 * waiting (rejected, approved or found landed).
 *
 * @param name The push's name at the gate.
 * @param commit The full commit that waited.
 * @param end How it ended.
 * @param openThreads How many open review threads a reject carried to the task; 0 for the other
 *     ends.
 * @param mergedCommit The commit an approval forwarded or a landing found, or null for a reject.
 * @param branch The upstream branch that commit went onto, or null with mergedCommit.
 * @param at When the end was recorded, or for a waiting push when it was taken, in whole seconds.
 * @param waitingSince When the push was taken at the gate, in whole seconds: the same as {@code at}
 *     while it waits, carried along by its end; null for an end of a push taken before waiting
 *     times were kept.
 * @param rejectedBy The task that rejected the push, by its container name, for a reject a task
 *     holding {@code reject} made; null for the owner account's reject and every other end.
 */
public record PushEnd(
        String name,
        String commit,
        End end,
        int openThreads,
        @Nullable String mergedCommit,
        @Nullable String branch,
        Instant at,
        @Nullable Instant waitingSince,
        @Nullable String rejectedBy) {

    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";

    /** What a task's container name may be, as the gate names a rejecting task. */
    private static final String CONTAINER = "[A-Za-z0-9][A-Za-z0-9_.-]{0,254}";

    /** How a push stopped waiting. */
    public enum End {
        /** It waits; nothing decided it yet. */
        WAITING,
        /** A person rejected it; the task was told, with the open threads counted. */
        REJECTED,
        /** A person approved it, and the gate forwarded it. */
        APPROVED,
        /** It was found merged on the upstream, through no approval here. */
        LANDED
    }

    /**
     * Constructor for an end without a waiting time.
     *
     * @param name The push's name at the gate.
     * @param commit The full commit that waited.
     * @param end How it ended.
     * @param openThreads The open threads a reject carried.
     * @param mergedCommit The commit that went, or null.
     * @param branch The branch it went onto, or null.
     * @param at When the end was recorded.
     */
    public PushEnd(
            final String name,
            final String commit,
            final End end,
            final int openThreads,
            final @Nullable String mergedCommit,
            final @Nullable String branch,
            final Instant at) {
        this(name, commit, end, openThreads, mergedCommit, branch, at, null, null);
    }

    /**
     * Constructor for an end no task rejected.
     *
     * @param name The push's name at the gate.
     * @param commit The full commit that waited.
     * @param end How it ended.
     * @param openThreads The open threads a reject carried.
     * @param mergedCommit The commit that went, or null.
     * @param branch The branch it went onto, or null.
     * @param at When the end was recorded.
     * @param waitingSince When the push was taken, or null.
     */
    public PushEnd(
            final String name,
            final String commit,
            final End end,
            final int openThreads,
            final @Nullable String mergedCommit,
            final @Nullable String branch,
            final Instant at,
            final @Nullable Instant waitingSince) {
        this(name, commit, end, openThreads, mergedCommit, branch, at, waitingSince, null);
    }

    /**
     * Refuses a blank name, a commit not written in full, a count below zero, a merged commit on a
     * reject or a waiting push or missing on the others, a waiting push whose waiting time is not
     * its own, a rejecting task on any end but a reject or one that names no container, and cuts
     * the times to whole seconds.
     */
    public PushEnd {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(at, "at");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name is blank");
        }
        if (!commit.matches(COMMIT)) {
            throw new IllegalArgumentException("commit is not a full commit id");
        }
        if (openThreads < 0 || (end != End.REJECTED && openThreads != 0)) {
            throw new IllegalArgumentException("only a reject carries open threads");
        }
        final boolean went = end == End.APPROVED || end == End.LANDED;
        if (went == (mergedCommit == null)) {
            throw new IllegalArgumentException("a merged commit belongs to an approval or landing");
        }
        if (mergedCommit != null && !mergedCommit.matches(COMMIT)) {
            throw new IllegalArgumentException("mergedCommit is not a full commit id");
        }
        if ((mergedCommit == null) != (branch == null) || (branch != null && branch.isBlank())) {
            throw new IllegalArgumentException("a branch goes with a merged commit");
        }
        at = at.truncatedTo(ChronoUnit.SECONDS);
        if (waitingSince != null) {
            waitingSince = waitingSince.truncatedTo(ChronoUnit.SECONDS);
        }
        if (end == End.WAITING && !at.equals(waitingSince)) {
            throw new IllegalArgumentException("a waiting push waits since it was taken");
        }
        if (rejectedBy != null && (end != End.REJECTED || !rejectedBy.matches(CONTAINER))) {
            throw new IllegalArgumentException("only a reject names the task that made it");
        }
    }

    /**
     * Returns a push that waits, taken at the gate.
     *
     * @param name The push's name.
     * @param commit The commit taken.
     * @param at When it was taken.
     * @return The entry.
     */
    public static PushEnd waiting(final String name, final String commit, final Instant at) {
        return new PushEnd(name, commit, End.WAITING, 0, null, null, at, at);
    }

    /**
     * Returns this end with the time its push was taken at the gate.
     *
     * @param since When it was taken, or null when that is not known.
     * @return A copy.
     */
    public PushEnd waitedSince(final @Nullable Instant since) {
        return new PushEnd(
                name, commit, end, openThreads, mergedCommit, branch, at, since, rejectedBy);
    }

    /**
     * Returns this reject as one a task holding {@code reject} made.
     *
     * @param task The rejecting task's container name.
     * @return A copy.
     */
    public PushEnd by(final String task) {
        Objects.requireNonNull(task, "task");
        return new PushEnd(
                name, commit, end, openThreads, mergedCommit, branch, at, waitingSince, task);
    }

    /**
     * Returns a reject's end.
     *
     * @param name The push's name.
     * @param commit The commit that waited.
     * @param openThreads The open threads the reject carried.
     * @param at When.
     * @return The end.
     */
    public static PushEnd rejected(
            final String name, final String commit, final int openThreads, final Instant at) {
        return new PushEnd(name, commit, End.REJECTED, openThreads, null, null, at);
    }

    /**
     * Returns an approval's end.
     *
     * @param name The push's name.
     * @param commit The commit that waited and was reviewed.
     * @param forwarded The commit forwarded: the same, or a signed merge of it.
     * @param branch The branch it was forwarded onto.
     * @param at When.
     * @return The end.
     */
    public static PushEnd approved(
            final String name,
            final String commit,
            final String forwarded,
            final String branch,
            final Instant at) {
        return new PushEnd(name, commit, End.APPROVED, 0, forwarded, branch, at);
    }

    /**
     * Returns a landing's end.
     *
     * @param name The push's name.
     * @param commit The commit that waited and was found on the branch.
     * @param branch The branch it was found on.
     * @param at When.
     * @return The end.
     */
    public static PushEnd landed(
            final String name, final String commit, final String branch, final Instant at) {
        return new PushEnd(name, commit, End.LANDED, 0, commit, branch, at);
    }
}
