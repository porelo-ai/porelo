package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * One run of a task at a place, pinned to the state it reviews.
 *
 * @param place The place's name, as {@link ReviewPlace#name()} gives it.
 * @param role The task's role there.
 * @param pinned The state the run reads: a push whose head is the pinned commit, and for a pull
 *     request the facts the forge answered.
 * @param number The run's number at that place for its task, from 1.
 */
public record Run(String place, Role role, Pinned pinned, int number) {

    /** Refuses a blank place and a number below 1. */
    public Run {
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(pinned, "pinned");
        if (place.isBlank()) {
            throw new IllegalArgumentException("a run names its place");
        }
        if (number < 1) {
            throw new IllegalArgumentException("a run is counted from 1");
        }
    }

    /**
     * Returns the pinned push.
     *
     * @return Its head the commit the run reviews.
     */
    public Push push() {
        return pinned.push();
    }
}
