package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.wire.LimitFactors;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * Begins a task's run at its pull request in the daemon, for a start made outside it: the daemon
 * keeps every task's current run, and a run begun anywhere else would end with the process that
 * began it. Without a daemon that answers, the run does not begin and the start is refused.
 */
public final class DaemonRunStarts implements TaskLaunch.RunStarts {

    private final Path socket;
    private final LimitFactors limits;

    /**
     * Constructor.
     *
     * @param socket The daemon's socket.
     * @param limits The account's time factors.
     */
    public DaemonRunStarts(final Path socket, final LimitFactors limits) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Returns the daemon of a machine.
     *
     * @param context The machine.
     * @return Its run starts.
     */
    public static DaemonRunStarts of(final PoreloContext context) {
        return new DaemonRunStarts(context.paths().daemonSocket(), context.limits());
    }

    @Override
    public void begin(final String container, final RunPlace place) {
        if (place.pullRequest() == null) {
            return;
        }
        final VarlinkClient client;
        try {
            client = new VarlinkClient(socket, limits);
        } catch (VarlinkException ex) {
            throw new Refused(
                    "porelod does not answer at "
                            + socket
                            + ", and a run at a pull request begins there; start porelod and"
                            + " start the task again",
                    ex);
        }
        try (client) {
            client.call(TasksContract.INTERFACE + ".BeginRun", Map.of("task", container));
        } catch (VarlinkException ex) {
            final String error = Objects.requireNonNullElse(ex.getErrorName(), "Refused");
            final String name = error.substring(error.lastIndexOf('.') + 1);
            throw new Refused(name + ": " + said(ex.getParameters()), ex);
        } catch (java.io.IOException ex) {
            // Closed after the daemon answered: the run began.
        }
    }

    /** The refusal's fields, for a person; never a credential, since none is in them. */
    private static String said(final @Nullable Map<String, Object> fields) {
        return fields == null || fields.isEmpty() ? "the run could not begin" : fields.toString();
    }
}
