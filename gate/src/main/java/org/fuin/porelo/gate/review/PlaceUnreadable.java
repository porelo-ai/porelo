package org.fuin.porelo.gate.review;

import java.util.Objects;

/** A place that could not be read or pinned; nothing was made. Its reasons are the contract's. */
public final class PlaceUnreadable extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why, in the contract's words of {@code PlaceUnreadable} and {@code NoSuchPullRequest}. */
    public enum Reason {
        /** No push of that name waits at the gate. */
        NO_SUCH_PUSH("NoSuchPush"),
        /** The place is not in the task's project. */
        NOT_IN_PROJECT("NotInProject"),
        /** The place moved past the commit asked for. */
        MOVED_SINCE_REVIEW("MovedSinceReview"),
        /** The vault holds no token for the forge. */
        MISSING_FORGE_TOKEN("MissingForgeToken"),
        /** No adapter for the forge is installed. */
        FORGE_ADAPTER_MISSING("ForgeAdapterMissing"),
        /** The forge did not answer. */
        UNREACHABLE("Unreachable"),
        /**
         * The forge knows no pull request at that address; on the wire its own error {@code
         * NoSuchPullRequest(url)}, never a reason of {@code PlaceUnreadable}.
         */
        NO_SUCH_PULL_REQUEST("NoSuchPullRequest"),
        /**
         * The machine knows no forge at the address's host, or its entry cannot be read; on the
         * wire its own error {@code UnknownForge(host)}.
         */
        UNKNOWN_FORGE("UnknownForge"),
        /**
         * The machine's forge file cannot be read; on the wire its own error {@code
         * ForgeFileUnreadable}.
         */
        FORGE_FILE_UNREADABLE("ForgeFileUnreadable");

        private final String wireName;

        Reason(final String wireName) {
            this.wireName = wireName;
        }

        /**
         * Returns the reason's word in the contract.
         *
         * @return For example {@code Unreachable}.
         */
        public String wireName() {
            return wireName;
        }
    }

    private final Reason reason;

    /**
     * Constructor with the reason and a detail for a person.
     *
     * @param reason Why.
     * @param detail For a person; never a token or a forge's text.
     */
    public PlaceUnreadable(final Reason reason, final String detail) {
        super(Objects.requireNonNull(detail, "detail"));
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns why.
     *
     * @return The reason.
     */
    public Reason reason() {
        return reason;
    }
}
