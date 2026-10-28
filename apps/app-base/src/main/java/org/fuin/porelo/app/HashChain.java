package org.fuin.porelo.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The hash a line of a chained record carries: SHA-256 over the previous line's hash and the line's
 * own fields, each length-prefixed, so a line edited or removed afterwards breaks every hash after
 * it. One way for every chained record Porelo keeps.
 */
public final class HashChain {

    private HashChain() {}

    /**
     * Returns the hash of a line.
     *
     * @param previous The hash of the line before it, or "" for the first.
     * @param fields The line's fields, in their order.
     * @return Lower-case hex.
     */
    public static String link(final String previous, final List<String> fields) {
        // Length-prefixed rather than separated: a separator can appear inside a field, and two
        // different sets of fields would then hash alike.
        final StringBuilder material = new StringBuilder();
        material.append(previous.length()).append(':').append(previous);
        for (final String field : fields) {
            material.append(field.length()).append(':').append(field);
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
