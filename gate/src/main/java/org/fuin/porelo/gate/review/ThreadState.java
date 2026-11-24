package org.fuin.porelo.gate.review;

/** Whether a thread still waits for an answer. Resolving clears no finding and approves nothing. */
public enum ThreadState {
    /** Waiting for an answer. */
    OPEN,
    /** Answered, as its writer judged. */
    RESOLVED
}
