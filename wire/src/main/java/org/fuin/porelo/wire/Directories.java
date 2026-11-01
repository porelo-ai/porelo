package org.fuin.porelo.wire;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Makes directories as {@link Files#createDirectories} does, none of them writable by the group or
 * by others, whatever the account's umask.
 *
 * <p>An account whose umask is {@code 002} got {@code ~/.config}, {@code ~/.local/share} and every
 * directory below them group-writable, and a program that checks the directories above its own
 * files (the host's helpers do) then refused the account's whole home. Only the directories this
 * makes are changed; one that was there keeps its mode.
 */
public final class Directories {

    private Directories() {}

    /**
     * Makes a directory and every missing one above it.
     *
     * @param directory The directory; {@code null} is refused as {@link Files#createDirectories}
     *     refuses it, which callers that hand on a path's parent rely on.
     * @param attributes What {@link Files#createDirectories} takes; the group's and others' write
     *     permission is taken from each directory made all the same.
     * @return The directory.
     * @throws IOException As {@link Files#createDirectories} throws.
     * @throws NullPointerException For no directory.
     */
    public static Path create(final @Nullable Path directory, final FileAttribute<?>... attributes)
            throws IOException {
        Objects.requireNonNull(directory, "directory");
        final List<Path> missing = new ArrayList<>();
        for (Path at = directory.toAbsolutePath(); at != null; at = at.getParent()) {
            if (Files.exists(at)) {
                break;
            }
            missing.add(at);
        }
        final Path made = Files.createDirectories(directory, attributes);
        for (final Path each : missing.reversed()) {
            unwritable(each);
        }
        return made;
    }

    private static void unwritable(final Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        final Set<PosixFilePermission> mode;
        try {
            mode = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
        } catch (final UnsupportedOperationException ex) {
            // A file system without POSIX permissions has no group to keep out.
            return;
        }
        if (mode.remove(PosixFilePermission.GROUP_WRITE)
                | mode.remove(PosixFilePermission.OTHERS_WRITE)) {
            Files.setPosixFilePermissions(directory, mode);
        }
    }
}
