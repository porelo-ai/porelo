package org.fuin.porelo.app;

import org.fuin.porelo.gate.review.ProfileRefused;

/**
 * Takes a run's submission from its task's mailbox: the comments and replies of one {@code comment}
 * message to the review, as its data part's bytes. The messages' side parses none of it.
 *
 * <p>A stand-in records what it was given, or answers a fixed refusal.
 */
public interface ReviewerComments {

    /**
     * Takes one submission.
     *
     * @param task The task whose mailbox the message came through.
     * @param document The message's data part, a profile 2 submission, as bytes.
     * @param unsigned Whether no certified key signed the message, so each item is held marked so.
     * @throws ProfileRefused When it is refused whole, by rule; nothing of it is held.
     */
    void take(String task, byte[] document, boolean unsigned) throws ProfileRefused;
}
