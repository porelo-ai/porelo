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
        /** Judging each comment by the place's reading setting. */
        JUDGE,
        /** Delivering the conversation to the task, its pages written. */
        DELIVER,
        /** Counting the task's items that wait for the owner account. */
        COUNT
    }

    private final Step step;

    private final PlaceUnreadable.@Nullable Reason reason;

    /**
     * Constructor with the step and what stopped it.
     *
     * @param step Where it stopped.
     * @param cause Why; a {@link PlaceUnreadable} names its reason and detail, anything else only
     *     its class.
     */
    public RunRefused(final Step step, final Exception cause) {
        super(
                "the run could not start: "
                        + Objects.requireNonNull(step, "step")
                                .name()
                                .toLowerCase(java.util.Locale.ROOT)
                        + " failed: "
                        + said(Objects.requireNonNull(cause, "cause")),
                cause);
        this.step = step;
        this.reason = cause instanceof PlaceUnreadable unreadable ? unreadable.reason() : null;
    }

    /**
     * What the message says of the cause: a place's reason and its fixed detail, otherwise only the
     * failure's class, since its message may name local paths.
     */
    private static String said(final Exception cause) {
        if (cause instanceof PlaceUnreadable unreadable) {
            return unreadable.reason().wireName() + ": " + unreadable.getMessage();
        }
        return cause.getClass().getSimpleName();
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
