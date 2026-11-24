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

    /** How often the writer looks at the screen, at the polling factor 1.0. */
    static final Duration LOOK = Duration.ofSeconds(1);

    /**
     * Older than this, the writer is taken to have stopped, and the screen is asked of the task as
     * before; at the polling factor 1.0, as the writer's own looks are.
     */
    static final Duration FRESH = Duration.ofSeconds(5);

    /** The most of a screen that is read: a terminal's screen is far less. */
    static final int MOST = 64 * 1024;

    /**
     * The recording of the session's output, in the directory: what the session wrote, as it wrote
     * it, cut into {@link #SEGMENT}-byte files named by {@link #SUFFIX} decimal digits that count
     * from {@code 00000000}, so a file's name says where its bytes lie in the whole.
     */
    public static final String RECORDING = "recording";

    /**
     * Why the session is not recorded, beside the recording, written by the writer when it cannot
     * record and removed once it can: one line.
     */
    public static final String RECORDING_ERROR = "recording.error";

    /** The line {@link #RECORDING_ERROR} holds when the task image has no GNU split. */
    static final String NO_GNU_SPLIT =
            "porelo: this session is not recorded, as the task image has no GNU split, which the"
                    + " recording needs: install coreutils in it";

    /** Which session the recording is of, beside it: a pipe that ended goes on in its own. */
    static final String SESSION = ".session";

    /** How many bytes one file of the recording holds; the newest holds as many or fewer. */
    public static final int SEGMENT = 1024 * 1024;

    /** How many letters name a file of the recording. */
    public static final int SUFFIX = 8;

    /**
     * How many files of the recording are kept: the oldest goes when there are more, so a session
     * that prints without end holds at most this many {@link #SEGMENT}s of the host's memory.
     */
    public static final int KEPT = 8;

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
     * <p>It also has the server record the session's output into {@link #RECORDING}: tmux's own
     * pipe, whose command the server runs as root, writes it there, and the oldest file goes once
     * there are more than {@link #KEPT}. Output from before the writer's first look at a new
     * session, at most a second, is not recorded.
     *
     * <p>The root's alone: the server is root's, on a socket the agent cannot reach, so the agent
     * has no part in what is copied beyond what its screen shows and its session writes.
     *
     * @param limits The factors how often it looks follows.
     * @return The command and its arguments.
     */
    public static List<String> writer(final org.fuin.porelo.wire.LimitFactors limits) {
        return List.of("sh", "-c", writerScript(MOUNT, limits.polling(LOOK)));
    }

    /**
     * Returns the root's script.
     *
     * @param directory Where it writes.
     * @param look How often it looks.
     * @return The script.
     */
    static String writerScript(final String directory, final Duration look) {
        final String tmux = String.join(" ", org.fuin.porelo.runtime.Podman.tmux());
        final String session = org.fuin.porelo.runtime.Containerfile.SESSION;
        return "d='"
                + directory
                + "'; [ -d \"$d\" ] || exit 0; r=\"$d/"
                + RECORDING
                + "\";"
                + " while :; do"
                // A session's output is recorded from its first look on, and a new session starts
                // a new recording. A pipe that ended in the same session, as on a full file
                // system, goes on in its recording: the newest file filled up first, then the
                // files after it, so every byte stays where its position says.
                + "   if p=$("
                + tmux
                + " display-message -p -t "
                + session
                + " '#{pane_pipe} #{session_id} #{session_created}' 2>/dev/null)"
                + " && [ \"${p%% *}\" != 1 ]; then"
                // Only GNU split names its files from a number: any other ends the pipe at once,
                // so none is started, and why is said beside the recording instead.
                + "   if ! split --version >/dev/null 2>&1; then"
                + "     [ -s \"$d/"
                + RECORDING_ERROR
                + "\" ] || echo '"
                + NO_GNU_SPLIT
                + "' > \"$d/"
                + RECORDING_ERROR
                + "\";"
                + "   else rm -f \"$d/"
                + RECORDING_ERROR
                + "\";"
                + "     s=${p#* }; f=0; c=;"
                + "     if [ -d \"$r\" ] && [ \"$(cat \"$d/"
                + SESSION
                + "\" 2>/dev/null)\" = \"$s\" ]; then"
                + "       set -- \"$r\"/"
                + "?".repeat(SUFFIX)
                + ";"
                + "       if [ -f \"$1\" ]; then"
                + "         eval \"l=\\${$#}\"; f=$(expr \"${l##*/}\" + 1);"
                + "         c=\"head -c $(("
                + SEGMENT
                + " - $(wc -c < \"$l\"))) >> '$l' && \";"
                + "       fi;"
                + "     else rm -rf \"$r\"; mkdir \"$r\" && printf '%s\\n' \"$s\" > \"$d/"
                + SESSION
                + "\"; fi;"
                + "     [ -d \"$r\" ] && "
                + tmux
                + " pipe-pane -t "
                + session
                + " \"${c}exec split -b "
                + SEGMENT
                + " -a "
                + SUFFIX
                + " --numeric-suffixes=$f - '$r/'\";"
                + "   fi;"
                + "   fi;"
                + "   set -- \"$r\"/"
                + "?".repeat(SUFFIX)
                + "; while [ $# -gt "
                + KEPT
                + " ]; do rm -f \"$1\"; shift; done;"
                + "   if "
                + String.join(" ", org.fuin.porelo.runtime.Podman.tmux("capture-pane", "-p", "-J"))
                + " -t "
                + org.fuin.porelo.runtime.Containerfile.SESSION
                + " > \"$d/.next\" 2>/dev/null && [ -s \"$d/.next\" ]; then"
                + "     cmp -s \"$d/.next\" \"$d/screen\" || mv -f \"$d/.next\" \"$d/screen\";"
                + "   else rm -f \"$d/screen\" \"$d/.next\"; fi;"
                + "   : > \"$d/alive\"; "
                + ShellSeconds.sleep(look)
                + ";"
                + " done";
    }

    /**
     * What the task wrote: its screen, or that it has no session; {@code null} when no writer
     * answers, and the screen has to be asked of the task.
     *
     * @param state The task's state directory.
     * @param clock What "now" is.
     * @param limits The factors the writer's looks, and so the age it counts as stopped at, follow.
     * @return The screen's text, "" for no session, or {@code null}.
     */
    public static @Nullable String read(
            final Path state, final Clock clock, final org.fuin.porelo.wire.LimitFactors limits) {
        final Path directory = directory(state);
        final Duration fresh = limits.polling(FRESH);
        try {
            final Path alive = directory.resolve(ALIVE);
            if (!Files.isRegularFile(alive, LinkOption.NOFOLLOW_LINKS)
                    || Files.getLastModifiedTime(alive, LinkOption.NOFOLLOW_LINKS)
                            .toInstant()
                            .plus(fresh)
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
