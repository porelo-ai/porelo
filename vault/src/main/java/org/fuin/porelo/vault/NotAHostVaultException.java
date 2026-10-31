package org.fuin.porelo.vault;

/** A passphrase or device slot makes this vault ineligible for host-key unlock or reset. */
public final class NotAHostVaultException extends VaultException {
    private static final long serialVersionUID = 1L;

    /** Names the slot-kind refusal without interpreting it as another person's key. */
    public NotAHostVaultException() {
        super("This is not a host vault; it has passphrase or other slots");
    }
}
