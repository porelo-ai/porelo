package org.fuin.porelo.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A repository's reviewed tree, exported from the gate's bare mirror for the adapters to read when
 * there is no checkout on this machine - a repository of {@value DefaultProject#NAME} added by its
 * address.
 *
 * <p><strong>Only the default branch, as the mirror's HEAD names it.</strong> A task's work lands
 * on {@code porelo/<task>} and reaches the default branch only once a person approved it, so a HEAD
 * naming such a branch exports nothing, and no other ref is read: the commit is resolved once and
 * only its tree is listed.
 *
 * <p><strong>Plain git plumbing, never a checkout.</strong> {@code ls-tree} lists the tree and
 * {@code cat-file --batch} hands each blob over byte for byte, and this class writes the files.
 * {@code git archive} would apply the tree's own {@code .gitattributes} ({@code export-ignore},
 * {@code export-subst}) and leave a tar to unpack; {@code checkout-index} would apply its filters
 * and line endings and write its links. Here a file is what was reviewed, a link or a submodule is
 * left out - a link would hand an adapter whatever it names on this machine - and git runs with
 * neither the account's nor the system's configuration and with no hooks.
 *
 * <p><strong>A fresh {@code 0700} directory under the runtime directory, files read-only, removed
 * on {@link #close()}</strong>, also when the reader failed; an export that fails half-way is
 * removed before the failure is passed on.
 */
final class MirrorTree implements AutoCloseable {

    /** Where a task's own work lands, and which is never read here. */
    private static final String TASK_BRANCHES = "refs/heads/porelo/";

    /** The longest any one git call may take; a stuck git must not hold the daemon. */
    private static final long GIT_SECONDS = 120;

    private final Path directory;

    private MirrorTree(final Path directory) {
        this.directory = directory;
    }

    /**
     * Returns whether a repository of {@value DefaultProject#NAME} has a mirror at all.
     *
     * @param context This machine.
     * @param repository The repository's name.
     * @return Whether its mirror's directory exists.
     */
    static boolean exists(final PoreloContext context, final String repository) {
        return Files.isDirectory(GateSupport.mirror(context, DefaultProject.NAME, repository));
    }

    /**
     * Exports a repository of {@value DefaultProject#NAME} from its mirror.
     *
     * @param context This machine.
     * @param repository The repository's name.
     * @return The export, or empty when there is no mirror or no default branch to read.
     * @throws IOException When the mirror cannot be read or the export written.
     */
    static Optional<MirrorTree> export(final PoreloContext context, final String repository)
            throws IOException {
        return export(
                GateSupport.mirror(context, DefaultProject.NAME, repository),
                context.paths().xdg().runtime());
    }

    /**
     * Exports a mirror's default branch.
     *
     * @param mirror The bare mirror.
     * @param runtime The runtime directory, {@code $XDG_RUNTIME_DIR/porelo}.
     * @return The export, or empty when there is no mirror or no default branch to read.
     * @throws IOException When the mirror cannot be read or the export written.
     */
    static Optional<MirrorTree> export(final Path mirror, final Path runtime) throws IOException {
        if (!Files.isDirectory(mirror)) {
            return Optional.empty();
        }
        final Git head = git(mirror, "symbolic-ref", "--quiet", "HEAD");
        final String ref = head.text().strip();
        if (head.exit() != 0 || !ref.startsWith("refs/heads/") || ref.startsWith(TASK_BRANCHES)) {
            return Optional.empty();
        }
        final Git commit = git(mirror, "rev-parse", "--verify", "--quiet", ref + "^{commit}");
        if (commit.exit() != 0) {
            return Optional.empty();
        }
        final Git listed = git(mirror, "ls-tree", "-r", "-z", "--full-tree", commit.text().strip());
        if (listed.exit() != 0) {
            throw new IOException("Cannot list the tree of " + ref + " in " + mirror);
        }
        final List<Blob> blobs = blobs(listed.text());
        final Path parent = Files.createDirectories(runtime.resolve("mirror-trees"));
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        final Path directory =
                Files.createTempDirectory(
                        parent,
                        "tree-",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        final MirrorTree tree = new MirrorTree(directory);
        try {
            tree.write(mirror, blobs);
        } catch (IOException | RuntimeException ex) {
            try {
                tree.close();
            } catch (IOException removal) {
                ex.addSuppressed(removal);
            }
            throw ex;
        }
        return Optional.of(tree);
    }

    /**
     * Returns the directory the tree is in.
     *
     * @return The directory, gone after {@link #close()}.
     */
    Path directory() {
        return directory;
    }

    /** Removes the export, read-only files and all. */
    @Override
    public void close() throws IOException {
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(
                directory,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(
                            final Path file, final BasicFileAttributes attributes)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(
                            final Path each, final IOException failed) throws IOException {
                        if (failed != null) {
                            throw failed;
                        }
                        Files.delete(each);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /** One plain file of the tree: its blob, its path, whether it is executable. */
    private record Blob(String id, String path, boolean executable) {}

    private static List<Blob> blobs(final String listing) throws IOException {
        final List<Blob> blobs = new ArrayList<>();
        for (final String line : listing.split("\0")) {
            if (line.isEmpty()) {
                continue;
            }
            // "<mode> SP <type> SP <id> TAB <path>"
            final int tab = line.indexOf('\t');
            final String[] head = line.substring(0, Math.max(tab, 0)).split(" ");
            if (tab < 0 || head.length != 3) {
                throw new IOException("Cannot read a tree entry: " + line);
            }
            final boolean plain = "100644".equals(head[0]) || "100755".equals(head[0]);
            // A link (120000) or a submodule (160000) is left out: neither is a file of its own.
            if (plain && "blob".equals(head[1])) {
                final String path = line.substring(tab + 1);
                check(path);
                blobs.add(new Blob(head[2], path, "100755".equals(head[0])));
            }
        }
        return blobs;
    }

    /** A path git would refuse to check out never leaves or reaches into the export's own. */
    private static void check(final String path) throws IOException {
        for (final String part : path.split("/", -1)) {
            if (part.isEmpty()
                    || ".".equals(part)
                    || "..".equals(part)
                    || ".git".equals(part.toLowerCase(Locale.ROOT))) {
                throw new IOException("A tree entry that is no path of a checkout: " + path);
            }
        }
    }

    private void write(final Path mirror, final List<Blob> blobs) throws IOException {
        if (blobs.isEmpty()) {
            return;
        }
        final Process process = start(mirror, "cat-file", "--batch");
        try {
            final StringBuilder requests = new StringBuilder();
            for (final Blob blob : blobs) {
                requests.append(blob.id()).append('\n');
            }
            // Written beside the reading, so a full pipe on either side never stops both.
            final Thread writer =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (OutputStream input = process.getOutputStream()) {
                                            input.write(
                                                    requests.toString()
                                                            .getBytes(StandardCharsets.US_ASCII));
                                        } catch (IOException ignored) {
                                            // The reading side finds the answer short.
                                        }
                                    });
            try (InputStream output = new BufferedInputStream(process.getInputStream())) {
                for (final Blob blob : blobs) {
                    writeOne(output, blob);
                }
            }
            writer.join();
            if (!process.waitFor(GIT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Cannot read the blobs of " + mirror);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted reading " + mirror, ex);
        } finally {
            process.destroyForcibly();
        }
    }

    private void writeOne(final InputStream output, final Blob blob) throws IOException {
        final String header = line(output);
        final String[] parts = header.split(" ");
        if (parts.length != 3 || !parts[0].equals(blob.id()) || !"blob".equals(parts[1])) {
            throw new IOException("Cannot read blob " + blob.id() + ": " + header);
        }
        long left = Long.parseLong(parts[2]);
        final Path target = directory.resolve(blob.path()).normalize();
        if (!target.startsWith(directory)) {
            throw new IOException("A tree entry that leaves the export: " + blob.path());
        }
        Files.createDirectories(target.getParent());
        try (OutputStream file =
                Files.newOutputStream(
                        target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            final byte[] buffer = new byte[8192];
            while (left > 0) {
                final int read = output.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) {
                    throw new IOException("Blob " + blob.id() + " ends early");
                }
                file.write(buffer, 0, read);
                left -= read;
            }
        }
        if (output.read() != '\n') {
            throw new IOException("Blob " + blob.id() + " is not followed by its end");
        }
        Files.setPosixFilePermissions(
                target,
                PosixFilePermissions.fromString(blob.executable() ? "r-x------" : "r--------"));
    }

    private static String line(final InputStream input) throws IOException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int next = input.read(); next != '\n'; next = input.read()) {
            if (next < 0) {
                throw new IOException("git's answer ends early");
            }
            line.write(next);
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    /** What one git call said, and how it ended. */
    private record Git(int exit, String text) {}

    private static Git git(final Path mirror, final String... arguments) throws IOException {
        final Process process = start(mirror, arguments);
        try {
            process.getOutputStream().close();
            final String text =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(GIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("git " + arguments[0] + " did not end in " + mirror);
            }
            return new Git(process.exitValue(), text);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted reading " + mirror, ex);
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * Starts git on the bare mirror as the host's rules have it: the mirror named, never found; no
     * account or system configuration; no hooks; no replacement objects.
     */
    private static Process start(final Path mirror, final String... arguments) throws IOException {
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "--no-replace-objects",
                                "--git-dir=" + mirror,
                                "-c",
                                "core.hooksPath=/dev/null"));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.directory(mirror.toFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }
}
