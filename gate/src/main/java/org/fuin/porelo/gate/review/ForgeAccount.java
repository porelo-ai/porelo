package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * An account at a forge, stamped from the forge adapter's answer for the forge it reads, never from
 * an item's text.
 *
 * @param forge The forge instance's host, such as {@code github.com}.
 * @param id The account's id at that forge, which a renamed account keeps.
 * @param name The account's name at that forge, as shown.
 */
public record ForgeAccount(String forge, String id, String name) implements Producer {

    /** The longest account id and name taken, as profile 2 bounds them. */
    public static final int ID_LIMIT = 64;

    /** The longest account name taken. */
    public static final int NAME_LIMIT = 256;

    /**
     * Refuses a blank part, an overlong id or name, and a forge, id or name with a character a
     * comment's text could not carry on one line, since all three are shown.
     */
    public ForgeAccount {
        Objects.requireNonNull(forge, "forge");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (forge.isBlank() || !printable(forge)) {
            throw new IllegalArgumentException("forge is blank or not printable");
        }
        if (id.isBlank() || id.length() > ID_LIMIT || !printable(id)) {
            throw new IllegalArgumentException("account id is blank, too long or not printable");
        }
        if (name.isBlank() || name.length() > NAME_LIMIT || !printable(name)) {
            throw new IllegalArgumentException("account name is blank, too long or not printable");
        }
    }

    @Override
    public String words() {
        return "forge account " + name;
    }

    private static boolean printable(final String text) {
        return CommentFields.lineCharacterRule(text) == null;
    }
}
