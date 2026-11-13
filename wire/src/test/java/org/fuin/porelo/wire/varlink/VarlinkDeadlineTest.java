package org.fuin.porelo.wire.varlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for a varlink call's deadline, from its first byte sent to its answer, and its size cap.
 */
class VarlinkDeadlineTest {

    private static final String INTERFACE = "org.fuin.porelo.Clearance1";

    /** What a stand-in peer does with the one connection it accepts. */
    @FunctionalInterface
    private interface Peer {
        void serve(SocketChannel client) throws IOException, InterruptedException;
    }

    /**
     * A peer that accepts one connection and runs a given part with it, until the test is over.
     *
     * @param socket Where it listens.
     * @param peer What it does with the connection.
     * @return The bound channel, closed by the test.
     */
    private static ServerSocketChannel peer(final Path socket, final Peer peer) throws IOException {
        final ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        channel.bind(UnixDomainSocketAddress.of(socket));
        Thread.ofPlatform()
                .daemon()
                .start(
                        () -> {
                            try (SocketChannel client = channel.accept()) {
                                peer.serve(client);
                            } catch (IOException | InterruptedException ex) {
                                // The test is over.
                            }
                        });
        return channel;
    }

    private static void write(final SocketChannel client, final String text) throws IOException {
        final ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
            client.write(buffer);
        }
    }

    /** A reply whose frame, the NUL left out, is exactly a given number of bytes. */
    private static String replyOf(final int bytes) {
        final String head = "{\"parameters\":{\"pad\":\"";
        final String tail = "\"}}";
        final char[] pad = new char[bytes - head.length() - tail.length()];
        Arrays.fill(pad, 'x');
        return head + new String(pad) + tail;
    }

    @Test
    @Timeout(10)
    void aPeerThatNeverReadsALargeRequestEndsTheCallAtItsDeadline(@TempDir Path dir)
            throws Exception {

        // The send was blocking and came before the deadline started: a peer that stopped reading
        // held a request larger than the socket's buffers for ever.
        final CountDownLatch released = new CountDownLatch(1);
        final char[] large = new char[8 * 1024 * 1024];
        Arrays.fill(large, 'x');
        try (var service = peer(dir.resolve("deaf.sock"), client -> released.await());
                VarlinkClient client = new VarlinkClient(dir.resolve("deaf.sock"))) {
            final long started = System.nanoTime();

            assertThatThrownBy(
                            () ->
                                    client.call(
                                            INTERFACE + ".Deaf",
                                            Map.of("large", new String(large)),
                                            Duration.ofMillis(300)))
                    .isInstanceOf(VarlinkTimeoutException.class)
                    .hasMessageContaining(INTERFACE + ".Deaf")
                    .hasMessageContaining("300 ms");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("ended at its deadline, counted from its first byte sent")
                    .isLessThan(Duration.ofSeconds(2));
            assertThatThrownBy(() -> client.call(INTERFACE + ".Next", Map.of()))
                    .as(
                            "half a request sent is never followed by another: the connection is closed")
                    .isInstanceOf(VarlinkException.class);
        } finally {
            released.countDown();
        }
    }

    @Test
    @Timeout(20)
    void aFrameOfFourMebibytesAndOneByteEndingInTheLastReadIsRefused(@TempDir Path dir)
            throws Exception {

        // The frame was parsed before its size was looked at: one just over the cap whose end
        // came with the last read was accepted, since the cap was checked only while no end was
        // in sight.
        final String over = replyOf(VarlinkConnection.MAX_MESSAGE + 1);
        try (var service =
                        peer(
                                dir.resolve("over.sock"),
                                client -> {
                                    client.read(ByteBuffer.allocate(4096));
                                    write(client, over.substring(0, over.length() - 1));
                                    // Read up to the cap before the last byte comes with the end.
                                    Thread.sleep(500);
                                    write(client, over.substring(over.length() - 1) + "\0");
                                    client.read(ByteBuffer.allocate(16));
                                });
                VarlinkClient client = new VarlinkClient(dir.resolve("over.sock"))) {

            assertThatThrownBy(() -> client.call(INTERFACE + ".Over", Map.of()))
                    .isInstanceOf(VarlinkException.class)
                    .hasMessageContaining(String.valueOf(VarlinkConnection.MAX_MESSAGE));
        }
    }

    @Test
    @Timeout(20)
    void aFrameOfExactlyFourMebibytesIsRead(@TempDir Path dir) throws Exception {

        // The cap to the byte: the largest frame allowed still comes through.
        final String most = replyOf(VarlinkConnection.MAX_MESSAGE);
        try (var service =
                        peer(
                                dir.resolve("most.sock"),
                                client -> {
                                    client.read(ByteBuffer.allocate(4096));
                                    write(client, most + "\0");
                                    client.read(ByteBuffer.allocate(16));
                                });
                VarlinkClient client = new VarlinkClient(dir.resolve("most.sock"))) {

            assertThat(client.call(INTERFACE + ".Most", Map.of())).containsKey("pad");
        }
    }

    @Test
    @Timeout(10)
    void aPeerTricklingAnAnswerPastTheWholeDeadlineEndsTheCall(@TempDir Path dir) throws Exception {

        // A byte every little while never ends a message: each part read must not start the
        // deadline again.
        final CountDownLatch released = new CountDownLatch(1);
        try (var service =
                        peer(
                                dir.resolve("trickle.sock"),
                                client -> {
                                    client.read(ByteBuffer.allocate(4096));
                                    write(client, "{\"parameters\":{\"pad\":\"");
                                    while (released.getCount() > 0) {
                                        write(client, "x");
                                        Thread.sleep(20);
                                    }
                                });
                VarlinkClient client = new VarlinkClient(dir.resolve("trickle.sock"))) {
            final long started = System.nanoTime();

            assertThatThrownBy(
                            () ->
                                    client.call(
                                            INTERFACE + ".Trickle",
                                            Map.of(),
                                            Duration.ofMillis(300)))
                    .isInstanceOf(VarlinkTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("ended at the whole deadline, not long after")
                    .isLessThan(Duration.ofSeconds(2));
        } finally {
            released.countDown();
        }
    }

    @Test
    @Timeout(10)
    void eachReplyOfAStreamGetsTheWholeDeadlineAgain(@TempDir Path dir) throws Exception {

        // Three replies 200 ms apart take longer than one reply's 300 ms: a deadline counted from
        // the call rather than from the reply before would end a stream that is keeping pace.
        try (var service =
                        peer(
                                dir.resolve("pace.sock"),
                                client -> {
                                    client.read(ByteBuffer.allocate(4096));
                                    for (int i = 1; i <= 3; i++) {
                                        Thread.sleep(200);
                                        write(
                                                client,
                                                "{\"parameters\":{\"n\":"
                                                        + i
                                                        + "}"
                                                        + (i < 3 ? ",\"continues\":true" : "")
                                                        + "}\0");
                                    }
                                    client.read(ByteBuffer.allocate(16));
                                });
                VarlinkClient client = new VarlinkClient(dir.resolve("pace.sock"))) {
            final List<Object> seen = new ArrayList<>();

            client.callMore(
                    INTERFACE + ".Pace",
                    Map.of(),
                    reply -> seen.add(reply.get("n")),
                    Duration.ofMillis(300));

            assertThat(seen).containsExactly(1L, 2L, 3L);
        }
    }

    @Test
    @Timeout(10)
    void aBoundedSendOrReadLeavesTheChannelBlocking(@TempDir Path dir) throws Exception {

        // Both switch the channel to non-blocking for their wait; a channel left that way makes
        // the next unbounded read return nothing at once instead of waiting.
        try (ServerSocketChannel listening =
                ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listening.bind(UnixDomainSocketAddress.of(dir.resolve("pair.sock")));
            try (SocketChannel near =
                            SocketChannel.open(
                                    UnixDomainSocketAddress.of(dir.resolve("pair.sock")));
                    SocketChannel far = listening.accept();
                    VarlinkConnection connection = new VarlinkConnection(near)) {

                connection.send(Map.of("one", 1), Duration.ofSeconds(1));
                assertThat(near.isBlocking()).as("after a bounded send").isTrue();

                assertThatThrownBy(() -> connection.receive(Duration.ofMillis(50)))
                        .isInstanceOf(java.net.SocketTimeoutException.class);
                assertThat(near.isBlocking()).as("after a bounded read that timed out").isTrue();

                write(far, "{\"two\":2}\0");
                assertThat(connection.receive(Duration.ofSeconds(1))).containsEntry("two", 2L);
                assertThat(near.isBlocking()).as("after a bounded read that was answered").isTrue();
            }
        }
    }
}
