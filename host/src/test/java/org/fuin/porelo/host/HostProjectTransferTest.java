package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class HostProjectTransferTest {
    @Test
    void aBundleStreamsEveryByteBeyondTheTextLimit() throws Exception {
        final byte[] bundle = new byte[1024 * 1024];
        new Random(546).nextBytes(bundle);
        final var output = new ByteArrayOutputStream();
        final var result =
                new HostPrograms(Duration.ofSeconds(5), 32, Path::of)
                        .transfer(
                                List.of("/usr/bin/cat"),
                                new ByteArrayInputStream(bundle),
                                output,
                                Map.of());
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEmpty();
        assertThat(output.toByteArray()).containsExactly(bundle);
    }

    @Test
    void aBrokenBundleDestinationCannotBeReportedAsSuccess() {
        final OutputStream broken =
                new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        throw new IOException("fixture write failed");
                    }
                };
        assertThatThrownBy(
                        () ->
                                new HostPrograms(Duration.ofSeconds(2), 100, Path::of)
                                        .transfer(
                                                List.of("/usr/bin/cat"),
                                                new ByteArrayInputStream(new byte[1024]),
                                                broken,
                                                Map.of()))
                .isInstanceOf(IOException.class);
    }
}
