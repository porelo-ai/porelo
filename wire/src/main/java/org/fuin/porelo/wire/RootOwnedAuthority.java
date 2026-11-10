package org.fuin.porelo.wire;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Resolves a CA bundle through links that only root can replace. */
public final class RootOwnedAuthority {
    private RootOwnedAuthority() {}

    /** Uses the same bounded component walk for maintenance and person-side transfers. */
    public static Path resolve(
            Path path, Path anchor, int owner, RootOwnedFile.Attributes attributes)
            throws IOException {
        final Path resolved = walk(path, anchor, owner, attributes, 0);
        final var status = attributes.read(resolved);
        if (!(status.get("mode") instanceof Number mode)
                || (mode.intValue() & 0170000) != 0100000
                || !Files.isReadable(resolved)) {
            throw new IOException("CA is not a readable regular file");
        }
        return resolved;
    }

    private static Path walk(
            Path path, Path anchor, int owner, RootOwnedFile.Attributes attributes, int links)
            throws IOException {
        if (!path.isAbsolute() || !path.startsWith(anchor) || links > 40)
            throw new IOException("CA path is outside its trust anchor or has too many links");
        if (path.equals(anchor)) return path;
        final Path parent =
                walk(
                        Objects.requireNonNull(path.getParent(), "A non-root CA path has a parent"),
                        anchor,
                        owner,
                        attributes,
                        links);
        final Path resolved = parent.resolve(path.getFileName()).normalize();
        if (!resolved.startsWith(anchor)) throw new IOException("CA path leaves its trust anchor");
        final var status = attributes.read(resolved);
        if (!Integer.valueOf(owner).equals(status.get("uid")))
            throw new IOException("CA path is not owned by root");
        final int mode =
                ((Number)
                                Objects.requireNonNull(
                                        status.get("mode"), "Unix attributes include mode"))
                        .intValue();
        if ((mode & 0170000) == 0120000) {
            final Path target = Files.readSymbolicLink(resolved);
            return walk(
                    target.isAbsolute() ? target : parent.resolve(target),
                    anchor,
                    owner,
                    attributes,
                    links + 1);
        }
        if ((mode & 0022) != 0 || ((mode & 0170000) != 0040000 && (mode & 0170000) != 0100000))
            throw new IOException("CA path is not a directory or file only root can change");
        return resolved;
    }
}
