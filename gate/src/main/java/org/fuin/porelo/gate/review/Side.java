package org.fuin.porelo.gate.review;

/** The side of a change a line comment is anchored on: the commit before it, or after it. */
public enum Side {
    /** The base commit's version of the file. */
    OLD,
    /** The reviewed commit's version of the file. */
    NEW;

    /**
     * Returns the side a contract word names.
     *
     * @param word {@code old} or {@code new}, exactly.
     * @return The side.
     * @throws IllegalArgumentException For any other word.
     */
    public static Side named(final String word) {
        return switch (word) {
            case "old" -> OLD;
            case "new" -> NEW;
            default -> throw new IllegalArgumentException("a side is old or new");
        };
    }

    /**
     * Returns the word the contract and the SARIF use for this side.
     *
     * @return {@code old} or {@code new}.
     */
    public String word() {
        return this == OLD ? "old" : "new";
    }
}
