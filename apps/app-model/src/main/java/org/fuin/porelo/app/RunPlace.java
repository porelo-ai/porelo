package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.porelo.gate.GateMode;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * A run's role and the gate push it works at, as its start took them and its task keeps them.
 *
 * <p>Only a task with a gate has one: a task that works without a gate, or only reads, has no push
 * to work at.
 *
 * @param role {@link #AUTHOR} or {@link #REVIEWER}.
 * @param repository The push's repository, or {@code null} for the project's own.
 * @param name The push's name, which is its task's name within the project.
 * @param pinned The state a reviewer's run is pinned to, or {@code null} while none is.
 * @param pullRequest The pull request the run works at, its https address, or {@code null} for a
 *     gate push; with one, {@code name} is the task's own and {@code repository} is {@code null}.
 */
public record RunPlace(
        String role,
        @Nullable String repository,
        String name,
        @Nullable Pin pinned,
        @Nullable String pullRequest) {

    /**
     * A role and place, pinned or not, at a gate push.
     *
     * @param role {@link #AUTHOR} or {@link #REVIEWER}.
     * @param repository The push's repository, or {@code null} for the project's own.
     * @param name The push's name.
     * @param pinned The pinned state, or {@code null}.
     */
    public RunPlace(
            final String role,
            final @Nullable String repository,
            final String name,
            final @Nullable Pin pinned) {
        this(role, repository, name, pinned, null);
    }

    /**
     * A run at a pull request, its address checked as {@link PullRequestRef} takes one.
     *
     * @param role {@link #AUTHOR} or {@link #REVIEWER}.
     * @param task The task's own name.
     * @param url The pull request's https address.
     * @return The place.
     */
    public static RunPlace atPullRequest(final String role, final String task, final String url) {
        return new RunPlace(role, null, task, null, url);
    }

    /**
     * The state a reviewer's run reads: the ref {@code refs/porelo/pinned/<task>} in its task's
     * mirror points at the head.
     *
     * @param head The commit reviewed, in full.
     * @param base The commit it is compared with, in full, or {@code null} when none is named.
     * @param repository The pull request's repository, its https address, or {@code null} for a
     *     gate push.
     */
    public record Pin(String head, @Nullable String base, @Nullable String repository) {

        private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

        /** Refuses a commit not written in full and a repository not on https. */
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
        }
    }

    /**
     * A role and place pinned to nothing yet.
     *
     * @param role {@link #AUTHOR} or {@link #REVIEWER}.
     * @param repository The push's repository, or {@code null} for the project's own.
     * @param name The push's name.
     */
    public RunPlace(final String role, final @Nullable String repository, final String name) {
        this(role, repository, name, null);
    }

    /**
     * Returns this role and place pinned to a state.
     *
     * @param pin The state.
     * @return A copy.
     */
    public RunPlace pinnedTo(final Pin pin) {
        return new RunPlace(role, repository, name, pin, pullRequest);
    }

    /** The role of a run that makes the work at its place. */
    public static final String AUTHOR = "AUTHOR";

    /** The role of a run that reviews the work at its place. */
    public static final String REVIEWER = "REVIEWER";

    /** The file in a task's state that holds its run's role and place. */
    static final String FILE = "run-place";

    /**
     * What a push's name or repository may be: what a task's name and a repository's key are, and
     * nothing that could reach outside the mirrors or read as an option.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");

    /**
     * Refuses a role or a name no start gives.
     *
     * @throws IllegalArgumentException For a role other than {@link #AUTHOR} or {@link #REVIEWER},
     *     or a name or repository of another shape.
     */
    public RunPlace {
        if (!AUTHOR.equals(role) && !REVIEWER.equals(role)) {
            throw new IllegalArgumentException("no run's role: " + role);
        }
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
     * An author's run at its own task's gate push.
     *
     * @param project The task's project.
     * @param repository The task's repository, or {@code null} for the project's own.
     * @param task The task's name within the project.
     * @return The place.
     */
    public static RunPlace ownPush(
            final String project, final @Nullable String repository, final String task) {
        return new RunPlace(AUTHOR, otherThanOwn(project, repository), task);
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
     * Writes the role and place into a task's state, which keeps them through a stop and a reboot.
     *
     * @param state The task's state directory.
     * @throws IOException If they cannot be written.
     */
    public void writeTo(final Path state) throws IOException {
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("role", role);
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
            document.put("pinned", pin);
        }
        final Path file = state.resolve(FILE);
        Files.writeString(file, Json.write(document), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    /**
     * Records the role and place of a task being made, or that it has none.
     *
     * @param context The machine.
     * @param container The task's container.
     * @param place Its role and place, or {@code null} for a task without a gate.
     * @throws IOException If they cannot be written or removed.
     */
    public static void record(
            final PoreloContext context, final String container, final @Nullable RunPlace place)
            throws IOException {
        final Path state = context.paths().tasks().containerState(container);
        // The saved copy is an earlier task's of the name, and the row reads it when the state
        // has none; the task's own is saved from the state when its record is.
        clear(context.paths().tasks().taskRecord(container));
        if (place == null) {
            clear(state);
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
     * Reads a task's role and place from its state.
     *
     * @param state The task's state directory, or its durable record.
     * @return Them, or {@code null} when its start recorded none or the file does not read.
     */
    public static @Nullable RunPlace readFrom(final Path state) {
        final Path file = state.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            final Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed instanceof Map<?, ?> document
                    && document.get("role") instanceof String role
                    && document.get("place") instanceof Map<?, ?> place
                    && place.get("pullRequest") instanceof String url
                    && document.get("name") instanceof String task) {
                return new RunPlace(
                        role,
                        null,
                        task,
                        document.containsKey("pinned") ? pin(document.get("pinned")) : null,
                        url);
            }
            if (parsed instanceof Map<?, ?> document
                    && document.get("role") instanceof String role
                    && document.get("place") instanceof Map<?, ?> place
                    && place.get("gatePush") instanceof Map<?, ?> push
                    && push.get("name") instanceof String name
                    && (push.get("repository") == null
                            || push.get("repository") instanceof String)) {
                // Refused by the constructor, and so never shown, when it is no start's.
                return new RunPlace(
                        role,
                        push.get("repository") instanceof String repository ? repository : null,
                        name,
                        document.containsKey("pinned") ? pin(document.get("pinned")) : null);
            }
        } catch (IOException | RuntimeException ex) {
            // A file that does not read says nothing of the run; the task is still listed.
        }
        return null;
    }

    /** A pin that does not read throws, so the whole record reads as none. */
    private static Pin pin(final @Nullable Object kept) {
        if (kept instanceof Map<?, ?> pin
                && pin.get("head") instanceof String head
                && (pin.get("base") == null || pin.get("base") instanceof String)
                && (pin.get("repository") == null || pin.get("repository") instanceof String)) {
            return new Pin(
                    head,
                    pin.get("base") instanceof String base ? base : null,
                    pin.get("repository") instanceof String repository ? repository : null);
        }
        throw new IllegalArgumentException("a pin that does not read");
    }

    /**
     * Returns the head commit that waits at a gate push, for a start refused at another task's.
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
