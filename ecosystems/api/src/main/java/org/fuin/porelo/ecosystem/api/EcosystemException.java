package org.fuin.porelo.ecosystem.api;

import java.io.Serial;

/**
 * A build-tool plugin could not be started or understood: a fault of the installation, not of the
 * directory it was asked about.
 */
public final class EcosystemException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param message What went wrong, naming the plugin.
     */
    public EcosystemException(final String message) {
        super(message);
    }

    /**
     * Constructor with a cause.
     *
     * @param message What went wrong, naming the plugin.
     * @param cause Why.
     */
    public EcosystemException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
