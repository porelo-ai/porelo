package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for {@link ShellSeconds}: a limit written for a program that takes seconds. */
class ShellSecondsTest {

    @ParameterizedTest
    @CsvSource({
        "60000, 60",
        "100000, 100",
        "600000, 600",
        "500, 0.5",
        "1500, 1.5",
        "100, 0.1",
        "1, 0.001",
        "30000, 30",
        "0, 0"
    })
    void decimalWritesThePlainSecondsWithNoExponentAndNoTrailingZeros(
            final long millis, final String expected) {
        assertThat(ShellSeconds.decimal(Duration.ofMillis(millis))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "60000, 60",
        "1000, 1",
        "1001, 2",
        "1500, 2",
        "100, 1",
        "1, 1",
        "0, 1",
        "100000, 100"
    })
    void wholeRoundsUpToWholeSecondsAndNeverBelowOne(final long millis, final String expected) {
        assertThat(ShellSeconds.whole(Duration.ofMillis(millis))).isEqualTo(expected);
    }
}
