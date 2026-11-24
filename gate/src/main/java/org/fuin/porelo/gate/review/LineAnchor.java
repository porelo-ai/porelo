package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * A range of lines in one file, on one side of the change.
 *
 * <p>Lines are counted from 1 and both ends are inclusive. Only the shape is held here; the path's
 * grammar, the limits and whether the lines exist in the pinned tree are {@link CommentCheck}'s.
 *
 * @param path The file's path in the repository, as the contract gave it.
 * @param side The side the lines are on.
 * @param startLine The first line, from 1.
 * @param endLine The last line, never before the first.
 */
public record LineAnchor(String path, Side side, int startLine, int endLine) implements Anchor {

    /** Refuses a blank path and a range that is not one. */
    public LineAnchor {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(side, "side");
        if (path.isBlank()) {
            throw new IllegalArgumentException("a line anchor's path is blank");
        }
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine is counted from 1");
        }
        if (endLine < startLine) {
            throw new IllegalArgumentException("endLine is before startLine");
        }
    }

    @Override
    public boolean takesSuggestion() {
        return side == Side.NEW;
    }

    /**
     * Returns how many lines the anchor covers.
     *
     * @return At least one.
     */
    public long lines() {
        return (long) endLine - startLine + 1;
    }
}
