package org.fuin.porelo.gate.review;

/** Where a held item stands; only {@link #UNDECIDED} awaits the owner account. */
public enum HeldState {
    /** Waiting for the owner account's decision. */
    UNDECIDED,
    /** An outgoing item released, not yet written to its place. */
    RELEASED,
    /** An outgoing item refused by the owner account; it goes nowhere. */
    REFUSED,
    /** An incoming item passed to the run. */
    PASSED,
    /** An incoming item dismissed; the run never sees it. */
    DISMISSED,
    /** Undecided when a later run of its task began at its place; it can no longer be decided. */
    LAPSED,
    /** A released item written to its place. */
    DELIVERED,
    /**
     * A released item its place refused, with the place's reason; never written again by itself.
     */
    NOT_DELIVERED,
    /** A released item the place asked to wait for; written again once its retry time passed. */
    RETRY,
    /**
     * A released item whose write was sent and no answer says whether it was written; never
     * retried.
     */
    OUTCOME_UNKNOWN
}
