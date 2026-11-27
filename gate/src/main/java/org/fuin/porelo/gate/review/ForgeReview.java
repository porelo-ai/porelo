package org.fuin.porelo.gate.review;

/**
 * A forge adapter's review calls for one pull request: its facts, its conversation as profile 2
 * pages, posting one submission and resolving one thread. Porelo parses nothing here: the pages go
 * to {@link ProfileReading}.
 *
 * <p>A stand-in answers fixed facts and pages, and records each post.
 */
public interface ForgeReview {

    /**
     * Reads the pull request's facts.
     *
     * @param ref The pull request.
     * @param credential The read credential, lent for this call.
     * @return Its facts now.
     * @throws PlaceUnreadable As the forge answered: no such pull request, unreachable.
     */
    PullRequestFacts facts(PullRequestRef ref, ForgeCredential credential) throws PlaceUnreadable;

    /**
     * Reads the pull request's whole conversation.
     *
     * @param ref The pull request.
     * @param credential The read credential, lent for this call.
     * @return Every page, unread, and why it is not all of it if it is not.
     * @throws PlaceUnreadable When no page could be read.
     */
    ForgePages conversation(PullRequestRef ref, ForgeCredential credential) throws PlaceUnreadable;

    /**
     * Posts one profile 2 submission of one result, once.
     *
     * @param ref The pull request.
     * @param document The submission.
     * @param credential The write credential of the account it is written as, lent for this call.
     * @return What became of it.
     */
    WriteOutcome post(PullRequestRef ref, byte[] document, ForgeCredential credential);

    /**
     * Marks one thread of the pull request resolved, once, as the account the credential stands
     * for. A forge whose adapter has no such call answers as one that cannot resolve a thread.
     *
     * @param ref The pull request.
     * @param thread The forge's thread id, as its conversation gave it.
     * @param credential The write credential of the account it is resolved as, lent for this call.
     * @return What became of it: delivered with the thread, {@code Refused} with {@value
     *     #RESOLVE_UNSUPPORTED} for a forge that cannot resolve a thread or {@code NoSuchThread},
     *     and of unknown outcome when it was sent and nothing says whether it was resolved.
     */
    default WriteOutcome resolve(
            final PullRequestRef ref, final String thread, final ForgeCredential credential) {
        return new WriteOutcome.Refused(RESOLVE_UNSUPPORTED);
    }

    /** The reason a forge that cannot resolve a thread gives; the thread stays open. */
    String RESOLVE_UNSUPPORTED = "ResolveUnsupported";
}
