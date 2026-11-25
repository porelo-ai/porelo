package org.fuin.porelo.gate.review;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a push's comments, replies and thread states in a journal only its owner can read, one per
 * task container, so the daemon and the command line read the same threads, a reject or a later
 * push of the same name leaves them readable, and a task started later under a reused name, which
 * runs in another container, inherits none.
 *
 * <p>The journal is {@code <root>/<container id>/journal}: one JSON object per line, each with its
 * format version, the push's project, repository, name, head and base, and either a kept item with
 * its producer, retry id and the time it was kept (UTC, RFC 3339, whole seconds; absent on an item
 * kept before items carried one) or a thread's state. It only grows; every write takes the lock (in
 * this process and, through a file lock, in every other), writes the whole journal to a temporary
 * file, forces it, moves it over the journal and syncs the directory. A temporary left by an
 * interrupted write is removed on the next use. A journal that is cut off, unreadable or of a newer
 * format is refused with an exception, never read as empty. Nothing in the store is followed
 * through a symbolic link.
 *
 * <p>A later format adds entries (runs, rounds) beside these; a version-1 entry stays as it is.
 */
public final class ReviewComments implements ReviewStore, ReviewCounts {

    /** The format version this class writes and the highest it reads. */
    static final int VERSION = 1;

    /** The most entries one container's journal holds. */
    static final int MAX_ENTRIES = 10_000;

    /** The most bytes one container's journal holds. */
    static final long MAX_BYTES = 16L * 1024 * 1024;

    /** A retry id: what a caller's request id needs, and nothing that could break a line. */
    private static final Pattern RETRY_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private static final String JOURNAL = "journal";

    private static final String LOCK = "journal.lock";

    private static final String TEMPORARY = "journal.tmp";

    private static final Set<PosixFilePermission> DIRECTORY_MODE =
            PosixFilePermissions.fromString("rwx------");

    private static final Set<PosixFilePermission> FILE_MODE =
            PosixFilePermissions.fromString("rw-------");

    private static final FileAttribute<Set<PosixFilePermission>> FILE_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(FILE_MODE);

    /** One lock per container in this process; a file lock alone throws for a second channel. */
    private static final ConcurrentHashMap<Path, ReentrantLock> HELD = new ConcurrentHashMap<>();

    private final Path root;

    private final Clock clock;

    /**
     * Constructor with the store's directory, each item's time read from the system's clock. It and
     * its missing parents are made, owner-only, on the first write.
     *
     * @param root The directory that holds one directory per task container.
     */
    public ReviewComments(final Path root) {
        this(root, Clock.systemUTC());
    }

    /**
     * Constructor with the store's directory and the clock each item's time is read from.
     *
     * @param root The directory that holds one directory per task container.
     * @param clock What the time an item is kept is read from.
     */
    public ReviewComments(final Path root, final Clock clock) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Kept keep(
            final Push push, final ReviewItem item, final Origin origin, final @Nullable String id)
            throws ReviewRefused {
        Objects.requireNonNull(push, "push");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(origin, "origin");
        if (id != null && !RETRY_ID.matcher(id).matches()) {
            throw new ReviewRefused(new ReviewRefusal(ReviewError.TEXT_REFUSED, "id"));
        }
        return io(
                () -> {
                    final Path container = container(push, true).orElseThrow();
                    return locked(
                            container,
                            () -> {
                                final Journal journal = read(container);
                                if (id != null) {
                                    final Kept before = retried(journal, push, item, origin, id);
                                    if (before != null) {
                                        return before;
                                    }
                                }
                                final String thread =
                                        switch (item) {
                                            case ReviewComment comment ->
                                                    UUID.randomUUID().toString();
                                            case ReviewReply reply -> {
                                                if (!journal.threads(push)
                                                        .containsKey(reply.thread())) {
                                                    throw refused(ReviewError.NO_SUCH_THREAD);
                                                }
                                                yield reply.thread();
                                            }
                                        };
                                final Kept kept =
                                        new Kept(
                                                UUID.randomUUID().toString(),
                                                thread,
                                                origin,
                                                item,
                                                clock.instant().truncatedTo(ChronoUnit.SECONDS));
                                append(container, journal, itemEntry(push, id, kept));
                                return kept;
                            });
                });
    }

    @Override
    public List<ReviewThread> threads(final Push push) {
        Objects.requireNonNull(push, "push");
        try {
            return io(
                    () -> {
                        final Optional<Path> found = container(push, false);
                        if (found.isEmpty()) {
                            return List.of();
                        }
                        final Path container = found.get();
                        return locked(
                                container,
                                () -> List.copyOf(read(container).threads(push).values()));
                    });
        } catch (ReviewRefused ex) {
            throw new IllegalStateException("reading threads refused nothing", ex);
        }
    }

    @Override
    public Optional<Push> recorded(
            final String project, final String repository, final String name, final String task) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(task, "task");
        // It names a directory: only a recorded container id, as a Push takes it.
        if (!task.matches("[0-9a-f]{12,64}")) {
            throw new IllegalArgumentException("task is no recorded container id");
        }
        final Scope scope = new Scope(project, repository, name);
        try {
            return io(
                    () -> {
                        final Optional<Path> found = container(task, false);
                        if (found.isEmpty()) {
                            return Optional.empty();
                        }
                        final Path container = found.get();
                        return locked(
                                container,
                                () -> {
                                    Push last = null;
                                    for (final Entry entry : read(container).entries()) {
                                        if (entry.scope().equals(scope)) {
                                            last = entry.push();
                                        }
                                    }
                                    return Optional.ofNullable(last);
                                });
                    });
        } catch (ReviewRefused ex) {
            throw new IllegalStateException("reading a recorded push refused nothing", ex);
        }
    }

    @Override
    public ReviewThread state(final Push push, final String thread, final ThreadState state)
            throws ReviewRefused {
        Objects.requireNonNull(push, "push");
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(state, "state");
        return io(
                () -> {
                    final Optional<Path> found = container(push, false);
                    if (found.isEmpty()) {
                        throw refused(ReviewError.NO_SUCH_THREAD);
                    }
                    final Path container = found.get();
                    return locked(
                            container,
                            () -> {
                                final Journal journal = read(container);
                                final ReviewThread now = journal.threads(push).get(thread);
                                if (now == null) {
                                    throw refused(ReviewError.NO_SUCH_THREAD);
                                }
                                if (now.state() == state) {
                                    return now;
                                }
                                append(container, journal, stateEntry(push, thread, state));
                                return new ReviewThread(thread, state, now.items());
                            });
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p>No finding is kept yet, so none is open; the owner account's review is the one run, and
     * complete, once the push has a thread.
     */
    @Override
    public Counts of(final Push push) {
        final List<ReviewThread> threads = threads(push);
        final int open = (int) threads.stream().filter(t -> t.state() == ThreadState.OPEN).count();
        final List<Run> runs =
                threads.isEmpty()
                        ? List.of()
                        : List.of(new Run(new OwnerAccount().words(), Completeness.COMPLETE, 0));
        return new Counts(0, open, runs);
    }

    // The container's directory, checked to be a real directory under the root; made when asked
    // and missing, or empty when not asked and missing.
    private Optional<Path> container(final Push push, final boolean make) throws IOException {
        return container(push.task(), make);
    }

    // The same directory for a task's container id alone, as the lookup of a past push needs.
    private Optional<Path> container(final String task, final boolean make) throws IOException {
        return container(root, task, make);
    }

    /**
     * Returns a task container's directory in a store, checked as the journal's is: owner-only,
     * never a link, made with its missing parents when asked.
     *
     * @param root The store's directory.
     * @param task The container id Porelo recorded for the task.
     * @param make Whether to make it when missing.
     * @return The directory, or empty when it is missing and not made.
     * @throws IOException When it cannot be made, or is refused.
     */
    static Optional<Path> container(final Path root, final String task, final boolean make)
            throws IOException {
        if (!directory(root, make, false)) {
            return Optional.empty();
        }
        // The real path keys the lock in this process: the same directory given under two
        // spellings (a linked parent, a relative path) must share one lock, or the second
        // channel's file lock in this process throws instead of waiting for the first.
        final Path real = root.toRealPath();
        final Path container = real.resolve(task).normalize();
        if (!real.equals(container.getParent())) {
            throw new IOException("A container directory outside the review store: " + container);
        }
        return directory(container, make, true) ? Optional.of(container) : Optional.empty();
    }

    // Whether a directory of the store is there, made owner-only when asked and missing. One that
    // is a link or no directory is refused, and, when it must be owner-only, one that another
    // account can reach is refused too: never widened, never used.
    private static boolean directory(final Path path, final boolean make, final boolean ownerOnly)
            throws IOException {
        PosixFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) {
            if (!make) {
                return false;
            }
            final Path parent = path.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                directory(parent, true, false);
            }
            try {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
                mode(path, DIRECTORY_MODE);
            } catch (FileAlreadyExistsException madeMeanwhile) {
                // Another writer made it; it is checked below like any other.
            }
            attributes = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
        }
        if (attributes.isSymbolicLink()) {
            throw new IOException("The review store refuses a symbolic link: " + path);
        }
        if (!attributes.isDirectory()) {
            throw new IOException("The review store expected a directory: " + path);
        }
        if (ownerOnly && !DIRECTORY_MODE.containsAll(attributes.permissions())) {
            throw new IOException(
                    "The review store refuses a directory another account can reach ("
                            + PosixFilePermissions.toString(attributes.permissions())
                            + "): "
                            + path);
        }
        return true;
    }

    private static <T> T locked(final Path container, final Work<T> work)
            throws IOException, ReviewRefused {
        final ReentrantLock here = HELD.computeIfAbsent(container, path -> new ReentrantLock());
        here.lock();
        try {
            final Path lock = container.resolve(LOCK);
            regularOrAbsent(lock);
            try (FileChannel channel =
                    FileChannel.open(lock, Set.of(CREATE, WRITE, NOFOLLOW_LINKS), FILE_ATTRIBUTE)) {
                // Before the lock: setting the mode opens the file and closes it again, and closing
                // any descriptor of a locked file gives up this process's lock on it.
                mode(lock, FILE_MODE);
                try (FileLock held = channel.lock()) {
                    Files.deleteIfExists(container.resolve(TEMPORARY));
                    return work.run();
                }
            }
        } finally {
            here.unlock();
        }
    }

    // Whether a file of the store is there; one that is anything but a regular file is refused, so
    // a link is never followed and a pipe never waited on.
    private static boolean regularOrAbsent(final Path path) throws IOException {
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) {
            return false;
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("The review store expected a regular file: " + path);
        }
        return true;
    }

    private static Journal read(final Path container) throws IOException {
        final Path path = container.resolve(JOURNAL);
        if (!regularOrAbsent(path)) {
            return new Journal(new byte[0], List.of());
        }
        final byte[] bytes;
        try (FileChannel channel = FileChannel.open(path, Set.of(READ, NOFOLLOW_LINKS))) {
            final long size = channel.size();
            if (size > MAX_BYTES) {
                throw new IOException("The review journal is over its limit: " + path);
            }
            final ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read until full or at the end.
            }
            if (buffer.hasRemaining()) {
                throw new IOException("The review journal changed while read: " + path);
            }
            bytes = buffer.array();
        } catch (NoSuchFileException ex) {
            return new Journal(new byte[0], List.of());
        }
        final String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("The review journal is not UTF-8: " + path, ex);
        }
        if (!text.endsWith("\n")) {
            throw new IOException("The review journal is cut off: " + path);
        }
        final String[] lines = text.split("\n", -1);
        // The text ends in a line feed, so the last part is empty.
        if (lines.length - 1 > MAX_ENTRIES) {
            throw new IOException("The review journal has more entries than its limit: " + path);
        }
        final String task = String.valueOf(container.getFileName());
        final List<Entry> entries = new ArrayList<>(lines.length - 1);
        for (int i = 0; i < lines.length - 1; i++) {
            try {
                entries.add(entry(lines[i], task));
            } catch (JsonException | IllegalArgumentException | NullPointerException ex) {
                throw new IOException(
                        "The review journal's entry "
                                + (i + 1)
                                + " cannot be read ("
                                + ex.getClass().getSimpleName()
                                + "): "
                                + path,
                        ex);
            }
        }
        whole(entries, path);
        return new Journal(bytes, entries);
    }

    // Checks the threads of every push in the journal, not only the one asked about: each thread
    // is opened once, and a reply or a state follows its thread's comment, in the same push.
    private static void whole(final List<Entry> entries, final Path path) throws IOException {
        final Map<String, Scope> opened = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            final String thread =
                    switch (entry) {
                        case ItemEntry kept -> kept.kept().thread();
                        case StateEntry state -> state.thread();
                    };
            final String broken;
            if (entry instanceof ItemEntry kept && kept.kept().item() instanceof ReviewComment) {
                broken =
                        opened.putIfAbsent(thread, entry.scope()) == null
                                ? null
                                : "opens a thread twice";
            } else {
                broken =
                        entry.scope().equals(opened.get(thread))
                                ? null
                                : "names a thread its push did not open before";
            }
            if (broken != null) {
                throw new IOException(
                        "The review journal's entry " + (i + 1) + " " + broken + ": " + path);
            }
        }
    }

    private static void append(final Path container, final Journal journal, final String line)
            throws IOException, ReviewRefused {
        if (journal.entries().size() + 1 > MAX_ENTRIES) {
            throw new ReviewRefused(new ReviewRefusal(ReviewError.TOO_LARGE, "journal"));
        }
        final byte[] added = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if ((long) journal.bytes().length + added.length > MAX_BYTES) {
            throw new ReviewRefused(new ReviewRefusal(ReviewError.TOO_LARGE, "journal"));
        }
        final Path temporary = container.resolve(TEMPORARY);
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            temporary, Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS), FILE_ATTRIBUTE)) {
                mode(temporary, FILE_MODE);
                final ByteBuffer[] buffers = {
                    ByteBuffer.wrap(journal.bytes()), ByteBuffer.wrap(added)
                };
                while (buffers[1].hasRemaining()) {
                    channel.write(buffers);
                }
                channel.force(true);
            }
            Files.move(
                    temporary,
                    container.resolve(JOURNAL),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        try (FileChannel directory = FileChannel.open(container, READ, NOFOLLOW_LINKS)) {
            directory.force(true);
        }
    }

    private static void mode(final Path path, final Set<PosixFilePermission> mode)
            throws IOException {
        final PosixFileAttributeView view =
                Files.getFileAttributeView(path, PosixFileAttributeView.class, NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("No owner-only mode can be set on " + path);
        }
        view.setPermissions(mode);
    }

    private static @Nullable Kept retried(
            final Journal journal,
            final Push push,
            final ReviewItem item,
            final Origin origin,
            final String id)
            throws ReviewRefused {
        for (final Entry entry : journal.entries()) {
            if (entry instanceof ItemEntry kept
                    && kept.scope().covers(push)
                    && id.equals(kept.id())) {
                if (kept.kept().item().equals(item) && kept.kept().origin().equals(origin)) {
                    return kept.kept();
                }
                throw refused(ReviewError.ID_REUSED);
            }
        }
        return null;
    }

    private static ReviewRefused refused(final ReviewError error) {
        return new ReviewRefused(new ReviewRefusal(error, null));
    }

    private static <T> T io(final Work<T> work) throws ReviewRefused {
        try {
            return work.run();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex.getMessage(), ex);
        }
    }

    // Writing ------------------------------------------------------------------------------------

    private static String itemEntry(final Push push, final @Nullable String id, final Kept kept) {
        final Map<String, @Nullable Object> entry = head("item", push);
        entry.put("producer", producer(kept.origin().producer()));
        entry.put("id", id);
        entry.put("guid", kept.guid());
        entry.put("thread", kept.thread());
        final Instant at = kept.at();
        if (at != null) {
            entry.put("at", at.toString());
        }
        entry.put("item", item(kept.item()));
        return Json.write(entry);
    }

    private static String stateEntry(
            final Push push, final String thread, final ThreadState state) {
        final Map<String, @Nullable Object> entry = head("state", push);
        entry.put("thread", thread);
        entry.put(
                "state",
                switch (state) {
                    case OPEN -> "open";
                    case RESOLVED -> "resolved";
                });
        return Json.write(entry);
    }

    private static Map<String, @Nullable Object> head(final String kind, final Push push) {
        final Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("v", VERSION);
        entry.put("entry", kind);
        entry.put("project", push.project());
        entry.put("repository", push.repository());
        entry.put("name", push.name());
        entry.put("head", push.head());
        entry.put("base", push.base());
        return entry;
    }

    private static Map<String, Object> producer(final Producer producer) {
        return switch (producer) {
            case OwnerAccount owner -> Map.of("channel", "owner account");
            case Task task -> Map.of("channel", "task", "task", task.id());
        };
    }

    private static Map<String, @Nullable Object> item(final ReviewItem item) {
        final Map<String, @Nullable Object> written = new LinkedHashMap<>();
        switch (item) {
            case ReviewComment comment -> {
                written.put("kind", "comment");
                written.put("anchor", anchor(comment.anchor()));
                written.put("body", comment.body().text());
                final Suggestion suggestion = comment.suggestion();
                written.put("suggestion", suggestion == null ? null : suggestion.replacement());
            }
            case ReviewReply reply -> {
                written.put("kind", "reply");
                written.put("body", reply.body().text());
            }
        }
        return written;
    }

    private static Map<String, Object> anchor(final Anchor anchor) {
        return switch (anchor) {
            case LineAnchor line ->
                    Map.of(
                            "kind", "line",
                            "path", line.path(),
                            "side", line.side().word(),
                            "startLine", line.startLine(),
                            "endLine", line.endLine());
        };
    }

    // Reading ------------------------------------------------------------------------------------

    private static Entry entry(final String line, final String task) {
        final Map<?, ?> entry = object(Json.parse(line), "entry");
        final int version = number(entry, "v");
        if (version != VERSION) {
            throw new IllegalArgumentException("format version " + version + " is not read here");
        }
        // Checked as a push is, so an altered entry refuses the journal instead of vanishing from
        // every query. The head and base are kept for the record; a thread is the push's whatever
        // its head.
        final Push push =
                new Push(
                        text(entry, "project"),
                        text(entry, "repository"),
                        text(entry, "name"),
                        text(entry, "head"),
                        optional(entry, "base"),
                        task);
        final Scope scope = new Scope(push.project(), push.repository(), push.name());
        final String thread = text(entry, "thread");
        return switch (text(entry, "entry")) {
            case "item" -> {
                final Origin origin =
                        Origin.of(producer(object(entry.get("producer"), "producer")));
                final ReviewItem item = item(object(entry.get("item"), "item"), thread);
                yield new ItemEntry(
                        push,
                        scope,
                        optional(entry, "id"),
                        new Kept(text(entry, "guid"), thread, origin, item, time(entry)));
            }
            case "state" ->
                    new StateEntry(
                            push,
                            scope,
                            thread,
                            switch (text(entry, "state")) {
                                case "open" -> ThreadState.OPEN;
                                case "resolved" -> ThreadState.RESOLVED;
                                default -> throw new IllegalArgumentException("an unknown state");
                            });
            default -> throw new IllegalArgumentException("an unknown entry");
        };
    }

    private static Producer producer(final Map<?, ?> producer) {
        return switch (text(producer, "channel")) {
            case "owner account" -> new OwnerAccount();
            case "task" -> new Task(text(producer, "task"));
            default -> throw new IllegalArgumentException("an unknown channel");
        };
    }

    private static ReviewItem item(final Map<?, ?> item, final String thread) {
        final Body body = new Body(text(item, "body"));
        return switch (text(item, "kind")) {
            case "comment" -> {
                final String suggestion = optional(item, "suggestion");
                yield new ReviewComment(
                        anchor(object(item.get("anchor"), "anchor")),
                        body,
                        suggestion == null ? null : new Suggestion(suggestion));
            }
            case "reply" -> new ReviewReply(thread, body);
            default -> throw new IllegalArgumentException("an unknown item");
        };
    }

    private static Anchor anchor(final Map<?, ?> anchor) {
        if (!"line".equals(text(anchor, "kind"))) {
            throw new IllegalArgumentException("an unknown anchor");
        }
        return new LineAnchor(
                text(anchor, "path"),
                Side.named(text(anchor, "side")),
                number(anchor, "startLine"),
                number(anchor, "endLine"));
    }

    // An item's time, absent on one kept before items carried a time; one that is no instant in
    // whole seconds refuses the entry.
    private static @Nullable Instant time(final Map<?, ?> entry) {
        if (!entry.containsKey("at")) {
            return null;
        }
        final Instant at;
        try {
            at = Instant.parse(text(entry, "at"));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("at is not a time", ex);
        }
        if (!at.equals(at.truncatedTo(ChronoUnit.SECONDS))) {
            throw new IllegalArgumentException("at is not in whole seconds");
        }
        return at;
    }

    private static Map<?, ?> object(final @Nullable Object value, final String what) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException(what + " is not an object");
    }

    private static String text(final Map<?, ?> map, final String key) {
        if (map.get(key) instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(key + " is not a text");
    }

    private static @Nullable String optional(final Map<?, ?> map, final String key) {
        if (!map.containsKey(key)) {
            throw new IllegalArgumentException(key + " is missing");
        }
        final Object value = map.get(key);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalArgumentException(key + " is not a text");
    }

    private static int number(final Map<?, ?> map, final String key) {
        if (map.get(key) instanceof Number number) {
            final double value = number.doubleValue();
            if (value == Math.rint(value) && value >= 0 && value <= Integer.MAX_VALUE) {
                return (int) value;
            }
        }
        throw new IllegalArgumentException(key + " is not a whole number");
    }

    // The model ----------------------------------------------------------------------------------

    /** What runs under the lock. */
    @FunctionalInterface
    private interface Work<T> {

        T run() throws IOException, ReviewRefused;
    }

    /** The push a thread belongs to within its container, whatever its head. */
    private record Scope(String project, String repository, String name) {

        boolean covers(final Push push) {
            return project.equals(push.project())
                    && repository.equals(push.repository())
                    && name.equals(push.name());
        }
    }

    private sealed interface Entry permits ItemEntry, StateEntry {

        /** The push as the entry recorded it, with the head and base it was written for. */
        Push push();

        Scope scope();
    }

    private record ItemEntry(Push push, Scope scope, @Nullable String id, Kept kept)
            implements Entry {}

    private record StateEntry(Push push, Scope scope, String thread, ThreadState state)
            implements Entry {}

    /** A container's journal as read: its bytes, to append to, and its entries. */
    private record Journal(byte[] bytes, List<Entry> entries) {

        /**
         * The push's threads, the oldest first, by id. The journal was read whole, so each thread
         * is opened once and every reply and state follows its comment in the same push.
         */
        Map<String, ReviewThread> threads(final Push push) {
            final Map<String, List<Kept>> items = new LinkedHashMap<>();
            final Map<String, ThreadState> states = new LinkedHashMap<>();
            for (final Entry entry : entries) {
                if (!entry.scope().covers(push)) {
                    continue;
                }
                switch (entry) {
                    case ItemEntry kept ->
                            items.computeIfAbsent(kept.kept().thread(), t -> new ArrayList<>())
                                    .add(kept.kept());
                    case StateEntry state -> states.put(state.thread(), state.state());
                }
            }
            final Map<String, ReviewThread> threads = new LinkedHashMap<>();
            for (final Map.Entry<String, List<Kept>> thread : items.entrySet()) {
                threads.put(
                        thread.getKey(),
                        new ReviewThread(
                                thread.getKey(),
                                states.getOrDefault(thread.getKey(), ThreadState.OPEN),
                                thread.getValue()));
            }
            return threads;
        }
    }
}
