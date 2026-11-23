package org.fuin.porelo.daemon;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Readings this daemon keeps for the later pages of a reply: each a little while after it was read,
 * the newest few, and no more bytes together than a bound, so a client asking again and again holds
 * no more. A reading no longer kept is the caller's to answer: by name, or by reading again.
 *
 * @param <K> What names a reading.
 * @param <V> The reading.
 */
final class KeptReadings<K, V> {

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private record Kept<V>(V value, Instant at, long bytes) {}

    private final InstantSource clock;
    private final Duration kept;
    private final int most;
    private final long mostBytes;
    private final Map<K, Kept<V>> readings = new LinkedHashMap<>();

    /**
     * Creates the kept readings of one kind.
     *
     * @param clock When a reading is kept and when it is asked for.
     * @param kept How long a reading is kept after it was read.
     * @param most How many readings are kept at most; a new one takes the oldest one's place.
     * @param mostBytes How many bytes all kept readings hold together at most.
     */
    KeptReadings(
            final InstantSource clock, final Duration kept, final int most, final long mostBytes) {
        this.clock = clock;
        this.kept = kept;
        this.most = most;
        this.mostBytes = mostBytes;
    }

    /**
     * Returns a new reading's name, which a client cannot guess: 32 lowercase hexadecimal digits.
     *
     * @return The name.
     */
    static String name() {
        final byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        return java.util.HexFormat.of().formatHex(random);
    }

    /**
     * Keeps a reading, in place of one kept under the same name. One larger than all may hold
     * together is not kept.
     *
     * @param key Its name.
     * @param value The reading.
     * @param bytes What it holds.
     */
    synchronized void keep(final K key, final V value, final long bytes) {
        expire();
        readings.remove(key);
        if (bytes > mostBytes) {
            return;
        }
        while (readings.size() >= most || held() + bytes > mostBytes) {
            final Iterator<K> oldest = readings.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        readings.put(key, new Kept<>(value, clock.instant(), bytes));
    }

    /**
     * Returns a kept reading.
     *
     * @param key Its name.
     * @return It, or {@code null} when it is not kept.
     */
    synchronized @Nullable V get(final K key) {
        expire();
        final Kept<V> found = readings.get(key);
        return found == null ? null : found.value();
    }

    /**
     * Returns how many readings are kept now.
     *
     * @return The count.
     */
    synchronized int kept() {
        expire();
        return readings.size();
    }

    private long held() {
        long held = 0;
        for (final Kept<V> each : readings.values()) {
            held += each.bytes();
        }
        return held;
    }

    private void expire() {
        final Instant now = clock.instant();
        readings.values().removeIf(each -> !now.isBefore(each.at().plus(kept)));
    }
}
