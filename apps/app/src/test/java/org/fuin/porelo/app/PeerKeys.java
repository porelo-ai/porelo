package org.fuin.porelo.app;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.vault.SigningKey;

/**
 * Peers a test believes by their plain keys, for the steps whose test is not about whom a
 * certificate vouches for: those are {@link MessageSigners}' and tested there.
 */
final class PeerKeys {

    private PeerKeys() {}

    /**
     * Believes one key, as one peer.
     *
     * @param name The peer.
     * @param key Its key.
     * @return The signers.
     */
    static MessageDelivery.Signers of(final String name, final SigningKey key) {
        return of(Map.of(name, key));
    }

    /**
     * Believes each key, as its peer.
     *
     * @param peers The peers by name.
     * @return The signers.
     */
    static MessageDelivery.Signers of(final Map<String, SigningKey> peers) {
        final Map<String, SigningKey> known = new LinkedHashMap<>(peers);
        return (key, arrived) -> {
            for (final Map.Entry<String, SigningKey> peer : known.entrySet()) {
                if (Arrays.equals(peer.getValue().keyBlob(), key)) {
                    return new MessageSigners.Traced(peer.getKey(), "");
                }
            }
            return new MessageSigners.Traced("", "it is signed by a key no peer is allowed to use");
        };
    }

    /** Believes nobody. */
    static final MessageDelivery.Signers NONE = of(Map.of());
}
