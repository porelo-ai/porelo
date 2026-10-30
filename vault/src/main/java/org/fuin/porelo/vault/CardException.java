package org.fuin.porelo.vault;

/** A card was not well formed, or was not signed as it must be; the message says which. */
public class CardException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message What is wrong, for a person to read.
     */
    public CardException(String message) {
        super(message);
    }
}
