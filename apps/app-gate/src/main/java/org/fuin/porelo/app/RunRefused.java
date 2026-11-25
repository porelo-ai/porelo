package org.fuin.porelo.app;

import java.util.Objects;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.jspecify.annotations.Nullable;

/** A run's start that stopped at one of its steps; no run of the task is current then. */
public final class RunRefused extends Exception {

    private static final long serialVersionUID = 1L;

    /** The step a start stopped at. */
    public enum Step {
        /** Pinning the place's state. */
        PIN,
        /** Reading the place's whole conversation, its credential included. */
        READ,
        /** Delivering the conversation to the task. */
        DELIVER
    }

    private final Step step;

    private final PlaceUnreadable.@Nullable Reason reason;

    /**
     * Constructor with the step and what stopped it.
     *
     * @param step Where it stopped.
     * @param cause Why; a {@link PlaceUnreadable} names its reason.
     */
    public RunRefused(final Step step, final Exception cause) {
        super(
                "the run could not start: "
                        + Objects.requireNonNull(step, "step")
                                .name()
                                .toLowerCase(java.util.Locale.ROOT)
                        + " failed: "
                        + Objects.requireNonNull(cause, "cause").getMessage(),
                cause);
        this.step = step;
        this.reason = cause instanceof PlaceUnreadable unreadable ? unreadable.reason() : null;
    }

    /**
     * Returns the step it stopped at.
     *
     * @return The step.
     */
    public Step step() {
        return step;
    }

    /**
     * Returns the place's reason, when the place could not be read or pinned.
     *
     * @return The reason, or null when something else stopped it.
     */
    public PlaceUnreadable.@Nullable Reason reason() {
        return reason;
    }
}
