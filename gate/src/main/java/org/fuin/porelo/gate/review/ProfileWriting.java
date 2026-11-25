package org.fuin.porelo.gate.review;

import java.util.List;

/**
 * The one writer of profile 2: a conversation as pages for a run, an item for the message filter's
 * check, and a submission for a forge.
 *
 * <p>A stand-in answers fixed bytes.
 */
public interface ProfileWriting {

    /**
     * Writes a pull request's conversation as profile 2 pages.
     *
     * @param facts The pull request as the run pinned it.
     * @param conversation What the run is to see.
     * @return The pages, each within the profile's bounds.
     */
    List<byte[]> pages(PullRequestFacts facts, Conversation conversation);

    /**
     * Writes one item of a conversation as a one-result profile 2 document.
     *
     * @param facts The pull request as the run pinned it.
     * @param item The item with its producer.
     * @param root Its thread's opening comment, whose place a reply stands at; for a comment, the
     *     comment itself.
     * @return The document.
     */
    byte[] one(PullRequestFacts facts, Kept item, Kept root);

    /**
     * Writes one item as a one-result submission, without a producer, for a forge to post.
     *
     * @param facts The pull request as the run pinned it, whose head is the commit the item was
     *     held for.
     * @param item The comment or reply.
     * @param place Its thread's place as the producer read it, which a reply is written at; for a
     *     comment, its own anchor.
     * @return The submission.
     */
    byte[] submission(PullRequestFacts facts, ReviewItem item, LineAnchor place);
}
