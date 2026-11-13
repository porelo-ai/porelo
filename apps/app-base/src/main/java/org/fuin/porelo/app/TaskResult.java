package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * The one file a task hands back at its end, taken from the box its agent writes it into and kept
 * on the host, bounded, until the next start of its name.
 *
 * <p>The box is the only part mounted into the task. What was taken lives beside it, where the
 * agent cannot reach, and is answered after the container is gone and after the task is removed.
 *
 * @param paths The machine's roots.
 */
public record TaskResult(PoreloPaths paths) {

    /** Where the box appears inside the container. */
    public static final String MOUNT = "/run/porelo/result";

    /** The most bytes a handed-back file may have. */
    public static final int MOST = 1024 * 1024;

    /** The most UTF-8 bytes of a result file's name. */
    public static final int MOST_NAME = 255;

    private static final int MOST_RECORD = 4 * 1024;

    private static final String NAME = "name";

    private static final String BOX = "box";

    private static final String RECORD = "taken.json";

    private static final String CONTENT = "taken";

    private static final String HELD = "held";

    private static final String LOCK = "result.lock";

    /** Why a task has handed back its file, or why not. */
    public enum State {
        /** Taken whole, as UTF-8 text. */
        HANDED("handed"),
        /** No file of that name was there. */
        MISSING("missing"),
        /** Larger than {@link #MOST}. */
        TOO_LARGE("too-large"),
        /** Not a regular file, not readable by the host, or not text. */
        UNREADABLE("unreadable");

        private final String wire;

        State(final String wire) {
            this.wire = wire;
        }

        /**
         * Returns the spelling an interface reads.
         *
         * @return The state's word.
         */
        public String wire() {
            return wire;
        }

        static @Nullable State of(final String wire) {
            for (final State state : values()) {
                if (state.wire.equals(wire)) return state;
            }
            return null;
        }
    }

    /**
     * What was taken from a task.
     *
     * @param name The file's name, as its start gave it.
     * @param state Whether it was handed back, or why not.
     * @param bytes Its size: what was read, or for one too large the size its attributes give.
     */
    public record Taken(String name, State state, long bytes) {

        /**
         * Returns it as an interface reads it.
         *
         * @return Name, state and bytes.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("state", state.wire());
            map.put("bytes", bytes);
            return map;
        }
    }

    /**
     * What a reader is answered.
     *
     * @param taken What was taken, or {@code missing} while nothing was.
     * @param content The text, only when it was handed back.
     */
    public record Answer(Taken taken, @Nullable String content) {

        /**
         * Returns it as an interface reads it.
         *
         * @return Name, state, bytes and the content when there is one.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>(taken.asMap());
            if (content != null) map.put("content", content);
            return map;
        }
    }

    /**
     * Says why a name cannot be a result file's, or {@code null} when it can.
     *
     * @param name The name a start was given.
     * @return The refusal, a sentence; {@code null} for a name that is one file's.
     */
    public static @Nullable String refusal(final @Nullable String name) {
        if (name == null) return null;
        if (name.isEmpty()) return "a result file needs a name";
        if (name.equals(".") || name.equals("..")) return "'" + name + "' names no file";
        if (name.contains("/")) return "a result file is named without a directory";
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            return "a result file's name holds no control character";
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(name)) {
            return "a result file's name is UTF-8 text";
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MOST_NAME) {
            return "a result file's name has at most " + MOST_NAME + " bytes";
        }
        return null;
    }

    private Path directory(final String container) {
        if (!named(container)) {
            throw new IllegalArgumentException("no task's name: " + container);
        }
        return paths.tasks().result(container);
    }

    /**
     * Tells whether a name is one a task's results are kept under: a task's container name, one
     * path element of letters, digits, {@code _}, {@code .} and {@code -}.
     *
     * @param container The name as given.
     * @return {@code true} for a task's name; a name with a directory part is none.
     */
    public static boolean named(final String container) {
        return org.fuin.porelo.runtime.ContainerName.isTask(container)
                && container.matches("[A-Za-z0-9_.-]{1,64}")
                && !container.contains("..");
    }

    /**
     * Makes the task's box for the file it is to hand back, forgetting whatever an earlier task of
     * the same name left.
     *
     * @param container The task, being made now.
     * @param name The file's name.
     * @throws IOException Making it failed.
     */
    public void prepare(final String container, final String name) throws IOException {
        final String refused = refusal(name);
        if (refused != null) throw new IllegalArgumentException(refused);
        forget(container);
        final Path directory = directory(container);
        Directories.create(directory.getParent());
        Files.createDirectory(
                directory,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        staged(directory, NAME, name.getBytes(StandardCharsets.UTF_8));
        final Path box = Files.createDirectory(directory.resolve(BOX));
        // Open: the agent is another user than the account, a subordinate id under rootless podman;
        // only the box is mounted, and the directory around it keeps every other account out.
        Files.setPosixFilePermissions(box, PosixFilePermissions.fromString("rwxrwxrwx"));
    }

    /**
     * Returns whether the task has a box to mount.
     *
     * @param container The task.
     * @return {@code true} when its start named a file and its box is a directory.
     */
    public boolean hasBox(final String container) {
        return name(container) != null
                && Files.isDirectory(directory(container).resolve(BOX), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Returns the host path of the task's box.
     *
     * @param container The task.
     * @return The directory mounted at {@link #MOUNT}.
     */
    public Path box(final String container) {
        return directory(container).resolve(BOX);
    }

    /**
     * Returns the name of the file the task is to hand back.
     *
     * @param container The task.
     * @return The name, or {@code null} when its start named none.
     */
    public @Nullable String name(final String container) {
        final Path directory = directory(container);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        final byte[] bytes = bounded(directory.resolve(NAME), MOST_NAME + 1);
        if (bytes == null || bytes.length > MOST_NAME) return null;
        final String name = text(bytes);
        return name == null || refusal(name) != null ? null : name;
    }

    /**
     * Takes the file from the task's box, unless one was handed back already: taken again it would
     * be what the agent made of it after it said it was done.
     *
     * <p>Moved out of the box first, into a directory the agent cannot reach, and only then looked
     * at: a file it swaps for a link or a pipe while the host reads it is never followed and never
     * opened. Moved back afterwards, unless the agent wrote a new one meanwhile.
     *
     * @param container The task.
     * @return What was taken, or {@code null} when its start named no file.
     * @throws IOException Keeping what was taken failed.
     */
    public @Nullable Taken take(final String container) throws IOException {
        final Path directory = directory(container);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        return FileLocks.holding(
                        directory.resolve(LOCK),
                        () -> {
                            final String name = name(container);
                            final Taken before = taken(container);
                            if (name == null || before != null && before.state() == State.HANDED) {
                                return java.util.Optional.ofNullable(before);
                            }
                            final Taken now = fromBox(directory, name);
                            if (now.state() != State.HANDED) {
                                Files.deleteIfExists(directory.resolve(CONTENT));
                            }
                            staged(
                                    directory,
                                    RECORD,
                                    Json.write(now.asMap()).getBytes(StandardCharsets.UTF_8));
                            return java.util.Optional.of(now);
                        })
                .orElse(null);
    }

    /**
     * Takes the file once it is handed back: what a listing asks of a task whose agent ended. A
     * kept record other than {@link State#HANDED} is provisional and taken again; only a file
     * handed back is final.
     *
     * @param container The task.
     * @return What was taken, now or before, or {@code null} when its start named no file.
     * @throws IOException Keeping what was taken failed.
     */
    public @Nullable Taken takeOnce(final String container) throws IOException {
        final Taken before = taken(container);
        return before != null && before.state() == State.HANDED ? before : take(container);
    }

    private Taken fromBox(final Path directory, final String name) throws IOException {
        final Path box = directory.resolve(BOX);
        if (!Files.exists(box, LinkOption.NOFOLLOW_LINKS)) return new Taken(name, State.MISSING, 0);
        if (!Files.isDirectory(box, LinkOption.NOFOLLOW_LINKS)) {
            return new Taken(name, State.UNREADABLE, 0);
        }
        final Path file = box.resolve(name);
        final Path held = directory.resolve(HELD);
        deleteTree(held);
        try {
            Files.move(file, held, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException ex) {
            return new Taken(name, State.MISSING, 0);
        } catch (IOException ex) {
            return new Taken(name, State.UNREADABLE, 0);
        }
        try {
            return read(directory, held, name);
        } finally {
            putBack(held, file);
        }
    }

    /**
     * Puts a taken file back into the box, unless the agent wrote another there meanwhile, which
     * stays: a rename would replace it, a link refuses a name that exists.
     *
     * @param held Where the taken file is.
     * @param file Its place in the box.
     * @throws IOException What is held cannot be deleted.
     */
    static void putBack(final Path held, final Path file) throws IOException {
        try {
            if (Files.isDirectory(held, LinkOption.NOFOLLOW_LINKS)) {
                // A directory cannot be linked; moved without replacing, it is refused by a name
                // that exists but for the moment between the look and the rename.
                Files.move(held, file);
                return;
            }
            Files.createLink(file, held);
        } catch (FileAlreadyExistsException | NoSuchFileException ex) {
            // The agent wrote another meanwhile; or there is nothing to put back.
        } catch (IOException | UnsupportedOperationException ex) {
            // Not put back: the agent's box goes without it, the host keeps what it took.
        }
        deleteTree(held);
    }

    private Taken read(final Path directory, final Path held, final String name)
            throws IOException {
        final BasicFileAttributes attributes;
        try {
            attributes =
                    Files.readAttributes(
                            held, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            return new Taken(name, State.UNREADABLE, 0);
        }
        if (!attributes.isRegularFile()) return new Taken(name, State.UNREADABLE, 0);
        final byte[] bytes;
        try (InputStream input = Files.newInputStream(held, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MOST + 1);
        } catch (IOException ex) {
            return new Taken(name, State.UNREADABLE, attributes.size());
        }
        if (bytes.length > MOST || attributes.size() > MOST) {
            return new Taken(name, State.TOO_LARGE, Math.max(attributes.size(), bytes.length));
        }
        final String text = text(bytes);
        // Tab, line feed and carriage return only: a control character is no text, and it is
        // written six bytes long on the wire, past what one reply carries.
        if (text == null
                || text.codePoints()
                        .anyMatch(
                                c ->
                                        Character.isISOControl(c)
                                                && c != '\t'
                                                && c != '\n'
                                                && c != '\r')) {
            return new Taken(name, State.UNREADABLE, bytes.length);
        }
        staged(directory, CONTENT, bytes);
        return new Taken(name, State.HANDED, bytes.length);
    }

    /**
     * Returns what was taken from the task.
     *
     * @param container The task.
     * @return It, or {@code null} when nothing was taken since its start.
     */
    public @Nullable Taken taken(final String container) {
        final Path directory = directory(container);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        final byte[] bytes = bounded(directory.resolve(RECORD), MOST_RECORD + 1);
        if (bytes == null || bytes.length > MOST_RECORD) return null;
        final String text = text(bytes);
        if (text == null) return null;
        try {
            if (Json.parse(text) instanceof Map<?, ?> fields
                    && fields.get("name") instanceof String name
                    && refusal(name) == null
                    && fields.get("state") instanceof String word
                    && State.of(word) instanceof State state
                    && fields.get("bytes") instanceof Number size
                    && size.longValue() >= 0) {
                return new Taken(name, state, size.longValue());
            }
        } catch (JsonException ex) {
            return null;
        }
        return null;
    }

    /**
     * Answers a reader: what was taken and, when it was handed back, its text.
     *
     * @param container The task.
     * @return The answer, {@code missing} while nothing was taken; {@code null} when its start
     *     named no file and nothing of it is kept.
     */
    public @Nullable Answer answer(final String container) {
        if (!named(container)) return null;
        final Taken taken = taken(container);
        if (taken == null) {
            final String name = name(container);
            return name == null ? null : new Answer(new Taken(name, State.MISSING, 0), null);
        }
        if (taken.state() != State.HANDED) return new Answer(taken, null);
        final byte[] bytes = bounded(directory(container).resolve(CONTENT), MOST + 1);
        final String text = bytes == null || bytes.length > MOST ? null : text(bytes);
        return text == null
                ? new Answer(new Taken(taken.name(), State.UNREADABLE, taken.bytes()), null)
                : new Answer(taken, text);
    }

    /**
     * Forgets what was taken, as a task's next start does; the name and the box stay.
     *
     * @param container The task.
     */
    public void clear(final String container) {
        final Path directory = directory(container);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            FileLocks.holding(
                    directory.resolve(LOCK),
                    () -> {
                        Files.deleteIfExists(directory.resolve(RECORD));
                        Files.deleteIfExists(directory.resolve(CONTENT));
                        return true;
                    });
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(
                    "could not clear the result of " + container, ex);
        }
    }

    /**
     * Ends the task's box as its removal does: takes the file once more when none was handed back,
     * and keeps what was taken for a reader, until the next start of the name.
     *
     * @param container The task, being removed.
     * @throws IOException Taking or deleting failed.
     */
    public void ended(final String container) throws IOException {
        final Path directory = directory(container);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(directory);
            return;
        }
        take(container);
        if (taken(container) == null) {
            forget(container);
            return;
        }
        FileLocks.holding(
                directory.resolve(LOCK),
                () -> {
                    deleteTree(directory.resolve(BOX));
                    deleteTree(directory.resolve(HELD));
                    Files.deleteIfExists(directory.resolve(NAME));
                    return true;
                });
    }

    /**
     * Forgets everything of the task's result: its box, its name and what was taken.
     *
     * @param container The task.
     * @throws IOException Deleting failed.
     */
    public void forget(final String container) throws IOException {
        deleteTree(directory(container));
    }

    private static void staged(final Path directory, final String name, final byte[] bytes)
            throws IOException {
        final Path staged = directory.resolve(name + ".new");
        try {
            Files.deleteIfExists(staged);
            Files.write(
                    staged,
                    bytes,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-------"));
            Files.move(
                    staged,
                    directory.resolve(name),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static byte @Nullable [] bounded(final Path file, final int most) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return input.readNBytes(most);
        } catch (IOException ex) {
            return null;
        }
    }

    private static @Nullable String text(final byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        // Walked without following a link, so a link the agent left is removed, never its target.
        try (Stream<Path> tree = Files.walk(root)) {
            for (final Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
