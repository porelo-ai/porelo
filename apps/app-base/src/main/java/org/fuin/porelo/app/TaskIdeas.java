package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;

/**
 * The ideas a task proposed for itself, kept by the host until a person takes them or the task is
 * removed.
 *
 * <p>Beside the task's {@code done} record, in its durable record directory, outside every
 * directory mounted into the task: the task proposes over its socket, and only the host writes or
 * numbers what it proposed. The record counts every idea the task proposed, the taken ones too, so
 * a taken idea's number is not given again and frees no room under the bound. A later start keeps
 * them; the task's removal deletes the directory.
 */
public record TaskIdeas(PoreloPaths paths) {

    /** The most characters of an idea's title, which is one line. */
    public static final int MOST_TITLE = 120;

    /** The most characters of what must be true, one or two sentences. */
    public static final int MOST_WHAT_MUST_BE_TRUE = 1000;

    /** The record's name in the task's record directory. */
    static final String FILE = "ideas.json";

    /**
     * Larger than the most ideas a project may allow, each at its longest and escaped; a larger
     * record is not one the host wrote.
     */
    private static final int MOST_RECORD = 4 * 1024 * 1024;

    /** The largest counter an id can carry. */
    private static final long MOST_PROPOSED = 999_999_999L;

    private static final java.util.regex.Pattern ID =
            java.util.regex.Pattern.compile("idea-[1-9][0-9]{0,8}");

    /** The ideas a record holds, and how many the task proposed in all, the taken ones too. */
    private record Kept(long proposed, List<Idea> ideas) {}

    /** One idea, as the task proposed it and the host numbered it. */
    public record Idea(String id, String title, String whatMustBeTrue, String at) {

        /** Refuses an idea the host would not have kept. */
        public Idea {
            if (!ID.matcher(id).matches()
                    || !validTitle(title)
                    || !validWhatMustBeTrue(whatMustBeTrue)) {
                throw new IllegalArgumentException("not an idea the host keeps: " + id);
            }
            Instant.parse(at);
        }

        /**
         * Returns it as a client is sent it.
         *
         * @return Its four fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("title", title);
            map.put("whatMustBeTrue", whatMustBeTrue);
            map.put("at", at);
            return map;
        }
    }

    /** A proposal past the bound the project file names. */
    public static final class BoundReached extends Exception {
        private static final long serialVersionUID = 1L;

        private final int most;

        /**
         * Constructor.
         *
         * @param most The bound.
         */
        public BoundReached(final int most) {
            super("the task has proposed the most ideas its project allows, " + most);
            this.most = most;
        }

        /**
         * Returns the bound.
         *
         * @return The most ideas one task may propose.
         */
        public int most() {
            return most;
        }
    }

    /**
     * Returns whether an id is one the host gives: {@code idea-} and the task's counter.
     *
     * @param id What a client gave.
     * @return Whether an idea can carry it.
     */
    public static boolean validId(final String id) {
        return ID.matcher(id).matches();
    }

    /**
     * Returns whether a title can be kept: one line of at most {@link #MOST_TITLE} characters,
     * neither blank nor holding a control character, refused rather than trimmed or cut.
     *
     * @param title What the task gave.
     * @return Whether it is kept as it is.
     */
    public static boolean validTitle(final String title) {
        return text(title, MOST_TITLE)
                && title.codePoints()
                        .noneMatch(
                                point ->
                                        Character.isISOControl(point)
                                                || point == 0x2028
                                                || point == 0x2029);
    }

    /**
     * Returns whether what must be true can be kept: at most {@link #MOST_WHAT_MUST_BE_TRUE}
     * characters, not blank, with no control character but line breaks.
     *
     * @param whatMustBeTrue What the task gave.
     * @return Whether it is kept as it is.
     */
    public static boolean validWhatMustBeTrue(final String whatMustBeTrue) {
        return text(whatMustBeTrue, MOST_WHAT_MUST_BE_TRUE)
                && whatMustBeTrue
                        .codePoints()
                        .noneMatch(
                                point ->
                                        Character.isISOControl(point)
                                                && point != '\n'
                                                && point != '\r');
    }

    private static boolean text(final String value, final int most) {
        return !value.isBlank()
                && value.codePointCount(0, value.length()) <= most
                && StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }

    /**
     * Reads the task's ideas.
     *
     * @param container The task.
     * @return Its ideas, oldest first; none when none can be read.
     */
    public List<Idea> read(final String container) {
        try {
            return load(record(container)).ideas();
        } catch (IOException ex) {
            return List.of();
        }
    }

    /**
     * Keeps one more idea of the task's, numbered by the task's own counter.
     *
     * @param container The task.
     * @param title Its title.
     * @param whatMustBeTrue What must be true.
     * @param most The most ideas the task may propose.
     * @return The idea as kept.
     * @throws IOException If the record cannot be read or written; an unreadable record is never
     *     taken for an empty one, which would number an idea again.
     * @throws BoundReached If the task proposed {@code most} already.
     */
    public Idea propose(
            final String container, final String title, final String whatMustBeTrue, final int most)
            throws IOException, BoundReached {
        final Path directory = paths.tasks().taskRecord(container);
        Directories.create(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        final Path file = record(container);
        final java.util.Optional<Idea> kept =
                FileLocks.holding(
                        directory.resolve("ideas.lock"),
                        () -> {
                            final Kept record = load(file);
                            // Of every idea it proposed: one taken frees no room.
                            if (record.proposed() >= most) {
                                return java.util.Optional.<Idea>empty();
                            }
                            final List<Idea> ideas = new ArrayList<>(record.ideas());
                            final Idea idea =
                                    new Idea(
                                            "idea-" + (record.proposed() + 1),
                                            title,
                                            whatMustBeTrue,
                                            Instant.now().toString());
                            ideas.add(idea);
                            write(directory, file, new Kept(record.proposed() + 1, ideas));
                            return java.util.Optional.of(idea);
                        });
        return kept.orElseThrow(() -> new BoundReached(most));
    }

    /**
     * Forgets one of the task's ideas a person took, kept or declined, so a reply carries its next
     * ones. The caller holds the lock a removal takes: a record the removal deleted is not made
     * again.
     *
     * @param container The task.
     * @param id The idea's id.
     * @return Whether the task held it: not when taken before, never proposed, or the task is
     *     removed.
     * @throws IOException If the record cannot be read or written.
     */
    public boolean take(final String container, final String id) throws IOException {
        final Path directory = paths.tasks().taskRecord(container);
        final Path file = record(container);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return FileLocks.holding(
                directory.resolve("ideas.lock"),
                () -> {
                    final Kept kept = load(file);
                    final List<Idea> ideas = new ArrayList<>(kept.ideas());
                    if (!ideas.removeIf(idea -> idea.id().equals(id))) {
                        return false;
                    }
                    write(directory, file, new Kept(kept.proposed(), ideas));
                    return true;
                });
    }

    private Path record(final String container) {
        return paths.tasks().taskRecord(container).resolve(FILE);
    }

    private static Kept load(final Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Kept(0, List.of());
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(file + " is not a file the host wrote");
        }
        final byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MOST_RECORD + 1);
        }
        if (bytes.length > MOST_RECORD) {
            throw new IOException(file + " is larger than the host writes one");
        }
        try {
            final String text =
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (!(Json.parseExact(text) instanceof Map<?, ?> fields
                    && fields.get("ideas") instanceof List<?> listed)) {
                throw new IOException(file + " holds no ideas");
            }
            final List<Idea> ideas = new ArrayList<>();
            long numbered = 0;
            for (final Object each : listed) {
                if (!(each instanceof Map<?, ?> idea
                        && idea.get("id") instanceof String id
                        && idea.get("title") instanceof String title
                        && idea.get("whatMustBeTrue") instanceof String what
                        && idea.get("at") instanceof String at)) {
                    throw new IOException(file + " holds something that is no idea");
                }
                final Idea read = new Idea(id, title, what, at);
                final long number = Long.parseLong(id.substring("idea-".length()));
                if (number <= numbered) {
                    throw new IOException(file + " holds its ideas out of order");
                }
                numbered = number;
                ideas.add(read);
            }
            // A record from before ideas were taken counts by its newest.
            final Object proposed =
                    fields.containsKey("proposed") ? fields.get("proposed") : numbered;
            if (!(proposed instanceof Long count) || count < numbered || count > MOST_PROPOSED) {
                throw new IOException(file + " counts its ideas wrongly");
            }
            return new Kept(count, List.copyOf(ideas));
        } catch (CharacterCodingException
                | JsonException
                | IllegalArgumentException
                | DateTimeException ex) {
            throw new IOException(file + " cannot be read: " + ex.getMessage(), ex);
        }
    }

    private static void write(final Path directory, final Path file, final Kept kept)
            throws IOException {
        final Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("proposed", kept.proposed());
        fields.put("ideas", kept.ideas().stream().map(Idea::asMap).toList());
        final String value = Json.write(fields);
        final Path staged = directory.resolve("ideas.new");
        try {
            Files.writeString(
                    staged,
                    value,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-------"));
            Files.move(
                    staged,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
