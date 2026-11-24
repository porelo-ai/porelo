package org.fuin.porelo.gate.review;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Writes a push's threads as SARIF in Porelo's profile, one page at a time. The one place SARIF is
 * written.
 *
 * <p>A cursor is opaque to the caller, handed out by this writer for this push only, and names
 * where the next page starts in the threads' order (the oldest first); a cursor for another push,
 * or one this writer never handed out, is refused with {@code CursorRefused}. A reader that needs
 * every thread follows the cursors until none is left. A stand-in answers a fixed page and records
 * what it was given.
 */
public interface ReviewWriter {

    /**
     * Writes one page.
     *
     * @param push The push.
     * @param threads Its threads, as the store answers them.
     * @param cursor Where the page starts, from the last page, or null for the first.
     * @return The page and the cursor of the next one.
     * @throws ReviewRefused {@code CursorRefused}.
     */
    Page page(Push push, List<ReviewThread> threads, @Nullable String cursor) throws ReviewRefused;

    /**
     * One page.
     *
     * @param sarif The SARIF document.
     * @param next The cursor of the next page, or null after the last.
     */
    record Page(String sarif, @Nullable String next) {

        /** Refuses a missing document. */
        public Page {
            Objects.requireNonNull(sarif, "sarif");
        }
    }
}
