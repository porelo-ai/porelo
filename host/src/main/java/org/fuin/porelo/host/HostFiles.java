package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.RootOwnedFile;

/** Root's fixed file tree; only package-local fixtures may substitute its root or attributes. */
final class HostFiles {
    static final int MAXIMUM_BYTES = 16 * 1024 * 1024;
    private final Path root;
    private final RootOwnedFile.Attributes attributes;
    private final RootOwnedFile.Attributes openedAttributes;

    HostFiles() {
        this(
                Path.of("/"),
                path -> Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS),
                path -> Files.readAttributes(path, "unix:uid,mode"));
    }

    HostFiles(Path root, RootOwnedFile.Attributes attributes) {
        this(root, attributes, attributes);
    }

    ProjectFiles projects() {
        check(root, 69, true, true, -1);
        return new ProjectFiles(root, 0, path -> Files.deleteIfExists(path), attributes::read);
    }

    private HostFiles(
            Path root,
            RootOwnedFile.Attributes attributes,
            RootOwnedFile.Attributes openedAttributes) {
        this.openedAttributes = openedAttributes;
        this.root = root.toAbsolutePath().normalize();
        this.attributes = attributes;
    }

    Path path(String name) {
        final Path result = root.resolve(name.replaceFirst("^/", "")).normalize();
        if (!result.startsWith(root)) {
            throw new IllegalArgumentException("Host path must stay under its root");
        }
        return result;
    }

    boolean check(Path path, int code, boolean required, boolean directory, int permissions) {
        if (!path.equals(path.getRoot())) {
            check(
                    java.util.Objects.requireNonNull(
                            path.getParent(), "Non-root path has a parent"),
                    code,
                    false,
                    true,
                    -1);
        }
        final Map<String, Object> values;
        try {
            values = attributes.read(path);
        } catch (NoSuchFileException ex) {
            if (required) {
                throw new HostRefusal(code, path + " is missing; prepare the host first");
            }
            return false;
        } catch (IOException ex) {
            throw new HostRefusal(code, "cannot inspect " + path);
        }
        final int mode =
                ((Number)
                                java.util.Objects.requireNonNull(
                                        values.get("mode"), "Unix attributes include mode"))
                        .intValue();
        if (!Integer.valueOf(0).equals(values.get("uid"))
                || (mode & 0170000) != (directory ? 0040000 : 0100000)
                || (mode & 0022) != 0
                || (permissions >= 0 && (mode & 07777) != permissions)) {
            throw new HostRefusal(
                    code,
                    path
                            + " is not a "
                            + (directory ? "directory" : "plain file")
                            + " of root's alone");
        }
        return true;
    }

    boolean exists(String name) {
        return check(path(name), 69, false, false, -1);
    }

    String read(Path path) {
        check(path, 69, true, false, -1);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(readBytes(path)))
                    .toString();
        } catch (IOException ex) {
            throw new HostRefusal(69, "cannot read " + path);
        }
    }

    byte[] readBytes(Path path) {
        check(path, 69, true, false, -1);
        try {
            RootOwnedFile.checkPath(path, attributes, 0);
        } catch (IOException ex) {
            throw new HostRefusal(69, "cannot read " + path);
        }
        try (var opened = HostConfigurationLock.openRead(path)) {
            return RootOwnedFile.readOpened(
                    opened.descriptorPath(), MAXIMUM_BYTES, openedAttributes, 0);
        } catch (IOException ex) {
            throw new HostRefusal(69, "cannot read " + path);
        }
    }

    void checkOpened(Path descriptor, int permissions) throws IOException {
        if (!descriptor.toString().matches("/proc/self/fd/[0-9]+"))
            throw new IllegalArgumentException("Expected a pinned host descriptor");
        final var status = openedAttributes.read(descriptor);
        if (!Integer.valueOf(0).equals(status.get("uid"))
                || !(status.get("mode") instanceof Number mode)
                || (mode.intValue() & 0177777) != (0100000 | permissions))
            throw new HostRefusal(73, "the open arrival record is not root's alone");
    }

    void directories(Path directory) throws IOException {
        final boolean publicDirectory =
                List.of(
                                path("/etc/ssh/authorized_keys"),
                                path("/run/porelo-host"),
                                path("/run/porelo-host/people"))
                        .contains(directory);
        final int mode = publicDirectory ? 0755 : 0700;
        if (check(directory, 73, false, true, publicDirectory ? -1 : 0700)) {
            return;
        }
        final Path parent =
                java.util.Objects.requireNonNull(
                        directory.getParent(), "A host directory has a parent");
        if (!check(parent, 73, false, true, -1)) {
            directories(parent);
        }
        try {
            Files.createDirectory(
                    directory,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString(
                                    publicDirectory ? "rwxr-xr-x" : "rwx------")));
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            // A shared configuration lock permits another push to create the directory first.
            check(directory, 73, true, true, mode);
            return;
        }
        Files.setAttribute(directory, "unix:mode", mode);
        check(directory, 73, true, true, mode);
    }

    Path syncDirectory(String prefix) throws IOException {
        final Path sync = path("/run/porelo-host/sync");
        directories(sync);
        for (Path left : list(sync, 73, 0700)) {
            removeTree(left);
        }
        return Files.createTempDirectory(
                sync,
                prefix + ".",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
    }

    void removeTree(Path path) throws IOException {
        final Path parent =
                java.util.Objects.requireNonNull(path.getParent(), "A scratch entry has a parent");
        try (var stream = Files.newDirectoryStream(parent)) {
            if (!(stream instanceof java.nio.file.SecureDirectoryStream<Path> secure)) {
                throw new HostRefusal(
                        69, "safe removal of the host's scratch directory is unavailable");
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                removeDirectory(secure, path.getFileName());
            } else {
                secure.deleteFile(path.getFileName());
            }
        }
    }

    String read(String name) {
        return read(path(name));
    }

    List<Path> list(Path directory, int code, int permissions) {
        if (!check(directory, code, false, true, permissions)) {
            return List.of();
        }
        try (var entries = Files.list(directory)) {
            return entries.sorted().toList();
        } catch (IOException ex) {
            throw new HostRefusal(73, "cannot read or change the host's files");
        }
    }

    /** Whether a path is a plain file itself, not a link to one; its owner and mode unchecked. */
    boolean isPlainFile(Path path) {
        try {
            return (((Number)
                                            java.util.Objects.requireNonNull(
                                                    attributes.read(path).get("mode"),
                                                    "Unix attributes include mode"))
                                    .intValue()
                            & 0170000)
                    == 0100000;
        } catch (IOException ex) {
            return false;
        }
    }

    boolean isSocket(Path path) {
        try {
            return (((Number)
                                            java.util.Objects.requireNonNull(
                                                    attributes.read(path).get("mode"),
                                                    "Unix attributes include mode"))
                                    .intValue()
                            & 0170000)
                    == 0140000;
        } catch (IOException ex) {
            return false;
        }
    }

    void remove(String name) throws IOException {
        final Path path = path(name);
        check(path, 73, false, false, -1);
        Files.deleteIfExists(path);
    }

    void writePerson(String login, boolean locked, boolean pending) throws IOException {
        final Path path = path("/etc/porelo-host/people/" + login + ".conf");
        check(path, 73, true, false, 0600);
        final String original = read(path);
        final String text =
                original.lines()
                                .filter(line -> !line.equals("lock=pending"))
                                .map(line -> line.startsWith("locked=") ? "locked=" + locked : line)
                                .collect(java.util.stream.Collectors.joining("\n", "", "\n"))
                        + (pending ? "lock=pending\n" : "");
        final Path temporary =
                ProjectFiles.stagePrivate(path, ProjectFiles.STAGED_PREFIX, text, null);
        try {
            Files.move(
                    temporary,
                    path,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void removeDelivery(String user, ProjectRuntime runtime) {
        final Path directory = path("/run/porelo-host/people/" + user);
        check(
                java.util.Objects.requireNonNull(
                        directory.getParent(), "The delivery directory has a parent"),
                73,
                false,
                true,
                -1);
        final Map<String, Object> status;
        try {
            status = attributes.read(directory);
        } catch (NoSuchFileException ex) {
            return;
        } catch (IOException ex) {
            throw new HostRefusal(
                    73,
                    "cannot remove the person's forge token delivery directory; it remains for retry");
        }
        final ProjectRuntime.Account account = runtime.find(user);
        final int mode =
                ((Number)
                                java.util.Objects.requireNonNull(
                                        status.get("mode"), "Unix attributes include mode"))
                        .intValue();
        if ((mode & 0170000) != 0040000
                || account != null
                        && !Long.valueOf(account.uid())
                                .equals(
                                        ((Number)
                                                        java.util.Objects.requireNonNull(
                                                                status.get("uid"),
                                                                "Unix attributes include uid"))
                                                .longValue())) {
            throw new HostRefusal(
                    73,
                    "the forge token delivery directory is not a private directory of the person's");
        }
        try (var stream = Files.newDirectoryStream(directory.getParent())) {
            if (!(stream instanceof java.nio.file.SecureDirectoryStream<Path> secure)) {
                throw new HostRefusal(
                        69, "safe removal of the forge token delivery directory is unavailable");
            }
            removeDirectory(secure, directory.getFileName());
        } catch (IOException ex) {
            throw new HostRefusal(
                    73,
                    "cannot remove the person's forge token delivery directory; it remains for retry");
        }
    }

    private static void removeDirectory(java.nio.file.SecureDirectoryStream<Path> parent, Path name)
            throws IOException {
        try (var directory = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
            for (Path entry : directory) {
                final Path child = entry.getFileName();
                final var view =
                        java.util.Objects.requireNonNull(
                                directory.getFileAttributeView(
                                        child,
                                        java.nio.file.attribute.BasicFileAttributeView.class,
                                        LinkOption.NOFOLLOW_LINKS),
                                "Unix directory entries have basic attributes");
                if (view.readAttributes().isDirectory()) {
                    removeDirectory(directory, child);
                } else {
                    directory.deleteFile(child);
                }
            }
        }
        parent.deleteDirectory(name);
    }
}
