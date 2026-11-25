package org.fuin.porelo.gate.review;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A pull request as its forge answered it at one moment: what a run pins and reviews.
 *
 * @param ref The pull request.
 * @param repository The forge's https address of the repository its head is read from.
 * @param head The commit reviewed: the pull request's head, for a merged one the head that merged.
 * @param base The base branch's commit it is compared with.
 * @param mergeBase The merge base of head and base, against which its changes are shown.
 * @param state Open, merged or closed.
 * @param approved Whether the forge shows it approved at its head, as the forge says; never a
 *     verdict of Porelo's.
 * @param mergeCommit The merge commit when merged, else null.
 * @param mergedHead The branch the merge commit went onto when merged, else null.
 * @param decidedAt When the forge merged or closed it, exactly when merged or closed, else null.
 * @param viewer The forge account the token the facts were read with stands for, or null when the
 *     forge does not say.
 */
public record PullRequestFacts(
        PullRequestRef ref,
        String repository,
        String head,
        String base,
        String mergeBase,
        PullRequestState state,
        boolean approved,
        @Nullable String mergeCommit,
        @Nullable String mergedHead,
        @Nullable Instant decidedAt,
        @Nullable ForgeAccount viewer) {

    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";

    /**
     * Refuses a repository not on https, a commit not written in full, and a merge commit or branch
     * on a pull request that is not merged, or missing on one that is, and a decision time on an
     * open pull request, or missing on a merged or closed one.
     */
    public PullRequestFacts {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(mergeBase, "mergeBase");
        Objects.requireNonNull(state, "state");
        if (!repository.startsWith("https://")) {
            throw new IllegalArgumentException("the repository's address is https");
        }
        for (final String commit : new String[] {head, base, mergeBase}) {
            if (!commit.matches(COMMIT)) {
                throw new IllegalArgumentException("a commit is not a full commit id");
            }
        }
        final boolean merged = state == PullRequestState.MERGED;
        if (merged != (mergeCommit != null) || merged != (mergedHead != null)) {
            throw new IllegalArgumentException(
                    "a merge commit and its branch are given exactly when merged");
        }
        if ((state == PullRequestState.OPEN) != (decidedAt == null)) {
            throw new IllegalArgumentException(
                    "a decision time is given exactly when merged or closed");
        }
        if (mergeCommit != null && !mergeCommit.matches(COMMIT)) {
            throw new IllegalArgumentException("the merge commit is not a full commit id");
        }
        if (mergedHead != null && mergedHead.isBlank()) {
            throw new IllegalArgumentException("the merged branch is blank");
        }
    }
}
