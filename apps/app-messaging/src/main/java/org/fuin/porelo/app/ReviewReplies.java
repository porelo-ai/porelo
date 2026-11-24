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
     * @param taskId The name of the task that sent it, from the mailbox it came through, never from
     *     the message's own words; the assembly takes the push from that task's current container,
     *     so a task started later under the same name reaches no older thread.
     * @param thread The thread it answers, from the message's metadata.
     * @param text The reply's text.
     * @return The refusal's words (its error name and rule, never the reply's text), which bounce
     *     to the task, or empty when the reply was kept.
     */
    Optional<String> take(String taskId, String thread, String text);
}
