package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.fuin.porelo.wire.Directories;

/** A task's own declaration of completion, kept by the host across stops and reboots. */
public record TaskDone(PoreloPaths paths) {
    private static final String VALUE = "done\n";

    /** Returns whether the host holds the fixed completion value for this task. */
    public boolean recorded(final String container) {
        final Path file = paths.tasks().doneRecord(container);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return java.util.Arrays.equals(
                    input.readNBytes(VALUE.length() + 1), VALUE.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return false;
        }
    }

    /** Records only completion: no text or path supplied by a task is stored as its value. */
    public void record(final String container) throws IOException {
        final Path file = paths.tasks().doneRecord(container);
        final Path directory = paths.tasks().taskRecord(container);
        Directories.create(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        FileLocks.holding(
                directory.resolve("done.lock"),
                () -> {
                    final Path staged = directory.resolve("done.new");
                    try {
                        Files.writeString(
                                staged,
                                VALUE,
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS);
                        Files.setPosixFilePermissions(
                                staged, PosixFilePermissions.fromString("rw-------"));
                        Files.move(
                                staged,
                                file,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(staged);
                    }
                    return true;
                });
    }

    /** Clears the declaration before a later start; a failure refuses the start. */
    public void clear(final String container) {
        final Path directory = paths.tasks().taskRecord(container);
        if (!Files.isDirectory(directory)) return;
        try {
            FileLocks.holding(
                    directory.resolve("done.lock"),
                    () -> {
                        Files.deleteIfExists(paths.tasks().doneRecord(container));
                        return true;
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException("could not clear completion of " + container, ex);
        }
    }
}
