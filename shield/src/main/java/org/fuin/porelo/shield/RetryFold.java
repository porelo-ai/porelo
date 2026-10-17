package org.fuin.porelo.shield;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tells a packet a client sends again for the same connection attempt from a new attempt.
 *
 * <p><strong>One attempt, one line.</strong> A client that is not answered sends its SYN again
 * after a second or so, from the same source port; each copy is dropped and logged. Recorded as
 * they came, one attempt read as two drops, and an analysis of a task counted twice the connections
 * it made (found on 2026-10-13). A new attempt takes a new source port, so the protocol,
 * destination, port and source port name one attempt, and a repeat of them within {@link #WINDOW}
 * is the same attempt.
 *
 * <p>Not thread-safe: the reader calls it from its one loop.
 */
public final class RetryFold {

    /**
     * How long a repeat counts as the same attempt: past every retry a client makes before it gives
     * up on one.
     */
    public static final Duration WINDOW = Duration.ofSeconds(10);

    /**
     * At most this many attempts are remembered; a flood of new ports only forgets the oldest,
     * never grows.
     */
    private static final int REMEMBERED = 4096;

    private final Map<String, Instant> lastSeen =
            new LinkedHashMap<>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Instant> eldest) {
                    return size() > REMEMBERED;
                }
            };

    /**
     * Says whether an event is a new attempt, and remembers it.
     *
     * @param event What was stopped.
     * @return {@code true} for the first packet of an attempt, {@code false} for a repeat within
     *     {@link #WINDOW}.
     */
    public boolean isNew(final BlockedConnection event) {
        if (event.sourcePort() == 0) {
            // Without ports there is nothing that tells a retry from another attempt, so every
            // packet counts.
            return true;
        }
        final String attempt =
                event.protocol()
                        + " "
                        + event.destination()
                        + " "
                        + event.port()
                        + " "
                        + event.sourcePort();
        final Instant before = lastSeen.put(attempt, event.at());
        return before == null || Duration.between(before, event.at()).compareTo(WINDOW) > 0;
    }
}
