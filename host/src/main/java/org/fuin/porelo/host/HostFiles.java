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
    private static final int MAXIMUM_BYTES = 16 * 1024 * 1024;
    private final Path root;
    private final RootOwnedFile.Attributes attributes;

    HostFiles() {
        this(
                Path.of("/"),
                path -> Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS));
    }

    HostFiles(Path root, RootOwnedFile.Attributes attributes) {
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
                    .decode(ByteBuffer.wrap(RootOwnedFile.read(path, MAXIMUM_BYTES, attributes, 0)))
                    .toString();
        } catch (IOException ex) {
            throw new HostRefusal(69, "cannot read " + path);
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
}
