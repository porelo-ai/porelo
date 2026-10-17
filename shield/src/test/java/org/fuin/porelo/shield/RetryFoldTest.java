package org.fuin.porelo.shield;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * One connection attempt is one line, however often the client sends it again.
 *
 * <p>An analysis of a task found 22 lines for 11 drops (found on 2026-10-13): a SYN and its retry,
 * each logged.
 */
class RetryFoldTest {

    private static final Instant T0 = Instant.parse("2026-10-13T17:06:53Z");

    private static BlockedConnection syn(final int sourcePort, final long secondsLater) {
        return new BlockedConnection(
                "porelo-drop ",
                BlockedConnection.TCP,
                "192.0.2.10",
                443,
                T0.plusSeconds(secondsLater),
                sourcePort);
    }

    @Test
    void aRetryOfOneAttemptIsNotANewLine() {

        final RetryFold fold = new RetryFold();

        assertThat(fold.isNew(syn(40001, 0))).isTrue();
        assertThat(fold.isNew(syn(40001, 1))).as("the SYN again after a second").isFalse();
        assertThat(fold.isNew(syn(40001, 3))).isFalse();
    }

    @Test
    void anotherAttemptIsALineOfItsOwnEvenToTheSamePlace() {

        final RetryFold fold = new RetryFold();

        assertThat(fold.isNew(syn(40001, 0))).isTrue();
        assertThat(fold.isNew(syn(40002, 0))).as("a new attempt takes a new source port").isTrue();
        assertThat(fold.isNew(syn(40001, 30)))
                .as("past the window, the same port is a new attempt")
                .isTrue();
    }

    @Test
    void withoutPortsEveryPacketCounts() {

        final RetryFold fold = new RetryFold();
        final BlockedConnection ping =
                new BlockedConnection("porelo-drop ", 1, "192.0.2.10", 0, T0);

        assertThat(fold.isNew(ping)).isTrue();
        assertThat(fold.isNew(ping)).isTrue();
    }
}
