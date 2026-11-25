package org.fuin.porelo.gate.review;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A forge instance this machine knows: its host, the adapter that reads it and how its comments are
 * read; or an entry that could not be read, which is read as extended and names why.
 *
 * @param host The host, such as {@code github.com}.
 * @param adapter The installed forge adapter's name, such as {@code github}; null when the entry
 *     could not be read.
 * @param reading How its comments are read: trusted only when the entry says so explicitly.
 * @param unreadable Why the entry could not be read; null when it was.
 */
public record ForgeInstance(
        String host,
        @Nullable String adapter,
        CommentReading reading,
        @Nullable String unreadable) {

    /** The prefix of a forge's read credential's vault entry, followed by its host. */
    public static final String CREDENTIAL_PREFIX = "git.token.";

    /**
     * Refuses a blank host, an entry both read and unreadable, and an unreadable one that is not
     * read as extended.
     */
    public ForgeInstance {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(reading, "reading");
        if (host.isBlank()) {
            throw new IllegalArgumentException("a forge instance names its host");
        }
        if ((adapter == null) == (unreadable == null)) {
            throw new IllegalArgumentException(
                    "an entry names its adapter or why it is unreadable");
        }
        if (adapter != null && adapter.isBlank() || unreadable != null && unreadable.isBlank()) {
            throw new IllegalArgumentException("a blank adapter or reason");
        }
        if (unreadable != null && reading != CommentReading.EXTENDED) {
            throw new IllegalArgumentException("an unreadable entry is read as extended");
        }
    }

    /**
     * Returns the vault entry of this forge's read credential, also the owner account's write
     * credential there.
     *
     * @return For example {@code git.token.github.com}.
     */
    public String credential() {
        return CREDENTIAL_PREFIX + host;
    }
}
