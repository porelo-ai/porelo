package org.fuin.porelo.gate.review;

/** Which way a held item goes. */
public enum Direction {
    /** From a task's run to its place, released or refused by the owner account. */
    OUTGOING,
    /** From a place to a task's run, passed or dismissed by the owner account. */
    INCOMING
}
