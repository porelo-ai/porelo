package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import org.fuin.porelo.vault.LockedSecret;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;
import org.jspecify.annotations.Nullable;

/** Owns the daemon's share, bound to the vault and serialized with lock and clear. */
public final class HostVaultOwner implements HostVaultAccess, AutoCloseable {
    private @Nullable LockedSecret secret;
    private @Nullable VaultFile vault;
    private @Nullable Path openedPath;
    private @Nullable String identity;
    private boolean closed;
    private long generation;
    private final Runnable invalidated;

    /** Creates an owner holding nothing. */
    public HostVaultOwner() {
        this(() -> {});
    }

    /** Creates an owner that drops any decrypted cache whenever it forgets. */
    public HostVaultOwner(Runnable invalidated) {
        this.invalidated = invalidated;
    }

    /** Binds this owner to its context's vault before it can accept a share. */
    public synchronized void bind(VaultFile value) {
        if (vault != null && !vault.path().equals(value.path())) {
            throw new VaultException("The host vault owner is already bound to another vault");
        }
        vault = value;
    }

    @Override
    public void keep(byte[] share) {
        final long before;
        final VaultFile file;
        try {
            synchronized (this) {
                release();
                if (closed) {
                    throw new VaultException("The host vault owner has stopped");
                }
                if (share.length != 32) {
                    throw new VaultException("A host vault share is 32 bytes");
                }
                file =
                        java.util.Objects.requireNonNull(
                                vault, "An owner must be bound before unlocking");
                before = generation;
            }
        } finally {
            invalidated.run();
        }
        final Path path = realPath(file);
        if (!file.isHostVault()) {
            throw new org.fuin.porelo.vault.NotAHostVaultException();
        }
        final String verified = file.shareIdentity(share);
        file.read(share);
        synchronized (this) {
            if (closed || generation != before) {
                throw new VaultException("The host vault was locked during unlock; unlock again");
            }
            if (!file.isHostVault()) throw new org.fuin.porelo.vault.NotAHostVaultException();
            if (!path.equals(realPath(file)) || !file.hasKeyIdentity(verified)) {
                throw new VaultException("The host vault changed during unlock; unlock again");
            }
            release();
            secret = new LockedSecret(share);
            openedPath = path;
            identity = verified;
        }
    }

    @Override
    public boolean isOpen() {
        boolean changed = false;
        try {
            synchronized (this) {
                if (secret == null) return false;
                if (matchesVault()) return true;
                changed = release();
                return false;
            }
        } finally {
            if (changed) invalidated.run();
        }
    }

    private boolean matchesVault() {
        if (secret == null || vault == null || identity == null || openedPath == null) {
            return false;
        }
        try {
            return openedPath.equals(realPath(vault))
                    && vault.isHostVault()
                    && vault.hasKeyIdentity(identity);
        } catch (VaultException ex) {
            return false;
        }
    }

    @Override
    public byte[] share() {
        return held().orElseThrow(
                        () ->
                                new VaultException(
                                        "The host vault is locked; use 'porelo host unlock <host>'"));
    }

    @Override
    public java.util.Optional<byte[]> held() {
        boolean changed = false;
        try {
            synchronized (this) {
                if (secret == null) return java.util.Optional.empty();
                if (!matchesVault()) {
                    changed = release();
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(secret.copy());
            }
        } finally {
            if (changed) invalidated.run();
        }
    }

    @Override
    public boolean forget() {
        try {
            synchronized (this) {
                return release();
            }
        } finally {
            // Cache reads can ask this owner while holding their own lock.
            invalidated.run();
        }
    }

    private boolean release() {
        generation++;
        final LockedSecret held = secret;
        secret = null;
        identity = null;
        openedPath = null;
        if (held == null) {
            return false;
        }
        held.close();
        return true;
    }

    private static Path realPath(VaultFile file) {
        try {
            return file.path().toRealPath();
        } catch (IOException ex) {
            throw new VaultException("Cannot resolve the host vault path", ex);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        forget();
    }
}
