package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.fuin.porelo.vault.PersonCard;

/**
 * Where this machine keeps the person's own card: {@code $XDG_CONFIG_HOME/porelo/identity/card},
 * and its key's public line beside it as {@code key.pub}. No private key is ever kept here; it is
 * the agent's.
 */
final class IdentityStore {

    /** The longest card read, as a registry reads one. */
    static final int MAX_CARD = 64 * 1024;

    private final Path home;

    private final Path directory;

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    IdentityStore(final PoreloContext context) {
        this.home = context.paths().xdg().config();
        this.directory = home.resolve("identity");
    }

    /**
     * The card file.
     *
     * @return Its path, whether or not it exists.
     */
    Path cardFile() {
        return directory.resolve("card");
    }

    /**
     * The card kept here, read as a registry reads it, or empty when none is kept.
     *
     * @return The card.
     * @throws IOException When the file is there but not a regular file, too large or unreadable.
     * @throws org.fuin.porelo.vault.CardException When it does not read as a card.
     */
    Optional<PersonCard> card() throws IOException {
        final Path file = cardFile();
        if (!holdsCard()) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(file + " is not a regular file");
        }
        if (Files.size(file) > MAX_CARD) {
            throw new IOException(file + " is larger than a card may be");
        }
        return Optional.of(
                PersonCard.read(Files.readString(file, StandardCharsets.UTF_8), file.toString()));
    }

    /**
     * Keeps a card, replacing the one kept, with its key's line beside it; each written whole and
     * owner-only, so a reader never finds half of one.
     *
     * @param card The card's text, as {@link PersonCard#write} made it.
     * @throws IOException When it cannot be written.
     */
    void keep(final String card) throws IOException {
        final PersonCard read = PersonCard.read(card, "the card to keep");
        privateDirectory(home, directory);
        write(directory.resolve("key.pub"), read.key() + " " + read.login() + "\n");
        write(cardFile(), card);
    }

    /**
     * Makes a directory under an XDG home owner-only, as {@link
     * org.fuin.porelo.vault.PrivateDirectory#make} does.
     *
     * @param home Porelo's directory in an XDG base directory.
     * @param directory The directory, inside the home.
     * @return The directory.
     * @throws IOException When one is a link, another's, no directory, or cannot be made.
     */
    static Path privateDirectory(final Path home, final Path directory) throws IOException {
        return org.fuin.porelo.vault.PrivateDirectory.make(home, directory);
    }

    /**
     * Whether such a directory is there to read from, as {@link
     * org.fuin.porelo.vault.PrivateDirectory#found} judges it.
     *
     * @param home Porelo's directory in an XDG base directory.
     * @param directory The directory, inside the home.
     * @return {@code false} when it is not there.
     * @throws IOException When one on the way is a link, another's or no directory.
     */
    static boolean privateDirectoryFound(final Path home, final Path directory) throws IOException {
        return org.fuin.porelo.vault.PrivateDirectory.found(home, directory);
    }

    /**
     * Whether a card file is kept here, whatever it holds, its directories judged first.
     *
     * @return Whether one is.
     * @throws IOException When a directory on the way is a link, another's or no directory.
     */
    boolean holdsCard() throws IOException {
        return privateDirectoryFound(home, directory)
                && Files.exists(cardFile(), LinkOption.NOFOLLOW_LINKS);
    }

    private void write(final Path file, final String text) throws IOException {
        final Path staged =
                Files.createTempFile(
                        directory,
                        ".",
                        ".staged",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(staged, text, StandardCharsets.UTF_8);
            Files.move(
                    staged,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
