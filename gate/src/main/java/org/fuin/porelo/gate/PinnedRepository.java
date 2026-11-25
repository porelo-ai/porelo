package org.fuin.porelo.gate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/**
 * The repository a reviewer's gate serves: a bare repository of its own that holds only what its
 * pinned head and base reach, with {@code HEAD} at the pinned ref.
 *
 * <p>Hiding refs in the shared mirror does not hold against git itself: inherited configuration
 * lets a want of a hidden commit through, negotiation acknowledges a hidden commit it holds, and an
 * annotated tag on the pinned commit travels with {@code include-tag}. A commit this repository
 * does not hold is one no request can reach.
 */
public final class PinnedRepository {

    /** Its directory in a reviewer task's state. */
    public static final String DIRECTORY = "pinned.git";

    /** Where the pinned base is kept, one ref per task, so it stays while it is pinned. */
    public static final String BASE = "refs/porelo/pinned-base/";

    private PinnedRepository() {}

    /**
     * Returns where a reviewer task's served repository lies.
     *
     * @param state The task's state directory.
     * @return The repository's directory.
     */
    public static Path in(final Path state) {
        return state.resolve(DIRECTORY);
    }

    /**
     * Builds a fresh bare repository holding the pinned head and base and nothing else: no
     * alternates, no shared object store, no tags.
     *
     * @param git Runs git on the host.
     * @param mirror The mirror that holds both commits.
     * @param into An absent directory to build it in.
     * @param task The reviewer's task, which names the pinned ref {@code HEAD} points at.
     * @param head The pinned head, in full.
     * @param base The pinned base, in full, or {@code null} when none is named.
     * @throws IOException When the directory exists or git refuses a step.
     */
    public static void build(
            final CommandRunner git,
            final Path mirror,
            final Path into,
            final String task,
            final String head,
            final @Nullable String base)
            throws IOException {
        // Made here, so git init never takes over a directory something else left.
        Files.createDirectory(into);
        ran(git, List.of("git", "init", "--bare", "-q", into.toString()), "init");
        final String ref = GitGate.PINNED + task;
        // Version 2 takes a want of any commit the mirror holds, whatever the host configured.
        final List<String> fetch =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "protocol.version=2",
                                "-C",
                                into.toString(),
                                "fetch",
                                "-q",
                                "--no-tags",
                                "--no-write-fetch-head",
                                "--",
                                mirror.toString(),
                                head + ":" + ref));
        if (base != null) {
            fetch.add(base + ":" + BASE + task);
        }
        ran(git, fetch, "fetch");
        ran(
                git,
                List.of("git", "-C", into.toString(), "symbolic-ref", "HEAD", ref),
                "symbolic-ref");
    }

    /**
     * A served repository replaced by a newly built one, the earlier one kept aside until the
     * replacement is kept or undone.
     *
     * @param served Where the repository is served from.
     * @param earlier Where the earlier one waits, absent when there was none.
     */
    public record Replacement(Path served, Path earlier) {

        /**
         * Keeps the new repository and removes the earlier one.
         *
         * @throws IOException When the earlier one cannot be removed.
         */
        public void keep() throws IOException {
            removed(earlier);
        }

        /**
         * Puts the earlier repository back, or none when there was none.
         *
         * @throws IOException When it cannot be put back.
         */
        public void undo() throws IOException {
            removed(served);
            if (Files.exists(earlier, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.move(earlier, served, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    /**
     * Builds a new repository beside the served one and moves it into place whole; the earlier one
     * waits aside until the caller keeps or undoes the replacement. A build that fails leaves the
     * served repository as it was.
     *
     * @param git Runs git on the host.
     * @param mirror The mirror that holds both commits.
     * @param served Where the reviewer's gate serves from.
     * @param task The reviewer's task.
     * @param head The pinned head, in full.
     * @param base The pinned base, in full, or {@code null}.
     * @return The replacement.
     * @throws IOException When it cannot be built or moved into place.
     */
    public static Replacement replace(
            final CommandRunner git,
            final Path mirror,
            final Path served,
            final String task,
            final String head,
            final @Nullable String base)
            throws IOException {
        final Path built = served.resolveSibling(served.getFileName() + ".new");
        final Path earlier = served.resolveSibling(served.getFileName() + ".old");
        // What an interrupted replacement left is never served or built on.
        removed(built);
        removed(earlier);
        try {
            build(git, mirror, built, task, head, base);
        } catch (IOException | RuntimeException ex) {
            removed(built);
            throw ex;
        }
        final boolean existed = Files.exists(served, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (existed) {
            Files.move(served, earlier, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            Files.move(built, served, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            if (existed) {
                Files.move(earlier, served, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            removed(built);
            throw ex;
        }
        return new Replacement(served, earlier);
    }

    /**
     * Removes a reviewer's served repository, as removing its task does.
     *
     * @param served Where it is served from.
     * @throws IOException When it cannot be removed.
     */
    public static void remove(final Path served) throws IOException {
        removed(served);
    }

    private static void removed(final Path tree) throws IOException {
        if (!Files.exists(tree, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(tree)) {
            for (final Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }

    private static void ran(final CommandRunner git, final List<String> command, final String step)
            throws IOException {
        final CommandResult result = git.run(Command.of(command));
        if (!result.successful()) {
            throw new IOException("the reviewer's repository could not be built: git " + step);
        }
    }
}
