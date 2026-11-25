package org.fuin.porelo.gate.review;

/**
 * A forge adapter's review calls for one pull request: its facts, its conversation as profile 2
 * pages, and posting one submission. Porelo parses nothing here: the pages go to {@link
 * ProfileReading}.
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
}
