package org.fuin.porelo.vault;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.fuin.porelo.wire.Directories;

/**
 * A directory under one of Porelo's XDG homes that only this account reads and writes: each
 * directory from the home down is a directory of this account's, never a link, whether it is made
 * or only read from.
 */
public final class PrivateDirectory {

    private PrivateDirectory() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Makes a directory owner-only, and each between the home and it as needed: each below the home
     * is set to {@code rwx------} whatever the umask or a former mode left.
     *
     * @param home Porelo's directory in an XDG base directory, such as {@code
     *     $XDG_STATE_HOME/porelo}.
     * @param directory The directory, inside the home.
     * @return The directory.
     * @throws IOException When one is a link, another's, no directory, or cannot be made.
     */
    public static Path make(final Path home, final Path directory) throws IOException {
        Directories.create(Objects.requireNonNull(home.getParent(), "a home has a parent"));
        for (final Path each : steps(home, directory)) {
            if (!Files.exists(each, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(each);
                } catch (FileAlreadyExistsException ex) {
                    // Made by a run at once; it is judged below as any found.
                }
            }
            judge(each);
            if (!each.equals(home)) {
                // Set, not asked of the umask, which may leave the directory readable to others.
                Files.setPosixFilePermissions(each, PosixFilePermissions.fromString("rwx------"));
            }
        }
        return directory;
    }

    /**
     * Whether a directory {@link #make} makes is there to read from, judged as it judges them, from
     * the home down: a file read through a link or another account's directory would be anybody's.
     *
     * @param home Porelo's directory in an XDG base directory.
     * @param directory The directory, inside the home.
     * @return {@code false} when it, or one above it, is not there: nothing is kept in it.
     * @throws IOException When one is a link, another's or no directory.
     */
    public static boolean found(final Path home, final Path directory) throws IOException {
        for (final Path each : steps(home, directory)) {
            if (!Files.exists(each, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            judge(each);
        }
        return true;
    }

    /** The home, then each directory from it down to the directory. */
    private static List<Path> steps(final Path home, final Path directory) {
        if (!directory.startsWith(home) || directory.equals(home)) {
            throw new IllegalArgumentException(directory + " does not lie in " + home);
        }
        final List<Path> steps = new ArrayList<>(List.of(home));
        Path step = home;
        for (final Path name : home.relativize(directory)) {
            step = step.resolve(name);
            steps.add(step);
        }
        return steps;
    }

    /** Refuses a step that is a link, no directory, or another account's. */
    private static void judge(final Path each) throws IOException {
        final PosixFileAttributes seen =
                Files.readAttributes(each, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!seen.isDirectory()) {
            throw new IOException(each + " is not a directory (a link is not followed)");
        }
        if (!seen.owner().equals(Files.getOwner(Path.of("/proc/self")))) {
            throw new IOException(each + " is not owned by this account");
        }
    }
}
