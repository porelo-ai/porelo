package org.fuin.porelo.host;

import java.io.IOException;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** A repeatable HTTP load through an installed gate, including its socket activation cost. */
final class ForgeMeasure {
    private ForgeMeasure() {}

    @FunctionalInterface
    interface Connect {
        SocketChannel open(Path socket) throws IOException;
    }

    static Map<String, Object> run(
            Path socket, String host, String path, int requests, int concurrency, int perConnection)
            throws Exception {
        return run(
                socket,
                host,
                path,
                requests,
                concurrency,
                perConnection,
                target -> {
                    final var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                    try {
                        channel.connect(UnixDomainSocketAddress.of(target));
                        return channel;
                    } catch (IOException | RuntimeException ex) {
                        channel.close();
                        throw ex;
                    }
                });
    }

    static Map<String, Object> run(
            Path socket,
            String host,
            String path,
            int requests,
            int concurrency,
            int perConnection,
            Connect connect)
            throws Exception {
        if (requests < 1
                || requests > 1_000_000
                || concurrency < 1
                || concurrency > 256
                || perConnection < 1
                || perConnection > 10000
                || !host.matches("[A-Za-z0-9.:-]+")
                || !path.startsWith("/")
                || path.startsWith("//")
                || path.chars().anyMatch(value -> value <= 32 || value == 127))
            throw new HostRefusal(64, "invalid gate measurement parameters");
        final AtomicInteger next = new AtomicInteger();
        final LongAdder connections = new LongAdder();
        final LongAdder failures = new LongAdder();
        final long started = System.nanoTime();
        final List<Long> latencies = new ArrayList<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<List<Long>>> results = new ArrayList<>();
            for (int worker = 0; worker < concurrency; worker++)
                results.add(
                        workers.submit(
                                () -> {
                                    final List<Long> samples = new ArrayList<>();
                                    while (next.getAndIncrement() < requests) {
                                        final SocketChannel channel = connect.open(socket);
                                        try (channel) {
                                            connections.increment();
                                            try (var connection = new GateConnection(channel)) {
                                                for (int count = 0;
                                                        count < perConnection;
                                                        count++) {
                                                    final long begin = System.nanoTime();
                                                    try {
                                                        GateHttp.write(
                                                                connection.output,
                                                                "GET "
                                                                        + path
                                                                        + " HTTP/1.1\r\nHost: "
                                                                        + host
                                                                        + "\r\n\r\n");
                                                        final var response =
                                                                GateHttp.response(connection.input);
                                                        if (response.status() != 200)
                                                            failures.increment();
                                                        final var lengths =
                                                                response.headers()
                                                                        .all("Content-Length");
                                                        if (response.headers()
                                                                .first("Transfer-Encoding")
                                                                .equalsIgnoreCase("chunked"))
                                                            GateHttp.chunks(
                                                                    connection.input,
                                                                    OutputStream.nullOutputStream(),
                                                                    false,
                                                                    true);
                                                        else if (lengths.size() == 1
                                                                && lengths.getFirst()
                                                                        .matches("[0-9]{1,18}"))
                                                            GateHttp.fixed(
                                                                    connection.input,
                                                                    OutputStream.nullOutputStream(),
                                                                    Long.parseLong(
                                                                            lengths.getFirst()));
                                                        else
                                                            connection.input.transferTo(
                                                                    OutputStream
                                                                            .nullOutputStream());
                                                        samples.add(System.nanoTime() - begin);
                                                        if (!response.headers()
                                                                .first("Connection")
                                                                .equalsIgnoreCase("keep-alive"))
                                                            break;
                                                        if (count + 1 < perConnection
                                                                && next.getAndIncrement()
                                                                        >= requests) break;
                                                    } catch (IOException ex) {
                                                        failures.increment();
                                                        samples.add(System.nanoTime() - begin);
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    return samples;
                                }));
            for (var result : results) latencies.addAll(result.get());
        }
        final double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        latencies.sort(Long::compare);
        return Map.of(
                "requests",
                latencies.size(),
                "connections",
                connections.sum(),
                "failures",
                failures.sum(),
                "seconds",
                seconds,
                "requests_per_second",
                latencies.size() / seconds,
                "p50_ms",
                percentile(latencies, 50),
                "p95_ms",
                percentile(latencies, 95),
                "concurrency",
                concurrency,
                "per_connection",
                perConnection);
    }

    private static double percentile(List<Long> samples, int percentile) {
        return samples.isEmpty()
                ? 0
                : samples.get(Math.min(samples.size() - 1, (samples.size() * percentile) / 100))
                        / 1_000_000.0;
    }
}
