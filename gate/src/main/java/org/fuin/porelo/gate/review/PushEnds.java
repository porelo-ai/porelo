package org.fuin.porelo.gate.review;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * Keeps how a task's last push stopped waiting at the gate, one entry per task container, beside
 * that container's review journal, so a reader can tell a returned push from an approved one after
 * nothing waits any more, and a task started later under a reused name, which runs in another
 * container, finds none.
 *
 * <p>The entry is {@code <root>/<container id>/end}: one JSON object with its format version and
 * the {@link PushEnd}'s fields, the time in UTC, RFC 3339, whole seconds. Each write goes to an
 * owner-only temporary file, is forced, moved over the entry and the directory synced, so a reader
 * sees the old entry or the new one whole. An entry that is cut off, of a newer format or missing a
 * field is refused with an exception, never read as no end. Nothing is followed through a symbolic
 * link.
 */
public final class PushEnds {

    /** The format version this class writes and the highest it reads. */
    static final int VERSION = 1;

    /** The most bytes an entry holds; a real one is a few hundred. */
    static final int MAX_BYTES = 8 * 1024;

    static final String ENTRY = "end";

    private static final Pattern CONTAINER = Pattern.compile("[0-9a-f]{12,64}");

    private static final FileAttribute<Set<PosixFilePermission>> FILE_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final Path root;

    /**
     * Constructor with the store's directory, the one the review journals are kept in. It and its
     * missing parents are made, owner-only, on the first write.
     *
     * @param root The directory that holds one directory per task container.
     */
    public PushEnds(final Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /**
     * Keeps a push's end for its task, over any earlier one.
     *
     * @param task The container id Porelo recorded for the task that pushed it.
     * @param end How the push ended.
     * @throws IOException When it cannot be written.
     */
    public void keep(final String task, final PushEnd end) throws IOException {
        Objects.requireNonNull(end, "end");
        final Path container = ReviewComments.container(root, checked(task), true).orElseThrow();
        final byte[] bytes = (Json.write(entry(end)) + "\n").getBytes(StandardCharsets.UTF_8);
        final Path temporary = Files.createTempFile(container, ENTRY + ".", ".tmp", FILE_ATTRIBUTE);
        try {
            try (FileChannel channel = FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS)) {
                final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(
                    temporary,
                    container.resolve(ENTRY),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        sync(container);
    }

    /**
     * Reads a task's kept end.
     *
     * @param task The container id Porelo recorded for the task.
     * @return The end, or empty when none is kept for that container.
     * @throws IOException When the entry cannot be read, or is malformed.
     */
    public Optional<PushEnd> read(final String task) throws IOException {
        final Optional<Path> container = ReviewComments.container(root, checked(task), false);
        if (container.isEmpty()) {
            return Optional.empty();
        }
        final Path path = container.get().resolve(ENTRY);
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) {
            return Optional.empty();
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("A push's end that is no plain file: " + path);
        }
        final byte[] bytes;
        try (InputStream in = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("A push's end larger than " + MAX_BYTES + " bytes: " + path);
        }
        try {
            return Optional.of(parsed(new String(bytes, StandardCharsets.UTF_8)));
        } catch (JsonException | IllegalArgumentException | DateTimeParseException ex) {
            throw new IOException("A push's end that cannot be read: " + path, ex);
        }
    }

    /**
     * Forgets a task's kept end, when its next push waits.
     *
     * @param task The container id Porelo recorded for the task.
     * @throws IOException When it cannot be removed.
     */
    public void clear(final String task) throws IOException {
        final Optional<Path> container = ReviewComments.container(root, checked(task), false);
        if (container.isPresent() && Files.deleteIfExists(container.get().resolve(ENTRY))) {
            sync(container.get());
        }
    }

    private static String checked(final String task) {
        Objects.requireNonNull(task, "task");
        if (!CONTAINER.matcher(task).matches()) {
            throw new IllegalArgumentException("task is not a recorded container id");
        }
        return task;
    }

    private static void sync(final Path container) throws IOException {
        try (FileChannel directory = FileChannel.open(container, READ, NOFOLLOW_LINKS)) {
            directory.force(true);
        }
    }

    private static Map<String, Object> entry(final PushEnd end) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("version", VERSION);
        entry.put("name", end.name());
        entry.put("commit", end.commit());
        entry.put("end", end.end().name());
        entry.put("openThreads", end.openThreads());
        if (end.mergedCommit() != null) {
            entry.put("mergedCommit", end.mergedCommit());
        }
        if (end.branch() != null) {
            entry.put("branch", end.branch());
        }
        entry.put("at", end.at().toString());
        return entry;
    }

    private static PushEnd parsed(final String text) {
        if (!(Json.parseExact(text) instanceof Map<?, ?> entry)) {
            throw new IllegalArgumentException("no JSON object");
        }
        final long version = number(entry, "version");
        if (version < 1 || version > VERSION) {
            throw new IllegalArgumentException("format version " + version);
        }
        final long open = number(entry, "openThreads");
        if (open > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("openThreads out of range");
        }
        return new PushEnd(
                text(entry, "name"),
                text(entry, "commit"),
                PushEnd.End.valueOf(text(entry, "end")),
                (int) open,
                optional(entry, "mergedCommit"),
                optional(entry, "branch"),
                Instant.parse(text(entry, "at")));
    }

    private static String text(final Map<?, ?> entry, final String key) {
        if (!(entry.get(key) instanceof String value)) {
            throw new IllegalArgumentException(key + " missing");
        }
        return value;
    }

    private static @Nullable String optional(final Map<?, ?> entry, final String key) {
        return entry.containsKey(key) ? text(entry, key) : null;
    }

    private static long number(final Map<?, ?> entry, final String key) {
        if (!(entry.get(key) instanceof Long value)) {
            throw new IllegalArgumentException(key + " missing");
        }
        return value;
    }
}
