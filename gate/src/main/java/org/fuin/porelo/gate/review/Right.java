package org.fuin.porelo.gate.review;

import java.util.Optional;

/**
 * One thing a task's start may allow it: an action at its place, an act through its own endpoint,
 * or the one judgment.
 */
public enum Right {
    /** Pushes work to its gate. */
    PUSH("push"),
    /** Answers in a thread at its place. */
    REPLY("reply"),
    /** Opens a thread at its place. */
    OPEN_THREADS("openThreads"),
    /** Marks a thread at its place resolved. */
    RESOLVE_THREADS("resolveThreads"),
    /** Starts tasks of its own project through its endpoint. */
    START("start"),
    /** Stops the tasks it started. */
    STOP("stop"),
    /** Declares its own end. */
    DONE("done"),
    /** Gives a result back. */
    REPORT("report"),
    /** Discards the waiting push it reads. */
    REJECT("reject");

    private final String word;

    Right(final String word) {
        this.word = word;
    }

    /**
     * Returns the word the contract, the command line and a task's record name this right by.
     *
     * @return The word.
     */
    public String word() {
        return word;
    }

    /**
     * Returns every right's word, in the contract's order.
     *
     * @return The words.
     */
    public static java.util.List<String> words() {
        return java.util.Arrays.stream(values()).map(Right::word).toList();
    }

    /**
     * Returns the right a word names.
     *
     * @param word The word, exactly.
     * @return The right, or empty for a word that names none.
     */
    public static Optional<Right> named(final String word) {
        for (final Right each : values()) {
            if (each.word.equals(word)) {
                return Optional.of(each);
            }
        }
        return Optional.empty();
    }
}
