package org.fuin.porelo.wire.varlink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * One varlink connection: JSON objects separated by a NUL byte.
 *
 * <p>That is the entire framing. No length prefixes, no handshake, no headers - which is why
 * writing this was preferable to depending on a Java varlink library, of which there is no
 * maintained one.
 *
 * <p>Not thread-safe. A connection belongs to one caller.
 */
public class VarlinkConnection implements AutoCloseable {

    /** Largest message accepted from a peer. */
    static final int MAX_MESSAGE = 4 * 1024 * 1024;

    private final SocketChannel channel;

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    private final ByteBuffer reading = ByteBuffer.allocate(8192);

    /**
     * Constructor.
     *
     * @param channel The connected socket.
     */
    public VarlinkConnection(SocketChannel channel) {
        this.channel = channel;
    }

    /**
     * Sends one message.
     *
     * @param message The object to send.
     * @throws IOException If writing fails.
     */
    public void send(Map<String, Object> message) throws IOException {
        final ByteBuffer buffer = framed(message);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * Sends one message, waiting no longer than a limit for the peer to take it.
     *
     * <p>Only on this connection's one thread: it switches the channel to non-blocking for the
     * wait, as {@link #receive(Duration)} does, since a unix-domain channel has no write timeout.
     *
     * @param message The object to send.
     * @param within How long the whole message may take to be sent.
     * @throws SocketTimeoutException If the peer did not take the whole message within the limit;
     *     part of it may be sent, so the connection is the caller's to close.
     * @throws IOException If writing fails, or the waiting thread is interrupted.
     */
    public void send(final Map<String, Object> message, final Duration within) throws IOException {
        final long deadline = System.nanoTime() + within.toNanos();
        final ByteBuffer buffer = framed(message);
        channel.configureBlocking(false);
        try {
            channel.write(buffer);
            if (!buffer.hasRemaining()) {
                // Most messages fit the socket's buffer: no selector for them.
                return;
            }
            try (Selector selector = Selector.open()) {
                channel.register(selector, SelectionKey.OP_WRITE);
                while (true) {
                    final long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        throw new SocketTimeoutException("Not sent within " + within);
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Interrupted while sending");
                    }
                    selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left + 999_999)));
                    selector.selectedKeys().clear();
                    channel.write(buffer);
                    if (!buffer.hasRemaining()) {
                        return;
                    }
                }
            }
        } finally {
            // As after a bounded read: the selector is closed, so the channel can block again.
            channel.configureBlocking(true);
        }
    }

    /** A message as it goes on the wire: its JSON and the NUL that ends it. */
    private static ByteBuffer framed(final Map<String, Object> message) {
        final byte[] payload = Json.write(message).getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocate(payload.length + 1);
        buffer.put(payload).put((byte) 0).flip();
        return buffer;
    }

    /**
     * Reads one message, waiting as long as it takes.
     *
     * @return The object, or {@code null} when the peer closed the connection.
     * @throws IOException If reading fails.
     * @throws VarlinkException If the peer sent something that is not a varlink message.
     */
    @Nullable
    public Map<String, Object> receive() throws IOException {
        while (true) {
            final Map<String, Object> message = buffered();
            if (message != null) {
                return message;
            }
            reading.clear();
            if (channel.read(reading) < 0) {
                return null;
            }
            keep();
        }
    }

    /**
     * Reads one message, waiting no longer than a limit.
     *
     * <p>Only on this connection's one thread: it switches the channel to non-blocking for the
     * wait, as {@link #peerGone()} does, since a unix-domain channel has no read timeout.
     *
     * @param within How long to wait for the whole message.
     * @return The object, or {@code null} when the peer closed the connection.
     * @throws SocketTimeoutException If no whole message arrived within the limit; what did arrive
     *     is kept, and the connection is the caller's to close.
     * @throws IOException If reading fails, or the waiting thread is interrupted.
     * @throws VarlinkException If the peer sent something that is not a varlink message.
     */
    @Nullable
    public Map<String, Object> receive(final Duration within) throws IOException {
        final long deadline = System.nanoTime() + within.toNanos();
        final Map<String, Object> waiting = buffered();
        if (waiting != null) {
            return waiting;
        }
        channel.configureBlocking(false);
        try {
            try (Selector selector = Selector.open()) {
                channel.register(selector, SelectionKey.OP_READ);
                while (true) {
                    // What is there is read before the clock is looked at, so an answer that came
                    // in time is never refused because this thread was scheduled late.
                    reading.clear();
                    final int read = channel.read(reading);
                    if (read < 0) {
                        return null;
                    }
                    if (read > 0) {
                        keep();
                        final Map<String, Object> message = buffered();
                        if (message != null) {
                            return message;
                        }
                    }
                    // Looked at after a part too: a peer trickling bytes with no end would
                    // otherwise hold the caller until the message limit.
                    final long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        throw new SocketTimeoutException("No whole message within " + within);
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        // A select returns at once for an interrupted thread: without this it
                        // would spin until the deadline.
                        throw new InterruptedIOException("Interrupted while waiting for a reply");
                    }
                    selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left + 999_999)));
                    selector.selectedKeys().clear();
                }
            }
        } finally {
            // The selector is closed by now, which deregisters the channel; a registered channel
            // cannot be made blocking again.
            channel.configureBlocking(true);
        }
    }

    /** Takes the next whole message out of what was read, if one is there. */
    @Nullable
    private Map<String, Object> buffered() {
        final byte[] buffered = pending.toByteArray();
        for (int i = 0; i < buffered.length; i++) {
            if (buffered[i] == 0) {
                if (i > MAX_MESSAGE) {
                    // Looked at before it is parsed: a frame just over the limit whose end came
                    // with the last read never reached the check below.
                    throw tooLarge();
                }
                final String text = new String(buffered, 0, i, StandardCharsets.UTF_8);
                pending.reset();
                pending.write(buffered, i + 1, buffered.length - i - 1);
                return parse(text);
            }
        }
        if (pending.size() > MAX_MESSAGE) {
            // A peer that never sends a NUL is either broken or trying to make this process
            // allocate without limit.
            throw tooLarge();
        }
        return null;
    }

    private static VarlinkException tooLarge() {
        return new VarlinkException("A varlink message exceeded " + MAX_MESSAGE + " bytes");
    }

    /** Keeps what the last read brought. */
    private void keep() {
        reading.flip();
        pending.write(reading.array(), 0, reading.limit());
    }

    /**
     * Tells whether the peer has closed its side, without waiting: what it sent meanwhile is kept
     * for the next {@link #receive()}.
     *
     * <p>Only between calls on this connection's one thread: it switches the channel to
     * non-blocking for one read.
     *
     * @return {@code true} once the peer has closed, or the connection cannot be read any more.
     */
    public boolean peerGone() {
        try {
            channel.configureBlocking(false);
            try {
                reading.clear();
                final int read = channel.read(reading);
                if (read < 0) {
                    return true;
                }
                reading.flip();
                pending.write(reading.array(), 0, reading.limit());
                // The limit a message has: a client sending without end during a stream is broken
                // or trying to make this process allocate, and is answered as gone.
                return pending.size() > MAX_MESSAGE;
            } finally {
                channel.configureBlocking(true);
            }
        } catch (IOException ex) {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) {
        // Exact numbers: read as doubles, an int arrives as 1024.0 and an int64 above 2^53 loses
        // digits, for the daemon reading a call and for every client reading a reply. A whole
        // number is a Long; a reader wanting an int takes VarlinkNumbers.intOf, which saturates as
        // a double did rather than keeping the low bits.
        if (!(Json.parseExact(text) instanceof Map<?, ?> map)) {
            throw new VarlinkException("A varlink message must be a JSON object");
        }
        return (Map<String, Object>) map;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
