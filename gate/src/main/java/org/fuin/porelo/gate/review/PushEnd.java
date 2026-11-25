package org.fuin.porelo.gate.review;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * How a task's last push stopped waiting at the gate: rejected, approved or found landed.
 *
 * @param name The push's name at the gate.
 * @param commit The full commit that waited.
 * @param end How it ended.
 * @param openThreads How many open review threads a reject carried to the task; 0 for the other
 *     ends.
 * @param mergedCommit The commit an approval forwarded or a landing found, or null for a reject.
 * @param branch The upstream branch that commit went onto, or null with mergedCommit.
 * @param at When the end was recorded, in whole seconds.
 */
public record PushEnd(
        String name,
        String commit,
        End end,
        int openThreads,
        @Nullable String mergedCommit,
        @Nullable String branch,
        Instant at) {

    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";

    /** How a push stopped waiting. */
    public enum End {
        /** A person rejected it; the task was told, with the open threads counted. */
        REJECTED,
        /** A person approved it, and the gate forwarded it. */
        APPROVED,
        /** It was found merged on the upstream, through no approval here. */
        LANDED
    }

    /**
     * Refuses a blank name, a commit not written in full, a count below zero, a merged commit on a
     * reject or missing on the others, and cuts the time to whole seconds.
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
        if ((end == End.REJECTED) != (mergedCommit == null)) {
            throw new IllegalArgumentException("a merged commit belongs to an approval or landing");
        }
        if (mergedCommit != null && !mergedCommit.matches(COMMIT)) {
            throw new IllegalArgumentException("mergedCommit is not a full commit id");
        }
        if ((mergedCommit == null) != (branch == null) || (branch != null && branch.isBlank())) {
            throw new IllegalArgumentException("a branch goes with a merged commit");
        }
        at = at.truncatedTo(ChronoUnit.SECONDS);
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
