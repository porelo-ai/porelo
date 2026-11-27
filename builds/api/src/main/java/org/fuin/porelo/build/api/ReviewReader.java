package org.fuin.porelo.build.api;

import org.jspecify.annotations.Nullable;

/**
 * What a forge adapter implements beside {@link BuildReader} to answer Porelo about one pull
 * request's review: its facts, the open one from a branch, its conversation and one post.
 *
 * <p>A reader that also implements this is served by {@link BuildMain#run} on the same socket over
 * {@link ReviewProtocol#INTERFACE}; one that does not serves the build contract alone. It reaches
 * no verdict of its own, and nothing of its forge's format crosses: a conversation and a submission
 * are review profile 2 documents. Each method throws {@link ForgeReviewRefused} when the forge
 * would not answer; anything else it throws is answered as the interface's {@code Failed}, so its
 * message never holds the token.
 */
public interface ReviewReader {

    /**
     * Returns a pull request's facts.
     *
     * @param access The forge's API and the token lent for this call.
     * @param url The pull request's https address.
     * @return Its facts now.
     * @throws ForgeReviewRefused When the forge would not answer.
     */
    ReviewFacts pullRequest(ForgeAccess access, String url);

    /**
     * Returns the open pull request from a branch, opening none.
     *
     * @param access The forge's API and the token lent for this call.
     * @param upstream The repository as the project file names it.
     * @param branch The branch's name, not a ref.
     * @return Its https address; {@code null} when none is open from it.
     * @throws ForgeReviewRefused When the forge would not answer.
     */
    @Nullable String pullRequestOf(ForgeAccess access, String upstream, String branch);

    /**
     * Returns one page of a pull request's whole conversation.
     *
     * @param access The forge's API and the token lent for this call.
     * @param url The pull request's https address.
     * @param cursor Where the page starts, as the previous page said; {@code null} for the first.
     * @return One profile 2 document of kind {@code page} stating its completeness, at most {@link
     *     ReviewProtocol#MOST_PAGE_RESULTS} results, and where the next page starts.
     * @throws ForgeReviewRefused When the forge would not answer, or holds what profile 2 cannot
     *     carry.
     */
    ConversationPage comments(ForgeAccess access, String url, @Nullable String cursor);

    /**
     * Writes one released result, once.
     *
     * @param access The forge's API and the write token lent for this call.
     * @param url The pull request's https address.
     * @param document One profile 2 submission of exactly one result.
     * @return The thread answered, or the new thread root's id: the forge's native id in decimal.
     * @throws ForgeReviewRefused When the forge would not write it, or {@link
     *     ForgeReviewRefused.Reason#WRITE_OUTCOME_UNKNOWN} when it was sent and no answer says
     *     whether it was written.
     */
    String post(ForgeAccess access, String url, String document);

    /**
     * Marks one thread of a pull request resolved, as the account the token stands for. A thread
     * the forge already shows resolved is done, so a second call for it changes nothing.
     *
     * @param access The forge's API and the write token lent for this call.
     * @param url The pull request's https address.
     * @param thread The thread's id, as {@link #comments} and {@link #post} give it.
     * @throws ForgeReviewRefused When the forge would not resolve it: {@link
     *     ForgeReviewRefused.Reason#RESOLVE_UNSUPPORTED} for a forge that cannot resolve a thread,
     *     {@link ForgeReviewRefused.Reason#NO_SUCH_THREAD} for a thread the pull request does not
     *     hold, or {@link ForgeReviewRefused.Reason#WRITE_OUTCOME_UNKNOWN} when it was sent and no
     *     answer says whether it was written.
     */
    void resolve(ForgeAccess access, String url, String thread);
}
