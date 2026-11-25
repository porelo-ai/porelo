package org.fuin.porelo.wire.varlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A method that ends with its caller: its thread is told when the connection that asked for it ends
 * while it runs, and only then.
 */
@Timeout(30)
class VarlinkCallerGoneTest {

    private static final String INTERFACE = "org.fuin.porelo.Clearance1";

    /** What the server said in its log. */
    private final List<String> said = new CopyOnWriteArrayList<>();

    /** Entered by the handler once it runs. */
    private final CountDownLatch entered = new CountDownLatch(1);

    /** Counted down once the handler was told, by an interrupt, to stop. */
    private final CountDownLatch told = new CountDownLatch(1);

    /** Lets a handler that was not told finish. */
    private final CountDownLatch release = new CountDownLatch(1);

    private VarlinkServer server(final Path dir) {
        final VarlinkServer server =
                new VarlinkServer(dir.resolve("v.sock"), INTERFACE).reportingTo(said::add);
        Thread.ofVirtual().start(server);
        return server;
    }

    /** Waits for the release, and says whether it was told to stop meanwhile. */
    private VarlinkServer.Method blocking() {
        return (parameters, replies) -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                told.countDown();
                throw new IllegalStateException("stopped", ex);
            }
            replies.last(Map.of("done", Boolean.TRUE));
        };
    }

    private static SocketChannel call(final Path socket, final String method) throws Exception {
        final SocketChannel raw = SocketChannel.open(UnixDomainSocketAddress.of(socket));
        send(raw, method);
        return raw;
    }

    private static void send(final SocketChannel raw, final String method) throws Exception {
        raw.write(
                ByteBuffer.wrap(
                        ("{\"method\":\"" + INTERFACE + "." + method + "\"}\0")
                                .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aBlockingMethodIsToldWhenItsCallerCloses(@TempDir Path dir) throws Exception {

        // A review nobody waits for any more went on reading git to its end.
        try (VarlinkServer server = server(dir)) {
            server.methodEndingWithItsCaller("Slow", blocking());
            try (SocketChannel raw = call(server.socketPath(), "Slow")) {
                assertThat(entered.await(5, TimeUnit.SECONDS)).as("the call runs").isTrue();
            }
            assertThat(told.await(2, TimeUnit.SECONDS))
                    .as("the call was told its caller went")
                    .isTrue();
            final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (said.isEmpty() && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertThat(said)
                    .as("one line says the caller went, none a failure")
                    .singleElement()
                    .satisfies(
                            line ->
                                    assertThat(line)
                                            .contains(INTERFACE + ".Slow", "caller went")
                                            .doesNotContain("failed"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void aBlockingMethodWhoseCallerStaysIsNotToldAndAnswersAsBefore(@TempDir Path dir)
            throws Exception {

        try (VarlinkServer server = server(dir)) {
            server.methodEndingWithItsCaller("Slow", blocking());
            server.method("Ping", (parameters, replies) -> replies.last(Map.of("pong", true)));
            try (SocketChannel raw = call(server.socketPath(), "Slow")) {
                assertThat(entered.await(5, TimeUnit.SECONDS)).as("the call runs").isTrue();
                // The next call, sent while the first runs, is read and kept, never lost.
                send(raw, "Ping");
                assertThat(told.await(500, TimeUnit.MILLISECONDS))
                        .as("a caller that is still there is not said to have gone")
                        .isFalse();
                release.countDown();
                final VarlinkConnection connection = new VarlinkConnection(raw);
                assertThat(connection.receive())
                        .as("the call answers exactly as before")
                        .isEqualTo(Map.of("parameters", Map.of("done", true)));
                assertThat(connection.receive())
                        .as("the call sent meanwhile is answered after it")
                        .isEqualTo(Map.of("parameters", Map.of("pong", true)));
                send(raw, "Ping");
                assertThat(connection.receive())
                        .as("the connection serves further calls")
                        .isEqualTo(Map.of("parameters", Map.of("pong", true)));
            }
            assertThat(said).as("nothing is said of a call that finished").isEmpty();
        }
    }

    @Test
    void aCallerThatLeavesRightAfterItsAnswerIsNotSaidToHaveGone(@TempDir Path dir)
            throws Exception {

        // A caller that read its answer and closed while the method was still returning was taken
        // for one that went during the call: told, logged and its connection ended.
        try (VarlinkServer server = server(dir)) {
            server.methodEndingWithItsCaller(
                    "Quick",
                    (parameters, replies) -> {
                        replies.last(Map.of("done", Boolean.TRUE));
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            told.countDown();
                        }
                        entered.countDown();
                    });
            try (SocketChannel raw = call(server.socketPath(), "Quick")) {
                assertThat(new VarlinkConnection(raw).receive())
                        .as("the call answers")
                        .isEqualTo(Map.of("parameters", Map.of("done", true)));
            }
            assertThat(told.await(1, TimeUnit.SECONDS))
                    .as("a call that has answered is not told its caller went")
                    .isFalse();
            release.countDown();
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("the method returned").isTrue();
            Thread.sleep(200);
            assertThat(said).as("nothing is said of a call that answered").isEmpty();
        } finally {
            release.countDown();
        }
    }

    @Test
    void watchedCallsSentTogetherShareOneReaderOfTheConnection(@TempDir Path dir) throws Exception {

        // A second watch started while the first was still in its read had two threads read one
        // socket, whose parts could then be kept out of order.
        final List<Long> readers = new CopyOnWriteArrayList<>();
        try (VarlinkServer server = server(dir)) {
            server.methodEndingWithItsCaller(
                    "Count",
                    (parameters, replies) -> {
                        readers.add(
                                Thread.getAllStackTraces().keySet().stream()
                                        .filter(t -> t.getName().equals("varlink-caller-watch"))
                                        .count());
                        replies.last(Map.of("n", readers.size()));
                    });
            try (SocketChannel raw =
                    SocketChannel.open(UnixDomainSocketAddress.of(server.socketPath()))) {
                final String one = "{\"method\":\"" + INTERFACE + ".Count\"}\0";
                raw.write(ByteBuffer.wrap((one + one).getBytes(StandardCharsets.UTF_8)));
                final VarlinkConnection connection = new VarlinkConnection(raw);
                assertThat(connection.receive())
                        .as("the first call answers")
                        .isEqualTo(Map.of("parameters", Map.of("n", 1L)));
                assertThat(connection.receive())
                        .as("the second call answers after it")
                        .isEqualTo(Map.of("parameters", Map.of("n", 2L)));
            }
            assertThat(readers)
                    .as("the second call's watch takes on the first one's reader")
                    .hasSize(2)
                    .satisfies(counts -> assertThat(counts.get(1)).isEqualTo(counts.get(0)));
        }
    }

    @Test
    void aMethodNotEndingWithItsCallerIsNotTold(@TempDir Path dir) throws Exception {

        // Every other method runs to its end as before: one a script asked through a bridge that
        // ends its half when its input ends is still answered.
        final AtomicBoolean finished = new AtomicBoolean();
        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Slow",
                    (parameters, replies) -> {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException ex) {
                            told.countDown();
                            Thread.currentThread().interrupt();
                            return;
                        }
                        finished.set(true);
                        replies.last(Map.of());
                    });
            try (SocketChannel raw = call(server.socketPath(), "Slow")) {
                assertThat(entered.await(5, TimeUnit.SECONDS)).as("the call runs").isTrue();
            }
            assertThat(told.await(500, TimeUnit.MILLISECONDS)).as("it is not told").isFalse();
            release.countDown();
            final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!finished.get() && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertThat(finished).as("it ran to its end").isTrue();
        }
    }

    private static final String SECOND = "org.fuin.porelo.Review1";

    private static SocketChannel callFull(final Path socket, final String fullName)
            throws Exception {
        final SocketChannel raw = SocketChannel.open(UnixDomainSocketAddress.of(socket));
        raw.write(
                ByteBuffer.wrap(
                        ("{\"method\":\"" + fullName + "\"}\0").getBytes(StandardCharsets.UTF_8)));
        return raw;
    }

    @Test
    void aFurtherInterfacesMethodEndingWithItsCallerIsToldWhenItsCallerCloses(@TempDir Path dir)
            throws Exception {

        try (VarlinkServer server = server(dir)) {
            server.serving(SECOND, "interface " + SECOND + "\n")
                    .method(SECOND, "Slow", (parameters, replies) -> replies.last(Map.of()))
                    .methodEndingWithItsCaller(SECOND, "Slow", blocking());
            try (SocketChannel raw = callFull(server.socketPath(), SECOND + ".Slow")) {
                assertThat(entered.await(5, TimeUnit.SECONDS)).as("the call runs").isTrue();
            }
            assertThat(told.await(2, TimeUnit.SECONDS))
                    .as("the further interface's call was told its caller went")
                    .isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void aFurtherInterfacesMethodRegisteredAgainPlainlyNoLongerEndsWithItsCaller(@TempDir Path dir)
            throws Exception {

        final AtomicBoolean finished = new AtomicBoolean();
        try (VarlinkServer server = server(dir)) {
            server.serving(SECOND, "interface " + SECOND + "\n")
                    .methodEndingWithItsCaller(SECOND, "Slow", blocking())
                    .method(
                            SECOND,
                            "Slow",
                            (parameters, replies) -> {
                                entered.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException ex) {
                                    told.countDown();
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                finished.set(true);
                                replies.last(Map.of());
                            });
            try (SocketChannel raw = callFull(server.socketPath(), SECOND + ".Slow")) {
                assertThat(entered.await(5, TimeUnit.SECONDS)).as("the call runs").isTrue();
            }
            assertThat(told.await(500, TimeUnit.MILLISECONDS)).as("it is not told").isFalse();
            release.countDown();
            final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!finished.get() && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertThat(finished).as("it ran to its end").isTrue();
        }
    }
}
