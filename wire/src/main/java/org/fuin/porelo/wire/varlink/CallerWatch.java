package org.fuin.porelo.wire.varlink;

/**
 * Watches the connection of one running call for its caller's end, and interrupts the call's thread
 * when the caller has gone while the call still runs.
 *
 * <p>The connection reads the peer on one thread of its own, blocking, while the call's thread only
 * writes: a read and a write on one socket do not wait for each other. What the caller sends
 * meanwhile, its next call, is kept for the connection's next {@link VarlinkConnection#receive()}.
 * Once the call has ended no thread is told any more; the reading thread then ends with its read
 * under way, which returns as soon as the caller sends again or goes, or is taken on by the next
 * watched call.
 */
final class CallerWatch {

    private final VarlinkConnection connection;

    private final Thread call;

    /** Whether the call still runs. Guarded by this. */
    private boolean running = true;

    /** Whether the caller has gone. Guarded by this. */
    private boolean gone;

    /** Whether the call's thread was told. Guarded by this. */
    private boolean told;

    /** Whether the call's end was taken already. Guarded by this. */
    private boolean ended;

    private CallerWatch(final VarlinkConnection connection, final Thread call) {
        this.connection = connection;
        this.call = call;
    }

    /**
     * Starts watching for the caller of a call that runs on this thread.
     *
     * @param connection The call's connection.
     * @return The watch.
     */
    static CallerWatch start(final VarlinkConnection connection) {
        final CallerWatch watch = new CallerWatch(connection, Thread.currentThread());
        connection.watchFor(watch);
        return watch;
    }

    /** Takes the caller's end, from the connection's reading thread: a running call is told. */
    synchronized void gone() {
        gone = true;
        if (running) {
            told = true;
            call.interrupt();
        }
    }

    /**
     * Tells whether the caller has gone, without waiting.
     *
     * @return Whether it has.
     */
    synchronized boolean callerGone() {
        return gone;
    }

    /**
     * Ends the watch of a call that answers, has returned or thrown, on the call's thread: no
     * thread is told after this, and the interrupt of a call that was told is taken back from it.
     *
     * @return Whether the call was told its caller went.
     */
    boolean end() {
        final boolean wasTold;
        final boolean first;
        synchronized (this) {
            running = false;
            wasTold = told;
            first = !ended;
            ended = true;
        }
        if (first) {
            connection.unwatch(this);
            if (wasTold) {
                // The connection's thread reads the next call, and an interrupted read closes it.
                Thread.interrupted();
            }
        }
        return wasTold;
    }
}
