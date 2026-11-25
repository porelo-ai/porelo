package org.fuin.porelo.gate.review;

/** How a place's comments are read before a run sees them, as the machine's setting for it says. */
public enum CommentReading {
    /** Every comment goes through the message filter's inbound check; a finding is held. */
    EXTENDED,
    /** Comments reach the run after the format and size check alone. */
    TRUSTED
}
