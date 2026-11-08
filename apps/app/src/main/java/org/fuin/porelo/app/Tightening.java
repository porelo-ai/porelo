package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.fuin.porelo.core.config.XdgPaths;

/**
 * What {@code porelo tighten} changes, or would: the one answer the command and the daemon render.
 */
public final class Tightening {

    private Tightening() {}

    /** Why a path found loose was not changed. */
    public enum Reason {
        /** It is a link by now, and a chmod would follow it. */
        LINK,
        /** Another uid owns it, or the file system refused. */
        REFUSED,
        /** It went away after it was found, which leaves nothing to read. */
        GONE
    }

    /**
     * One path tightened, or to be.
     *
     * @param path The path.
     * @param from Its mode before, as {@code rw-r--r--}.
     * @param to Its mode after.
     */
    public record Change(Path path, String from, String to) {}

    /**
     * One path found loose and not changed.
     *
     * @param path The path.
     * @param reason Why.
     */
    public record Left(Path path, Reason reason) {}

    /**
     * The answer.
     *
     * @param found How many paths anyone else could read, write or enter.
     * @param changes Each path tightened, or to be in a dry run, parents first.
     * @param left Each path that is not, or would not be, changed.
     * @param topmost The topmost of what was found, which stands for what is below it.
     */
    public record Outcome(int found, List<Change> changes, List<Left> left, List<Path> topmost) {}

    /**
     * Looks where this account's Porelo writes and tightens what anyone else has of it.
     *
     * @param paths The account's directories.
     * @param dryRun True to say what would change and change nothing.
     * @return What changed, or would.
     * @throws IOException If one of the directories cannot be walked.
     */
    public static Outcome of(final XdgPaths paths, final boolean dryRun) throws IOException {
        return of(new LooseFiles(paths), dryRun);
    }

    static Outcome of(final LooseFiles looseFiles, final boolean dryRun) throws IOException {
        return of(looseFiles.found(), dryRun);
    }

    /**
     * Takes from each what anyone else had of it, parents first as {@link LooseFiles#found()} gives
     * them: a directory tightened first is one nobody else can then swap a child in, and a
     * directory that was not loose is one nobody else could. A path that is a link by now is left
     * and said: the JDK's chmod follows a link even through a view asked not to.
     *
     * <p>A file with a second name, as an object of a mirror hard-linked from a person's own clone
     * is, gets an inode of its own first: the mode belongs to the inode, and the person's clone is
     * not this command's to change.
     *
     * <p>A file another uid owns is left as refused, in a dry run too: with a second name it would
     * otherwise be copied and become this account's. A dry run reads each mode and lists the same
     * change; only a refusal the file system makes when asked to change it cannot foresee.
     *
     * @param loose What {@link LooseFiles#found()} found.
     * @param dryRun True to change nothing.
     * @return What changed, or would.
     */
    static Outcome of(final List<Path> loose, final boolean dryRun) {
        return of(loose, dryRun, ownUid());
    }

    private static int ownUid() {
        try {
            return (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
        } catch (final IOException ex) {
            throw new UncheckedIOException("Cannot read this process's uid", ex);
        }
    }

    static Outcome of(final List<Path> loose, final boolean dryRun, final int ownUid) {
        final List<Change> changes = new ArrayList<>();
        final List<Left> left = new ArrayList<>();
        for (final Path path : loose) {
            if (Files.isSymbolicLink(path)) {
                left.add(new Left(path, Reason.LINK));
                continue;
            }
            try {
                if ((int) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)
                        != ownUid) {
                    left.add(new Left(path, Reason.REFUSED));
                    continue;
                }
                final PosixFileAttributeView view =
                        Files.getFileAttributeView(
                                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                final Set<PosixFilePermission> mode = view.readAttributes().permissions();
                final String from = PosixFilePermissions.toString(mode);
                mode.removeAll(LooseFiles.OTHERS);
                if (!dryRun) {
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && (int)
                                            Files.getAttribute(
                                                    path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)
                                    > 1) {
                        aloneUnderItsName(path, mode);
                    }
                    view.setPermissions(mode);
                }
                changes.add(new Change(path, from, PosixFilePermissions.toString(mode)));
            } catch (final NoSuchFileException ex) {
                left.add(new Left(path, Reason.GONE));
            } catch (final IOException | UnsupportedOperationException ex) {
                left.add(new Left(path, Reason.REFUSED));
            }
        }
        return new Outcome(
                loose.size(), List.copyOf(changes), List.copyOf(left), LooseFiles.topmost(loose));
    }

    static void aloneUnderItsName(final Path file, final Set<PosixFilePermission> mode)
            throws IOException {
        final Path copy =
                Files.createTempFile(
                        file.getParent(),
                        ".tighten",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            // Into the copy made rw-------, so the new inode is never looser than the mode it gets.
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                    OutputStream out = Files.newOutputStream(copy)) {
                in.transferTo(out);
            }
            Files.setPosixFilePermissions(copy, mode);
            Files.move(copy, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(copy);
        }
    }
}
