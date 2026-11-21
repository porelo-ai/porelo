package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Guards that a scenario of an attached agent's shown state passes or fails by what Porelo shows,
 * never by how fast a machine starts the task, and that a failure names the bound that ran out.
 */
class ShownWithinTest {

    private static final Duration BOUND = Duration.ofSeconds(60);

    private static final String HINT = "Its declaration may no longer fit it.";

    private static final String DRAWING = "activity   working\n";

    private static final String WAITING =
            "activity   waiting\nscreen     reads as waiting for you - trust\n";

    private static final String NOT_WAITING =
            "activity   working\nscreen     reads as not waiting\n";

    private static final Predicate<ShownWithin.Look> IS_WAITING =
            look -> WaitingSteps.screen(look.status()).startsWith("reads as waiting for you");

    /** A clock a second passes on at every pause, and a task that draws and shows on cue. */
    private static class Machine implements ShownWithin.Clock, ShownWithin.Status {

        private final long drawsAt;
        private final long showsAt;
        private Instant now = Instant.parse("2026-11-21T06:00:00Z");
        private long second;

        Machine(long drawsAt, long showsAt) {
            this.drawsAt = drawsAt;
            this.showsAt = showsAt;
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public void pause() {
            now = now.plusSeconds(1);
            second++;
        }

        @Override
        public ShownWithin.Look look() {
            final String status =
                    second < drawsAt ? DRAWING : second < showsAt ? NOT_WAITING : WAITING;
            return new ShownWithin.Look(status, "");
        }
    }

    @Test
    void aSlowStartDoesNotSpendTheStatesBound() throws Exception {
        // Measured on a leg: the start took most of one bound counted from the task's existence.
        final Machine machine = new Machine(50, 50 + 50);

        final ShownWithin.Took took =
                new ShownWithin(machine, BOUND, BOUND)
                        .waitFor(
                                machine,
                                IS_WAITING,
                                Duration.ZERO,
                                "the agent shown waiting",
                                HINT);

        assertThat(took)
                .as("50s to the first screen, then 50s to the state, each within its own bound")
                .isEqualTo(new ShownWithin.Took(Duration.ofSeconds(50), Duration.ofSeconds(50)));
    }

    @Test
    void aStartHeldBackFailsOnTheStartsBoundAndNamesTheFirstScreen() {
        final Machine machine = new Machine(Long.MAX_VALUE, Long.MAX_VALUE);

        assertThatThrownBy(
                        () ->
                                new ShownWithin(machine, BOUND, BOUND)
                                        .waitFor(
                                                machine,
                                                IS_WAITING,
                                                Duration.ZERO,
                                                "the agent shown waiting",
                                                HINT))
                .as("the bound that ran out, what it waited for, and what was last read")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("The start's bound of 60s ran out")
                .hasMessageContaining("waiting for the agent's first screen")
                .hasMessageContaining("the agent shown waiting was not asked yet")
                .hasMessageContaining(DRAWING)
                .hasMessageNotContaining(HINT);
    }

    @Test
    void aStateNeverShownFailsOnTheStatesBoundAndNamesTheState() {
        final Machine machine = new Machine(20, Long.MAX_VALUE);

        assertThatThrownBy(
                        () ->
                                new ShownWithin(machine, BOUND, BOUND)
                                        .waitFor(
                                                machine,
                                                IS_WAITING,
                                                Duration.ZERO,
                                                "the agent shown waiting",
                                                HINT))
                .as("the bound that ran out, where it began, and what it waited for")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("The state's bound of 60s from the agent's first screen")
                .hasMessageContaining("20s after the step found the task")
                .hasMessageContaining("ran out waiting for the agent shown waiting. " + HINT)
                .hasMessageContaining(NOT_WAITING);
    }

    @Test
    void aStateThatBreaksBeforeItHasHeldIsNotTakenAsHeld() {
        final Machine machine =
                new Machine(0, 0) {
                    private int looks;

                    @Override
                    public ShownWithin.Look look() {
                        looks++;
                        return new ShownWithin.Look(looks % 5 == 0 ? NOT_WAITING : WAITING, "");
                    }
                };

        assertThatThrownBy(
                        () ->
                                new ShownWithin(machine, BOUND, BOUND)
                                        .waitFor(
                                                machine,
                                                IS_WAITING,
                                                Duration.ofSeconds(8),
                                                "the agent held waiting",
                                                HINT))
                .as("shown for four looks at a time never holds for eight seconds")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("The state's bound of 60s");
    }
}
