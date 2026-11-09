package org.fuin.porelo.wire.planning;

/** Closed set of role values in the planning contract. */
public enum Role {
    /** Main selection. */
    MAIN,
    /** Builder selection. */
    BUILDER,
    /** Tester selection. */
    TESTER,
    /** Reader selection. */
    READER,
    /** Unknown adapter proposal; display only, never authorizes work. */
    OTHER
}
