package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.porelo.gate.GateMode;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * A task's parts and the place its run works at, as its start took them and its task keeps them.
 *
 * <p>Only a task with a gate has a place: a task that works without a gate, or only reads, has no
 * push to work at, and its record holds its parts alone ({@link #recordParts}).
 *
 * @param parts The parts its start recorded, fixed for the task's life.
 * @param repository The push's repository, or {@code null} for the project's own.
 * @param name The push's name, which is its task's name within the project.
 * @param pinned The state a run that reads another task's work is pinned to, or {@code null} while
 *     none is.
 * @param pullRequest The pull request the run works at, its https address, or {@code null} for a
 *     gate push; with one, {@code name} is the task's own and {@code repository} is {@code null}.
 */
public record RunPlace(
        TaskParts parts,
        @Nullable String repository,
        String name,
        @Nullable Pin pinned,
        @Nullable String pullRequest) {

    /**
     * Parts and a place, pinned or not, at a gate push.
     *
     * @param parts The task's parts.
     * @param repository The push's repository, or {@code null} for the project's own.
     * @param name The push's name.
     * @param pinned The pinned state, or {@code null}.
     */
    public RunPlace(
            final TaskParts parts,
            final @Nullable String repository,
            final String name,
            final @Nullable Pin pinned) {
        this(parts, repository, name, pinned, null);
    }

    /**
     * A run at a pull request, its address checked as {@link PullRequestRef} takes one.
     *
     * @param parts The task's parts.
     * @param task The task's own name.
     * @param url The pull request's https address.
     * @return The place.
     */
    public static RunPlace atPullRequest(
            final TaskParts parts, final String task, final String url) {
        return new RunPlace(parts, null, task, null, url);
    }

    /**
     * The state a run that reads another task's work reads: the ref {@code
     * refs/porelo/pinned/<task>} in its task's mirror points at the head.
     *
     * @param head The commit reviewed, in full.
     * @param base The commit it is compared with, in full, or {@code null} when none is named.
     * @param repository The pull request's repository, its https address, or {@code null} for a
     *     gate push.
     * @param pushedBy The recorded container id of the task whose push waits under the name, for a
     *     gate push: the run reads that task's work, and another task's push that waits under the
     *     same name later is none of it. {@code null} for a pull request.
     */
    public record Pin(
            String head,
            @Nullable String base,
            @Nullable String repository,
            @Nullable String pushedBy) {

        private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

        /** What a recorded container id is. */
        private static final Pattern CONTAINER = Pattern.compile("[0-9a-f]{12,64}");

        /**
         * A pin naming no pushing task: a pull request's.
         *
         * @param head The commit reviewed, in full.
         * @param base The commit it is compared with, in full, or {@code null}.
         * @param repository The pull request's repository, or {@code null}.
         */
        public Pin(
                final String head, final @Nullable String base, final @Nullable String repository) {
            this(head, base, repository, null);
        }

        /**
         * Refuses a commit not written in full, a repository not on https, a pushing task that is
         * no recorded container id, and one named for a pull request.
         */
        public Pin {
            if (!COMMIT.matcher(head).matches()
                    || (base != null && !COMMIT.matcher(base).matches())) {
                throw new IllegalArgumentException("a pin names full commit ids");
            }
            if (repository != null
                    && (!repository.startsWith("https://")
                            || !repository.chars().allMatch(c -> c > 0x20 && c < 0x7f))) {
                throw new IllegalArgumentException("a pinned repository is an https address");
            }
            if (pushedBy != null
                    && (repository != null || !CONTAINER.matcher(pushedBy).matches())) {
                throw new IllegalArgumentException(
                        "a pin names the pushing task of a gate push by its recorded id");
            }
        }
    }

    /**
     * Parts and a place pinned to nothing yet.
     *
     * @param parts The task's parts.
     * @param repository The push's repository, or {@code null} for the project's own.
     * @param name The push's name.
     */
    public RunPlace(final TaskParts parts, final @Nullable String repository, final String name) {
        this(parts, repository, name, null);
    }

    /**
     * Returns these parts and place pinned to a state.
     *
     * @param pin The state.
     * @return A copy.
     */
    public RunPlace pinnedTo(final Pin pin) {
        return new RunPlace(parts, repository, name, pin, pullRequest);
    }

    /**
     * Whether a run begins at this place once the task is made, before its agent runs: at a pull
     * request, and at another task's push waiting at the gate, which a task that reads another
     * task's work is pinned to. A task at its own gate push begins none.
     *
     * @return True where a run is pinned and given its conversation.
     */
    public boolean beginsRun() {
        return pullRequest != null || parts.readsAnothersWork();
    }

    /** The file in a task's state that holds its parts and its run's place. */
    static final String FILE = "run-place";

    /**
     * What a push's name or repository may be: what a task's name and a repository's key are, and
     * nothing that could reach outside the mirrors or read as an option.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");

    /**
     * Refuses a name no start gives.
     *
     * @throws IllegalArgumentException For a name or repository of another shape.
     */
    public RunPlace {
        java.util.Objects.requireNonNull(parts, "parts");
        if (!named(name) || (repository != null && !named(repository))) {
            throw new IllegalArgumentException("no push's name: " + repository + " " + name);
        }
        if (pullRequest != null) {
            if (repository != null) {
                throw new IllegalArgumentException("a pull request's place names no repository");
            }
            // Of the one shape a pull request's address has; refused otherwise.
            PullRequestRef.of(java.net.URI.create(pullRequest));
        }
    }

    private static boolean named(final String name) {
        return NAME.matcher(name).matches() && !name.contains("..");
    }

    /**
     * A run at its own task's gate push, with the parts a start naming none gets.
     *
     * @param project The task's project.
     * @param repository The task's repository, or {@code null} for the project's own.
     * @param task The task's name within the project.
     * @return The place.
     */
    public static RunPlace ownPush(
            final String project, final @Nullable String repository, final String task) {
        return new RunPlace(TaskParts.ownWork(), otherThanOwn(project, repository), task);
    }

    /**
     * Returns a repository as a gate push names it: {@code null} for the project's own.
     *
     * @param project The project.
     * @param repository A repository of it, or {@code null} or {@code ""} for its own.
     * @return The repository, or {@code null}.
     */
    public static @Nullable String otherThanOwn(
            final String project, final @Nullable String repository) {
        return repository == null || repository.isEmpty() || repository.equals(project)
                ? null
                : repository;
    }

    /**
     * The place as the contract's {@code Place} gives it: a gate push, its repository absent for
     * the project's own.
     *
     * @return The place.
     */
    public Map<String, Object> place() {
        if (pullRequest != null) {
            return Map.of("pullRequest", pullRequest);
        }
        final Map<String, Object> push = new LinkedHashMap<>();
        if (repository != null) {
            push.put("repository", repository);
        }
        push.put("name", name);
        return Map.of("gatePush", push);
    }

    /**
     * Writes the parts and place into a task's state, which keeps them through a stop and a reboot;
     * the record before it stays whole when the write fails.
     *
     * @param state The task's state directory.
     * @throws IOException If they cannot be written.
     */
    public void writeTo(final Path state) throws IOException {
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put(PARTS, parts.document());
        document.put("place", place());
        if (pullRequest != null) {
            // A pull request's place does not name the task, which the record still does.
            document.put("name", name);
        }
        if (pinned != null) {
            final Map<String, Object> pin = new LinkedHashMap<>();
            pin.put("head", pinned.head());
            if (pinned.base() != null) {
                pin.put("base", pinned.base());
            }
            if (pinned.repository() != null) {
                pin.put("repository", pinned.repository());
            }
            if (pinned.pushedBy() != null) {
                pin.put("pushedBy", pinned.pushedBy());
            }
            document.put("pinned", pin);
        }
        write(state, document);
    }

    /**
     * Records the parts of a task without a gate, which has no place, in its state.
     *
     * @param state The task's state directory.
     * @param parts Its parts.
     * @throws IOException If they cannot be written.
     */
    public static void recordParts(final Path state, final TaskParts parts) throws IOException {
        write(state, Map.<String, Object>of(PARTS, parts.document()));
    }

    /**
     * Reads the parts a task's start recorded, with a place or without one.
     *
     * @param state The task's state directory, or its durable record.
     * @return Them, or {@code null} when the directory holds no record.
     * @throws IllegalStateException When a record is there and its parts do not read: such a task
     *     is given no right, never a default's.
     */
    public static @Nullable TaskParts partsIn(final Path state) {
        final Path file = state.resolve(FILE);
        if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                            instanceof Map<?, ?> document
                    && document.get(PARTS) != null) {
                return TaskParts.of(document.get(PARTS));
            }
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException(
                    "the recorded parts in " + file + " do not read; the task is given no right",
                    ex);
        }
        throw new IllegalStateException(
                "the record " + file + " holds no parts; the task is given no right");
    }

    /** The record's member that holds the parts. */
    private static final String PARTS = "parts";

    /**
     * Reads the parts a task's start recorded: from its state, and from its durable record only
     * while its state holds none.
     *
     * @param context The machine.
     * @param container The task's container.
     * @return Them, or {@code null} when neither holds a record.
     * @throws IllegalStateException When a record is there and its parts do not read: such a task
     *     is given no right, never a default's.
     */
    public static @Nullable TaskParts recordedParts(
            final PoreloContext context, final String container) {
        final TaskParts inState = partsIn(context.paths().tasks().containerState(container));
        return inState != null ? inState : partsIn(context.paths().tasks().taskRecord(container));
    }

    /**
     * Reads the place a task's start recorded: from its state, and from its durable record only
     * while its state holds no record at all, so a record in the state that does not read is no
     * place rather than an earlier one.
     *
     * @param context The machine.
     * @param container The task's container.
     * @return It, or {@code null} for a task without a place or whose record does not read.
     */
    public static @Nullable RunPlace recordedPlace(
            final PoreloContext context, final String container) {
        final Path state = context.paths().tasks().containerState(container);
        return Files.exists(state.resolve(FILE), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                ? readFrom(state)
                : readFrom(context.paths().tasks().taskRecord(container));
    }

    private static void write(final Path state, final Map<String, Object> document)
            throws IOException {
        // Written beside it and moved over it: a write that does not finish leaves the old record.
        final Path written =
                Files.createTempFile(
                        state,
                        "." + FILE + "-",
                        ".new",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(written, Json.write(document), StandardCharsets.UTF_8);
            Files.move(
                    written,
                    state.resolve(FILE),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(written);
        }
    }

    /**
     * Records the parts and place of a task being made; a task without a gate records its parts
     * alone.
     *
     * @param context The machine.
     * @param container The task's container.
     * @param place Its parts and place, or {@code null} for a task without a gate.
     * @param parts The parts of a task without a gate; not read where a place is given.
     * @throws IOException If they cannot be written or removed.
     */
    public static void record(
            final PoreloContext context,
            final String container,
            final @Nullable RunPlace place,
            final TaskParts parts)
            throws IOException {
        final Path state = context.paths().tasks().containerState(container);
        // The saved copy is an earlier task's of the name, and the row reads it when the state
        // has none; the task's own is saved from the state when its record is.
        clear(context.paths().tasks().taskRecord(container));
        if (place == null) {
            recordParts(state, parts);
        } else {
            place.writeTo(state);
        }
    }

    /**
     * Removes what an earlier task of the same name left, for a start that records none.
     *
     * @param state The task's state directory.
     * @throws IOException If it cannot be removed.
     */
    public static void clear(final Path state) throws IOException {
        Files.deleteIfExists(state.resolve(FILE));
    }

    /**
     * Reads a task's parts and place from its state.
     *
     * @param state The task's state directory, or its durable record.
     * @return Them, or {@code null} when its start recorded no place or the file does not read.
     */
    public static @Nullable RunPlace readFrom(final Path state) {
        final Path file = state.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            final Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed instanceof Map<?, ?> document
                    && document.get(PARTS) != null
                    && document.get("place") instanceof Map<?, ?> place
                    && place.get("pullRequest") instanceof String url
                    && document.get("name") instanceof String task) {
                return new RunPlace(
                        TaskParts.of(document.get(PARTS)),
                        null,
                        task,
                        document.containsKey("pinned") ? pin(document.get("pinned")) : null,
                        url);
            }
            if (parsed instanceof Map<?, ?> document
                    && document.get(PARTS) != null
                    && document.get("place") instanceof Map<?, ?> place
                    && place.get("gatePush") instanceof Map<?, ?> push
                    && push.get("name") instanceof String name
                    && (push.get("repository") == null
                            || push.get("repository") instanceof String)) {
                // Refused by the constructor, and so never shown, when it is no start's.
                return new RunPlace(
                        TaskParts.of(document.get(PARTS)),
                        push.get("repository") instanceof String repository ? repository : null,
                        name,
                        document.containsKey("pinned") ? pin(document.get("pinned")) : null);
            }
        } catch (IOException | RuntimeException ex) {
            // A file that does not read says nothing of the run; the task is still listed.
        }
        return null;
    }

    /**
     * A pin that does not read throws, so the whole record reads as none: a gate push's that names
     * no pushing task among them, never taken as any task's.
     */
    private static Pin pin(final @Nullable Object kept) {
        if (kept instanceof Map<?, ?> pin
                && pin.get("head") instanceof String head
                && (pin.get("base") == null || pin.get("base") instanceof String)
                && (pin.get("repository") == null || pin.get("repository") instanceof String)
                && (pin.get("pushedBy") == null || pin.get("pushedBy") instanceof String)
                && (pin.get("repository") != null) != (pin.get("pushedBy") != null)) {
            return new Pin(
                    head,
                    pin.get("base") instanceof String base ? base : null,
                    pin.get("repository") instanceof String repository ? repository : null,
                    pin.get("pushedBy") instanceof String pushedBy ? pushedBy : null);
        }
        throw new IllegalArgumentException("a pin that does not read");
    }

    /**
     * Returns the head commit that waits at a gate push: for a start refused at another task's, and
     * for a task that reads another task's work, which is started only at a push that waits.
     *
     * @param context The machine.
     * @param project The project.
     * @param repository The push's repository, or {@code null} for the project's own; one of the
     *     project's, as the caller checked.
     * @param name The push's name.
     * @return The full commit, or {@code ""} when nothing waits under that name.
     */
    public static String waitingHead(
            final PoreloContext context,
            final String project,
            final @Nullable String repository,
            final String name) {
        // Names that could reach outside the mirrors, or read as an option, are never looked up.
        if (!named(name) || (repository != null && !named(repository))) {
            return "";
        }
        final Path mirror = GateSupport.mirror(context, project, repository);
        if (!Files.isDirectory(mirror)) {
            return "";
        }
        try {
            final GitGate.Waiting waiting =
                    new GitGate(context.runner(), mirror, GateMode.GATEKEEPING, null).waiting(name);
            return waiting == null ? "" : waiting.commit();
        } catch (RuntimeException ex) {
            // A mirror git cannot read holds no head anybody could name.
            return "";
        }
    }
}
