package org.fuin.porelo.gate.review;

/** The owner account's decision on a held item. */
public enum Decision {
    /** An outgoing item goes to its place. */
    RELEASE,
    /** An outgoing item goes nowhere. */
    REFUSE,
    /** An incoming item reaches the run. */
    PASS,
    /** An incoming item never reaches the run. */
    DISMISS;

    /**
     * Whether this decision is one for an item going that way.
     *
     * @param direction The item's direction.
     * @return True for release and refuse on an outgoing item, pass and dismiss on an incoming one.
     */
    public boolean fits(final Direction direction) {
        return switch (this) {
            case RELEASE, REFUSE -> direction == Direction.OUTGOING;
            case PASS, DISMISS -> direction == Direction.INCOMING;
        };
    }

    /**
     * Returns the state an item takes on this decision.
     *
     * @return For example {@link HeldState#RELEASED}.
     */
    public HeldState state() {
        return switch (this) {
            case RELEASE -> HeldState.RELEASED;
            case REFUSE -> HeldState.REFUSED;
            case PASS -> HeldState.PASSED;
            case DISMISS -> HeldState.DISMISSED;
        };
    }
}
