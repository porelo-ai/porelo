package org.fuin.porelo.gate.review;

/** The machine's forge file could not be read or written as a whole; nothing was changed. */
public final class ForgeFileUnreadable extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with the reason.
     *
     * @param reason For a person; never a credential.
     */
    public ForgeFileUnreadable(final String reason) {
        super(reason);
    }
}
