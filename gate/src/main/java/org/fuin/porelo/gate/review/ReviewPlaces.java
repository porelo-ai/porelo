package org.fuin.porelo.gate.review;

import java.util.Optional;

/**
 * Finds a place by the name a hold carries, so a released item reaches its place after a restart.
 *
 * <p>A stand-in answers fixed places.
 */
public interface ReviewPlaces {

    /**
     * Finds a place.
     *
     * @param name As {@link ReviewPlace#name()} gives it: a pull request's https address.
     * @return The place, or empty when no place of that name can be reached any more.
     */
    Optional<ReviewPlace> named(String name);
}
