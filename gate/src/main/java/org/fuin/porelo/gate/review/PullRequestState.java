package org.fuin.porelo.gate.review;

/** Where a pull request stands at its forge. */
public enum PullRequestState {
    /** Open: its head may still move. */
    OPEN,
    /** Merged: its head went onto its base. */
    MERGED,
    /** Closed without a merge. */
    CLOSED
}
