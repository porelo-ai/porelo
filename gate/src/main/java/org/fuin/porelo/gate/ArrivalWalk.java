package org.fuin.porelo.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Pages the commits a pushed tip brings to a mirror in windows of a bounded size, so that a push of
 * any number of commits is judged whole without its list held in memory.
 *
 * <p>The refs whose commits are left out are read once, when the walk starts, and every window
 * walks against what they held then: a ref that landed between two windows would remove commits
 * from the list and shift every later window over commits that are then never judged.
 */
final class ArrivalWalk {

    /** How many commits one window holds. */
    static final int WINDOW = 4096;

    private static final Pattern ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private final GitHttpServer.GitProcess git;

    private final Path mirror;

    /** The excluded tips, one {@code ^<id>} line each, as read when the walk started. */
    private final String excluded;

    private final int size;

    private ArrivalWalk(GitHttpServer.GitProcess git, Path mirror, String excluded, int size) {
        this.git = git;
        this.mirror = mirror;
        this.excluded = excluded;
        this.size = size;
    }

    /**
     * Starts a walk that leaves out what the refs under the given prefixes hold.
     *
     * @param git Runs git.
     * @param mirror The bare mirror.
     * @param size How many commits one window holds.
     * @param held The ref prefixes whose commits were judged where they came from.
     * @return The walk.
     * @throws IOException If the refs cannot be read whole.
     */
    static ArrivalWalk excluding(
            GitHttpServer.GitProcess git, Path mirror, int size, List<String> held)
            throws IOException {
        return new ArrivalWalk(git, mirror, excluded(git, mirror, held), size);
    }

    /**
     * Returns how many commits one window holds; a shorter window is the walk's last.
     *
     * @return The window's size.
     */
    int size() {
        return size;
    }

    /**
     * Asks git for one window of the commits the tip brings, newest first.
     *
     * @param command The walk's command and its options, after {@code --git-dir}.
     * @param old What the ref held before, also left out, or {@code null}.
     * @param tip The pushed commit.
     * @param skip How many commits earlier windows held.
     * @return What git wrote.
     * @throws IOException If git cannot be asked, fails, or an id is not one.
     */
    byte[] window(List<String> command, @Nullable String old, String tip, long skip)
            throws IOException {
        final StringBuilder revisions = new StringBuilder(excluded);
        // On standard input, where any id is a revision and never an option.
        for (final String each : old == null ? List.of(tip) : List.of(old, tip)) {
            if (!ID.matcher(each).matches()) {
                throw new IOException("git names no commit " + each);
            }
        }
        if (old != null) {
            revisions.append('^').append(old).append('\n');
        }
        revisions.append(tip).append('\n');
        final List<String> arguments = new ArrayList<>(List.of("--git-dir", mirror.toString()));
        arguments.addAll(command);
        arguments.addAll(List.of("--max-count=" + size, "--skip=" + skip, "--stdin"));
        return git.runOrFail(arguments, revisions.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static String excluded(GitHttpServer.GitProcess git, Path mirror, List<String> held)
            throws IOException {
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "--git-dir",
                                mirror.toString(),
                                "for-each-ref",
                                "--format=%(objectname)"));
        arguments.addAll(held);
        final StringBuilder excluded = new StringBuilder();
        for (final String each :
                new String(git.runOrFail(arguments, new byte[0]), StandardCharsets.US_ASCII)
                        .lines()
                        .toList()) {
            if (each.isEmpty()) {
                continue;
            }
            if (!ID.matcher(each).matches()) {
                throw new IOException("the mirror's refs cannot be read whole");
            }
            excluded.append('^').append(each).append('\n');
        }
        return excluded.toString();
    }
}
