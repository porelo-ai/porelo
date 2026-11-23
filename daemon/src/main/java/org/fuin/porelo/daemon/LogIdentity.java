package org.fuin.porelo.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * What a page position is a position in: a task log, or the recording of a terminal session.
 *
 * <p>A position alone holds for whatever lies at the path now, and a new run's log or a session
 * begun anew can have grown past it: an earlier client then read the new one's lines as the old
 * one's. So every reply that gives a position names what it is a position in, and a page asked with
 * another name is answered as gone. The name is opaque: a digest of the file's key and its creation
 * time, which a log written to and a recording added to keep, and a file made anew in its place
 * does not.
 */
final class LogIdentity {

    private LogIdentity() {}

    /**
     * Returns the identity of a log file or of a recording's directory.
     *
     * @param path The file or directory, never followed as a link.
     * @return 32 lowercase hexadecimal digits.
     * @throws IOException When its attributes cannot be read, as for one gone.
     */
    static String of(final Path path) throws IOException {
        final BasicFileAttributes attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        final String said = attributes.fileKey() + "@" + attributes.creationTime().toInstant();
        try {
            final byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(said.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Every Java runtime has SHA-256", ex);
        }
    }
}
