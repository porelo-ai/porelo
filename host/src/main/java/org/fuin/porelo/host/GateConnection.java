package org.fuin.porelo.host;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Objects;

/** One connection's bounded reads and writes, also for Unix sockets without Socket adapters. */
final class GateConnection implements AutoCloseable {
    private final SocketChannel channel;
    private final boolean closeChannel;
    private final Selector selector;
    private final SelectionKey key;
    private final java.util.function.LongSupplier nanoTime;
    private long readDeadline;
    final InputStream input;
    final OutputStream output;

    GateConnection(SocketChannel channel) throws IOException {
        this(channel, true);
    }

    GateConnection(SocketChannel channel, boolean closeChannel) throws IOException {
        this(channel, closeChannel, 60);
    }

    GateConnection(SocketChannel channel, boolean closeChannel, int timeoutSeconds)
            throws IOException {
        this(channel, closeChannel, timeoutSeconds, System::nanoTime);
    }

    GateConnection(
            SocketChannel channel,
            boolean closeChannel,
            int timeoutSeconds,
            java.util.function.LongSupplier nanoTime)
            throws IOException {
        this.channel = channel;
        this.nanoTime = nanoTime;
        this.closeChannel = closeChannel;
        channel.configureBlocking(false);
        selector = Selector.open();
        key = channel.register(selector, 0);
        input =
                new BufferedInputStream(
                        new InputStream() {
                            @Override
                            public int read() throws IOException {
                                final byte[] one = new byte[1];
                                return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
                            }

                            @Override
                            public int read(byte[] bytes, int offset, int length)
                                    throws IOException {
                                Objects.checkFromIndexSize(offset, length, bytes.length);
                                if (length == 0) return 0;
                                final ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
                                final long deadline =
                                        readDeadline == 0
                                                ? nanoTime.getAsLong()
                                                        + timeoutSeconds * 1_000_000_000L
                                                : readDeadline;
                                int count;
                                while (true) {
                                    remaining(deadline);
                                    count = channel.read(buffer);
                                    if (count != 0) break;
                                    ready(SelectionKey.OP_READ, deadline);
                                }
                                return count;
                            }
                        });
        output =
                new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        write(new byte[] {(byte) value});
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        final ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
                        final long deadline =
                                nanoTime.getAsLong() + timeoutSeconds * 1_000_000_000L;
                        while (buffer.hasRemaining())
                            if (channel.write(buffer) == 0) ready(SelectionKey.OP_WRITE, deadline);
                    }
                };
    }

    void readDeadline(java.time.Duration timeout) {
        readDeadline = nanoTime.getAsLong() + timeout.toNanos();
    }

    private long remaining(long deadline) throws SocketTimeoutException {
        final long left = deadline - nanoTime.getAsLong();
        if (left <= 0 || Thread.currentThread().isInterrupted())
            throw new SocketTimeoutException("Gate connection timed out");
        return left;
    }

    private void ready(int operation, long deadline) throws IOException {
        final long remaining = remaining(deadline);
        key.interestOps(operation);
        selector.select(Math.max(1, remaining / 1_000_000));
        selector.selectedKeys().clear();
    }

    @Override
    public void close() throws IOException {
        try {
            if (closeChannel) channel.close();
        } finally {
            selector.close();
        }
    }
}
