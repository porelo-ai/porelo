package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostFollowTransferTest {
    @Test
    void aFollowDeadlineDoesNotImposeAByteLimitOnStreaming(@TempDir Path directory)
            throws Exception {
        final AtomicLong received = new AtomicLong();
        final var runtime =
                new ProjectRuntime(
                        new ProjectFiles(directory, 0),
                        (argv, input, environment, timeout) -> {
                            throw new AssertionError("this child streams");
                        },
                        Path::of,
                        path -> false,
                        (socket, token) -> {
                            throw new AssertionError("this child uses no forge API");
                        },
                        (argv, input, output, environment, timeout) -> {
                            assertThat(timeout).isEqualTo(Duration.ofSeconds(20));
                            final byte[] block = new byte[64 * 1024];
                            for (int count = 0; count < 8193; count++) {
                                output.write(block);
                            }
                            return new HostPrograms.Result(0, "");
                        });
        final Instant now = Instant.parse("2026-11-09T00:00:00Z");
        runtime.until(Clock.fixed(now, ZoneOffset.UTC), now.plusSeconds(20))
                .bundle(
                        "forge",
                        directory,
                        new OutputStream() {
                            @Override
                            public void write(int value) {
                                received.incrementAndGet();
                            }

                            @Override
                            public void write(byte[] bytes, int offset, int length) {
                                received.addAndGet(length);
                            }
                        });
        assertThat(received).hasValue(8193L * 64 * 1024);
    }
}
