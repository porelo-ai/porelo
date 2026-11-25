package org.fuin.porelo.gate.review;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A forge's conversation as its adapter answered it: profile 2 pages as bytes, unread.
 *
 * @param pages The pages in order, each one profile 2 document.
 * @param reason Why the pages are not all of it, such as {@code RateLimited}, {@code PagePartial}
 *     or {@code PageRefused}; null when they are.
 */
public record ForgePages(List<byte[]> pages, @Nullable String reason) {

    /** Copies the pages. */
    public ForgePages {
        pages = pages.stream().map(byte[]::clone).toList();
        if (reason != null && reason.isBlank()) {
            throw new IllegalArgumentException("the reason is blank");
        }
    }

    @Override
    public List<byte[]> pages() {
        return pages.stream().map(byte[]::clone).toList();
    }
}
