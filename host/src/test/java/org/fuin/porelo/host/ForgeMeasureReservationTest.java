package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ForgeMeasureReservationTest {
    @Test
    void oneRequestReservesOnlyOneConnectionEvenWhenWorkersRace() {
        final var opened = new AtomicInteger();
        final var raced = new CountDownLatch(32);
        assertThatThrownBy(
                        () ->
                                ForgeMeasure.run(
                                        Path.of("/unused"),
                                        "host:3000",
                                        "/",
                                        1,
                                        32,
                                        8,
                                        socket -> {
                                            opened.incrementAndGet();
                                            raced.countDown();
                                            try {
                                                raced.await(1, TimeUnit.SECONDS);
                                            } catch (InterruptedException ex) {
                                                Thread.currentThread().interrupt();
                                                throw new IOException(ex);
                                            }
                                            throw new IOException("fixture connection refused");
                                        }))
                .hasRootCauseInstanceOf(IOException.class);
        assertThat(opened.get())
                .as("workers reserve a request before opening its connection")
                .isOne();
    }
}
