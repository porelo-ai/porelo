package org.fuin.porelo.gate.review;

/**
 * Judges a comment read from a place before a run sees it, by the place's reading setting.
 *
 * <p>A stand-in answers a fixed judgement.
 */
public interface CommentGate {

    /**
     * Judges one comment of a conversation.
     *
     * @param place Where it was read.
     * @param run The run that is to see it.
     * @param item The comment with its producer.
     * @param root Its thread's opening comment, whose place a reply stands at; for a comment, the
     *     comment itself.
     * @return The rule it is withheld by, held for the owner account so the run sees only that one
     *     is held; empty when it passes.
     */
    java.util.Optional<String> judge(ReviewPlace place, Run run, Kept item, Kept root);
}
