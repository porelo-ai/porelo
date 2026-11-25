package org.fuin.porelo.gate.review;

/**
 * What one kind of place differs in for a run: pinning its state, reading its whole conversation,
 * writing a released item, and how its comments are read. A pull request is one implementation, a
 * waiting push at the gate another; the run's flow names neither.
 *
 * <p>A stand-in answers a fixed push, conversation and outcome, and records what it was asked.
 */
public interface ReviewPlace {

    /**
     * Returns the place's name, as the hold and the owner account's list show it: a pull request's
     * https address, or a gate push's {@code <repository>/<name>}.
     *
     * @return The name.
     */
    String name();

    /**
     * Pins the state a run of the task reads here.
     *
     * @param task The task's recorded container id.
     * @return The pinned push, its head the commit the run reviews, and a pull request's facts.
     * @throws PlaceUnreadable When the place cannot be read or pinned; nothing was made.
     */
    Pinned pin(String task) throws PlaceUnreadable;

    /**
     * Reads the place's whole conversation at the pinned state, as the run is to see it.
     *
     * @param pinned What {@link #pin} answered.
     * @param credential The read credential's vault entry, lent per call by the caller.
     * @return Every item checked for format and size, the ones refused withheld by rule, and how
     *     completely it was read.
     * @throws PlaceUnreadable When nothing of it could be read.
     */
    Conversation conversation(Pinned pinned, ForgeCredential credential) throws PlaceUnreadable;

    /**
     * Writes the conversation a run is to see as the pages handed to its task.
     *
     * @param pinned What {@link #pin} answered.
     * @param conversation The items that passed, in order.
     * @return The pages, each within the profile's bounds.
     */
    java.util.List<byte[]> pages(Pinned pinned, Conversation conversation);

    /**
     * Writes one released item to the place, once; a write whose outcome is unknown is never sent
     * again.
     *
     * @param held The released item; it is written against its own pinned commit, never the place's
     *     head now.
     * @param credential The write credential of the account it is written as, as {@link
     *     ReviewRole#postingEntry} named it, lent for this call.
     * @return What became of it.
     */
    WriteOutcome write(Held held, ForgeCredential credential);

    /**
     * Returns how this place's comments are read before a run sees them.
     *
     * @return Extended or trusted.
     */
    CommentReading reading();
}
