package org.fuin.porelo.acceptance;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

/**
 * Waits for Porelo to show an attached agent in a state, under two bounds named apart: the start,
 * from the task found to its agent's first screen, and the state, from that first screen.
 *
 * <p>A start's cost is the machine's, not what the scenario asks: measured, a slow leg spent most
 * of one bound counted from the task's existence before the agent drew at all. So the state's bound
 * counts from the first screen Porelo reads, and a failure says which bound ran out and what it
 * waited for.
 */
final class ShownWithin {

    /** What a task's status is asked by. */
    interface Status {

        /**
         * Asks once.
         *
         * @return What was read: the status, and whatever else the state is judged by.
         * @throws IOException If the machine cannot be reached.
         */
        Look look() throws IOException;
    }

    /** The time, and the wait between two looks. */
    interface Clock {

        /**
         * Says when it is.
         *
         * @return Now.
         */
        Instant now();

        /** Waits until the next look. */
        void pause();
    }

    /**
     * One look at a task.
     *
     * @param status What {@code porelo task status} printed.
     * @param shown What a failure shows besides it, or "".
     */
    record Look(String status, String shown) {

        /**
         * Says whether Porelo read the agent's screen: before the agent draws, it cannot say.
         *
         * @return Whether the status has a screen reading.
         */
        boolean hasScreen() {
            return WaitingSteps.screen(status).startsWith("reads as ");
        }

        String report() {
            return "'porelo task status' said:\n" + status + (shown.isEmpty() ? "" : "\n" + shown);
        }
    }

    /**
     * How long each part took.
     *
     * @param start From the task found to the first screen.
     * @param state From the first screen to the state held.
     */
    record Took(Duration start, Duration state) {}

    private final Clock clock;
    private final Duration startWithin;
    private final Duration stateWithin;

    /**
     * Constructor with the bounds.
     *
     * @param clock The time.
     * @param startWithin How long the agent may take to draw its first screen.
     * @param stateWithin How long the state may take to show, from the first screen.
     */
    ShownWithin(Clock clock, Duration startWithin, Duration stateWithin) {
        this.clock = clock;
        this.startWithin = startWithin;
        this.stateWithin = stateWithin;
    }

    /**
     * Waits for a state to be shown and to hold.
     *
     * @param status How the task is asked.
     * @param shown Whether a look shows the state; it may throw where a look shows its opposite.
     * @param holds How long the state must hold unbroken; zero for once.
     * @param what What is waited for, as a failure names it.
     * @param hint What the state's failure may mean, said after it.
     * @return How long each part took.
     * @throws IOException If the machine cannot be reached.
     */
    Took waitFor(Status status, Predicate<Look> shown, Duration holds, String what, String hint)
            throws IOException {
        final Instant began = clock.now();
        Look look = status.look();
        while (!look.hasScreen()) {
            if (!clock.now().isBefore(began.plus(startWithin))) {
                throw new AssertionError(
                        "The start's bound of "
                                + startWithin.toSeconds()
                                + "s ran out waiting for the agent's first screen; "
                                + what
                                + " was not asked yet. "
                                + look.report());
            }
            clock.pause();
            look = status.look();
        }
        final Instant firstScreen = clock.now();
        final Instant deadline = firstScreen.plus(stateWithin);
        Instant since = null;
        while (true) {
            if (shown.test(look)) {
                since = since == null ? clock.now() : since;
                if (!clock.now().isBefore(since.plus(holds))) {
                    return new Took(
                            Duration.between(began, firstScreen),
                            Duration.between(firstScreen, clock.now()));
                }
            } else {
                since = null;
            }
            if (!clock.now().isBefore(deadline)) {
                throw new AssertionError(
                        "The state's bound of "
                                + stateWithin.toSeconds()
                                + "s from the agent's first screen, "
                                + Duration.between(began, firstScreen).toSeconds()
                                + "s after the step found the task, ran out waiting for "
                                + what
                                + ". "
                                + hint
                                + " "
                                + look.report());
            }
            clock.pause();
            look = status.look();
        }
    }
}
