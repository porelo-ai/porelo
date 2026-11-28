package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * Thrown where an interface answers a refusal instead of a result. Open for a refusal that says
 * more to its producer than the contract's error, which it still answers on the wire.
 */
public class ReviewRefused extends Exception {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial")
    private final ReviewRefusal refusal;

    /**
     * Constructor with the refusal.
     *
     * @param refusal Why.
     */
    public ReviewRefused(final ReviewRefusal refusal) {
        super(Objects.requireNonNull(refusal, "refusal").error().wireName());
        this.refusal = refusal;
    }

    /**
     * Returns why.
     *
     * @return The refusal.
     */
    public final ReviewRefusal refusal() {
        return refusal;
    }
}
