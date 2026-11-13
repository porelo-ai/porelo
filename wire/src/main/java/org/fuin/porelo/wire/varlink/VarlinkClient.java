package org.fuin.porelo.wire.varlink;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Calls a varlink method over a unix socket.
 *
 * <p>A call answered once waits at most {@link #DEFAULT_DEADLINE}, or the deadline its caller
 * gives, counted from its first byte sent to its answer; a stream waits without one unless its
 * caller gives one.
 */
public class VarlinkClient implements AutoCloseable {

    /** How long a call answered once waits for its reply unless its caller says otherwise. */
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(30);

    private final VarlinkConnection connection;

    /**
     * Connects to a varlink service.
     *
     * @param socket Path of the socket.
     * @throws VarlinkException If the socket cannot be reached.
     */
    public VarlinkClient(Path socket) {
        this(socket, channel -> {}, DEFAULT_DEADLINE);
    }

    /**
     * Connects to a varlink service, waiting at most a given time for the connection.
     *
     * @param socket Path of the socket.
     * @param connectWithin How long the connect may take: a socket whose backlog is full holds a
     *     connect until somebody accepts.
     * @throws VarlinkException If the socket cannot be reached, or not in time.
     */
    public VarlinkClient(Path socket, Duration connectWithin) {
        this(socket, channel -> {}, connectWithin);
    }

    /** Connects and verifies the kernel peer before any request bytes can be sent. */
    public VarlinkClient(Path socket, java.util.function.Consumer<SocketChannel> verify) {
        this(socket, verify, DEFAULT_DEADLINE);
    }

    private VarlinkClient(
            Path socket,
            java.util.function.Consumer<SocketChannel> verify,
            Duration connectWithin) {
        try {
            final SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                connect(channel, UnixDomainSocketAddress.of(socket), connectWithin);
                verify.accept(channel);
                connection = new VarlinkConnection(channel);
            } catch (IOException | RuntimeException | Error ex) {
                channel.close();
                throw ex;
            }
        } catch (IOException ex) {
            throw new VarlinkException("Cannot connect to " + socket, ex);
        }
    }

    /**
     * Calls a method once and returns the reply, waiting at most {@link #DEFAULT_DEADLINE}.
     *
     * @param method Fully qualified method name.
     * @param parameters Call parameters.
     * @return The reply parameters.
     * @throws VarlinkTimeoutException If no reply came within the deadline.
     * @throws VarlinkException If the peer returns an error or closes early.
     */
    public Map<String, Object> call(String method, Map<String, Object> parameters) {
        return call(method, parameters, DEFAULT_DEADLINE);
    }

    /**
     * Calls a method once and returns the reply, waiting at most a given time.
     *
     * <p>The deadline is counted from the first byte sent: a peer that stops reading a large
     * request ends the call as one that never answers. A call that outlives its deadline closes the
     * connection, since a late reply would be read as the next call's, and half a request would be
     * read as the start of the next.
     *
     * @param method Fully qualified method name.
     * @param parameters Call parameters.
     * @param deadline How long sending the call and its reply may take together.
     * @return The reply parameters.
     * @throws VarlinkTimeoutException If no reply came within the deadline.
     * @throws VarlinkException If the peer returns an error or closes early.
     */
    public Map<String, Object> call(
            String method, Map<String, Object> parameters, Duration deadline) {
        try {
            final long since = System.nanoTime();
            send(method, parameters, false, deadline);
            final Map<String, Object> reply =
                    receive(method, deadline, deadline.minusNanos(System.nanoTime() - since));
            if (reply == null) {
                throw new VarlinkException("The service closed the connection without replying");
            }
            return replyParameters(reply);
        } catch (IOException ex) {
            throw new VarlinkException("Call to " + method + " failed", ex);
        }
    }

    /**
     * Calls a method once and waits for its reply as long as it takes, for a method whose answer
     * waits for a person: a clearance report is answered when the operator decides, or when the
     * watcher's own question time runs out.
     *
     * @param method Fully qualified method name.
     * @param parameters Call parameters.
     * @return The reply parameters.
     * @throws VarlinkException If the peer returns an error or closes early.
     */
    public Map<String, Object> callAwaitingAPerson(String method, Map<String, Object> parameters) {
        try {
            send(method, parameters, false);
            final Map<String, Object> reply = connection.receive();
            if (reply == null) {
                throw new VarlinkException("The service closed the connection without replying");
            }
            return replyParameters(reply);
        } catch (IOException ex) {
            throw new VarlinkException("Call to " + method + " failed", ex);
        }
    }

    /**
     * Calls a method with {@code more} and hands each reply to a consumer until the stream ends or
     * the consumer asks to stop, waiting for each reply as long as it takes: a stream may rightly
     * be quiet until there is news.
     *
     * @param method Fully qualified method name.
     * @param parameters Call parameters.
     * @param consumer Receives each reply; returning {@code false} stops the stream.
     * @throws VarlinkException If the peer returns an error.
     */
    public void callMore(
            String method,
            Map<String, Object> parameters,
            Predicate<Map<String, Object>> consumer) {
        callMore(method, parameters, consumer, null);
    }

    /**
     * Calls a method with {@code more} as {@link #callMore(String, Map, Predicate)} does, waiting
     * at most a given time for each reply.
     *
     * @param method Fully qualified method name.
     * @param parameters Call parameters.
     * @param consumer Receives each reply; returning {@code false} stops the stream.
     * @param eachReply How long each reply may take, counted again after each one; the first shares
     *     it with sending the call.
     * @throws VarlinkTimeoutException If a reply did not come within the limit; the connection is
     *     closed.
     * @throws VarlinkException If the peer returns an error.
     */
    public void callMore(
            String method,
            Map<String, Object> parameters,
            Predicate<Map<String, Object>> consumer,
            @Nullable Duration eachReply) {
        try {
            // Counted from the first byte sent for the first reply, and from the reply before for
            // each after it.
            long since = System.nanoTime();
            if (eachReply == null) {
                send(method, parameters, true);
            } else {
                send(method, parameters, true, eachReply);
            }
            while (true) {
                final Map<String, Object> reply =
                        eachReply == null
                                ? connection.receive()
                                : receive(
                                        method,
                                        eachReply,
                                        eachReply.minusNanos(System.nanoTime() - since));
                since = System.nanoTime();
                if (reply == null) {
                    // The peer closed mid-stream. Returning here would be indistinguishable from a
                    // stream that ended properly - a client would stop watching and go on showing
                    // what it last saw, which for a fleet view or a clearance prompt is worse than
                    // an error. A finished stream arrives as a reply without 'continues'; a socket
                    // that simply ends did not finish.
                    throw new VarlinkException(
                            "The service closed the stream of " + method + " before it ended");
                }
                final Map<String, Object> values = replyParameters(reply);
                if (!consumer.test(values)) {
                    return;
                }
                if (!Boolean.TRUE.equals(reply.get("continues"))) {
                    // The last reply in a stream omits 'continues'. Treating its absence as "keep
                    // waiting" is how a client hangs at the end of a perfectly good stream.
                    return;
                }
            }
        } catch (IOException ex) {
            throw new VarlinkException("Streaming call to " + method + " failed", ex);
        }
    }

    /**
     * Reads one reply within what is left of a deadline, closing the connection when none came or
     * the wait was interrupted: either way the answer may still come, and would be read as the next
     * call's.
     */
    private @Nullable Map<String, Object> receive(String method, Duration deadline, Duration left)
            throws IOException {
        return bounded(method, deadline, () -> connection.receive(left));
    }

    /** One step of a call within its deadline, the connection closed when it does not end in it. */
    @FunctionalInterface
    private interface Step<T> {
        @Nullable T run() throws IOException;
    }

    /**
     * Runs one step of a call within its deadline, closing the connection when the step did not end
     * in it or its wait was interrupted: a late answer would be read as the next call's, and a
     * request sent in part as the start of the next.
     */
    private <T> @Nullable T bounded(String method, Duration deadline, Step<T> step)
            throws IOException {
        try {
            return step.run();
        } catch (InterruptedIOException ex) {
            try {
                connection.close();
            } catch (IOException closing) {
                ex.addSuppressed(closing);
            }
            if (ex instanceof SocketTimeoutException) {
                final VarlinkTimeoutException timeout =
                        new VarlinkTimeoutException(method, deadline);
                timeout.initCause(ex);
                throw timeout;
            }
            throw ex;
        }
    }

    /**
     * Connects a channel, giving up after a time: a blocking connect has no limit of its own, so it
     * runs on a thread of its own, and closing the channel ends it.
     */
    private static void connect(
            SocketChannel channel, UnixDomainSocketAddress address, Duration within)
            throws IOException {
        final java.util.concurrent.FutureTask<Boolean> connecting =
                new java.util.concurrent.FutureTask<>(() -> channel.connect(address));
        // A platform thread: on a virtual one the JDK connects without blocking, and a Unix
        // socket whose backlog is full then refuses at once (EAGAIN) instead of waiting.
        Thread.ofPlatform().daemon().name("porelo-varlink-connect").start(connecting);
        try {
            connecting.get(within.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            channel.close();
            final SocketTimeoutException timeout =
                    new SocketTimeoutException("No connection within " + within.toMillis() + " ms");
            timeout.initCause(ex);
            throw timeout;
        } catch (InterruptedException ex) {
            channel.close();
            Thread.currentThread().interrupt();
            final InterruptedIOException interrupted =
                    new InterruptedIOException("Interrupted while connecting");
            interrupted.initCause(ex);
            throw interrupted;
        } catch (java.util.concurrent.ExecutionException ex) {
            final Throwable cause = ex.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(String.valueOf(cause), cause);
        }
    }

    private void send(String method, Map<String, Object> parameters, boolean more)
            throws IOException {
        connection.send(message(method, parameters, more));
    }

    /** Sends a call within a deadline, which its answer then shares. */
    private void send(
            String method, Map<String, Object> parameters, boolean more, Duration deadline)
            throws IOException {
        final Map<String, Object> call = message(method, parameters, more);
        bounded(
                method,
                deadline,
                () -> {
                    connection.send(call, deadline);
                    return Boolean.TRUE;
                });
    }

    private static Map<String, Object> message(
            String method, Map<String, Object> parameters, boolean more) {
        final Map<String, Object> call = new LinkedHashMap<>();
        call.put("method", method);
        call.put("parameters", parameters);
        if (more) {
            call.put("more", Boolean.TRUE);
        }
        return call;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> replyParameters(Map<String, Object> reply) {
        if (reply.get("error") instanceof String error) {
            final Object parameters = reply.get("parameters");
            throw new VarlinkException(
                    error,
                    parameters instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
        }
        final Object parameters = reply.get("parameters");
        return parameters instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }
}
