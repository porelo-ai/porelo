package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A kept reading is released by the sweep once its keep time has passed, so a burst of large
 * readings no later call asks for does not hold the heap; one inside its keep time stays, so a
 * caller still paging through it is answered from it.
 */
class KeptReadingsTest {

    private static final Duration KEPT = Duration.ofMinutes(5);

    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(start);
    private final KeptReadings<String, String> readings =
            new KeptReadings<>(now::get, KEPT, 16, 64L * 1024 * 1024);

    @Test
    void aReadingPastItsKeepTimeIsReleasedByTheSweep() {
        readings.keep("burst", "records", 64L * 1024 * 1024);
        now.set(start.plus(KEPT));

        readings.release();

        assertThat(readings.bytes()).as("nothing held after the sweep").isZero();
        assertThat(readings.get("burst")).as("the reading is gone").isNull();
    }

    @Test
    void aReadingInsideItsKeepTimeStays() {
        readings.keep("paging", "records", 1000);
        now.set(start.plus(KEPT).minusSeconds(1));

        readings.release();

        assertThat(readings.bytes()).as("still held").isEqualTo(1000);
        assertThat(readings.get("paging")).as("its pages still read it").isEqualTo("records");
    }

    @Test
    void theSweepAndConcurrentCallsNeitherLoseNorReleaseTwice() throws Exception {
        // Old readings past their keep time, new ones kept and read while the sweep runs.
        for (int i = 0; i < 8; i++) {
            readings.keep("old" + i, "old", 100);
        }
        now.set(start.plus(KEPT));
        final int fresh = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(fresh + 2);
        try {
            final CountDownLatch go = new CountDownLatch(1);
            final List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < fresh; i++) {
                final String key = "new" + i;
                runs.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    readings.keep(key, key, 10);
                                    for (int read = 0; read < 2000; read++) {
                                        assertThat(readings.get(key))
                                                .as("a reading inside its keep time is not lost")
                                                .isEqualTo(key);
                                    }
                                    return null;
                                }));
            }
            for (int s = 0; s < 2; s++) {
                runs.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    for (int sweep = 0; sweep < 2000; sweep++) {
                                        readings.release();
                                    }
                                    return null;
                                }));
            }
            go.countDown();
            for (final Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        readings.release();

        assertThat(readings.bytes())
                .as("exactly the fresh readings are held, each once")
                .isEqualTo(fresh * 10L);
        for (int i = 0; i < 8; i++) {
            assertThat(readings.get("old" + i)).as("an old reading is released").isNull();
        }
        assertThat(readings.kept()).as("the fresh readings are kept").isEqualTo(fresh);
    }
}
