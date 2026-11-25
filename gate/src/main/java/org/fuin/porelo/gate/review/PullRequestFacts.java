package org.fuin.porelo.gate.review;

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
 * @param mergeCommit The merge commit when merged, else null.
 * @param mergedHead The branch the merge commit went onto when merged, else null.
 */
public record PullRequestFacts(
        PullRequestRef ref,
        String repository,
        String head,
        String base,
        String mergeBase,
        PullRequestState state,
        @Nullable String mergeCommit,
        @Nullable String mergedHead) {

    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";

    /**
     * Refuses a repository not on https, a commit not written in full, and a merge commit or branch
     * on a pull request that is not merged, or missing on one that is.
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
        if (mergeCommit != null && !mergeCommit.matches(COMMIT)) {
            throw new IllegalArgumentException("the merge commit is not a full commit id");
        }
        if (mergedHead != null && mergedHead.isBlank()) {
            throw new IllegalArgumentException("the merged branch is blank");
        }
    }
}
