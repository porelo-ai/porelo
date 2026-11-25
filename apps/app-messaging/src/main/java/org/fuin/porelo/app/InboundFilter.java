package org.fuin.porelo.app;

import java.io.IOException;
import java.util.Optional;

/**
 * The message filter's inbound check of one message the host builds, judged as an external peer's
 * message is, in blocking mode. Unlike {@link InboundCheck}, it needs no signed message in a
 * mailbox and answers the filter's rule id rather than a sentence.
 *
 * <p>A stand-in answers a fixed rule, none, or that it cannot run.
 */
@FunctionalInterface
public interface InboundFilter {

    /** The rule a refusal is held by when the filter named none that can be shown. */
    String UNNAMED_RULE = "filter: refused";

    /**
     * Checks the data part of an inbound {@code comment} message from an external peer.
     *
     * @param peer The peer it is from, such as a forge's host.
     * @param data The message's data part, as the task would receive it.
     * @return The filter's rule id that refused it, {@link #UNNAMED_RULE} when it named none, or
     *     empty when it may be delivered.
     * @throws IOException When the filter cannot run, is not installed or cannot read it; the
     *     caller fails closed.
     */
    Optional<String> refuse(String peer, byte[] data) throws IOException;
}
