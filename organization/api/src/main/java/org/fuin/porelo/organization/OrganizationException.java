package org.fuin.porelo.organization;

import org.jspecify.annotations.Nullable;

/** The organization, or what is kept of it, is refused; the message says why, quoting nothing. */
public final class OrganizationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Makes the refusal.
     *
     * @param message Why.
     * @param cause What refused it.
     */
    public OrganizationException(final @Nullable String message, final Throwable cause) {
        super(message, cause);
    }
}
