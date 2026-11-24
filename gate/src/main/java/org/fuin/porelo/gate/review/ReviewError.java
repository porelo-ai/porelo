package org.fuin.porelo.gate.review;

/** The contract's named refusals of a review call, one constant each, so no lane spells its own. */
public enum ReviewError {
    /** The push moved past the commit the caller reviewed. */
    MOVED_SINCE_REVIEW("MovedSinceReview"),
    /** No push of that name waits. */
    NO_SUCH_PUSH("NoSuchPush"),
    /** The anchor's lines are not in the side's file. */
    ANCHOR_OUTSIDE_BLOB("AnchorOutsideBlob"),
    /** The anchor's path is no regular file on that side; a link is never followed. */
    NOT_A_REGULAR_FILE("NotARegularFile"),
    /** A suggestion reaches past its anchor's lines. */
    FIX_OUTSIDE_ANCHOR("FixOutsideAnchor"),
    /** A suggestion on the old side. */
    OLD_SIDE_FIX("OldSideFix"),
    /** A text broke a rule; the detail names the rule. */
    TEXT_REFUSED("TextRefused"),
    /** A text or range is over a limit; the detail names the limit. */
    TOO_LARGE("TooLarge"),
    /** No thread of that id in this push, or none the producer may write in. */
    NO_SUCH_THREAD("NoSuchThread"),
    /** A cursor this writer did not hand out for this push. */
    CURSOR_REFUSED("CursorRefused"),
    /** A retry id given before with another item. */
    ID_REUSED("IdReused");

    private final String wireName;

    ReviewError(final String wireName) {
        this.wireName = wireName;
    }

    /**
     * Returns the error's name in the contract.
     *
     * @return For example {@code TooLarge}.
     */
    public String wireName() {
        return wireName;
    }
}
