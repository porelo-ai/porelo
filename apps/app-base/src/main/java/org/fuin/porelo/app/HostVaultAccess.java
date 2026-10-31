package org.fuin.porelo.app;

/** The daemon's volatile opening share for a vault with no passphrase slot. */
public interface HostVaultAccess {
    /** Keeps a verified share until lock or daemon exit. */
    void keep(byte[] share);

    /** Reports whether a share is held, without copying it. */
    boolean isOpen();

    /** Returns a temporary copy, which the caller must erase. */
    byte[] share();

    /** Takes the open state and a temporary copy in the same owner transaction. */
    default java.util.Optional<byte[]> held() {
        synchronized (this) {
            return isOpen() ? java.util.Optional.of(share()) : java.util.Optional.empty();
        }
    }

    /** Erases the held share, returning whether one was held. */
    boolean forget();
}
