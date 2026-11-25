package org.fuin.porelo.build.api;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One page of a pull request's conversation, unread: a profile 2 document of kind {@code page}, and
 * where the next page starts.
 *
 * @param document The document's UTF-8 bytes, at most {@link ReviewProtocol#MOST_PAGE_BYTES}.
 * @param cursor Where the next page starts; {@code null} after the last one.
 */
public record ConversationPage(byte[] document, @Nullable String cursor) {

    /**
     * Copies the document and refuses one over {@link ReviewProtocol#MOST_PAGE_BYTES} or a blank
     * cursor.
     *
     * @param document The document's bytes.
     * @param cursor Where the next page starts, or {@code null}.
     */
    public ConversationPage {
        Objects.requireNonNull(document, "document");
        if (document.length > ReviewProtocol.MOST_PAGE_BYTES) {
            throw new IllegalArgumentException(
                    "a page is at most "
                            + ReviewProtocol.MOST_PAGE_BYTES
                            + " bytes, not "
                            + document.length);
        }
        if (cursor != null && cursor.isBlank()) {
            throw new IllegalArgumentException("a cursor is not blank");
        }
        document = document.clone();
    }

    /**
     * Returns a page from its document's text, as an adapter writes it.
     *
     * @param document The profile 2 document.
     * @param cursor Where the next page starts, or {@code null}.
     * @return The page.
     */
    public static ConversationPage of(final String document, final @Nullable String cursor) {
        return new ConversationPage(document.getBytes(StandardCharsets.UTF_8), cursor);
    }

    @Override
    public byte[] document() {
        return document.clone();
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        return other instanceof ConversationPage page
                && Arrays.equals(document, page.document)
                && Objects.equals(cursor, page.cursor);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(document) + Objects.hashCode(cursor);
    }

    @Override
    public String toString() {
        return "ConversationPage[" + document.length + " bytes, cursor=" + cursor + "]";
    }
}
