package org.fuin.porelo.supervisor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Carries bytes from a port inside a task's network namespace to the broker's socket on the host.
 *
 * <p><strong>Why this exists rather than binding the broker itself in the namespace.</strong> An
 * agent that can only be given a URL needs something listening on an address it can dial, and a
 * host-side listener is either unreachable from a rootless container or bound to every interface.
 * Putting the broker in the container's network namespace solves that and creates something worse:
 * the process keeps the host's filesystem, so it reads the host's {@code /etc/resolv.conf} and
 * tries a resolver that does not exist in that namespace - measured, and it fails as "could not
 * reach the provider". Its egress would also be governed by the task's own firewall.
 *
 * <p>A relay has neither problem. It resolves nothing and connects to nothing but a local file, so
 * the broker stays on the host with the host's DNS, the host's egress and the credential.
 */
public final class SocketRelay implements Runnable, AutoCloseable {

    private final ServerSocketChannel server;

    private final Path socket;

    /**
     * The one thread every upstream connection is made on where the AppArmor task profile holds,
     * entered into that profile before the first; {@code null} where none holds.
     */
    private final java.util.concurrent.@Nullable ExecutorService connector;

    private volatile boolean running = true;

    /**
     * Constructor.
     *
     * <p><strong>Where the task profile holds, the relay is held to it at its connect.</strong> An
     * agent that is given a URL reaches the broker through this process, and a task's own connects
     * are refused by the kernel unless the socket's peer wears {@code porelo}; the relay connects
     * on the task's behalf and must be refused the same. So every upstream connection is made on
     * one platform thread of its own, which enters the task's profile first: AppArmor labels a
     * thread, and the kernel then checks the relay's connect against the same peer rule as the
     * task's.
     *
     * <p>This stands where reading the peer's pid from {@code SO_PEERCRED} and its label from
     * {@code /proc/<pid>/attr/apparmor/current} was planned. Java's {@code SO_PEERCRED} answers a
     * user and a group and no pid, and getting the pid takes the channel's file descriptor, which
     * Porelo keeps clear of (see {@code SocketPeer}); the kernel's own check needs neither, and
     * cannot be raced by a peer that exits and leaves its pid to another process.
     *
     * <p>A platform thread, never a virtual one: a virtual thread runs on whichever carrier is
     * free, and the label belongs to the carrier. The thread never leaves the profile, which has no
     * way out; the pumping threads stay as they were and only move bytes on connections already
     * made.
     *
     * @param address Address to listen on, inside the task's namespace.
     * @param socket Broker socket on the host to forward to.
     * @param profile The AppArmor task profile to connect from, or {@code null} where none holds.
     * @param enter Puts the calling thread into a profile; {@code false} when the kernel refused.
     * @throws SupervisorException If the relay cannot listen, or the kernel refused the profile:
     *     unconfined, the relay would connect to whatever bound the socket.
     */
    public SocketRelay(
            InetSocketAddress address,
            Path socket,
            @Nullable String profile,
            java.util.function.Predicate<String> enter) {
        this.socket = socket;
        this.connector = profile == null ? null : confined(profile, enter);
        try {
            server = ServerSocketChannel.open();
            server.bind(address);
        } catch (IOException ex) {
            if (connector != null) {
                connector.shutdownNow();
            }
            throw new SupervisorException("Could not bind the relay to " + address, ex);
        }
    }

    /**
     * Starts the connecting thread and puts it into the profile.
     *
     * @param profile The profile.
     * @param enter Puts the calling thread into it.
     * @return The thread, as an executor.
     */
    private static java.util.concurrent.ExecutorService confined(
            String profile, java.util.function.Predicate<String> enter) {
        final java.util.concurrent.ExecutorService thread =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().name("porelo-relay-connect").daemon(true).factory());
        final boolean entered;
        try {
            entered = thread.submit(() -> enter.test(profile)).get();
        } catch (java.util.concurrent.ExecutionException ex) {
            thread.shutdownNow();
            throw new SupervisorException("The relay could not enter " + profile, ex);
        } catch (InterruptedException ex) {
            thread.shutdownNow();
            Thread.currentThread().interrupt();
            throw new SupervisorException("Interrupted while entering " + profile, ex);
        }
        if (!entered) {
            thread.shutdownNow();
            throw new SupervisorException(
                    "The kernel refused the relay the AppArmor profile "
                            + profile
                            + ", and outside it the relay would connect to a socket any process"
                            + " bound");
        }
        return thread;
    }

    /**
     * Returns where the relay listens.
     *
     * @return The bound address, with the port chosen when port 0 was asked for.
     */
    public InetSocketAddress address() {
        try {
            return (InetSocketAddress) server.getLocalAddress();
        } catch (IOException ex) {
            throw new SupervisorException("The relay's address cannot be read", ex);
        }
    }

    @Override
    public void run() {
        while (running) {
            final SocketChannel client;
            try {
                client = server.accept();
            } catch (IOException ex) {
                if (running) {
                    continue;
                }
                return;
            }
            // One virtual thread per connection, like the broker: an agent opens several and a slow
            // response on one must not stall the next.
            Thread.ofVirtual().start(() -> relay(client));
        }
    }

    private void relay(SocketChannel client) {
        try (client;
                SocketChannel upstream = connected()) {
            final Thread back = Thread.ofVirtual().start(() -> pump(upstream, client));
            pump(client, upstream);
            back.join();
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Opens and connects the upstream socket, on the confined thread where there is one.
     *
     * @return The connection.
     * @throws IOException If it cannot be made, or the kernel refused the peer.
     * @throws InterruptedException If interrupted while the confined thread connects.
     */
    private SocketChannel connected() throws IOException, InterruptedException {
        if (connector == null) {
            return open(socket);
        }
        try {
            return connector.submit(() -> open(socket)).get();
        } catch (java.util.concurrent.ExecutionException ex) {
            if (ex.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("The relay could not connect to " + socket, ex.getCause());
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            throw new IOException("The relay is closed", ex);
        }
    }

    private static SocketChannel open(Path socket) throws IOException {
        final SocketChannel upstream = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            upstream.connect(UnixDomainSocketAddress.of(socket));
            return upstream;
        } catch (IOException ex) {
            upstream.close();
            throw ex;
        }
    }

    private static void pump(SocketChannel from, SocketChannel to) {
        final ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
        try {
            while (from.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    to.write(buffer);
                }
                buffer.clear();
            }
        } catch (IOException ex) {
            // A closed connection is the normal end of a relayed request.
        }
        try {
            to.shutdownOutput();
        } catch (IOException ex) {
            // Already gone.
        }
    }

    @Override
    public void close() {
        running = false;
        if (connector != null) {
            connector.shutdownNow();
        }
        try {
            server.close();
        } catch (IOException ex) {
            // Nothing useful to do while shutting down.
        }
    }
}
