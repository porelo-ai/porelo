package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.app.ReviewStandIns.OTHER;
import static org.fuin.porelo.app.ReviewStandIns.SHOP;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.Role;
import org.fuin.porelo.gate.review.Run;
import org.junit.jupiter.api.Test;

/** The run each task is in now, kept for the daemon's life. */
class HeldRunsTest {

    private static final String PLACE = "https://github.com/uc/shop/pull/7";

    private static Run run(final int number) {
        return new Run(PLACE, Role.REVIEWER, new Pinned(number == 1 ? SHOP : OTHER, null), number);
    }

    @Test
    void aTaskWithoutARunBegunHasNone() {
        assertThat(new HeldRuns().of("porelo-uc-shop-2")).as("no run begun").isEmpty();
    }

    @Test
    void aRunBegunIsTheTasksUntilItEnds() {
        final HeldRuns runs = new HeldRuns();

        runs.begin("porelo-uc-shop-2", run(1));

        assertThat(runs.of("porelo-uc-shop-2")).as("the task's run").contains(run(1));
        assertThat(runs.of("porelo-uc-shop-3")).as("another task's").isEmpty();
        runs.end("porelo-uc-shop-2");
        assertThat(runs.of("porelo-uc-shop-2")).as("after its end").isEmpty();
    }

    @Test
    void aSecondRunOfTheSameTaskReplacesTheFirst() {
        // The start of the second has already lapsed what the first held; it answers for nothing.
        final HeldRuns runs = new HeldRuns();
        runs.begin("porelo-uc-shop-2", run(1));

        runs.begin("porelo-uc-shop-2", run(2));

        assertThat(runs.of("porelo-uc-shop-2")).as("the newer run").contains(run(2));
    }

    @Test
    void runsBegunFromManyThreadsAreEachKept() throws Exception {
        final HeldRuns runs = new HeldRuns();
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Future<?>> begun = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                final String task = "porelo-uc-shop-" + i;
                begun.add(pool.submit(() -> runs.begin(task, run(1))));
            }
            for (final Future<?> each : begun) {
                each.get();
            }
        } finally {
            pool.shutdown();
        }
        for (int i = 0; i < 200; i++) {
            assertThat(runs.of("porelo-uc-shop-" + i)).as("task %d's run", i).isPresent();
        }
    }
}
