package org.fuin.porelo.gate.review;

import java.util.Objects;

/** A decision on a held item refused; nothing changed. Its reasons are the contract's. */
public final class HoldRefused extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why, in the contract's error names. */
    public enum Reason {
        /** No held item of that id for that task. */
        NO_SUCH_ITEM("NoSuchItem"),
        /** The item's content is not the one shown. */
        DIGEST_CHANGED("DigestChanged"),
        /** The item was decided before. */
        ALREADY_DECIDED("AlreadyDecided"),
        /** A later run of its task began at its place. */
        LAPSED("Lapsed");

        private final String wireName;

        Reason(final String wireName) {
            this.wireName = wireName;
        }

        /**
         * Returns the error's name in the contract.
         *
         * @return For example {@code DigestChanged}.
         */
        public String wireName() {
            return wireName;
        }
    }

    private final Reason reason;
    private final String id;

    /**
     * Constructor with the reason and the item's id.
     *
     * @param reason Why.
     * @param id The held item's id, as asked.
     */
    public HoldRefused(final Reason reason, final String id) {
        super(Objects.requireNonNull(reason, "reason").wireName());
        this.reason = reason;
        this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * Returns why.
     *
     * @return The reason.
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns the held item's id, as asked.
     *
     * @return The id.
     */
    public String id() {
        return id;
    }
}
