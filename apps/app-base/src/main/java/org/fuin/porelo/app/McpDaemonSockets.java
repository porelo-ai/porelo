package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The per-task daemon sockets {@code porelod} serves {@code porelo-mcp} on.
 *
 * <p>A task's start asks for its socket before it starts {@code porelo-mcp}, and its stop closes
 * it. Which methods the socket carries, and whether the task gets one at all, is the daemon's, from
 * the project's signed file. Set by {@code porelod} when it starts; anywhere else, such as a
 * command run without the daemon, no task gets one.
 */
public final class McpDaemonSockets {

    /** Opens and closes one task's socket. */
    public interface Opener {

        /**
         * Opens the task's socket, or answers that its project allows none.
         *
         * @param task The task's name.
         * @param project Its project's name.
         * @return The socket's path, or empty when the project's file allows the task no endpoint.
         * @throws IOException If the socket cannot be made.
         */
        Optional<Path> open(String task, String project) throws IOException;

        /**
         * Closes the task's socket, if it has one. Closing one it does not have does nothing.
         *
         * @param task The task's name.
         */
        void close(String task);
    }

    /** No task gets a socket. */
    public static final Opener NONE =
            new Opener() {
                @Override
                public Optional<Path> open(final String task, final String project) {
                    return Optional.empty();
                }

                @Override
                public void close(final String task) {
                    // Nothing was opened.
                }
            };

    private static final AtomicReference<Opener> CURRENT = new AtomicReference<>(NONE);

    private McpDaemonSockets() {}

    /**
     * Returns the opener in use.
     *
     * @return {@link #NONE} unless {@code porelod} installed its own.
     */
    public static Opener current() {
        final Opener opener = CURRENT.get();
        return opener == null ? NONE : opener;
    }

    /**
     * Puts an opener in place, as {@code porelod} does at its start and a test does around itself.
     *
     * @param opener The opener.
     * @return The one it replaced, for a test to put back.
     */
    public static Opener install(final Opener opener) {
        final Opener before = CURRENT.getAndSet(opener);
        return before == null ? NONE : before;
    }
}
