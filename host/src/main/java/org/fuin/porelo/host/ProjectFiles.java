package org.fuin.porelo.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The project's root-owned records and atomic publications, with a test-only trust anchor. */
final class ProjectFiles {
    final Path root;
    final Path etc;
    final int owner;
    private final Deleting deleting;
    private final Attributes attributes;
    private final Ownership ownership;

    @FunctionalInterface
    interface Ownership {
        void set(Path path, int uid, int gid) throws IOException;
    }

    Map<String, Object> attributes(Path path) throws IOException {
        return attributes.read(path);
    }

    void owner(Path path, int uid, int gid) throws IOException {
        ownership.set(path, uid, gid);
    }

    @FunctionalInterface
    interface Attributes {
        Map<String, Object> read(Path path) throws IOException;
    }

    @FunctionalInterface
    interface Deleting {
        void delete(Path path) throws IOException;
    }

    ProjectFiles() {
        this(Path.of("/"), 0);
    }

    ProjectFiles(Path root, int owner) {
        this(root, owner, path -> Files.deleteIfExists(path));
    }

    ProjectFiles(Path root, int owner, Deleting deleting) {
        this(
                root,
                owner,
                deleting,
                path -> Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS));
    }

    ProjectFiles(Path root, int owner, Deleting deleting, Attributes attributes) {
        this(
                root,
                owner,
                deleting,
                attributes,
                (path, uid, gid) -> {
                    Files.setAttribute(path, "unix:gid", gid, LinkOption.NOFOLLOW_LINKS);
                    Files.setAttribute(path, "unix:uid", uid, LinkOption.NOFOLLOW_LINKS);
                });
    }

    ProjectFiles(
            Path root, int owner, Deleting deleting, Attributes attributes, Ownership ownership) {
        this.ownership = ownership;
        this.attributes = attributes;
        this.root = root.toAbsolutePath().normalize();
        this.etc = at("/etc/porelo-host");
        this.owner = owner;
        this.deleting = deleting;
    }

    Path at(String absolute) {
        return root.resolve(absolute.substring(1));
    }

    boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    void directory(Path path, boolean privateDirectory, boolean missing) throws IOException {
        requireInside(path);
        if (!path.equals(root)) {
            directory(parent(path), false, false);
        }
        final Map<String, Object> attributes;
        try {
            attributes = this.attributes.read(path);
        } catch (NoSuchFileException ex) {
            if (missing) {
                return;
            }
            throw new HostRefusal(69, path + " is missing; run up first");
        }
        final int mode =
                (int)
                        java.util.Objects.requireNonNull(
                                attributes.get("mode"), "Unix attributes include mode");
        if ((mode & 0170000) != 0040000
                || !Integer.valueOf(owner).equals(attributes.get("uid"))
                || (mode & (privateDirectory ? 0077 : 0022)) != 0) {
            throw new HostRefusal(
                    69,
                    path
                            + " is not a directory root's alone "
                            + (privateDirectory ? "to read" : "to write"));
        }
        if (privateDirectory && (mode & 07777) != 0700) {
            throw new HostRefusal(69, path + " must have mode 0700");
        }
    }

    void trusted(Path path, boolean privateFile) throws IOException {
        requireInside(path);
        directory(parent(path), false, false);
        final Map<String, Object> attributes;
        try {
            attributes = this.attributes.read(path);
        } catch (NoSuchFileException ex) {
            throw new HostRefusal(69, path + " is missing; no registered record or forge settings");
        }
        final int mode =
                (int)
                        java.util.Objects.requireNonNull(
                                attributes.get("mode"), "Unix attributes include mode");
        if ((mode & 0170000) != 0100000
                || !Integer.valueOf(owner).equals(attributes.get("uid"))
                || (mode & (privateFile ? 0077 : 0022)) != 0) {
            throw new HostRefusal(
                    69,
                    path
                            + " is not a plain file root's alone "
                            + (privateFile ? "to read" : "to write"));
        }
    }

    byte[] read(Path path, boolean privateFile, int maximum) throws IOException {
        trusted(path, privateFile);
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] bytes = in.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                java.util.Arrays.fill(bytes, (byte) 0);
                throw new HostRefusal(69, path + " exceeds read limit");
            }
            return bytes;
        }
    }

    String text(Path path, boolean privateFile) throws IOException {
        final byte[] bytes = read(path, privateFile, 1024 * 1024);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new HostRefusal(69, path + " is not UTF-8");
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    void mkdir(Path path, int mode) throws IOException {
        directory(path, mode == 0700, true);
        if (!exists(path)) {
            write(
                    path,
                    () -> {
                        Files.createDirectory(
                                path,
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString(
                                                mode == 0700 ? "rwx------" : "rwxr-xr-x")));
                        // A 0000 creation mode cannot be reopened with NOFOLLOW; its checked parent
                        // prevents replacement.
                        Files.setAttribute(path, "unix:mode", mode);
                    });
        }
    }

    Path stage(Path destination, String prefix, String content) throws IOException {
        directory(parent(destination), false, false);
        return stagePrivate(destination, prefix, content, owner);
    }

    /** Shared private-file publication; a null owner keeps the creating account's ownership. */
    static Path stagePrivate(
            Path destination, String prefix, String content, @Nullable Integer owner)
            throws IOException {
        final Path temporary;
        try {
            temporary =
                    Files.createTempFile(
                            parent(destination),
                            prefix,
                            "",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
        } catch (IOException ex) {
            throw writeRefusal(destination, ex);
        }
        try {
            write(
                    destination,
                    () -> {
                        // Set the mode through the checked parent before reopening a possibly 0000
                        // file.
                        Files.setAttribute(temporary, "unix:mode", 0600);
                        if (owner != null) {
                            Files.setAttribute(
                                    temporary, "unix:uid", owner, LinkOption.NOFOLLOW_LINKS);
                        }
                        try (FileChannel channel =
                                FileChannel.open(
                                        temporary,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS)) {
                            final ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
                            try {
                                while (bytes.hasRemaining()) {
                                    channel.write(bytes);
                                }
                                channel.force(true);
                            } finally {
                                java.util.Arrays.fill(bytes.array(), (byte) 0);
                            }
                        }
                    });
            return temporary;
        } catch (RuntimeException | IOException ex) {
            Files.deleteIfExists(temporary);
            throw ex;
        }
    }

    void replace(Path staged, Path destination) throws IOException {
        write(
                destination,
                () ->
                        Files.move(
                                staged,
                                destination,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING));
    }

    void publish(Path destination, String prefix, String content) throws IOException {
        final Path temporary = stage(destination, prefix, content);
        try {
            replace(temporary, destination);
        } finally {
            delete(temporary);
        }
    }

    void delete(Path path) throws IOException {
        write(path, () -> unlink(path));
    }

    // Read-key cleanup, like the Python's drop_read_keys, is outside the writing boundary.
    void unlink(Path path) throws IOException {
        deleting.delete(path);
    }

    List<Path> list(Path path) throws IOException {
        try (var entries = Files.list(path)) {
            return entries.sorted().toList();
        }
    }

    void removeTree(Path root) throws IOException {
        // Copies belong to another uid. Relative, pinned descriptors keep a renamed child
        // or substituted symlink from turning cleanup into a deletion outside this tree.
        try (var parent = Files.newDirectoryStream(parent(root))) {
            if (!(parent instanceof java.nio.file.SecureDirectoryStream<Path> secure)) {
                throw new IOException("Secure directory deletion is unavailable");
            }
            remove(secure, root.getFileName());
        }
    }

    private static void remove(java.nio.file.SecureDirectoryStream<Path> parent, Path name)
            throws IOException {
        final var view =
                java.util.Objects.requireNonNull(
                        parent.getFileAttributeView(
                                name,
                                java.nio.file.attribute.BasicFileAttributeView.class,
                                LinkOption.NOFOLLOW_LINKS),
                        "Secure directory supports basic file attributes");
        if (view.readAttributes().isDirectory()) {
            try (var directory = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                for (Path child : directory) {
                    remove(directory, child.getFileName());
                }
            }
            parent.deleteDirectory(name);
        } else {
            parent.deleteFile(name);
        }
    }

    void giveTree(Path root, int uid, int gid) throws IOException {
        try (var paths = Files.walk(root)) {
            // Keep the enclosing 0700 directory root's until every descendant has its owner.
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                final int mode =
                        (int) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                if ((mode & 0170000) != 0120000) {
                    Files.setAttribute(path, "unix:mode", mode & 0700, LinkOption.NOFOLLOW_LINKS);
                }
                Files.setAttribute(path, "unix:gid", gid, LinkOption.NOFOLLOW_LINKS);
                Files.setAttribute(path, "unix:uid", uid, LinkOption.NOFOLLOW_LINKS);
            }
        }
    }

    @FunctionalInterface
    interface Writing {
        void run() throws IOException;
    }

    static void write(Path destination, Writing operation) throws IOException {
        try {
            operation.run();
        } catch (IOException ex) {
            throw writeRefusal(destination, ex);
        }
    }

    private static HostRefusal writeRefusal(Path destination, IOException ex) {
        return new HostRefusal(
                73, "cannot write " + destination + ": " + ex.getClass().getSimpleName());
    }

    private void requireInside(Path path) {
        if (!path.isAbsolute() || !path.equals(path.normalize()) || !path.startsWith(root)) {
            throw new IllegalArgumentException("Expected a normalized path under the trust anchor");
        }
    }

    static Path parent(Path path) {
        return java.util.Objects.requireNonNull(path.getParent(), "Record path must have a parent");
    }
}
