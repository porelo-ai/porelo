package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link TaskRunCommand#started}: how often a start begins again after the person chose
 * another way follows the account's {@code retries} factor.
 */
class TaskRunRepeatsTest {

    @Test
    void aStartBeginsAgainAsOftenAsTheRetriesFactorSays() {
        assertThat(startsUntilGivenUp(LimitFactors.DEFAULT))
                .as("at 1.0: the first start and its repeats")
                .isEqualTo(1 + TaskRunCommand.REPEATS);
        assertThat(
                        startsUntilGivenUp(
                                AccountConfig.read(new StringReader("limits:\n  retries: 2\n"), "t")
                                        .limits()))
                .as("at retries 2: twice the repeats")
                .isEqualTo(1 + 2 * TaskRunCommand.REPEATS);
    }

    @Test
    void aStartThatAnswersEndsTheRepeats() {
        final int[] starts = {0};

        final int code =
                TaskRunCommand.started(
                        () -> ++starts[0] < 2 ? TaskLaunch.RETRY : 0, LimitFactors.DEFAULT);

        assertThat(code).isZero();
        assertThat(starts[0]).isEqualTo(2);
    }

    private static int startsUntilGivenUp(final LimitFactors limits) {
        final int[] starts = {0};

        final int code =
                TaskRunCommand.started(
                        () -> {
                            starts[0]++;
                            return TaskLaunch.RETRY;
                        },
                        limits);

        assertThat(code).as("given up").isEqualTo(69);
        return starts[0];
    }
}
