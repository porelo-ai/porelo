package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.fuin.porelo.wire.TestPrograms;
import org.junit.jupiter.api.Test;

/** Streams worker stdout while keeping worker stderr bounded. */
class HostPushOutputTest {
    @Test
    void aWorkerStreamsMoreThanTheFormerSixtyFourMebibyteAnswerLimit() throws Exception {
        final var runner =
                new HostPrograms(Duration.ofSeconds(10), 1024 * 1024, TestPrograms::find);
        final var count = new java.util.concurrent.atomic.AtomicLong();
        final var sink =
                new java.io.OutputStream() {
                    @Override
                    public void write(int value) {
                        count.incrementAndGet();
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) {
                        count.addAndGet(length);
                    }
                };
        final long bytes = 65L * 1024 * 1024;
        final var result =
                runner.transfer(
                        List.of("head", "-c", Long.toString(bytes), "/dev/zero"),
                        java.io.InputStream.nullInputStream(),
                        sink,
                        HostPrograms.ENVIRONMENT);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEmpty();
        assertThat(count.get()).isEqualTo(bytes);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aWorkerCannotRetainMoreThanOneMebibyteOfStandardError(boolean closedOutput) {
        final var runner = new HostPrograms(Duration.ofSeconds(2), 1024 * 1024, TestPrograms::find);
        assertThatThrownBy(
                        () ->
                                runner.run(
                                        List.of(
                                                "sh",
                                                "-c",
                                                (closedOutput ? "exec 1>&-; " : "")
                                                        + "head -c 1048577 /dev/zero >&2; sleep 30")))
                .as("excessive stderr must stop the worker immediately, independently of stdout")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("limit");
    }
}
