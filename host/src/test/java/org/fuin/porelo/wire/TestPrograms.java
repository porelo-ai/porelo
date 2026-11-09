package org.fuin.porelo.wire;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

/**
 * Models root ownership as SystemProgramTest does, independently of the test runner's namespace.
 */
public final class TestPrograms {
    private TestPrograms() {}

    /** Finds a real distribution executable with injected ownership attributes. */
    public static Path find(String name) {
        final Path path = Path.of(name);
        final List<Path> directories =
                path.isAbsolute()
                        ? List.of(java.util.Objects.requireNonNull(path.getParent()))
                        : List.of(
                                Path.of("/usr/sbin"),
                                Path.of("/usr/bin"),
                                Path.of("/sbin"),
                                Path.of("/bin"));
        try {
            return SystemProgram.find(
                    path.getFileName().toString(),
                    directories,
                    file -> {
                        final var attributes =
                                new HashMap<>(Files.readAttributes(file, "unix:uid,mode"));
                        attributes.put("uid", 0);
                        return attributes;
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
