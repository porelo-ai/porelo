package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.fuin.porelo.wire.Directories;
import org.jspecify.annotations.Nullable;

/**
 * An attached agent's screen, written by the task itself into a directory of the host's, so that
 * reading it costs a file and not a process in the container.
 *
 * <p>Asked with {@code podman exec tmux capture-pane}, one read took 100 ms, and 400-600 ms with
 * fifteen tasks at work; a listing read every running task's screen, and an open interface asks for
 * a listing every half second. Here the task's root captures its screen once a second and copies it
 * when it changed.
 *
 * <p><strong>Copied by the container's root into a directory the agent cannot write.</strong> In a
 * rootless container root is the account that runs Porelo, which owns the directory; the agent is
 * another user. So what the host reads is neither a link nor a pipe the agent put there, and the
 * agent can neither fill nor replace it. The agent decides what its screen shows, as it always did;
 * nothing more.
 */
public final class ScreenFile {

    /** Where the directory is in the task. */
    public static final String MOUNT = "/run/porelo/screen";

    /**
     * The screen, as {@code tmux capture-pane -p -J} draws it. Absent while there is no session.
     */
    static final String SCREEN = "screen";

    /**
     * Touched by the writer at every look, so a writer that stopped is told from a screen that did
     * not change.
     */
    static final String ALIVE = "alive";

    /**
     * Older than this, the writer is taken to have stopped, and the screen is asked of the task as
     * before.
     */
    static final Duration FRESH = Duration.ofSeconds(5);

    /** The most of a screen that is read: a terminal's screen is far less. */
    static final int MOST = 64 * 1024;

    private ScreenFile() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the host's side of the directory, in the task's state directory.
     *
     * @param state The task's state directory.
     * @return The directory.
     */
    public static Path directory(final Path state) {
        return state.resolve("screen");
    }

    /**
     * Makes the directory, writable by this account alone - the container's root - and readable by
     * the task.
     *
     * @param state The task's state directory.
     * @return The directory.
     * @throws IOException When it cannot be made.
     */
    public static Path prepare(final Path state) throws IOException {
        final Path directory = Directories.create(directory(state));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        return directory;
    }

    /**
     * Returns what the task runs as its root: the screen captured from Porelo's tmux server once a
     * second, copied when it changed into the directory the host reads, and the sign of life
     * touched. It ends when the directory is not mounted, so a task made before this does nothing.
     *
     * <p>The root's alone: the server is root's, on a socket the agent cannot reach, so the agent
     * has no part in what is copied beyond what its screen shows.
     *
     * @return The command and its arguments.
     */
    public static List<String> writer() {
        return List.of("sh", "-c", writerScript(MOUNT));
    }

    /**
     * Returns the root's script.
     *
     * @param directory Where it writes.
     * @return The script.
     */
    static String writerScript(final String directory) {
        return "d='"
                + directory
                + "'; [ -d \"$d\" ] || exit 0;"
                + " while :; do"
                + "   if "
                + String.join(" ", org.fuin.porelo.runtime.Podman.tmux("capture-pane", "-p", "-J"))
                + " -t "
                + org.fuin.porelo.runtime.Containerfile.SESSION
                + " > \"$d/.next\" 2>/dev/null && [ -s \"$d/.next\" ]; then"
                + "     cmp -s \"$d/.next\" \"$d/screen\" || mv -f \"$d/.next\" \"$d/screen\";"
                + "   else rm -f \"$d/screen\" \"$d/.next\"; fi;"
                + "   : > \"$d/alive\"; sleep 1;"
                + " done";
    }

    /**
     * What the task wrote: its screen, or that it has no session; {@code null} when no writer
     * answers, and the screen has to be asked of the task.
     *
     * @param state The task's state directory.
     * @param clock What "now" is.
     * @return The screen's text, "" for no session, or {@code null}.
     */
    public static @Nullable String read(final Path state, final Clock clock) {
        final Path directory = directory(state);
        try {
            final Path alive = directory.resolve(ALIVE);
            if (!Files.isRegularFile(alive, LinkOption.NOFOLLOW_LINKS)
                    || Files.getLastModifiedTime(alive, LinkOption.NOFOLLOW_LINKS)
                            .toInstant()
                            .plus(FRESH)
                            .isBefore(clock.instant())) {
                return null;
            }
            final Path screen = directory.resolve(SCREEN);
            if (!Files.isRegularFile(screen, LinkOption.NOFOLLOW_LINKS)) {
                return "";
            }
            try (InputStream in =
                    Files.newInputStream(
                            screen, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                return new String(in.readNBytes(MOST), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            return null;
        }
    }
}
