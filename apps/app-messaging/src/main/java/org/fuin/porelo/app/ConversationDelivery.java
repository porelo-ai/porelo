package org.fuin.porelo.app;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Hands a run's task the whole conversation at its place, as untrusted review input, one note per
 * profile 2 page; a conversation without comments is one page without results.
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
     * @param held How many comments the gate held for the owner account's decision.
     * @param unread How many comments the input path could not read; they are held nowhere.
     * @param reason Why the conversation is not whole, as the contract's word ({@code RateLimited},
     *     {@code PagePartial} or {@code PageRefused}); null when it is whole.
     */
    void deliver(
            String task,
            int run,
            String place,
            List<byte[]> pages,
            int held,
            int unread,
            @Nullable String reason);
}
