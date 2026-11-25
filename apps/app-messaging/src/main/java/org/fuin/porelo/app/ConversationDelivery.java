package org.fuin.porelo.app;

import java.util.List;

/**
 * Hands a run's task the whole conversation at its place, as untrusted review input in one note.
 *
 * <p>A stand-in records what it was given.
 */
public interface ConversationDelivery {

    /**
     * Delivers the conversation.
     *
     * @param task The task's name.
     * @param run The run's number at its place.
     * @param place The place's name.
     * @param pages The profile 2 pages, in order.
     * @param withheld How many comments are held for the owner account.
     */
    void deliver(String task, int run, String place, List<byte[]> pages, int withheld);
}
