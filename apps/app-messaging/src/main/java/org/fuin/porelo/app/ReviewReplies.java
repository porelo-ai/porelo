package org.fuin.porelo.app;

import java.util.Optional;

/**
 * Takes a task's reply in a review thread, after the message filter passed it.
 *
 * <p>The messaging area routes a message addressed to {@code review} here and never knows the
 * review's parts; the assembly implements it over the review's intake. A stand-in records what it
 * was given and answers a fixed refusal or none.
 */
public interface ReviewReplies {

    /**
     * Takes one reply.
     *
     * @param task The task that sent it, as this machine recorded it for the mailbox the reply came
     *     through, never from the message's own words; the assembly binds the reply to the push
     *     recorded for this identity's project and recorded container id, so a later task of the
     *     same name, or a task of another project, reaches no thread of it.
     * @param thread The thread it answers, from the message's metadata.
     * @param text The reply's text.
     * @return The refusal's words (its error name and rule, never the reply's text), which bounce
     *     to the task, or empty when the reply was kept.
     */
    Optional<String> take(TaskIdentity task, String thread, String text);
}
