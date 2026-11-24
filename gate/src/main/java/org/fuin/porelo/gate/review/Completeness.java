package org.fuin.porelo.gate.review;

/** How completely a run reviewed a push, in the profile's four words. */
public enum Completeness {
    /** Everything was reviewed. */
    COMPLETE("complete"),
    /** Some of it was reviewed. */
    PARTIAL("partial"),
    /** The run failed. */
    FAILED("failed"),
    /** Nothing says. */
    UNKNOWN("unknown");

    private final String word;

    Completeness(final String word) {
        this.word = word;
    }

    /**
     * Returns the profile's word.
     *
     * @return For example {@code complete}.
     */
    public String word() {
        return word;
    }
}
