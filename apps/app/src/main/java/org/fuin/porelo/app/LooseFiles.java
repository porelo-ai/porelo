package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.fuin.porelo.core.config.XdgPaths;

/**
 * Finds what Porelo left under the data and state directories and {@code ~/porelo/reviews} that
 * anyone but this account can read, and takes that away when asked.
 *
 * <p>A machine that ran tasks under an older Porelo has these at whatever the shell's umask was.
 * They hold unreviewed work and every project's source, and what covered them was the mode of a
 * home directory nobody here chose. Nothing changes them unasked: {@code porelo doctor} names them,
 * and {@code porelo tighten} is a person's decision.
 *
 * <p>Some are open on purpose and are never named or changed, because a running task would lose
 * them: what is mounted into a task, read there by a subordinate uid, and what a build copies into
 * an image, which keeps the mode it has here. They sit inside a directory of this account's alone.
 */
final class LooseFiles {

    private static final Set<PosixFilePermission> OTHERS =
            EnumSet.of(
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE);

    private final Path data;

    private final Path state;

    private final Path reviews;

    /**
     * Looks where this account's Porelo writes.
     *
     * @param paths The account's directories.
     */
    LooseFiles(final XdgPaths paths) {
        data = paths.data();
        state = paths.state();
        reviews = GatePaths.reviews(paths);
    }

    /**
     * Finds them.
     *
     * @return Each path anyone else can read, write or enter, in the order they were walked.
     * @throws IOException If one of the directories cannot be walked.
     */
    List<Path> found() throws IOException {
        final List<Path> loose = new ArrayList<>();
        for (final Path root : List.of(data, state, reviews)) {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                walk(root, loose);
            }
        }
        return List.copyOf(loose);
    }

    /**
     * Takes from each what anyone else had of it, parents first as {@link #found()} gives them: a
     * directory tightened first is one nobody else can then swap a child in, and a directory that
     * was not loose is one nobody else could. A path that is a link by now is left and said: the
     * JDK's chmod follows a link even through a view asked not to.
     *
     * <p>A file with a second name, as an object of a mirror hard-linked from a person's own clone
     * is, gets an inode of its own first: the mode belongs to the inode, and the person's clone is
     * not this command's to change.
     *
     * @param loose What {@link #found()} found.
     * @return The paths that could not be changed; a file another uid owns is one.
     */
    static List<Path> tighten(final List<Path> loose) {
        final List<Path> left = new ArrayList<>();
        for (final Path path : loose) {
            if (Files.isSymbolicLink(path)) {
                left.add(path);
                continue;
            }
            try {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && (int) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)
                                > 1) {
                    aloneUnderItsName(path);
                }
                final PosixFileAttributeView view =
                        Files.getFileAttributeView(
                                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                final Set<PosixFilePermission> mode = view.readAttributes().permissions();
                mode.removeAll(OTHERS);
                view.setPermissions(mode);
            } catch (final NoSuchFileException ex) {
                // Gone since it was found, which leaves nothing to read.
                continue;
            } catch (final IOException | UnsupportedOperationException ex) {
                left.add(path);
            }
        }
        return List.copyOf(left);
    }

    private static void aloneUnderItsName(final Path file) throws IOException {
        final Path copy =
                Files.createTempFile(
                        file.getParent(),
                        ".tighten",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(
                    copy, Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
            Files.move(copy, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    /**
     * Says them short: a loose directory stands for what is loose below it, since a mirror alone
     * can be thousands of files.
     *
     * @param loose What was found, parents first.
     * @return The topmost of them.
     */
    static List<Path> topmost(final List<Path> loose) {
        final List<Path> top = new ArrayList<>();
        for (final Path path : loose) {
            if (top.isEmpty() || !path.startsWith(top.getLast())) {
                top.add(path);
            }
        }
        return List.copyOf(top);
    }

    private void walk(final Path root, final List<Path> loose) throws IOException {
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            final Path directory, final BasicFileAttributes attributes)
                            throws IOException {
                        if (openOnPurpose(directory, true)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        note(directory, loose);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(
                            final Path file, final BasicFileAttributes attributes)
                            throws IOException {
                        if (attributes.isRegularFile() && !openOnPurpose(file, false)) {
                            note(file, loose);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(final Path file, final IOException ex)
                            throws IOException {
                        if (ex instanceof NoSuchFileException) {
                            return FileVisitResult.CONTINUE;
                        }
                        throw ex;
                    }
                });
    }

    private static void note(final Path path, final List<Path> loose) throws IOException {
        final Set<PosixFilePermission> mode;
        try {
            mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        } catch (final NoSuchFileException ex) {
            return;
        }
        if (mode.stream().anyMatch(OTHERS::contains)) {
            loose.add(path);
        }
    }

    private boolean openOnPurpose(final Path path, final boolean directory) {
        if (path.startsWith(data)) {
            final Path below = data.relativize(path);
            // data/build/<project>/<tree>: an agent's own tree, staged for a COPY.
            return directory && below.getNameCount() == 3 && below.startsWith("build");
        }
        if (!path.startsWith(state) || path.equals(state)) {
            return false;
        }
        final Path below = state.relativize(path);
        // Staged beside what it replaces and made readable just before the rename.
        final String name = below.getFileName().toString();
        if (!directory && name.startsWith(".guide") && name.endsWith(".tmp")) {
            return below.startsWith("guides") || below.startsWith("mail");
        }
        // guides/<task> and mail/<task>/box are what is mounted. Beside the box, the mailbox
        // keeps the originals of refused messages: those are nobody else's.
        return directory
                && (below.startsWith("guides") && below.getNameCount() == 2
                        || below.startsWith("mail")
                                && below.getNameCount() == 3
                                && "box".equals(name));
    }
}
