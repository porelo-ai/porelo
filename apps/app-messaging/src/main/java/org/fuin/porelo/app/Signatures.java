package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Keeps a message's {@code .sig} beside the bytes it signs when the message moves. */
final class Signatures {

    private static final String SUFFIX = ".sig";

    private Signatures() {}

    /**
     * Moves the signature lying beside {@code from} to lie beside {@code to}, replacing one there;
     * with none to move, deletes the one lying beside {@code to}, which belongs to older bytes.
     */
    static void moveBeside(final Path from, final Path to) throws IOException {
        final Path source = from.resolveSibling(from.getFileName() + SUFFIX);
        final Path destination = to.resolveSibling(to.getFileName() + SUFFIX);
        if (Files.isRegularFile(source)) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(destination);
        }
    }
}
