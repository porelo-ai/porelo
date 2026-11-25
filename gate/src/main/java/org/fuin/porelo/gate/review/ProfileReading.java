package org.fuin.porelo.gate.review;

/**
 * The one input path for profile documents: every document from outside becomes records here, or is
 * refused whole.
 *
 * <p>A stand-in answers fixed items or a fixed refusal.
 */
public interface ProfileReading {

    /**
     * Reads one document.
     *
     * @param document The document's bytes, as received.
     * @param channel What it came through: an adapter admits forge accounts' pages, a task only a
     *     submission.
     * @return Its items in order, each stamped from the channel, and the completeness it states.
     * @throws ProfileRefused When it breaks the profile or its channel's rule; nothing is taken. A
     *     forge's comment on the whole pull request or a whole file never arrives: the adapter
     *     answers {@code NotInProfile} for it.
     */
    ProfileRead read(byte[] document, ProfileChannel channel) throws ProfileRefused;
}
