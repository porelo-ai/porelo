package org.fuin.porelo.wire.varlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the varlink client and server, talking to each other.
 *
 * <p>Conformance against an independent implementation is checked separately by {@link
 * VarlinkInteropTest}, which drives systemd's {@code varlinkctl}. Two halves written by the same
 * person agreeing with each other proves only that they are consistent.
 */
class VarlinkTest {

    private static final String INTERFACE = "org.fuin.porelo.Clearance1";

    private VarlinkServer server(Path dir) {
        final VarlinkServer server = new VarlinkServer(dir.resolve("v.sock"), INTERFACE);
        Thread.ofVirtual().start(server);
        return server;
    }

    @Test
    void callsAMethodAndGetsAReply(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Ping", (parameters, replies) -> replies.last(Map.of("pong", Boolean.TRUE)));

            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThat(client.call(INTERFACE + ".Ping", Map.of()))
                        .containsEntry("pong", Boolean.TRUE);
            }
        }
    }

    @Test
    void passesParametersThrough(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Echo",
                    (parameters, replies) -> replies.last(Map.of("got", parameters.get("say"))));

            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThat(client.call(INTERFACE + ".Echo", Map.of("say", "hello")))
                        .containsEntry("got", "hello");
            }
        }
    }

    @Test
    void streamsWithMore(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Stream",
                    (parameters, replies) -> {
                        for (int i = 1; i <= 3; i++) {
                            replies.more(Map.of("seq", Integer.valueOf(i)));
                        }
                        replies.last(Map.of("seq", Integer.valueOf(4)));
                    });

            final List<Object> seen = new ArrayList<>();
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                client.callMore(
                        INTERFACE + ".Stream",
                        Map.of(),
                        reply -> {
                            seen.add(reply.get("seq"));
                            return true;
                        });
            }
            assertThat(seen).hasSize(4);
        }
    }

    @Test
    void stopsWhenTheConsumerAsksTo(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Stream",
                    (parameters, replies) -> {
                        for (int i = 0; i < 100; i++) {
                            replies.more(Map.of("seq", Integer.valueOf(i)));
                        }
                        replies.last(Map.of("seq", Integer.valueOf(100)));
                    });

            final AtomicInteger seen = new AtomicInteger();
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                client.callMore(
                        INTERFACE + ".Stream", Map.of(), reply -> seen.incrementAndGet() < 3);
            }
            assertThat(seen).hasValue(3);
        }
    }

    @Test
    void endsTheStreamWhenContinuesIsAbsent(@TempDir Path dir) throws IOException {

        // The last reply omits 'continues'. Treating its absence as "keep waiting" is how a client
        // hangs at the end of a perfectly good stream.
        try (VarlinkServer server = server(dir)) {
            server.method(
                    "One", (parameters, replies) -> replies.last(Map.of("done", Boolean.TRUE)));

            final AtomicInteger seen = new AtomicInteger();
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                client.callMore(
                        INTERFACE + ".One",
                        Map.of(),
                        reply -> {
                            seen.incrementAndGet();
                            return true;
                        });
            }
            assertThat(seen).hasValue(1);
        }
    }

    @Test
    void aStreamCutShortIsAnErrorRatherThanAnEnding(@TempDir Path dir) throws Exception {

        // Measured against the real daemon: killing it mid-stream closes the socket with no final
        // reply, and returning normally there is indistinguishable from a stream that finished. A
        // client would stop watching and go on showing what it last saw - which for a fleet view,
        // or a clearance prompt that expires, is worse than an error.
        //
        // Written against a bare socket rather than VarlinkServer, because what is being modeled is
        // a service that dies: one reply, then the connection ends mid-stream. Closing the server
        // object would not do it - the accepted connection stays open and the client waits for
        // ever, which is how the first version of this test hung.
        final Path socket = dir.resolve("dying.sock");
        try (java.nio.channels.ServerSocketChannel channel =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            channel.bind(java.net.UnixDomainSocketAddress.of(socket));
            final Thread service =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (java.nio.channels.SocketChannel client =
                                                channel.accept()) {
                                            final java.nio.ByteBuffer in =
                                                    java.nio.ByteBuffer.allocate(4096);
                                            client.read(in);
                                            client.write(
                                                    java.nio.ByteBuffer.wrap(
                                                            ("{\"parameters\":{\"n\":1},\"continues\":true}\0")
                                                                    .getBytes(
                                                                            java.nio.charset
                                                                                    .StandardCharsets
                                                                                    .UTF_8)));
                                        } catch (IOException ex) {
                                            // The client went first, which this test does not care
                                            // about.
                                        }
                                    });

            try (VarlinkClient client = new VarlinkClient(socket)) {
                final List<Map<String, Object>> seen = new ArrayList<>();
                assertThatThrownBy(
                                () ->
                                        client.callMore(
                                                INTERFACE + ".Endless",
                                                Map.of(),
                                                reply -> {
                                                    seen.add(reply);
                                                    return true;
                                                }))
                        .isInstanceOf(VarlinkException.class)
                        .hasMessageContaining("before it ended");
                assertThat(seen).as("what did arrive is still delivered").hasSize(1);
            }
            service.join(java.time.Duration.ofSeconds(5));
        }
    }

    /**
     * A service that accepts, reads the call and never answers, until the test lets it go.
     *
     * @param socket Where it listens.
     * @param released Counted down by the test once it is done.
     * @return The bound channel, closed by the test.
     */
    private static java.nio.channels.ServerSocketChannel silent(
            final Path socket, final java.util.concurrent.CountDownLatch released)
            throws IOException {
        final java.nio.channels.ServerSocketChannel channel =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
        channel.bind(java.net.UnixDomainSocketAddress.of(socket));
        Thread.ofPlatform()
                .daemon()
                .start(
                        () -> {
                            try (java.nio.channels.SocketChannel client = channel.accept()) {
                                client.read(java.nio.ByteBuffer.allocate(4096));
                                released.await();
                            } catch (IOException | InterruptedException ex) {
                                // The test is over.
                            }
                        });
        return channel;
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void aCallToAServiceThatNeverAnswersEndsAtItsDeadlineNamingTheMethod(@TempDir Path dir)
            throws Exception {

        // The read had no limit: a daemon that accepted and never answered held every caller, a
        // task start and an editor's gate calls among them.
        final java.util.concurrent.CountDownLatch released =
                new java.util.concurrent.CountDownLatch(1);
        try (var service = silent(dir.resolve("silent.sock"), released);
                VarlinkClient client = new VarlinkClient(dir.resolve("silent.sock"))) {
            final long started = System.nanoTime();

            assertThatThrownBy(
                            () ->
                                    client.call(
                                            INTERFACE + ".Silent",
                                            Map.of(),
                                            java.time.Duration.ofMillis(200)))
                    .isInstanceOfSatisfying(
                            VarlinkTimeoutException.class,
                            timeout -> {
                                assertThat(timeout.getMethod())
                                        .as("the method that got no answer")
                                        .isEqualTo(INTERFACE + ".Silent");
                                assertThat(timeout.getErrorName())
                                        .as("no name, since the peer sent none")
                                        .isNull();
                            })
                    .hasMessageContaining(INTERFACE + ".Silent")
                    .hasMessageContaining("200 ms");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .as("ended at its deadline, not long after")
                    .isLessThan(java.time.Duration.ofSeconds(1));
            assertThatThrownBy(() -> client.call(INTERFACE + ".Next", Map.of()))
                    .as("a late answer is never read as the next call's: the connection is closed")
                    .isInstanceOf(VarlinkException.class);
        } finally {
            released.countDown();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void aStreamWithoutADeadlineKeepsWaitingAndOneWithADeadlineEnds(@TempDir Path dir)
            throws Exception {

        // A stream may rightly be quiet for long - a clearance subscription until a packet is
        // dropped - so only a caller that asks for a bound gets one.
        final java.util.concurrent.CountDownLatch released =
                new java.util.concurrent.CountDownLatch(1);
        try (var service = silent(dir.resolve("quiet.sock"), released);
                VarlinkClient client = new VarlinkClient(dir.resolve("quiet.sock"))) {
            final Thread waiting =
                    Thread.ofPlatform()
                            .daemon()
                            .start(
                                    () -> {
                                        try {
                                            client.callMore(
                                                    INTERFACE + ".Quiet", Map.of(), reply -> true);
                                        } catch (RuntimeException ex) {
                                            // Ended by the close below.
                                        }
                                    });

            assertThat(waiting.join(java.time.Duration.ofMillis(800)))
                    .as("a stream without a deadline is still waiting")
                    .isFalse();
            client.close();
            waiting.join(java.time.Duration.ofSeconds(5));
        } finally {
            released.countDown();
        }

        final java.util.concurrent.CountDownLatch again =
                new java.util.concurrent.CountDownLatch(1);
        try (var service = silent(dir.resolve("bounded.sock"), again);
                VarlinkClient client = new VarlinkClient(dir.resolve("bounded.sock"))) {
            assertThatThrownBy(
                            () ->
                                    client.callMore(
                                            INTERFACE + ".Quiet",
                                            Map.of(),
                                            reply -> true,
                                            java.time.Duration.ofMillis(200)))
                    .isInstanceOf(VarlinkTimeoutException.class)
                    .hasMessageContaining(INTERFACE + ".Quiet");
        } finally {
            again.countDown();
        }
    }

    /**
     * A service that accepts, reads the call, and answers it only once the test lets it.
     *
     * @param socket Where it listens.
     * @param released Counted down by the test when the late answer may go.
     * @return The bound channel, closed by the test.
     */
    private static java.nio.channels.ServerSocketChannel late(
            final Path socket, final java.util.concurrent.CountDownLatch released)
            throws IOException {
        final java.nio.channels.ServerSocketChannel channel =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
        channel.bind(java.net.UnixDomainSocketAddress.of(socket));
        Thread.ofPlatform()
                .daemon()
                .start(
                        () -> {
                            try (java.nio.channels.SocketChannel client = channel.accept()) {
                                client.read(java.nio.ByteBuffer.allocate(4096));
                                released.await();
                                client.write(
                                        java.nio.ByteBuffer.wrap(
                                                "{\"parameters\":{\"late\":true}}\0"
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)));
                                client.read(java.nio.ByteBuffer.allocate(4096));
                            } catch (IOException | InterruptedException ex) {
                                // The test is over.
                            }
                        });
        return channel;
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void anInterruptedCallClosesTheConnectionSoItsLateAnswerIsNoOtherCallsAnswer(@TempDir Path dir)
            throws Exception {

        // The interrupt left the connection open: the abandoned call's answer, arriving later,
        // was read as the next call's.
        final java.util.concurrent.CountDownLatch released =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<@Nullable Object> first =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<@Nullable Object> next =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (var service = late(dir.resolve("late.sock"), released);
                VarlinkClient client = new VarlinkClient(dir.resolve("late.sock"))) {
            final Thread caller =
                    Thread.ofPlatform()
                            .daemon()
                            .start(
                                    () -> {
                                        try {
                                            first.set(
                                                    client.call(
                                                            INTERFACE + ".Slow",
                                                            Map.of(),
                                                            java.time.Duration.ofSeconds(5)));
                                        } catch (RuntimeException ex) {
                                            first.set(ex);
                                        }
                                        Thread.interrupted();
                                        released.countDown();
                                        try {
                                            Thread.sleep(200);
                                            next.set(
                                                    client.call(
                                                            INTERFACE + ".Next",
                                                            Map.of(),
                                                            java.time.Duration.ofSeconds(2)));
                                        } catch (RuntimeException | InterruptedException ex) {
                                            next.set(ex);
                                        }
                                    });
            Thread.sleep(300);
            caller.interrupt();
            assertThat(caller.join(java.time.Duration.ofSeconds(8))).isTrue();

            assertThat(first.get())
                    .as("the interrupted call ends as a failed call")
                    .isInstanceOf(VarlinkException.class);
            assertThat(next.get())
                    .as("the next call never reads the interrupted call's answer")
                    .isInstanceOf(VarlinkException.class);
        } finally {
            released.countDown();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void aConnectToASocketThatAcceptsNoMoreEndsAtItsConnectDeadline(@TempDir Path dir)
            throws Exception {

        // A listening socket whose backlog is full holds a blocking connect for as long as nobody
        // accepts: the doctor's daemon probe and an agent's Find waited there without end.
        final Path socket = dir.resolve("full.sock");
        final List<VarlinkClient> held = new ArrayList<>();
        try (java.nio.channels.ServerSocketChannel full =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            full.bind(java.net.UnixDomainSocketAddress.of(socket), 1);
            VarlinkException refused = null;
            long took = 0;
            for (int i = 0; i < 10 && refused == null; i++) {
                final long started = System.nanoTime();
                try {
                    held.add(new VarlinkClient(socket, java.time.Duration.ofMillis(200)));
                } catch (VarlinkException ex) {
                    refused = ex;
                    took = System.nanoTime() - started;
                }
            }

            assertThat(refused)
                    .as("a connect nobody takes ends")
                    .isNotNull()
                    .hasMessageContaining(socket.toString());
            assertThat(java.time.Duration.ofNanos(took))
                    .as("at its connect deadline, not long after")
                    .isLessThan(java.time.Duration.ofSeconds(2));
        } finally {
            for (VarlinkClient client : held) {
                client.close();
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void anAnswerWithinTheDeadlineIsReadAndTheConnectionServesTheNextCall(@TempDir Path dir)
            throws IOException {

        // The bounded read switches the channel to non-blocking for its wait; a channel left that
        // way would make the next send or unbounded read fail.
        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Ping", (parameters, replies) -> replies.last(Map.of("pong", Boolean.TRUE)));

            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThat(
                                client.call(
                                        INTERFACE + ".Ping",
                                        Map.of(),
                                        java.time.Duration.ofSeconds(5)))
                        .containsEntry("pong", Boolean.TRUE);
                final List<Object> seen = new ArrayList<>();
                client.callMore(
                        INTERFACE + ".Ping",
                        Map.of(),
                        reply -> seen.add(reply.get("pong")),
                        java.time.Duration.ofSeconds(5));
                assertThat(seen).containsExactly(Boolean.TRUE);
                assertThat(client.call(INTERFACE + ".Ping", Map.of()))
                        .as("the connection serves another call after the bounded ones")
                        .containsEntry("pong", Boolean.TRUE);
            }
        }
    }

    @Test
    void reportsAnUnknownMethodAsAVarlinkError(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {

                assertThatThrownBy(() -> client.call(INTERFACE + ".Nope", Map.of()))
                        .isInstanceOf(VarlinkException.class)
                        .hasMessageContaining("MethodNotFound");
            }
        }
    }

    @Test
    void turnsAMethodFailureIntoAnErrorReplyRatherThanDroppingTheConnection(@TempDir Path dir)
            throws IOException {

        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Boom",
                    (parameters, replies) -> {
                        throw new IllegalStateException("deliberate");
                    });
            server.method(
                    "Ping", (parameters, replies) -> replies.last(Map.of("pong", Boolean.TRUE)));

            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThatThrownBy(() -> client.call(INTERFACE + ".Boom", Map.of()))
                        .isInstanceOf(VarlinkException.class)
                        .hasMessageContaining("deliberate");

                // The connection must survive, or one bad call costs every later one.
                assertThat(client.call(INTERFACE + ".Ping", Map.of()))
                        .containsEntry("pong", Boolean.TRUE);
            }
        }
    }

    @Test
    void answersGetInfoWithoutBeingTold(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {

                final Map<String, Object> info =
                        client.call("org.varlink.service.GetInfo", Map.of());

                assertThat(info).containsEntry("product", "Porelo");
                assertThat(((List<?>) info.get("interfaces")).contains(INTERFACE)).isTrue();
            }
        }
    }

    @Test
    void createsTheSocketWithOwnerOnlyPermissions(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            assertThat(java.nio.file.Files.getPosixFilePermissions(server.socketPath()))
                    .containsExactlyInAnyOrder(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        }
    }

    @Test
    void removesTheSocketWhenItStops(@TempDir Path dir) {

        final Path socket;
        try (VarlinkServer server = server(dir)) {
            socket = server.socketPath();
            assertThat(socket).exists();
        }
        assertThat(socket).doesNotExist();
    }

    @Test
    void aStreamThatWaitsForNewsEndsWhenItsClientHasGone(@TempDir Path dir) throws Exception {

        // It noticed only when it next sent: a client that reconnected left its old streams asking
        // podman every half second for the daemon's life.
        try (VarlinkServer server = server(dir)) {
            final java.util.concurrent.CountDownLatch ended =
                    new java.util.concurrent.CountDownLatch(1);
            server.method(
                    "Watch",
                    (parameters, replies) -> {
                        replies.more(Map.of("first", Boolean.TRUE));
                        try {
                            while (replies.open()) {
                                Thread.sleep(20);
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } finally {
                            ended.countDown();
                        }
                    });

            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                client.callMore(INTERFACE + ".Watch", Map.of(), reply -> false);
            }

            assertThat(ended.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the stream ended")
                    .isTrue();
        }
    }

    @Test
    void aClientStillListeningKeepsItsStreamOpen(@TempDir Path dir) throws Exception {
        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Watch",
                    (parameters, replies) -> {
                        for (int i = 0; i < 3; i++) {
                            replies.more(Map.of("open", replies.open()));
                        }
                        replies.last(Map.of("open", replies.open()));
                    });

            final java.util.List<Object> said = new java.util.ArrayList<>();
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                client.callMore(
                        INTERFACE + ".Watch", Map.of(), reply -> said.add(reply.get("open")));
            }
            assertThat(said).containsOnly(Boolean.TRUE).hasSize(4);
        }
    }

    @Test
    void aClientThatSendsWithoutEndDuringAStreamIsTreatedAsGoneNotBufferedForEver(@TempDir Path dir)
            throws Exception {

        // Asked whether it was still there, the connection kept whatever the client sent - without
        // the limit a message has - so a stream polling for news grew without bound.
        try (VarlinkServer server = server(dir)) {
            final java.util.concurrent.CountDownLatch ended =
                    new java.util.concurrent.CountDownLatch(1);
            server.method(
                    "Watch",
                    (parameters, replies) -> {
                        replies.more(Map.of("first", Boolean.TRUE));
                        try {
                            while (replies.open()) {
                                Thread.sleep(5);
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } finally {
                            ended.countDown();
                        }
                    });
            try (java.nio.channels.SocketChannel raw =
                    java.nio.channels.SocketChannel.open(
                            java.net.UnixDomainSocketAddress.of(server.socketPath()))) {
                raw.write(
                        java.nio.ByteBuffer.wrap(
                                ("{\"method\":\"" + INTERFACE + ".Watch\",\"more\":true}\0")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                final byte[] noEnd = new byte[1024 * 1024];
                java.util.Arrays.fill(noEnd, (byte) 'x');
                for (int i = 0; i < 12 && ended.getCount() > 0; i++) {
                    raw.write(java.nio.ByteBuffer.wrap(noEnd));
                }
                assertThat(ended.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        .as("the stream ended")
                        .isTrue();
            }
        }
    }

    @Test
    void aClientThatHasSaidAllItWillSayStillHearsWhatIsOpenNow(@TempDir Path dir) throws Exception {

        // 'porelo daemon connect' ends its half when its input ends: the stream read that end as
        // the client gone and ended before saying anything, so a refused start's question reached
        // nobody who asked from a script.
        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Watch",
                    (parameters, replies) -> {
                        while (replies.open()) {
                            replies.more(Map.of("now", "open"));
                            try {
                                Thread.sleep(20);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    });
            try (java.nio.channels.SocketChannel raw =
                    java.nio.channels.SocketChannel.open(
                            java.net.UnixDomainSocketAddress.of(server.socketPath()))) {
                raw.write(
                        java.nio.ByteBuffer.wrap(
                                ("{\"method\":\"" + INTERFACE + ".Watch\",\"more\":true}\0")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                raw.shutdownOutput();
                final java.nio.ByteBuffer heard = java.nio.ByteBuffer.allocate(4096);
                final long until =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (raw.read(heard) >= 0 && heard.position() == 0 && System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                assertThat(
                                new String(
                                        heard.array(),
                                        0,
                                        heard.position(),
                                        java.nio.charset.StandardCharsets.UTF_8))
                        .contains("\"now\":\"open\"");
            }
        }
    }

    @Test
    void aCallIsServedOnAThreadOfItsOwnAndNotOnAVirtualOne(@TempDir Path dir) throws Exception {

        // An agent that answered in a tenth of a second sometimes never answered at all, in the
        // native binary under a suite's load: the one wait left on a virtual thread was this one, a
        // blocking read of the call.
        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Where",
                    (parameters, replies) ->
                            replies.last(Map.of("virtual", Thread.currentThread().isVirtual())));
            Thread.ofPlatform().daemon().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThat(client.call(INTERFACE + ".Where", Map.of()).get("virtual"))
                        .isEqualTo(Boolean.FALSE);
            }
        }
    }

    @Test
    void aWholeNumberInAReplyKeepsEveryDigit(@TempDir Path dir) throws IOException {

        // 2^53 + 1 is the first whole number a double cannot hold: read as one, it arrives as 2^53.
        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Count",
                    (parameters, replies) ->
                            replies.last(Map.of("n", Long.valueOf(9_007_199_254_740_993L))));

            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Object n = client.call(INTERFACE + ".Count", Map.of()).get("n");
                assertThat(n).isInstanceOf(Number.class);
                assertThat(new java.math.BigDecimal(String.valueOf(n)))
                        .isEqualByComparingTo("9007199254740993");
            }
        }
    }

    @Test
    void aWholeNumberPassedOnIsWrittenAsTheWholeNumberItWas(@TempDir Path dir) throws IOException {

        // A reply handed on unchanged, as porelo-mcp does, is written again: 1024 must not leave
        // as 1024.0.
        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Size",
                    (parameters, replies) -> replies.last(Map.of("n", Integer.valueOf(1024))));

            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Map<String, Object> reply = client.call(INTERFACE + ".Size", Map.of());
                assertThat(org.fuin.porelo.wire.Json.write(reply)).isEqualTo("{\"n\":1024}");
            }
        }
    }

    @Test
    void aServerReadsAWholeNumberParameterAsTheWholeNumberSent(@TempDir Path dir)
            throws IOException {

        try (VarlinkServer server = server(dir)) {
            server.method(
                    "Echo",
                    (parameters, replies) ->
                            replies.last(Map.of("text", String.valueOf(parameters.get("n")))));

            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThat(client.call(INTERFACE + ".Echo", Map.of("n", Integer.valueOf(1024))))
                        .containsEntry("text", "1024");
            }
        }
    }
}
