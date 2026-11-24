package org.fuin.porelo.gate.review;

/**
 * The account that owns Porelo: its daemon socket and its command line. Porelo cannot tell whether
 * a human or a program used it, so it is never called a person.
 */
public record OwnerAccount() implements Producer {

    @Override
    public String words() {
        return "owner account";
    }
}
