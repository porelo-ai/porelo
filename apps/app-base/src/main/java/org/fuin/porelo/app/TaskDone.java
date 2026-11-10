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
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/** A task's own declaration of completion, kept by the host across stops and reboots. */
public record TaskDone(PoreloPaths paths) {
    private static final String VALUE = "done\n";

    /** The most bytes of UTF-8 a task may give as its reason. */
    public static final int MOST_WHY = 1024;

    private static final int MOST_RECORD = 8 * 1024;

    /** What a task may declare about its work. */
    public enum Outcome {
        /** The work is complete. */
        DONE,
        /** The work cannot continue. */
        BLOCKED
    }

    /** The task's declaration, kept until a later start. */
    public record Declared(Outcome outcome, String why, String at) {
        /** Refuses a reason that cannot be stored whole as one bounded line. */
        public Declared {
            if (why.getBytes(StandardCharsets.UTF_8).length > MOST_WHY
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(why)
                    || why.codePoints().anyMatch(TaskDone::breaksLine)
                    || outcome == Outcome.BLOCKED && why.isBlank()) {
                throw new IllegalArgumentException(
                        "why must be one line of at most 1024 UTF-8 bytes, non-blank for BLOCKED");
            }
            Instant.parse(at);
        }
    }

    private static boolean breaksLine(final int codePoint) {
        return Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029;
    }

    /** Reads the task's declaration, or null when none can be read. */
    public @Nullable Declared read(final String container) {
        final Path file = paths.tasks().doneRecord(container);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] bytes = input.readNBytes(MOST_RECORD + 1);
            if (bytes.length > MOST_RECORD) return null;
            final String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
            if (VALUE.equals(text)) {
                return new Declared(
                        Outcome.DONE,
                        "",
                        Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
                                .toInstant()
                                .toString());
            }
            if (Json.parse(text) instanceof Map<?, ?> fields
                    && fields.get("outcome") instanceof String outcome
                    && fields.get("why") instanceof String why
                    && fields.get("at") instanceof String at) {
                return new Declared(Outcome.valueOf(outcome), why, at);
            }
            return null;
        } catch (IOException | JsonException | IllegalArgumentException | DateTimeException ex) {
            return null;
        }
    }

    /** Records the task's declared outcome and its bounded reason. */
    public void record(final String container, final Outcome outcome, final String why)
            throws IOException {
        final Declared declared = new Declared(outcome, why, Instant.now().toString());
        final String value =
                Json.write(
                        Map.of(
                                "outcome",
                                declared.outcome().name(),
                                "why",
                                declared.why(),
                                "at",
                                declared.at()));
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
                                value,
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

    /** Returns whether the host holds a declaration of completed work for this task. */
    public boolean recorded(final String container) {
        final Declared declared = read(container);
        return declared != null && declared.outcome() == Outcome.DONE;
    }

    /** Records completion without a reason, as a caller from before outcomes did. */
    public void record(final String container) throws IOException {
        record(container, Outcome.DONE, "");
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
