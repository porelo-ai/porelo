package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A silent owner cannot indefinitely delay erasure or claim that locking succeeded. */
@Timeout(10)
class HostVaultConnectionTest {
    @Test
    void forgettingWithoutASocketNamesItsAbsence(@TempDir Path dir) {
        final Path socket = dir.resolve("absent.sock");
        final var connection = new HostVaultConnection(socket, dir.resolve("vault.bin"));
        assertThatThrownBy(connection::forget)
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("no porelod reachable", socket.toString())
                .hasRootCauseMessage("There is no socket at the expected path");
    }

    @Test
    void anExistingNonSocketReportsAConnectionFailure(@TempDir Path dir) throws Exception {
        final Path socket = java.nio.file.Files.createFile(dir.resolve("regular-file"));
        assertThatThrownBy(() -> new HostVaultConnection(socket, dir.resolve("vault.bin")).forget())
                .as("an existing path that cannot connect is distinct from absence")
                .isInstanceOf(HostVaultConnection.Unreachable.class)
                .hasMessageContaining(socket.toString())
                .hasRootCauseInstanceOf(java.net.SocketException.class);
    }

    @Test
    void aSymlinkAtTheSocketPathIsRefusedWithoutFollowingIt(@TempDir Path dir) throws Exception {
        final Path target = java.nio.file.Files.createFile(dir.resolve("target"));
        final Path socket =
                java.nio.file.Files.createSymbolicLink(dir.resolve("linked.sock"), target);
        assertThatThrownBy(() -> new HostVaultConnection(socket, dir.resolve("vault.bin")).forget())
                .as("a socket alias must be refused before connecting to its target")
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("symbolic link", socket.toString());
    }

    @Test
    void aDanglingLinkOrALinkedDirectoryIsRefusedAndNeverFollowed(@TempDir Path dir)
            throws Exception {
        // Asked whether the vault is open, a dangling link answered "no" instead of refusing.
        final Path dangling =
                java.nio.file.Files.createSymbolicLink(
                        dir.resolve("dangling.sock"), dir.resolve("nowhere"));
        assertThatThrownBy(
                        () -> new HostVaultConnection(dangling, dir.resolve("vault.bin")).isOpen())
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("symbolic link", dangling.toString());

        final Path real = java.nio.file.Files.createDirectory(dir.resolve("real"));
        final Path linked = java.nio.file.Files.createSymbolicLink(dir.resolve("linked"), real);
        final Path socket = linked.resolve("porelod.sock");
        // A link at the far end too: looking at the socket first would have passed through the
        // directory's link and named the socket.
        java.nio.file.Files.createSymbolicLink(
                real.resolve("porelod.sock"), dir.resolve("nowhere"));
        assertThatThrownBy(() -> new HostVaultConnection(socket, dir.resolve("vault.bin")).forget())
                .as("the directory holding the socket is not followed either")
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("directory must not be a symbolic link", linked.toString());
        assertThatThrownBy(
                        () ->
                                new HostVaultConnection(
                                                linked.resolve(".").resolve("porelod.sock"),
                                                dir.resolve("vault.bin"))
                                        .forget())
                .as("nor is it when the path spells it with a dot")
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("symbolic link", linked.toString());
    }

    @Test
    void theDefaultPredicateRefusesWhenKernelCredentialsCannotBeRead(@TempDir Path dir)
            throws Exception {
        final var connection =
                new HostVaultConnection(dir.resolve("daemon.sock"), dir.resolve("vault.bin"));
        final var ownUid = defaultUidCheck(connection);
        final var channel =
                java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX);
        channel.close();
        assertThatThrownBy(() -> ownUid.test(channel))
                .as("unreadable kernel credentials must never authorize a share exchange")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("peer credentials");
    }

    @Test
    void theDefaultPredicateAcceptsTheKernelUidOfAConnectedServer(@TempDir Path dir)
            throws Exception {
        final Path socket = dir.resolve("same-uid.sock");
        final var connection = new HostVaultConnection(socket, dir.resolve("vault.bin"));
        final var ownUid = defaultUidCheck(connection);
        try (var listener =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            listener.bind(java.net.UnixDomainSocketAddress.of(socket));
            try (var channel =
                            java.nio.channels.SocketChannel.open(
                                    java.net.UnixDomainSocketAddress.of(socket));
                    var accepted = listener.accept()) {
                assertThat(ownUid.test(channel))
                        .as("the production predicate accepts this uid")
                        .isTrue();
                assertThat(org.fuin.porelo.core.process.SocketPeer.read(channel).uid())
                        .as("SO_PEERCRED returns the server's numeric uid")
                        .isEqualTo(
                                ((Number) java.nio.file.Files.getAttribute(dir, "unix:uid"))
                                        .longValue());
            }
        }
    }

    private static java.util.function.Predicate<java.nio.channels.SocketChannel> defaultUidCheck(
            HostVaultConnection connection) throws Exception {
        final var predicate = HostVaultConnection.class.getDeclaredField("ownUid");
        predicate.setAccessible(true);
        return (java.util.function.Predicate<java.nio.channels.SocketChannel>)
                predicate.get(connection);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "lock,true",
        "clear,true",
        "lock,false",
        "clear,false"
    })
    void anUnreachableDaemonCountsAsEmptyOnlyIfItIsNotRunning(
            String action, boolean running, @TempDir Path dir) throws Exception {
        final var runner =
                new FakeCommandRunner()
                        .answering(
                                "ps -u",
                                running ? "porelod /usr/bin/porelod\n" : "bash /bin/bash\n");
        final var context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                XdgPaths.of(
                                        name ->
                                                name.equals("XDG_RUNTIME_DIR")
                                                        ? dir.resolve("run").toString()
                                                        : null,
                                        dir),
                                dir.resolve("bin")),
                        args -> 0);
        context.vault().write(java.util.Map.of(), new byte[32]);
        if (action.equals("lock")) {
            final var out = new StringWriter();
            assertThat(VaultLockCommand.lock(context, new PrintWriter(out)))
                    .isEqualTo(running ? 70 : 0);
            if (running)
                assertThat(out.toString()).contains(context.paths().daemonSocket().toString());
        } else {
            final var result = new VaultClearing(context).clear(false, true);
            assertThat(result.refused()).isEqualTo(running);
            assertThat(context.vault().exists()).isEqualTo(running);
            if (running)
                assertThat(result.items())
                        .anySatisfy(
                                item ->
                                        assertThat(item.why())
                                                .contains(
                                                        context.paths().daemonSocket().toString()));
        }
        assertThat(runner.invocations())
                .anySatisfy(command -> assertThat(command.program()).isEqualTo("ps"));
    }

    @Test
    void anotherServerUidIsRefusedBeforeAShareIsSent(@TempDir Path dir) throws Exception {
        final Path socket = dir.resolve("foreign.sock");
        final var bytes = new java.util.concurrent.atomic.AtomicInteger();
        try (var listener =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            listener.bind(java.net.UnixDomainSocketAddress.of(socket));
            final Thread server =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try (var client = listener.accept()) {
                                            final var buffer = java.nio.ByteBuffer.allocate(4096);
                                            int count;
                                            while ((count = client.read(buffer)) != -1) {
                                                bytes.addAndGet(count);
                                                buffer.clear();
                                            }
                                        } catch (java.io.IOException ex) {
                                            throw new AssertionError(ex);
                                        }
                                    });
            try {
                final var constructor =
                        HostVaultConnection.class.getDeclaredConstructor(
                                Path.class,
                                Path.class,
                                Duration.class,
                                java.util.function.Predicate.class);
                constructor.setAccessible(true);
                final HostVaultConnection connection =
                        (HostVaultConnection)
                                constructor.newInstance(
                                        socket,
                                        dir.resolve("vault.bin"),
                                        Duration.ofSeconds(2),
                                        (java.util.function.Predicate<
                                                        java.nio.channels.SocketChannel>)
                                                channel -> false);
                assertThatThrownBy(() -> connection.keep(new byte[32]))
                        .isInstanceOf(VaultException.class)
                        .hasMessageContaining("uid", socket.toString());
                server.join(3000);
                assertThat(bytes.get()).isZero();
            } finally {
                server.interrupt();
                server.join();
            }
        }
    }

    @Test
    void anUnansweredExchangeTimesOutAndInterruptsItsWorker() throws Exception {
        final CountDownLatch interrupted = new CountDownLatch(1);
        assertThatThrownBy(
                        () ->
                                HostVaultConnection.within(
                                        () -> waitForInterruption(interrupted),
                                        Duration.ofSeconds(1)))
                .as("an owner that never replies has a bounded wait")
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("did not answer");
        assertThat(interrupted.await(5, TimeUnit.SECONDS))
                .as("cancellation interrupts blocking socket operations")
                .isTrue();
    }

    @Test
    void anInterruptedCallerCancelsTheExchangeAndKeepsItsInterrupt() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final var restored = new java.util.concurrent.atomic.AtomicBoolean();
        final Thread caller =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        HostVaultConnection.within(
                                                () -> {
                                                    started.countDown();
                                                    return waitForInterruption(interrupted);
                                                },
                                                Duration.ofSeconds(5));
                                    } catch (Throwable ex) {
                                        failure.set(ex);
                                    } finally {
                                        restored.set(Thread.currentThread().isInterrupted());
                                    }
                                });
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).as("the exchange has begun").isTrue();
            caller.interrupt();
            caller.join();
            assertThat(failure.get())
                    .as("interruption refuses the operation")
                    .isInstanceOf(VaultException.class);
            assertThat(restored).as("the caller retains its interrupt").isTrue();
            assertThat(interrupted.await(5, TimeUnit.SECONDS))
                    .as("the exchange was canceled too")
                    .isTrue();
        } finally {
            caller.interrupt();
        }
    }

    @Test
    void lockReportsNonzeroWhenTheOwnerNeverAnswers(@TempDir Path dir) {
        final HostVaultAccess silent =
                new HostVaultAccess() {
                    public void keep(byte[] share) {
                        throw new UnsupportedOperationException();
                    }

                    public boolean isOpen() {
                        return false;
                    }

                    public byte[] share() {
                        throw new UnsupportedOperationException();
                    }

                    public boolean forget() {
                        return HostVaultConnection.within(
                                () -> waitForInterruption(new CountDownLatch(1)),
                                Duration.ofSeconds(1));
                    }
                };
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                args -> 0)
                        .withHostVault(silent);
        final StringWriter output = new StringWriter();
        assertThat(VaultLockCommand.lock(context, new PrintWriter(output)))
                .as("a silent owner cannot produce a successful lock")
                .isNotZero();
        assertThat(output.toString())
                .as("the failure names the unresponsive owner and local erasure")
                .contains("did not answer", "local keyring passphrase:", "device share:");
    }

    @Test
    void aSilentSocketIsDisconnectedAtTheDeadline(@TempDir Path dir) throws Exception {
        final Path socket = dir.resolve("silent.sock");
        final CountDownLatch disconnected = new CountDownLatch(1);
        try (var listener =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            listener.bind(java.net.UnixDomainSocketAddress.of(socket));
            final Thread server =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try (var client = listener.accept()) {
                                            final var buffer = java.nio.ByteBuffer.allocate(1024);
                                            while (client.read(buffer) != -1) {
                                                buffer.clear();
                                            }
                                            disconnected.countDown();
                                        } catch (java.io.IOException ex) {
                                            throw new AssertionError(ex);
                                        }
                                    });
            try {
                final var connection =
                        new HostVaultConnection(
                                socket, dir.resolve("vault.bin"), Duration.ofSeconds(1));
                assertThatThrownBy(connection::forget)
                        .as("an accepted socket still has a reply deadline")
                        .isInstanceOf(VaultException.class)
                        .hasMessageContaining("did not answer");
                assertThat(disconnected.await(5, TimeUnit.SECONDS))
                        .as("interrupting the worker closes the connected socket")
                        .isTrue();
            } finally {
                server.interrupt();
                server.join();
            }
        }
    }

    private static boolean waitForInterruption(CountDownLatch interrupted) {
        try {
            new CountDownLatch(1).await();
            throw new AssertionError("the owner must not return a reply");
        } catch (InterruptedException ex) {
            interrupted.countDown();
            throw new VaultException("exchange interrupted", ex);
        }
    }
}
