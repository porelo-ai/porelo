package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;

/** Asks the owner over the account's owner-only daemon socket. */
final class HostVaultConnection implements HostVaultAccess {
    private final Path socket;

    private final String vault;

    private final java.time.Duration timeout;

    private final java.util.function.Predicate<java.nio.channels.SocketChannel> ownUid;

    HostVaultConnection(Path socket, Path vault) {
        this(socket, vault, java.time.Duration.ofSeconds(5));
    }

    HostVaultConnection(Path socket, Path vault, java.time.Duration timeout) {
        this(
                socket,
                vault,
                timeout,
                channel -> org.fuin.porelo.core.process.SocketPeer.read(channel).sameUser());
    }

    HostVaultConnection(
            Path socket,
            Path vault,
            java.time.Duration timeout,
            java.util.function.Predicate<java.nio.channels.SocketChannel> ownUid) {
        this.socket = socket;
        this.vault = vault.toAbsolutePath().normalize().toString();
        this.timeout = timeout;
        this.ownUid = ownUid;
    }

    @Override
    public void keep(byte[] share) {
        call("KeepHostVaultShare", Map.of("share", Base64.getEncoder().encodeToString(share)));
    }

    @Override
    public boolean isOpen() {
        refuseLinks();
        return Files.exists(socket, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Boolean.TRUE.equals(call("HostVaultShare", Map.of()).get("open"));
    }

    @Override
    public byte[] share() {
        final Object share = call("ReadHostVaultShare", Map.of()).get("share");
        if (!(share instanceof String encoded)) {
            throw new VaultException("The daemon did not return a host vault share");
        }
        try {
            final byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != 32) {
                java.util.Arrays.fill(bytes, (byte) 0);
                throw new VaultException("The daemon returned an invalid host vault share");
            }
            return bytes;
        } catch (IllegalArgumentException ex) {
            throw new VaultException("The daemon returned an invalid host vault share", ex);
        }
    }

    @Override
    public boolean forget() {
        refuseLinks();
        if (!Files.exists(socket, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new Unreachable(
                    socket, new IOException("There is no socket at the expected path"));
        }
        return Boolean.TRUE.equals(call("ForgetHostVaultShare", Map.of()).get("wasHeld"));
    }

    private Map<String, Object> call(String method, Map<String, Object> parameters) {
        return within(() -> exchange(method, parameters), timeout);
    }

    static <T> T within(java.util.function.Supplier<T> exchange, java.time.Duration timeout) {
        final var task = new java.util.concurrent.FutureTask<T>(exchange::get);
        Thread.ofPlatform().daemon(true).name("host-vault-call").start(task);
        try {
            return task.get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            task.cancel(true);
            throw new VaultException(
                    "The host vault owner did not answer; retry after checking porelod", ex);
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new VaultException("Interrupted while waiting for the host vault owner", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            final Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            if (cause instanceof Error failure) {
                throw failure;
            }
            throw new VaultException("Cannot ask the host vault owner", ex);
        }
    }

    /** Refuses a link at the socket or at the directory holding it: neither is followed. */
    private void refuseLinks() {
        // The directory first: looking at the socket itself passes through it.
        final Path parent;
        try {
            // Its own name, so a trailing dot cannot have the check look through a link.
            parent = org.fuin.porelo.wire.varlink.VarlinkServer.directoryOf(socket);
        } catch (IOException ex) {
            throw new VaultException(ex.getMessage() + ": " + socket);
        }
        if (Files.isSymbolicLink(parent)) {
            throw new VaultException(
                    "The daemon socket's directory must not be a symbolic link: " + parent);
        }
        if (Files.isSymbolicLink(socket)) {
            throw new VaultException("The daemon socket must not be a symbolic link: " + socket);
        }
    }

    private Map<String, Object> exchange(String method, Map<String, Object> parameters) {
        refuseLinks();
        try (VarlinkClient client =
                new VarlinkClient(
                        socket,
                        channel -> {
                            final boolean matches;
                            try {
                                matches = ownUid.test(channel);
                            } catch (RuntimeException ex) {
                                throw new VaultException(
                                        "Cannot establish the server uid at " + socket, ex);
                            }
                            if (!matches) {
                                throw new VaultException(
                                        "The server uid at "
                                                + socket
                                                + " differs from this account; refusing the host vault request");
                            }
                        })) {
            final Map<String, Object> scoped = new java.util.LinkedHashMap<>(parameters);
            scoped.put("vault", vault);
            return client.call("org.fuin.porelo.Tasks1." + method, scoped);
        } catch (VarlinkException ex) {
            if ("org.fuin.porelo.Tasks1.NotAHostVault".equals(ex.getErrorName())) {
                throw new org.fuin.porelo.vault.NotAHostVaultException();
            }
            if ("org.fuin.porelo.Tasks1.OtherVaultKey".equals(ex.getErrorName())) {
                throw new org.fuin.porelo.vault.OtherVaultKeyException(
                        "No keyslot takes that share");
            }
            final Map<String, Object> detail = ex.getParameters();
            if ("org.fuin.porelo.Tasks1.Failed".equals(ex.getErrorName())
                    && detail != null
                    && detail.get("message") instanceof String message) {
                throw new VaultException(message, ex);
            }
            if (ex.getErrorName() == null) throw new Unreachable(socket, ex);
            throw new VaultException(
                    "Cannot ask the host vault owner at " + socket + "; check porelod and retry",
                    ex);
        } catch (IOException ex) {
            throw new Unreachable(socket, ex);
        }
    }

    static final class Unreachable extends VaultException {
        private static final long serialVersionUID = 1L;

        Unreachable(Path socket, Throwable cause) {
            super(
                    "There is no porelod reachable at "
                            + socket
                            + "; check the daemon and its socket path",
                    cause);
        }
    }
}
