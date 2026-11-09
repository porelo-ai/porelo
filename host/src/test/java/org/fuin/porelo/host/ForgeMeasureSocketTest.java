package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForgeMeasureSocketTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theMeasureCountsRequestsAndTheConnectionsTheyActuallyUse(boolean keep) throws Exception {
        final Path socket = Path.of("target", "measure.sock");
        try (var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            final FutureTask<Void> accepting =
                    new FutureTask<>(
                            () -> {
                                try {
                                    while (listener.isOpen()) {
                                        final var peer = listener.accept();
                                        clients.submit(
                                                () -> {
                                                    try (var connection =
                                                            new GateConnection(peer)) {
                                                        while (GateHttp.request(connection.input)
                                                                != null) {
                                                            GateHttp.write(
                                                                    connection.output,
                                                                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: "
                                                                            + (keep
                                                                                    ? "keep-alive"
                                                                                    : "close")
                                                                            + "\r\n\r\nok");
                                                            if (!keep) break;
                                                        }
                                                    } catch (IOException ex) {
                                                        throw new java.io.UncheckedIOException(ex);
                                                    }
                                                });
                                    }
                                } catch (java.nio.channels.AsynchronousCloseException ex) {
                                    // The measurement has finished and closes its own listener.
                                }
                                return null;
                            });
            Thread.ofPlatform().daemon().start(accepting);
            final var result = ForgeMeasure.run(socket, "host.localhost:3000", "/", 24, 1, 8);
            assertThat(result.get("requests"))
                    .as("every requested exchange has a sample")
                    .isEqualTo(24);
            assertThat(result.get("failures"))
                    .as("the stand-in answered every request successfully")
                    .isEqualTo(0L);
            assertThat(result.get("connections"))
                    .as("the same measure distinguishes current Python close from Java keep-alive")
                    .isEqualTo(keep ? 3L : 24L);
            listener.close();
            accepting.get(5, TimeUnit.SECONDS);
        } finally {
            Files.deleteIfExists(socket);
        }
    }
}
