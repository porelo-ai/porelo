package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostProjectUnixTest {
    @Test
    void addRegistersThroughTheUnixSocketAndNeverUsesCentral(@TempDir Path root) throws Exception {
        // A short directory of its own: Linux's Unix socket path limit is 108 bytes, and the client
        // connects to the absolute path, which inside a deep checkout would exceed it.
        final Path directory = Files.createTempDirectory("ph");
        final Path socket = directory.resolve("http.sock");
        try (ProjectFixture host = new ProjectFixture(root);
                var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                var workers = Executors.newSingleThreadExecutor()) {
            server.bind(UnixDomainSocketAddress.of(socket));
            host.realSocket = true;
            host.socketOverride = socket;
            final var served =
                    workers.submit(
                            () -> {
                                try {
                                    while (!Thread.currentThread().isInterrupted()) {
                                        try (var channel = server.accept()) {
                                            final var input = Channels.newInputStream(channel);
                                            final String[] request = line(input).split(" ");
                                            int length = 0;
                                            String authorization = "";
                                            for (String header = line(input);
                                                    !header.isEmpty();
                                                    header = line(input)) {
                                                if (header.startsWith("Content-Length: ")) {
                                                    length = Integer.parseInt(header.substring(16));
                                                }
                                                if (header.startsWith("Authorization: ")) {
                                                    authorization = header.substring(15);
                                                }
                                            }
                                            final byte[] body = input.readNBytes(length);
                                            final byte[] auth =
                                                    authorization.getBytes(
                                                            StandardCharsets.US_ASCII);
                                            try {
                                                final var reply =
                                                        host.localExchange(
                                                                request[0],
                                                                request[1].substring(
                                                                        "/api/v1".length()),
                                                                auth,
                                                                body);
                                                final var output =
                                                        Channels.newOutputStream(channel);
                                                output.write(
                                                        ("HTTP/1.1 "
                                                                        + reply.status()
                                                                        + " OK\r\nContent-Length: "
                                                                        + reply.body().length
                                                                        + "\r\nConnection: close\r\n\r\n")
                                                                .getBytes(
                                                                        StandardCharsets.US_ASCII));
                                                output.write(reply.body());
                                                output.flush();
                                            } finally {
                                                Arrays.fill(body, (byte) 0);
                                                Arrays.fill(auth, (byte) 0);
                                            }
                                        }
                                    }
                                } catch (java.nio.channels.ClosedByInterruptException expected) {
                                    // Test shutdown interrupts the blocked accept after the command
                                    // finishes.
                                }
                                return null;
                            });
            try {
                host.add();
                assertThat(host.organizations).containsExactly("alpha");
                assertThat(host.centralCalls).isEmpty();
                assertThat(Files.exists(host.records.record("alpha"))).isTrue();
            } finally {
                workers.shutdownNow();
                assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                served.get(5, TimeUnit.SECONDS);
            }
        } finally {
            Files.deleteIfExists(socket);
            Files.delete(directory);
        }
    }

    private static String line(java.io.InputStream input) throws IOException {
        final var bytes = new ByteArrayOutputStream();
        for (int size = 0; size < 65536; size++) {
            final int character = input.read();
            if (character < 0) {
                throw new IOException("fixture HTTP ended early");
            }
            if (character == '\n') {
                return bytes.toString(StandardCharsets.US_ASCII).stripTrailing();
            }
            bytes.write(character);
        }
        throw new IOException("fixture header too large");
    }
}
