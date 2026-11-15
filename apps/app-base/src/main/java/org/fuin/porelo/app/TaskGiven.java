package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkConnection;
import org.jspecify.annotations.Nullable;

/**
 * The files a task is given to read at its start, such as an issue as its planner holds it now:
 * kept by the host where only its account reads them, and mounted read only at {@link #MOUNT}.
 *
 * <p>Porelo never reads what they hold: it checks their names, their number and their size, and
 * writes them as they came. A task brought back keeps them; a new task of the name has only its
 * own, and its removal forgets them.
 *
 * @param paths The machine's roots.
 */
public record TaskGiven(PoreloPaths paths) {

    /** Where the files appear inside the container. */
    public static final String MOUNT = "/run/porelo/given";

    /** The most files one start gives. */
    public static final int MOST_FILES = 16;

    /** The most bytes of one file. */
    public static final int MOST = TaskResult.MOST;

    /** The directory inside the record that is mounted; the record around it is the account's. */
    private static final String FILES = "files";

    /**
     * Says why these files cannot be given, or {@code null} when they can.
     *
     * <p>Each name as a result file's is held to; at most {@link #MOST_FILES} files, each at most
     * {@link #MOST} bytes of UTF-8; and all of them, written as JSON, under the most one varlink
     * message carries, which is how a client hands them to the daemon.
     *
     * @param files File name to text, or {@code null} for none.
     * @return The refusal, a sentence.
     */
    public static @Nullable String refusal(final @Nullable Map<String, String> files) {
        if (files == null || files.isEmpty()) return null;
        if (files.size() > MOST_FILES) {
            return "a task is given at most " + MOST_FILES + " files, not " + files.size();
        }
        for (final Map.Entry<String, String> each : files.entrySet()) {
            final String named = TaskResult.refusal(each.getKey(), "a given file");
            if (named != null) return named;
            final byte[] bytes = utf8(each.getValue());
            if (bytes == null) return "'" + each.getKey() + "' is not UTF-8 text";
            if (bytes.length > MOST) {
                return "'"
                        + each.getKey()
                        + "' has "
                        + bytes.length
                        + " bytes, more than the 1 MiB a given file may have";
            }
        }
        final int encoded = Json.write(files).getBytes(StandardCharsets.UTF_8).length;
        if (encoded >= VarlinkConnection.MAX_MESSAGE) {
            return "the given files together are "
                    + encoded
                    + " bytes as JSON, more than the 4 MiB one message carries";
        }
        return null;
    }

    /** The text's UTF-8 bytes, or {@code null} for text that has none, such as a lone surrogate. */
    private static byte @Nullable [] utf8(final String text) {
        try {
            final var buffer =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(text));
            final byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    private Path record(final String container) {
        if (!TaskResult.named(container)) {
            throw new IllegalArgumentException("no task's name: " + container);
        }
        return paths.tasks().given(container);
    }

    /**
     * Writes the files a task is made with, forgetting whatever an earlier task of the name had.
     *
     * @param container The task, being made now.
     * @param files File name to text.
     * @throws IOException Writing failed.
     * @throws IllegalArgumentException The files are refused.
     */
    public void prepare(final String container, final Map<String, String> files)
            throws IOException {
        final String refused = refusal(files);
        if (refused != null) throw new IllegalArgumentException(refused);
        forget(container);
        if (files.isEmpty()) return;
        final Path record = record(container);
        Directories.create(record.getParent());
        Files.createDirectory(
                record,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(record, PosixFilePermissions.fromString("rwx------"));
        final Path written = Files.createDirectory(record.resolve(FILES));
        for (final Map.Entry<String, String> each : files.entrySet()) {
            final Path file = written.resolve(each.getKey());
            Files.write(
                    file,
                    java.util.Objects.requireNonNull(utf8(each.getValue()), "checked above"),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            // Readable by the agent, another uid under rootless podman; writable by nobody, as
            // its mount is read only.
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        }
        Files.setPosixFilePermissions(written, PosixFilePermissions.fromString("r-xr-xr-x"));
    }

    /**
     * Returns whether the task was given files.
     *
     * @param container The task.
     * @return {@code true} when it has their directory.
     */
    public boolean has(final String container) {
        return Files.isDirectory(files(container), LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(record(container));
    }

    /**
     * Returns the host's directory of the files, the one mounted at {@link #MOUNT}.
     *
     * @param container The task.
     * @return The directory.
     */
    public Path files(final String container) {
        return record(container).resolve(FILES);
    }

    /**
     * Returns the names of the files the task was given, in order.
     *
     * @param container The task.
     * @return The names; empty when it was given none.
     */
    public List<String> names(final String container) {
        if (!has(container)) return List.of();
        try (Stream<Path> each = Files.list(files(container))) {
            return each.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    /**
     * Forgets the files, as the task's removal and a new task of the name do.
     *
     * @param container The task.
     * @throws IOException Deleting failed.
     */
    public void forget(final String container) throws IOException {
        final Path record = record(container);
        if (!Files.exists(record, LinkOption.NOFOLLOW_LINKS)) return;
        // Writable again first: the files' directory was made read only.
        final Path written = record.resolve(FILES);
        if (Files.isDirectory(written, LinkOption.NOFOLLOW_LINKS)) {
            Files.setPosixFilePermissions(written, PosixFilePermissions.fromString("rwx------"));
        }
        // Walked without following a link.
        try (Stream<Path> tree = Files.walk(record)) {
            for (final Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
