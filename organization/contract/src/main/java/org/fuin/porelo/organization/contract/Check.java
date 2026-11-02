package org.fuin.porelo.organization.contract;

/**
 * One check of a contract: a name that reads as a sentence, and what it does with a new fixture.
 *
 * @param <F> The fixture it plays on.
 * @param name What it holds.
 * @param body What it does; it fails with an {@link AssertionError}, and any other exception is a
 *     fault of the fixture, not an answer.
 */
public record Check<F>(String name, Body<F> body) {

    /**
     * What a check does.
     *
     * @param <F> The fixture it plays on.
     */
    @FunctionalInterface
    public interface Body<F> {

        /**
         * Runs the check.
         *
         * @param fixture A new fixture, for this run alone.
         * @throws Exception When the fixture fails.
         */
        void run(F fixture) throws Exception;
    }
}
