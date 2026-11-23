package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for a request body that the broker passes on streamed: far beyond what it may hold, every
 * byte of it still read for a token grant, and a grant found late stops the request before the
 * destination has all of it.
 *
 * <p>The destination is a plain socket that counts what arrives and says whether a request was
 * completed, so "nothing completed" is observed on the far side rather than inferred from the
 * answer the agent got.
 */
class VaultProxyStreamTest {

    private static final String PHANTOM = "porelo_pt_thisIsTheTasksToken";

    private static final String REAL = "sk-ant-the-real-credential";

    private static final int MIB = 1024 * 1024;

    /**
     * Where a grant is placed: a multiple of every read size up to it, so it lies across a read
     * border whatever size the broker reads in, and far past what it reads before it connects.
     */
    private static final int BORDER = MIB;

    @Test
    void aBodyFarLargerThanTheOldBoundStreamsThroughWhileItIsStillBeingSent(@TempDir Path dir)
            throws Exception {

        // Refused at 32 MiB before; and held whole, a body this size would reach the destination
        // only after its last byte left the agent. The agent here waits, halfway, until the
        // destination has received a part of it.
        final long size = 100L * MIB;
        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            final String answer =
                    exchange(
                            proxy.socket(),
                            head("PUT /upload", "content-length: " + size),
                            out -> {
                                pattern(out, size / 2);
                                assertThat(upstream.awaitBytes(MIB, TimeUnit.SECONDS.toMillis(30)))
                                        .as("the destination has a part before the agent sent all")
                                        .isTrue();
                                pattern(out, size - size / 2);
                            });

            assertThat(answer).startsWith("HTTP/1.1 200");
            final Seen seen = upstream.only();
            assertThat(seen.bytes.get()).isEqualTo(size);
            assertThat(seen.complete).isTrue();
            assertThat(seen.head.value("x-api-key")).isEqualTo(REAL);
            assertThat(seen.head.value("content-length")).isEqualTo(Long.toString(size));
        }
    }

    @Test
    void aChunkedBodyFarLargerThanTheOldBoundStreamsThroughInChunks(@TempDir Path dir)
            throws Exception {

        final long size = 100L * MIB;
        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            final String answer =
                    exchange(
                            proxy.socket(),
                            head("POST /upload", "transfer-encoding: chunked"),
                            out -> {
                                final OutputStream chunked = new Chunks(out);
                                pattern(chunked, size);
                                chunked.close();
                            });

            assertThat(answer).startsWith("HTTP/1.1 200");
            final Seen seen = upstream.only();
            assertThat(seen.bytes.get()).isEqualTo(size);
            assertThat(seen.complete).isTrue();
        }
    }

    @Test
    void aLargeBodyStreamsToADestinationOnThisHost(@TempDir Path dir) throws Exception {

        // The gateway's own client for a socket on this host sends a body it does not hold, too.
        final long size = 64L * MIB;
        final Path socket = Files.createDirectories(dir.resolve("porelo/mcp")).resolve("t.sock");
        try (Upstream upstream = Upstream.unix(socket);
                VaultProxy proxy = proxy(dir, "unix:" + socket)) {
            final String chunkedAnswer =
                    exchange(
                            proxy.socket(),
                            head("POST /mcp", "transfer-encoding: chunked"),
                            out -> {
                                final OutputStream chunked = new Chunks(out);
                                pattern(chunked, size);
                                chunked.close();
                            });

            assertThat(chunkedAnswer).startsWith("HTTP/1.1 200");
            assertThat(upstream.only().bytes.get()).isEqualTo(size);
            assertThat(upstream.only().complete).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "grant_type=refr|esh_token",
                "grant_type=refresh%5|Ftoken",
                "grant_type=refresh%|5Ftoken",
                "{\"grant_type\":\"refresh\\u00|5ftoken\"}",
                "{\"grant_type\":\"refresh\\|u005ftoken\"}",
                "{\"grant\\u005ftype\": |\"client_credentials\"}"
            })
    void aGrantSplitAcrossAReadAndAChunkBorderIsRefusedBeforeTheRequestCompletes(
            final String spelled, @TempDir Path dir) throws Exception {

        // The bar marks where the body is cut: one chunk ends there, and the broker's own reads
        // meet there too. A window that did not overlap its neighbour saw neither half.
        final int cut = spelled.indexOf('|');
        final byte[] before = spelled.substring(0, cut).getBytes(StandardCharsets.US_ASCII);
        final byte[] after = spelled.substring(cut + 1).getBytes(StandardCharsets.US_ASCII);
        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            final String answer =
                    exchange(
                            proxy.socket(),
                            head("POST /oauth/token", "transfer-encoding: chunked"),
                            out -> {
                                final Chunks chunked = new Chunks(out);
                                pattern(chunked, BORDER - before.length);
                                chunked.write(before);
                                chunked.flushChunk();
                                chunked.write(after);
                                pattern(chunked, 4L * MIB);
                                chunked.close();
                            });

            assertThat(answer).startsWith("HTTP/1.1 403").contains("cannot be renewed");
            upstream.assertNothingCompleted();
            assertThat(upstream.connections())
                    .as("past the first read the request had begun: the grant stopped it midway")
                    .isNotEmpty();
        }
    }

    @Test
    void aGrantAfterAnyRunOfSeparatorsIsFoundAcrossReads(@TempDir Path dir) throws Exception {

        // 'grant_type' and its value may stand any number of separators apart, so no fixed overlap
        // between two reads holds both: a run is read as one separator.
        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            final String answer =
                    exchange(
                            proxy.socket(),
                            head("POST /oauth/token", "transfer-encoding: chunked"),
                            out -> {
                                final Chunks chunked = new Chunks(out);
                                pattern(chunked, BORDER);
                                chunked.write("grant_type".getBytes(StandardCharsets.US_ASCII));
                                chunked.write(
                                        " ".repeat(3 * MIB).getBytes(StandardCharsets.US_ASCII));
                                chunked.write(
                                        "=\t:\"refresh_token\""
                                                .getBytes(StandardCharsets.US_ASCII));
                                pattern(chunked, MIB);
                                chunked.close();
                            });

            assertThat(answer).startsWith("HTTP/1.1 403");
            upstream.assertNothingCompleted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "deflate", "zstd"})
    void aGrantDeepInACompressedStreamedBodyIsRefusedBeforeTheRequestCompletes(
            final String coding, @TempDir Path dir) throws Exception {

        // Text that does not compress, so the coded body is itself far past the first read.
        final ByteArrayOutputStream plain = new ByteArrayOutputStream();
        final java.util.Random random = new java.util.Random(428);
        for (int i = 0; i < 2 * MIB; i++) {
            plain.write('a' + random.nextInt(26));
        }
        plain.writeBytes("&grant_type=client_credentials&".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < MIB; i++) {
            plain.write('a' + random.nextInt(26));
        }
        final byte[] coded = code(coding, plain.toByteArray());
        assertThat(coded.length).isGreaterThan(MIB);
        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            final String answer =
                    exchange(
                            proxy.socket(),
                            head(
                                    "POST /oauth/token",
                                    "content-encoding: " + coding,
                                    "content-length: " + coded.length),
                            out -> out.write(coded));

            assertThat(answer).startsWith("HTTP/1.1 403");
            upstream.assertNothingCompleted();
            assertThat(upstream.connections()).isNotEmpty();
        }
    }

    @Test
    void aSecondGzipMemberIsReadAsTheDestinationWouldReadIt(@TempDir Path dir) throws Exception {

        // A reader that stops at the end of the first member passes the second on unread; a
        // destination that reads both gets the grant.
        final ByteArrayOutputStream first = new ByteArrayOutputStream();
        final java.util.Random random = new java.util.Random(4280);
        for (int i = 0; i < 2 * MIB; i++) {
            first.write('a' + random.nextInt(26));
        }
        final ByteArrayOutputStream coded = new ByteArrayOutputStream();
        coded.writeBytes(code("gzip", first.toByteArray()));
        coded.writeBytes(
                code("gzip", "grant_type=refresh_token".getBytes(StandardCharsets.US_ASCII)));
        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            final String answer =
                    exchange(
                            proxy.socket(),
                            head(
                                    "POST /oauth/token",
                                    "content-encoding: gzip",
                                    "content-length: " + coded.size()),
                            out -> out.write(coded.toByteArray()));

            assertThat(answer).startsWith("HTTP/1.1 403");
            upstream.assertNothingCompleted();
        }
    }

    @Test
    void aBodyThatEndsBeforeItsLastChunkNeverCompletesUpstream(@TempDir Path dir) throws Exception {

        // The agent goes away before the final chunk: what went on must not read as a whole body.
        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            exchange(
                    proxy.socket(),
                    head("POST /upload", "transfer-encoding: chunked"),
                    out -> {
                        final Chunks chunked = new Chunks(out);
                        pattern(chunked, 2L * MIB);
                        chunked.flushChunk();
                        out.flush();
                        throw new IOException("the agent hangs up before the last chunk");
                    });

            upstream.assertNothingCompleted();
        }
    }

    @Test
    void aBodyLargerThanTheBoundByItsLengthIsRefusedBeforeAnyOfItGoesOn(@TempDir Path dir)
            throws Exception {

        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            final String answer =
                    exchange(
                            proxy.socket(),
                            head("PUT /upload", "content-length: " + (VaultProxy.BODY_LIMIT + 1L)),
                            out -> pattern(out, MIB));

            assertThat(answer).startsWith("HTTP/1.1 413");
            Thread.sleep(200);
            assertThat(upstream.connections()).as("not even the head went on").isEmpty();
        }
    }

    @Test
    void aChunkedBodyThatPassesTheBoundIsRefusedAndTheRequestStopped(@TempDir Path dir)
            throws Exception {

        // The count passes the bound partway: what went on before is stopped short of complete.
        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            final String answer =
                    exchange(
                            proxy.socket(),
                            head("PUT /upload", "transfer-encoding: chunked"),
                            out -> {
                                final Chunks chunked = new Chunks(out);
                                pattern(chunked, 2L * MIB);
                                chunked.flushChunk();
                                // One chunk that would carry the total past the bound.
                                out.write(
                                        (Long.toHexString(VaultProxy.BODY_LIMIT) + "\r\n")
                                                .getBytes(StandardCharsets.US_ASCII));
                                pattern(out, MIB);
                            });

            assertThat(answer).startsWith("HTTP/1.1 413");
            upstream.assertNothingCompleted();
            assertThat(upstream.connections()).isNotEmpty();
        }
    }

    @Test
    void aModelRequestIsStillReadWholeUnderItsOwnBound(@TempDir Path dir) throws Exception {

        // The usage counting reads and may rewrite a model request, so it is held whole before
        // anything of it goes on: past its own bound it is refused, never streamed.
        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            final String answer =
                    exchange(
                            proxy.socket(),
                            head(
                                    "POST /v1/chat/completions",
                                    "content-length: " + (VaultProxy.WHOLE_LIMIT + 1L)),
                            out -> pattern(out, VaultProxy.WHOLE_LIMIT + 1L));

            assertThat(answer).doesNotStartWith("HTTP/1.1 200");
            Thread.sleep(200);
            assertThat(upstream.connections()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"content-encoding", "content-length", "transfer-encoding"})
    void aRequestThatRepeatsAHeaderFramingOrCodingItsBodyIsRefusedBeforeAnyOfItGoesOn(
            final String repeated, @TempDir Path dir) throws Exception {

        // The broker read the first line, the destination may join them all: a body coded by the
        // second 'Content-Encoding' line was scanned as it was written and decoded beyond.
        final byte[] coded =
                code("gzip", "grant_type=client_credentials".getBytes(StandardCharsets.US_ASCII));
        final String[] headers =
                switch (repeated) {
                    case "content-encoding" ->
                            new String[] {
                                "content-encoding: identity",
                                "content-encoding: gzip",
                                "content-length: " + coded.length
                            };
                    case "content-length" ->
                            new String[] {
                                "content-length: " + coded.length, "content-length: " + coded.length
                            };
                    default ->
                            new String[] {
                                "transfer-encoding: chunked", "transfer-encoding: chunked"
                            };
                };
        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            final String answer =
                    exchange(
                            proxy.socket(),
                            head("POST /upload", headers),
                            out -> {
                                if (repeated.equals("transfer-encoding")) {
                                    final Chunks chunked = new Chunks(out);
                                    chunked.write(coded);
                                    chunked.close();
                                } else {
                                    out.write(coded);
                                }
                            });

            assertThat(answer).startsWith("HTTP/1.1 400").contains(repeated);
            Thread.sleep(200);
            assertThat(upstream.connections()).as("not even the head went on").isEmpty();
        }
    }

    @Test
    void aGrantPartwayThroughAnUploadToASocketOnThisHostStopsItShortOfComplete(@TempDir Path dir)
            throws Exception {

        // The gateway's own client for a socket on this host stops as HttpClient does: the grant
        // lies far past the first read, so the request had begun.
        final Path socket = Files.createDirectories(dir.resolve("porelo/mcp")).resolve("t.sock");
        final byte[] grant = "&grant_type=client_credentials&".getBytes(StandardCharsets.US_ASCII);
        for (final boolean chunks : new boolean[] {true, false}) {
            try (Upstream upstream = Upstream.unix(socket);
                    VaultProxy proxy = proxy(dir, "unix:" + socket)) {
                final long size = 4L * MIB + grant.length;
                final String answer =
                        exchange(
                                proxy.socket(),
                                chunks
                                        ? head("POST /mcp", "transfer-encoding: chunked")
                                        : head("POST /mcp", "content-length: " + size),
                                out -> {
                                    final OutputStream body = chunks ? new Chunks(out) : out;
                                    pattern(body, 2L * MIB);
                                    body.write(grant);
                                    pattern(body, 2L * MIB);
                                    if (body instanceof Chunks chunked) {
                                        chunked.close();
                                    }
                                });

                assertThat(answer).startsWith("HTTP/1.1 403");
                upstream.assertNothingCompleted();
                assertThat(upstream.connections()).as("the upload had begun").isNotEmpty();
            }
            Files.deleteIfExists(socket);
        }
    }

    @Test
    @Tag("slow")
    void twoGibibytesPassWithTheHeapFlat(@TempDir Path dir) throws Exception {

        // The bound itself, end to end, with the heap sampled while it passes: a body held whole
        // would not fit the test's heap at all.
        final long size = VaultProxy.BODY_LIMIT;
        final AtomicLong highest = new AtomicLong();
        final Runtime runtime = Runtime.getRuntime();
        System.gc();
        final long before = runtime.totalMemory() - runtime.freeMemory();
        final java.util.concurrent.atomic.AtomicBoolean sending =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        final Thread sampler =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    while (sending.get()) {
                                        System.gc();
                                        highest.accumulateAndGet(
                                                runtime.totalMemory() - runtime.freeMemory(),
                                                Math::max);
                                        try {
                                            Thread.sleep(250);
                                        } catch (InterruptedException ex) {
                                            return;
                                        }
                                    }
                                });
        final long started = System.nanoTime();
        try (Upstream upstream = Upstream.tcp();
                VaultProxy proxy = proxy(dir, upstream.address())) {
            final String answer =
                    exchange(
                            proxy.socket(),
                            head("PUT /upload", "content-length: " + size),
                            out -> pattern(out, size));
            sending.set(false);
            sampler.join();

            assertThat(answer).startsWith("HTTP/1.1 200");
            assertThat(upstream.only().bytes.get()).isEqualTo(size);
            assertThat(upstream.only().complete).isTrue();
        }
        final long grew = highest.get() - before;
        System.out.println(
                "2 GiB through the broker in "
                        + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
                        + " s; heap grew by at most "
                        + grew / MIB
                        + " MiB");
        assertThat(grew).as("the heap stays flat while 2 GiB pass").isLessThan(64L * MIB);
    }

    // ---------------------------------------------------------------- helpers

    private static VaultProxy proxy(Path dir, String upstream) {
        final TokenExchange exchange =
                presented ->
                        PHANTOM.equals(presented)
                                ? new TokenExchange.Granted(REAL)
                                : new TokenExchange.Rejected();
        final VaultProxy proxy =
                new VaultProxy(dir.resolve("vault/v.sock"), upstream, exchange, "x-api-key", "");
        Thread.ofPlatform().daemon().start(proxy);
        return proxy;
    }

    private static String head(String line, String... headers) {
        final StringBuilder head = new StringBuilder(line).append(" HTTP/1.1\r\n");
        head.append("x-api-key: ").append(PHANTOM).append("\r\n");
        for (final String header : headers) {
            head.append(header).append("\r\n");
        }
        return head.append("\r\n").toString();
    }

    /** Writes a body to a connection. */
    @FunctionalInterface
    private interface Body {
        void write(OutputStream out) throws Exception;
    }

    /**
     * Sends a head and a body from its own thread, and returns what the broker answered: an answer
     * may come while the agent is still sending, and a refusal may cut the sending short.
     */
    private static String exchange(Path socket, String head, Body body) throws Exception {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            final OutputStream out = Channels.newOutputStream(channel);
            final InputStream in = Channels.newInputStream(channel);
            final java.util.concurrent.atomic.AtomicReference<Throwable> failed =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final Thread sender =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            out.write(head.getBytes(StandardCharsets.US_ASCII));
                                            body.write(out);
                                            out.flush();
                                        } catch (AssertionError ex) {
                                            failed.set(ex);
                                        } catch (Exception ex) {
                                            // A refusal closes the connection under the sender,
                                            // or the agent hangs up: the broker sees the end.
                                            try {
                                                channel.shutdownOutput();
                                            } catch (IOException closing) {
                                                // Gone either way.
                                            }
                                        }
                                    });
            final ByteArrayOutputStream answer = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    answer.write(buffer, 0, read);
                }
            } catch (IOException ex) {
                // A connection reset after the answer: the agent was still sending.
            }
            sender.join(TimeUnit.SECONDS.toMillis(60));
            if (failed.get() instanceof AssertionError error) {
                throw error;
            }
            return answer.toString(StandardCharsets.UTF_8);
        }
    }

    /** Writes letters that can never spell a grant. */
    private static void pattern(OutputStream out, long size) throws IOException {
        final byte[] block = new byte[64 * 1024];
        for (int i = 0; i < block.length; i++) {
            block[i] = (byte) ('a' + i % 7);
        }
        long left = size;
        while (left > 0) {
            final int now = (int) Math.min(block.length, left);
            out.write(block, 0, now);
            left -= now;
        }
    }

    private static byte[] code(String coding, byte[] plain) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (coding) {
            case "gzip" -> {
                try (OutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
                    gzip.write(plain);
                }
            }
            case "deflate" -> {
                try (OutputStream deflate = new java.util.zip.DeflaterOutputStream(out)) {
                    deflate.write(plain);
                }
            }
            default -> {
                final io.airlift.compress.v3.zstd.ZstdCompressor zstd =
                        io.airlift.compress.v3.zstd.ZstdCompressor.create();
                final byte[] frame = new byte[zstd.maxCompressedLength(plain.length)];
                out.write(frame, 0, zstd.compress(plain, 0, plain.length, frame, 0, frame.length));
            }
        }
        return out.toByteArray();
    }

    /** Frames what is written as chunks of at most 64 KiB, cut where it is told. */
    private static final class Chunks extends OutputStream {

        private final OutputStream out;

        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        Chunks(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            int at = offset;
            int left = length;
            while (left > 0) {
                final int now = Math.min(left, 64 * 1024 - pending.size());
                pending.write(data, at, now);
                at += now;
                left -= now;
                if (pending.size() == 64 * 1024) {
                    flushChunk();
                }
            }
        }

        void flushChunk() throws IOException {
            if (pending.size() == 0) {
                return;
            }
            out.write(
                    (Integer.toHexString(pending.size()) + "\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            pending.writeTo(out);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            pending.reset();
        }

        @Override
        public void close() throws IOException {
            flushChunk();
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    /** One request as the destination saw it. */
    private static final class Seen {

        private final HttpHead head;

        private final AtomicLong bytes = new AtomicLong();

        private volatile boolean complete;

        private final CountDownLatch done = new CountDownLatch(1);

        Seen(HttpHead head) {
            this.head = head;
        }
    }

    /**
     * A destination that counts the body it receives and answers only a request that arrived whole.
     */
    private static final class Upstream implements AutoCloseable {

        private final ServerSocketChannel server;

        private final List<Seen> seen = new CopyOnWriteArrayList<>();

        private final AtomicLong total = new AtomicLong();

        private Upstream(ServerSocketChannel server) {
            this.server = server;
            Thread.ofPlatform().daemon().start(this::serve);
        }

        static Upstream tcp() throws IOException {
            final ServerSocketChannel server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            return new Upstream(server);
        }

        static Upstream unix(Path socket) throws IOException {
            final ServerSocketChannel server =
                    ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            return new Upstream(server);
        }

        String address() throws IOException {
            return "http://127.0.0.1:" + ((InetSocketAddress) server.getLocalAddress()).getPort();
        }

        List<Seen> connections() {
            return seen;
        }

        Seen only() throws InterruptedException {
            assertThat(seen).hasSize(1);
            assertThat(seen.get(0).done.await(30, TimeUnit.SECONDS)).isTrue();
            return seen.get(0);
        }

        boolean awaitBytes(long least, long millis) throws InterruptedException {
            final long until = System.currentTimeMillis() + millis;
            while (total.get() < least) {
                if (System.currentTimeMillis() > until) {
                    return false;
                }
                Thread.sleep(10);
            }
            return true;
        }

        void assertNothingCompleted() throws InterruptedException {
            for (final Seen each : seen) {
                assertThat(each.done.await(30, TimeUnit.SECONDS))
                        .as("the destination's connection ends")
                        .isTrue();
                assertThat(each.complete)
                        .as(
                                "the destination never had a whole request (%d bytes arrived)",
                                each.bytes.get())
                        .isFalse();
            }
        }

        private void serve() {
            while (server.isOpen()) {
                final SocketChannel channel;
                try {
                    channel = server.accept();
                } catch (IOException ex) {
                    return;
                }
                Thread.ofVirtual().start(() -> receive(channel));
            }
        }

        private void receive(SocketChannel channel) {
            Seen request = null;
            try (channel) {
                final InputStream in = new BufferedInputStream(Channels.newInputStream(channel));
                final HttpHead head = HttpHead.read(in);
                if (head == null) {
                    return;
                }
                request = new Seen(head);
                seen.add(request);
                final byte[] buffer = new byte[64 * 1024];
                if (head.chunked()) {
                    while (true) {
                        final String size = line(in);
                        final long length = Long.parseLong(size.split(";")[0].strip(), 16);
                        if (length == 0) {
                            if (!line(in).isEmpty()) {
                                return;
                            }
                            break;
                        }
                        count(in, request, length, buffer);
                        if (!line(in).isEmpty()) {
                            return;
                        }
                    }
                } else {
                    count(in, request, Math.max(0, head.contentLength()), buffer);
                }
                request.complete = true;
                final OutputStream out = Channels.newOutputStream(channel);
                out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                        + "Content-Length: 11\r\nConnection: close\r\n\r\n"
                                        + "{\"ok\":true}")
                                .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException | RuntimeException ex) {
                // Cut off: the request is not complete.
            } finally {
                if (request != null) {
                    request.done.countDown();
                }
            }
        }

        private void count(InputStream in, Seen request, long length, byte[] buffer)
                throws IOException {
            long left = length;
            while (left > 0) {
                final int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) {
                    throw new IOException("ended early");
                }
                left -= read;
                request.bytes.addAndGet(read);
                total.addAndGet(read);
            }
        }

        private static String line(InputStream in) throws IOException {
            final StringBuilder text = new StringBuilder();
            int read;
            while ((read = in.read()) != -1) {
                if (read == '\n') {
                    return text.toString().strip();
                }
                text.append((char) read);
            }
            throw new IOException("ended inside a line");
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
