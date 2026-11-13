package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.fuin.porelo.gate.GitHttpServer;
import org.fuin.porelo.wire.Directories;
import org.jspecify.annotations.Nullable;

/**
 * Tells a task at its push that no build of it comes while it runs: {@code build-<commit>.txt} with
 * verdict {@code none} and why, handed in before the push is answered.
 *
 * <p>{@code none} is the gate's alone and final: the build watch writes {@code unknown} while a
 * build may still come (a rate limit, a forge it cannot reach, a run not shown yet), and the guide
 * has the agent read that again.
 *
 * <p>Here and not in the build watch, because only the gate sees a push it holds: the watch asks
 * the forge, which a held push reaches only when a person passes it on, and a project that follows
 * no builds has no watch at all, and a task whose watch did not start has none either. A push
 * passed on and built later is followed by the watch, which hands in the same file name and so
 * replaces this one.
 */
public final class UnbuiltPush implements GitHttpServer.Taken {

    /** Why a push a person has to pass on is not built while the task runs. */
    public static final String HELD =
            "held at the gate for a person; nothing is built until it is passed on";

    /** Why a push passed on at once is not reported on either. */
    public static final String NO_BUILDS =
            "this project follows no builds; no build of this push is reported";

    /**
     * Why a push passed on at once is not reported on although the project follows its builds: the
     * task's build watch did not start.
     */
    public static final String UNWATCHED =
            "the watch that reports this task's builds did not start; no build of this push is"
                    + " reported";

    /** The verdict of a push no build of which is reported while the task runs. */
    static final String NONE = "none";

    /** Hands a file into the task, as Porelo. */
    @FunctionalInterface
    public interface Delivery {

        /**
         * Hands it in.
         *
         * @param name Its name in {@code /porelo/files}.
         * @param file Its content.
         * @throws IOException When it could not be placed.
         */
        void give(String name, Path file) throws IOException;
    }

    private final Delivery delivery;

    private final Path scratch;

    private final Clock clock;

    private final String why;

    /**
     * Constructor.
     *
     * @param delivery How a file reaches the task.
     * @param scratch A directory of the task's own state, for the file before it is handed in.
     * @param clock What time it is.
     * @param why Why no build comes, said in the file's {@code detail}.
     */
    public UnbuiltPush(
            final Delivery delivery, final Path scratch, final Clock clock, final String why) {
        this.delivery = delivery;
        this.scratch = scratch;
        this.clock = clock;
        this.why = why;
    }

    /**
     * Returns why a task's push is not built while it runs, or {@code null} when a build watch
     * reports on it.
     *
     * @param passedOn Whether the gate passes a push on at once.
     * @param followsBuilds Whether the project follows the builds of what is passed on.
     * @return The reason, or {@code null}.
     */
    public static @Nullable String why(final boolean passedOn, final boolean followsBuilds) {
        return why(passedOn, followsBuilds, true);
    }

    /**
     * Returns why a task's push is not built while it runs, or {@code null} when a build watch
     * reports on it.
     *
     * @param passedOn Whether the gate passes a push on at once.
     * @param followsBuilds Whether the project follows the builds of what is passed on.
     * @param watched Whether the task's build watch started; a project that follows builds is told
     *     nothing of them without it.
     * @return The reason, or {@code null}.
     */
    public static @Nullable String why(
            final boolean passedOn, final boolean followsBuilds, final boolean watched) {
        if (!passedOn) {
            return HELD;
        }
        if (!followsBuilds) {
            return NO_BUILDS;
        }
        return watched ? null : UNWATCHED;
    }

    @Override
    public void taken(final String commit) {
        final TaskBuilds.Seen seen =
                new TaskBuilds.Seen(
                        commit,
                        NONE,
                        List.of(),
                        clock.instant().truncatedTo(ChronoUnit.SECONDS).toString(),
                        why,
                        "",
                        List.of());
        final String name = TaskBuilds.fileName(commit) + ".txt";
        try {
            Directories.create(scratch);
            final Path file = Files.createTempFile(scratch, "unbuilt-", ".txt");
            try {
                Files.writeString(file, TaskBuilds.file(seen), StandardCharsets.UTF_8);
                delivery.give(name, file);
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(name + " was not handed in", ex);
        }
    }
}
