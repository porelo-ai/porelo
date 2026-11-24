package org.fuin.porelo.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * A time limit written for a program that takes it in seconds on its command line: {@code sleep},
 * {@code timeout}, {@code curl}, {@code ssh}. A limit times a factor is seldom whole seconds.
 */
final class ShellSeconds {

    private ShellSeconds() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Writes a limit as seconds with at most three digits after the dot, as {@code sleep}, {@code
     * timeout} and {@code curl} take it: {@code 60} for a minute, {@code 0.5} for half a second.
     *
     * @param limit The limit.
     * @return Its seconds.
     */
    static String decimal(final Duration limit) {
        return BigDecimal.valueOf(limit.toMillis(), 3).stripTrailingZeros().toPlainString();
    }

    /**
     * Writes a limit as whole seconds, rounded up and at least one, for a program that takes no
     * fraction, such as {@code ssh}'s {@code ConnectTimeout}.
     *
     * @param limit The limit.
     * @return Its seconds.
     */
    static String whole(final Duration limit) {
        return BigDecimal.valueOf(limit.toMillis(), 3)
                .setScale(0, RoundingMode.CEILING)
                .max(BigDecimal.ONE)
                .toPlainString();
    }

    /**
     * Writes a sleep for a shell line that runs in a container, whose {@code sleep} may take no
     * fraction: a busybox one refuses {@code 0.5} and returns at once. Whole seconds go as they
     * are; a fraction is tried, and where it is refused the sleep is whole seconds rounded up, so
     * it never returns early and a loop around it never spins.
     *
     * @param limit How long.
     * @return A command for a shell line: one and-or list, which {@code &} puts in the background
     *     whole.
     */
    static String sleep(final Duration limit) {
        final String decimal = decimal(limit);
        final String whole = whole(limit);
        return decimal.equals(whole)
                ? "sleep " + whole
                : "sleep " + decimal + " 2>/dev/null || sleep " + whole;
    }
}
