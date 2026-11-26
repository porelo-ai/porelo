package org.fuin.porelo.gate.review;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the items held for the owner account's decision in a journal only its owner can read, one
 * per task, so the daemon and the command line see the same holds and a restart loses none.
 *
 * <p>The journal is {@code <root>/<task>/holds}: one JSON object per line. The first names the
 * format version and the task; each later one records a run started at a place, an item held (an
 * incoming one with what its place gave: its id and thread there, the forge account that wrote it
 * and its time, and the rule it was withheld by), a decision on it, or what became of a released
 * item's write. An outgoing item no certified key signed carries {@code "unsigned": true}, kept
 * through every later state. A state is only replayed: an undecided item of a run lapses when a
 * later run of its task starts at its place; a released one stays until it is handed to its place's
 * write, recorded as being sent before the write, and its answer marked: delivered, not delivered
 * (with the place's reason), to be retried (with the time, rounded up to a whole second) or of
 * unknown outcome. Only a released item or a retry whose time passed is due for a write; one left
 * being sent is shown as it is and never sent again by itself. Every write takes the store's lock
 * (in this process and, through a file lock, in every other) and appends one line, which it forces
 * to the disk. A last line without its line feed is the rest of a write cut off: it is ignored, and
 * cut away by the next write. Any other line that cannot be read, an item whose digest is not its
 * content's, or a newer format refuses the journal with an exception, never read as empty. Nothing
 * is followed through a symbolic link, and a task's directory or journal another account can reach
 * is refused. Removing a task's hold deletes its journal without reading it.
 *
 * <p>An item's digest is the SHA-256, in lowercase hex, of its canonical form: its fields in a
 * fixed order, each written as its length in UTF-8 bytes in decimal, a colon, its UTF-8 bytes and a
 * line feed. The fields are:
 *
 * <ul>
 *   <li>a comment: {@code comment}, the anchor's path, its side ({@code old} or {@code new}), its
 *       first and its last line in decimal, the text, and the suggestion's replacement only when it
 *       has one;
 *   <li>a reply in one of Porelo's threads: {@code reply}, the thread's id, the text;
 *   <li>a reply in a forge's thread: {@code forge reply}, the forge's thread id, the text.
 * </ul>
 *
 * <p>For example, a reply {@code ok} in thread {@code t-9} is the SHA-256 of {@code
 * "5:reply\n3:t-9\n2:ok\n"}.
 */
public final class HoldJournal implements ReviewHolds {

    /** The format version this class writes and the highest it reads. */
    static final int VERSION = 1;

    /** The most bytes one task's journal holds. */
    static final long MAX_BYTES = 16L * 1024 * 1024;

    private static final String JOURNAL = "holds";

    // A task is named by its container name, porelo-<project>_<task>.
    private static final Pattern TASK = Pattern.compile(Task.NAME);

    private static final HexFormat HEX = HexFormat.of();

    private final Path root;

    private final Clock clock;

    /**
     * Constructor with the store's directory, each item's time read from the system's clock. It and
     * its missing parents are made on the first write.
     *
     * @param root The directory that holds one directory per task.
     */
    public HoldJournal(final Path root) {
        this(root, Clock.systemUTC());
    }

    /**
     * Constructor with the store's directory and the clock each item's time is read from.
     *
     * @param root The directory that holds one directory per task.
     * @param clock What the time an item is held is read from.
     */
    public HoldJournal(final Path root, final Clock clock) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the digest of an item's exact content, as a decision on it must name.
     *
     * @param item The item.
     * @return The SHA-256 of its canonical form, in lowercase hex.
     */
    public static String digest(final ReviewItem item) {
        Objects.requireNonNull(item, "item");
        final List<String> fields = new ArrayList<>();
        switch (item) {
            case ReviewComment comment -> {
                fields.add("comment");
                switch (comment.anchor()) {
                    case LineAnchor line -> {
                        fields.add(line.path());
                        fields.add(line.side().word());
                        fields.add(Integer.toString(line.startLine()));
                        fields.add(Integer.toString(line.endLine()));
                    }
                }
                fields.add(comment.body().text());
                final Suggestion suggestion = comment.suggestion();
                if (suggestion != null) {
                    fields.add(suggestion.replacement());
                }
            }
            case ReviewReply reply -> {
                fields.add("reply");
                fields.add(reply.thread());
                fields.add(reply.body().text());
            }
            case ForgeReply reply -> {
                fields.add("forge reply");
                fields.add(reply.thread());
                fields.add(reply.body().text());
            }
        }
        final ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        for (final String field : fields) {
            final byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            canonical.writeBytes((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
            canonical.writeBytes(bytes);
            canonical.write('\n');
        }
        try {
            return HEX.formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java platform has SHA-256", ex);
        }
    }

    @Override
    public int startRun(final String task, final String place) {
        named(task);
        Objects.requireNonNull(place, "place");
        if (place.isBlank()) {
            throw new IllegalArgumentException("a run names its place");
        }
        return locked(
                        task,
                        true,
                        (container, journal) -> {
                            final int next = journal.runs().getOrDefault(place, 0) + 1;
                            append(container, journal, runEntry(place, next));
                            return next;
                        })
                .orElseThrow();
    }

    /**
     * {@inheritDoc}
     *
     * <p>An item of a run its task has already started again at the place is held lapsed, never
     * undecided. An incoming item's producer is the forge account that wrote it.
     *
     * @throws IllegalStateException When the run's number is above the last one its task started at
     *     the place.
     */
    @Override
    public Held hold(
            final Run run,
            final String task,
            final Direction direction,
            final ReviewItem item,
            final @Nullable Kept received,
            final @Nullable String rule) {
        return hold(run, task, direction, item, received, rule, false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The mark is journaled with the item and read back with it, never part of its digest.
     */
    @Override
    public Held hold(
            final Run run,
            final String task,
            final Direction direction,
            final ReviewItem item,
            final @Nullable Kept received,
            final @Nullable String rule,
            final boolean unsigned) {
        Objects.requireNonNull(run, "run");
        named(task);
        if (received != null && !(received.origin().producer() instanceof ForgeAccount)) {
            throw new IllegalArgumentException("an incoming item's producer is a forge account");
        }
        final Held held =
                new Held(
                        UUID.randomUUID().toString(),
                        direction,
                        task,
                        run.number(),
                        run.place(),
                        run.push().head(),
                        item,
                        received,
                        digest(item),
                        clock.instant(),
                        HeldState.UNDECIDED,
                        rule,
                        null,
                        unsigned);
        return locked(
                        task,
                        true,
                        (container, journal) -> {
                            if (journal.ahead(held)) {
                                throw new IllegalStateException(
                                        "run "
                                                + held.run()
                                                + " of "
                                                + task
                                                + " was not started at "
                                                + held.place());
                            }
                            append(container, journal, holdEntry(held));
                            return journal.lapses(held)
                                    ? held.in(HeldState.LAPSED, held.reason(), null)
                                    : held;
                        })
                .orElseThrow();
    }

    @Override
    public List<Held> undecided(final @Nullable String task) {
        return every(task, held -> held.state() == HeldState.UNDECIDED);
    }

    /**
     * {@inheritDoc}
     *
     * <p>An entry of the store that is no task's name, or whose journal is refused in any way, is
     * named and passed; a store that cannot be listed at all still refuses.
     */
    @Override
    public Undecided everyUndecided() {
        final List<Held> found = new ArrayList<>();
        final List<String> unreadable = new ArrayList<>();
        for (final String each : entries()) {
            if (!TASK.matcher(each).matches()) {
                unreadable.add(each);
                continue;
            }
            try {
                locked(
                                each,
                                false,
                                (container, journal) ->
                                        journal.items().values().stream()
                                                .filter(held -> held.state() == HeldState.UNDECIDED)
                                                .toList())
                        .ifPresent(found::addAll);
            } catch (UncheckedIOException | IllegalStateException | IllegalArgumentException ex) {
                unreadable.add(each);
            }
        }
        found.sort(OLDEST_FIRST);
        return new Undecided(found, unreadable);
    }

    @Override
    public Optional<Held> find(final String task, final String id) {
        named(task);
        Objects.requireNonNull(id, "id");
        return locked(task, false, (container, journal) -> Optional.ofNullable(journal.get(id)))
                .flatMap(found -> found);
    }

    @Override
    public Held decide(
            final String task, final String id, final String digest, final Decision decision)
            throws HoldRefused {
        named(task);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(decision, "decision");
        final Outcome outcome =
                locked(
                                task,
                                false,
                                (container, journal) -> {
                                    final Held now = journal.get(id);
                                    if (now == null) {
                                        return Outcome.refused(HoldRefused.Reason.NO_SUCH_ITEM);
                                    }
                                    if (!decision.fits(now.direction())) {
                                        throw new IllegalArgumentException(
                                                decision
                                                        + " is no decision on an item going "
                                                        + now.direction());
                                    }
                                    if (now.state() == HeldState.LAPSED) {
                                        return Outcome.refused(HoldRefused.Reason.LAPSED);
                                    }
                                    if (now.state() != HeldState.UNDECIDED) {
                                        return Outcome.refused(HoldRefused.Reason.ALREADY_DECIDED);
                                    }
                                    if (!now.digest().equals(digest)) {
                                        return Outcome.refused(HoldRefused.Reason.DIGEST_CHANGED);
                                    }
                                    append(container, journal, decideEntry(id, decision));
                                    return new Outcome(
                                            now.in(decision.state(), now.reason(), null), null);
                                })
                        .orElseGet(() -> Outcome.refused(HoldRefused.Reason.NO_SUCH_ITEM));
        final Held decided = outcome.held();
        if (decided == null) {
            throw new HoldRefused(Objects.requireNonNull(outcome.reason(), "reason"), id);
        }
        return decided;
    }

    @Override
    public Optional<Held> incoming(final String task, final String place, final String received) {
        named(task);
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(received, "received");
        return locked(
                        task,
                        false,
                        (container, journal) -> {
                            Held newest = null;
                            for (final Held held : journal.items().values()) {
                                final Kept given = held.received();
                                if (given != null
                                        && held.place().equals(place)
                                        && given.guid().equals(received)) {
                                    newest = held;
                                }
                            }
                            return Optional.ofNullable(newest);
                        })
                .flatMap(found -> found);
    }

    @Override
    public List<Held> due(final Instant now) {
        Objects.requireNonNull(now, "now");
        return every(null, held -> due(held, now));
    }

    /**
     * {@inheritDoc}
     *
     * <p>A retry is due when its time, read from this journal's clock, has passed.
     */
    @Override
    public Held sending(final String task, final String id) {
        named(task);
        Objects.requireNonNull(id, "id");
        final Instant now = clock.instant();
        return locked(
                        task,
                        false,
                        (container, journal) -> {
                            final Held held = journal.get(id);
                            if (held == null) {
                                throw new IllegalStateException("no held item " + id);
                            }
                            if (!due(held, now)) {
                                throw new IllegalStateException(
                                        "the held item " + id + " is not due: " + held.state());
                            }
                            append(container, journal, sendingEntry(id));
                            return held.in(HeldState.SENDING, null, null);
                        })
                .orElseThrow(() -> new IllegalStateException("no held item " + id));
    }

    @Override
    public void mark(
            final String task, final String id, final WriteOutcome outcome, final Instant now) {
        named(task);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(now, "now");
        locked(
                        task,
                        false,
                        (container, journal) -> {
                            final Held held = journal.get(id);
                            if (held == null) {
                                throw new IllegalStateException("no held item " + id);
                            }
                            if (held.state() != HeldState.SENDING
                                    && held.state() != HeldState.RELEASED
                                    && held.state() != HeldState.RETRY) {
                                throw new IllegalStateException(
                                        "the held item " + id + " is " + held.state());
                            }
                            final Held marked =
                                    switch (outcome) {
                                        case WriteOutcome.Delivered delivered ->
                                                held.in(HeldState.DELIVERED, null, null);
                                        case WriteOutcome.Refused refused ->
                                                held.in(
                                                        HeldState.NOT_DELIVERED,
                                                        refused.reason(),
                                                        null);
                                        case WriteOutcome.RateLimited limited ->
                                                held.in(
                                                        HeldState.RETRY,
                                                        null,
                                                        upToSecond(now.plus(limited.retryAfter())));
                                        case WriteOutcome.OutcomeUnknown unknown ->
                                                held.in(HeldState.OUTCOME_UNKNOWN, null, null);
                                    };
                            if (!marks(held.state(), marked.state())) {
                                throw new IllegalStateException(
                                        "the held item "
                                                + id
                                                + " is "
                                                + held.state()
                                                + " and takes no "
                                                + marked.state());
                            }
                            append(container, journal, markEntry(marked, outcome));
                            return marked;
                        })
                .orElseThrow(() -> new IllegalStateException("no held item " + id));
    }

    @Override
    public int count(final String task) {
        return undecided(Objects.requireNonNull(task, "task")).size();
    }

    /**
     * Drops a task's hold whole, every item in every state, as when the task is removed. A task
     * started later under its name holds nothing of it.
     *
     * @param task The task's name.
     */
    public void remove(final String task) {
        named(task);
        try {
            final Optional<Path> found = ReviewComments.container(root, task, false);
            if (found.isEmpty()) {
                return;
            }
            final Path container = found.get();
            // Never read: a journal that cannot be read is removed all the same.
            ReviewComments.locked(
                    container,
                    () -> {
                        Files.deleteIfExists(container.resolve(JOURNAL));
                        try (FileChannel directory =
                                FileChannel.open(container, READ, NOFOLLOW_LINKS)) {
                            directory.force(true);
                        }
                        return Boolean.TRUE;
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex.getMessage(), ex);
        } catch (ReviewRefused ex) {
            throw new IllegalStateException("the hold journal refuses nothing", ex);
        }
    }

    // Every item wanted, of one task or of every task, oldest first; a tie in time keeps the
    // tasks in name order and each task's items in journal order.
    private List<Held> every(final @Nullable String task, final Predicate<Held> wanted) {
        final List<String> tasks;
        if (task != null) {
            named(task);
            tasks = List.of(task);
        } else {
            tasks = tasks();
        }
        final List<Held> found = new ArrayList<>();
        for (final String each : tasks) {
            locked(
                            each,
                            false,
                            (container, journal) ->
                                    journal.items().values().stream().filter(wanted).toList())
                    .ifPresent(found::addAll);
        }
        found.sort(OLDEST_FIRST);
        return List.copyOf(found);
    }

    // Oldest first, a tie by task; the sort is stable, so each task's items keep the order they
    // were held in, which a random id would not: a new item never moves one listed before it.
    private static final Comparator<Held> OLDEST_FIRST =
            Comparator.comparing(Held::at).thenComparing(Held::task);

    // Every entry of the store, by name, task's or not; none while there is no store.
    private List<String> entries() {
        try {
            if (!ReviewComments.directory(root, false, false)) {
                return List.of();
            }
            final Set<String> names = new TreeSet<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root.toRealPath())) {
                for (final Path entry : entries) {
                    names.add(String.valueOf(entry.getFileName()));
                }
            }
            return List.copyOf(names);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex.getMessage(), ex);
        }
    }

    // The tasks that have a directory in the store, by name; anything else in it refuses the store.
    private List<String> tasks() {
        try {
            if (!ReviewComments.directory(root, false, false)) {
                return List.of();
            }
            final Set<String> names = new TreeSet<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root.toRealPath())) {
                for (final Path entry : entries) {
                    final String name = String.valueOf(entry.getFileName());
                    if (!TASK.matcher(name).matches()) {
                        throw new IOException("The hold store holds what is no task's: " + entry);
                    }
                    names.add(name);
                }
            }
            return List.copyOf(names);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex.getMessage(), ex);
        }
    }

    private static void named(final String task) {
        Objects.requireNonNull(task, "task");
        if (!TASK.matcher(task).matches()) {
            throw new IllegalArgumentException("not a task name");
        }
    }

    // Runs a step on a task's journal under the store's lock; empty when the task has no directory
    // and none is to be made.
    private <T> Optional<T> locked(final String task, final boolean make, final Step<T> step) {
        try {
            final Optional<Path> found = ReviewComments.container(root, task, make);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            final Path container = found.get();
            return Optional.of(
                    ReviewComments.locked(
                            container, () -> step.run(container, read(container, task))));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex.getMessage(), ex);
        } catch (ReviewRefused ex) {
            throw new IllegalStateException("the hold journal refuses nothing", ex);
        }
    }

    private static Journal read(final Path container, final String task) throws IOException {
        final Path path = container.resolve(JOURNAL);
        if (!ReviewComments.regularOrAbsent(path)) {
            return Journal.empty();
        }
        final Set<PosixFilePermission> mode =
                Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS).permissions();
        if (!ReviewComments.FILE_MODE.containsAll(mode)) {
            throw new IOException(
                    "The hold journal refuses a file another account can reach ("
                            + PosixFilePermissions.toString(mode)
                            + "): "
                            + path);
        }
        final byte[] bytes;
        try (FileChannel channel = FileChannel.open(path, Set.of(READ, NOFOLLOW_LINKS))) {
            final long size = channel.size();
            if (size > MAX_BYTES) {
                throw new IOException("The hold journal is over its limit: " + path);
            }
            final ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read until full or at the end.
            }
            if (buffer.hasRemaining()) {
                throw new IOException("The hold journal changed while read: " + path);
            }
            bytes = buffer.array();
        } catch (NoSuchFileException ex) {
            return Journal.empty();
        }
        // A line feed never occurs inside a UTF-8 character, so the cut is between characters.
        int valid = bytes.length;
        while (valid > 0 && bytes[valid - 1] != '\n') {
            valid--;
        }
        final String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes, 0, valid))
                            .toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("The hold journal is not UTF-8: " + path, ex);
        }
        final Journal journal = new Journal(valid, new LinkedHashMap<>(), new HashMap<>());
        if (text.isEmpty()) {
            return journal;
        }
        final String[] lines = text.split("\n", -1);
        // The text ends in a line feed, so the last part is empty.
        for (int i = 0; i < lines.length - 1; i++) {
            try {
                final Map<?, ?> entry = ReviewComments.object(Json.parse(lines[i]), "entry");
                if (i == 0) {
                    header(entry, task);
                } else {
                    journal.replay(entry, task);
                }
            } catch (JsonException | IllegalArgumentException | NullPointerException ex) {
                throw new IOException(
                        "The hold journal's entry "
                                + (i + 1)
                                + " cannot be read ("
                                + ex.getClass().getSimpleName()
                                + ": "
                                + ex.getMessage()
                                + "): "
                                + path,
                        ex);
            }
        }
        return journal;
    }

    private static void header(final Map<?, ?> entry, final String task) {
        final int version = ReviewComments.number(entry, "v");
        if (version != VERSION) {
            throw new IllegalArgumentException("format version " + version + " is not read here");
        }
        if (!"holds".equals(ReviewComments.text(entry, "entry"))) {
            throw new IllegalArgumentException("the first entry is no header");
        }
        if (!task.equals(ReviewComments.text(entry, "task"))) {
            throw new IllegalArgumentException("the journal is another task's");
        }
    }

    // Appends one entry, after the header on a new journal, in place of a torn last line.
    private static void append(final Path container, final Journal journal, final String line)
            throws IOException {
        final Path path = container.resolve(JOURNAL);
        final StringBuilder text = new StringBuilder();
        if (journal.valid() == 0) {
            text.append(headerEntry(String.valueOf(container.getFileName()))).append('\n');
        }
        text.append(line).append('\n');
        final byte[] added = text.toString().getBytes(StandardCharsets.UTF_8);
        if (journal.valid() + added.length > MAX_BYTES) {
            throw new IllegalStateException("The hold journal is full: " + path);
        }
        final boolean fresh = !ReviewComments.regularOrAbsent(path);
        try (FileChannel channel =
                FileChannel.open(
                        path,
                        Set.of(CREATE, WRITE, NOFOLLOW_LINKS),
                        ReviewComments.FILE_ATTRIBUTE)) {
            if (fresh) {
                ReviewComments.mode(path, ReviewComments.FILE_MODE);
            }
            channel.truncate(journal.valid());
            final ByteBuffer buffer = ByteBuffer.wrap(added);
            long position = journal.valid();
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }
            channel.force(true);
        }
        if (fresh) {
            try (FileChannel directory = FileChannel.open(container, READ, NOFOLLOW_LINKS)) {
                directory.force(true);
            }
        }
    }

    // Writing ------------------------------------------------------------------------------------

    private static String headerEntry(final String task) {
        final Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("v", VERSION);
        entry.put("entry", "holds");
        entry.put("task", task);
        return Json.write(entry);
    }

    private static String runEntry(final String place, final int number) {
        final Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("entry", "run");
        entry.put("place", place);
        entry.put("number", number);
        return Json.write(entry);
    }

    private static String holdEntry(final Held held) {
        final Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("entry", "hold");
        entry.put("id", held.id());
        entry.put("direction", word(held.direction()));
        entry.put("run", held.run());
        entry.put("place", held.place());
        entry.put("commit", held.commit());
        entry.put("digest", held.digest());
        entry.put("at", held.at().toString());
        entry.put("item", item(held.item()));
        final Kept received = held.received();
        entry.put("received", received == null ? null : received(received));
        entry.put("rule", held.reason());
        // Only when set, so a signed item's entry reads as it always did.
        if (held.unsigned()) {
            entry.put("unsigned", true);
        }
        return Json.write(entry);
    }

    private static String decideEntry(final String id, final Decision decision) {
        final Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("entry", "decide");
        entry.put("id", id);
        entry.put("decision", word(decision));
        return Json.write(entry);
    }

    private static String sendingEntry(final String id) {
        final Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("entry", "sending");
        entry.put("id", id);
        return Json.write(entry);
    }

    // The thread a delivered write landed in is kept for the record; no state reads it.
    private static String markEntry(final Held marked, final WriteOutcome outcome) {
        final Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("entry", "mark");
        entry.put("id", marked.id());
        entry.put("state", word(marked.state()));
        entry.put("reason", marked.reason());
        final Instant retryAt = marked.retryAt();
        entry.put("retryAt", retryAt == null ? null : retryAt.toString());
        entry.put(
                "thread",
                outcome instanceof WriteOutcome.Delivered delivered ? delivered.thread() : null);
        return Json.write(entry);
    }

    private static Map<String, @Nullable Object> received(final Kept received) {
        if (!(received.origin().producer() instanceof ForgeAccount account)) {
            throw new IllegalArgumentException("an incoming item's producer is a forge account");
        }
        final Map<String, @Nullable Object> written = new LinkedHashMap<>();
        written.put("guid", received.guid());
        written.put("thread", received.thread());
        written.put(
                "producer",
                Map.of("forge", account.forge(), "id", account.id(), "name", account.name()));
        final Instant at = received.at();
        written.put("at", at == null ? null : at.toString());
        return written;
    }

    private static Map<String, @Nullable Object> item(final ReviewItem item) {
        final Map<String, @Nullable Object> written = new LinkedHashMap<>();
        switch (item) {
            case ReviewComment comment -> {
                written.put("kind", "comment");
                written.put("anchor", ReviewComments.anchor(comment.anchor()));
                written.put("body", comment.body().text());
                final Suggestion suggestion = comment.suggestion();
                written.put("suggestion", suggestion == null ? null : suggestion.replacement());
            }
            case ReviewReply reply -> {
                written.put("kind", "reply");
                written.put("thread", reply.thread());
                written.put("body", reply.body().text());
            }
            case ForgeReply reply -> {
                written.put("kind", "forge reply");
                written.put("thread", reply.thread());
                written.put("body", reply.body().text());
            }
        }
        return written;
    }

    private static String word(final Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    // Reading ------------------------------------------------------------------------------------

    private static <E extends Enum<E>> E named(final Class<E> type, final String word) {
        for (final E value : type.getEnumConstants()) {
            if (word(value).equals(word)) {
                return value;
            }
        }
        throw new IllegalArgumentException("an unknown " + type.getSimpleName());
    }

    private static Held held(final Map<?, ?> entry, final String task) {
        final Instant at = time(ReviewComments.text(entry, "at"));
        final ReviewItem item = item(ReviewComments.object(entry.get("item"), "item"));
        final String digest = ReviewComments.text(entry, "digest");
        if (!digest.equals(digest(item))) {
            throw new IllegalArgumentException("an item's digest is not its content's");
        }
        return new Held(
                ReviewComments.text(entry, "id"),
                named(Direction.class, ReviewComments.text(entry, "direction")),
                task,
                ReviewComments.number(entry, "run"),
                ReviewComments.text(entry, "place"),
                ReviewComments.text(entry, "commit"),
                item,
                entry.containsKey("received") && entry.get("received") == null
                        ? null
                        : received(ReviewComments.object(entry.get("received"), "received"), item),
                digest,
                at,
                HeldState.UNDECIDED,
                ReviewComments.optional(entry, "rule"),
                null,
                unsigned(entry));
    }

    // Absent or true; anything else is no entry this class wrote.
    private static boolean unsigned(final Map<?, ?> entry) {
        if (!entry.containsKey("unsigned")) {
            return false;
        }
        if (!Boolean.TRUE.equals(entry.get("unsigned"))) {
            throw new IllegalArgumentException("an unsigned mark that is not true");
        }
        return true;
    }

    private static Kept received(final Map<?, ?> received, final ReviewItem item) {
        final Map<?, ?> producer = ReviewComments.object(received.get("producer"), "producer");
        final String at = ReviewComments.optional(received, "at");
        return new Kept(
                ReviewComments.text(received, "guid"),
                ReviewComments.text(received, "thread"),
                Origin.of(
                        new ForgeAccount(
                                ReviewComments.text(producer, "forge"),
                                ReviewComments.text(producer, "id"),
                                ReviewComments.text(producer, "name"))),
                item,
                at == null ? null : time(at));
    }

    private static Instant time(final String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("a time that is none", ex);
        }
    }

    // Whether an item may be handed to its place's write now.
    private static boolean due(final Held held, final Instant now) {
        final Instant retryAt = held.retryAt();
        return held.state() == HeldState.RELEASED
                || held.state() == HeldState.RETRY && retryAt != null && !retryAt.isAfter(now);
    }

    // Whether an item in one state takes an answer: one being sent any, one never handed to its
    // place's write only a refusal, as when its place or credential is missing.
    private static boolean marks(final HeldState from, final HeldState to) {
        return switch (from) {
            case SENDING ->
                    Set.of(
                                    HeldState.DELIVERED,
                                    HeldState.NOT_DELIVERED,
                                    HeldState.RETRY,
                                    HeldState.OUTCOME_UNKNOWN)
                            .contains(to);
            case RELEASED, RETRY -> to == HeldState.NOT_DELIVERED;
            default -> false;
        };
    }

    // A wait is never cut short: a fraction of a second rounds up to the next whole one.
    private static Instant upToSecond(final Instant at) {
        final Instant whole = at.truncatedTo(ChronoUnit.SECONDS);
        return whole.equals(at) ? at : whole.plusSeconds(1);
    }

    private static ReviewItem item(final Map<?, ?> item) {
        final Body body = new Body(ReviewComments.text(item, "body"));
        return switch (ReviewComments.text(item, "kind")) {
            case "comment" -> {
                final String suggestion = ReviewComments.optional(item, "suggestion");
                yield new ReviewComment(
                        ReviewComments.anchor(ReviewComments.object(item.get("anchor"), "anchor")),
                        body,
                        suggestion == null ? null : new Suggestion(suggestion));
            }
            case "reply" -> new ReviewReply(ReviewComments.text(item, "thread"), body);
            case "forge reply" -> new ForgeReply(ReviewComments.text(item, "thread"), body);
            default -> throw new IllegalArgumentException("an unknown item");
        };
    }

    // The model ----------------------------------------------------------------------------------

    /** What runs on a task's journal under the lock. */
    @FunctionalInterface
    private interface Step<T> {

        T run(Path container, Journal journal) throws IOException;
    }

    /** A decision taken, or why not. */
    private record Outcome(@Nullable Held held, HoldRefused.@Nullable Reason reason) {

        static Outcome refused(final HoldRefused.Reason reason) {
            return new Outcome(null, reason);
        }
    }

    /**
     * A task's journal as replayed: how many of its bytes end in a whole line, its items by id in
     * the order held, each in its state, and the last run's number at each place.
     */
    private record Journal(long valid, Map<String, Held> items, Map<String, Integer> runs) {

        static Journal empty() {
            return new Journal(0, new LinkedHashMap<>(), new HashMap<>());
        }

        @Nullable Held get(final String id) {
            return items.get(id);
        }

        /** Whether the item's run is above the last its task started at its place. */
        boolean ahead(final Held held) {
            return held.run() > runs.getOrDefault(held.place(), 0);
        }

        /** Whether a later run of the item's task has started at its place. */
        boolean lapses(final Held held) {
            return runs.getOrDefault(held.place(), 0) > held.run();
        }

        void replay(final Map<?, ?> entry, final String task) {
            switch (ReviewComments.text(entry, "entry")) {
                case "run" -> {
                    final String place = ReviewComments.text(entry, "place");
                    if (place.isBlank()) {
                        throw new IllegalArgumentException("a run names no place");
                    }
                    final int number = ReviewComments.number(entry, "number");
                    if (number != runs.getOrDefault(place, 0) + 1) {
                        throw new IllegalArgumentException("a run out of its order");
                    }
                    runs.put(place, number);
                    items.replaceAll(
                            (id, held) ->
                                    held.state() == HeldState.UNDECIDED && lapses(held)
                                            ? held.in(HeldState.LAPSED, held.reason(), null)
                                            : held);
                }
                case "hold" -> {
                    final Held held = held(entry, task);
                    if (items.containsKey(held.id())) {
                        throw new IllegalArgumentException("an item held twice");
                    }
                    if (ahead(held)) {
                        throw new IllegalArgumentException("an item of a run not started");
                    }
                    items.put(
                            held.id(),
                            lapses(held) ? held.in(HeldState.LAPSED, held.reason(), null) : held);
                }
                case "decide" -> {
                    final Held now = existing(entry);
                    final Decision decision =
                            named(Decision.class, ReviewComments.text(entry, "decision"));
                    if (now.state() != HeldState.UNDECIDED || !decision.fits(now.direction())) {
                        throw new IllegalArgumentException("a decision that could not be taken");
                    }
                    items.put(now.id(), now.in(decision.state(), now.reason(), null));
                }
                case "mark" -> {
                    final Held now = existing(entry);
                    final HeldState state =
                            named(HeldState.class, ReviewComments.text(entry, "state"));
                    final String reason = ReviewComments.optional(entry, "reason");
                    final String retryAt = ReviewComments.optional(entry, "retryAt");
                    if (!marks(now.state(), state)
                            || (state == HeldState.NOT_DELIVERED) != (reason != null)) {
                        throw new IllegalArgumentException("a mark that could not be made");
                    }
                    items.put(
                            now.id(),
                            now.in(state, reason, retryAt == null ? null : time(retryAt)));
                }
                case "sending" -> {
                    final Held now = existing(entry);
                    // The time a retry was due is not journaled; its retry state is enough.
                    if (now.state() != HeldState.RELEASED && now.state() != HeldState.RETRY) {
                        throw new IllegalArgumentException("an item sent that was not due");
                    }
                    items.put(now.id(), now.in(HeldState.SENDING, null, null));
                }
                default -> throw new IllegalArgumentException("an unknown entry");
            }
        }

        private Held existing(final Map<?, ?> entry) {
            final Held now = items.get(ReviewComments.text(entry, "id"));
            if (now == null) {
                throw new IllegalArgumentException("an entry names no held item");
            }
            return now;
        }
    }
}
