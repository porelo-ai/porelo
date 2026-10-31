package org.fuin.porelo.vault;

/** No keyslot takes the supplied share; this outcome never authorizes a reset. */
public final class OtherVaultKeyException extends VaultException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param message What failed, without the share.
     */
    public OtherVaultKeyException(final String message) {
        super(message);
    }
}
