package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.Push;

/**
 * Pins a task that reads another task's work to that task's push as it waits at the gate: its head
 * and base, from the mirror of the push's repository, into the reading task's own repository, which
 * its gate serves alone under {@code refs/porelo/pinned/<task>}. No other waiting push of the
 * project, the reading task's siblings' included, is in that repository, so no request reaches it.
 */
public final class WaitingPushPins {

    private WaitingPushPins() {}

    /**
     * Pins a reading task, found on a machine by its recorded container id, to a waiting push.
     *
     * @param context The machine.
     * @param task The reading task's recorded container id.
     * @param push The push as it waits now.
     * @throws PlaceUnreadable As {@link #pin(CommandRunner, Path, PullRequestPins.TaskGate, String,
     *     Push)}, and {@code NotInProject} when the task or its gate is not found.
     */
    public static void pin(final PoreloContext context, final String task, final Push push)
            throws PlaceUnreadable {
        final PullRequestPins.TaskGate gate = PullRequestPins.gatesOn(context).locate(task);
        pin(
                context.runner(),
                GateSupport.mirror(context, push.project(), push.repository()),
                gate,
                task,
                push);
    }

    /**
     * Pins a reading task's gate to a waiting push.
     *
     * @param git Runs git on the host.
     * @param mirror The mirror of the push's repository, which holds its head and base.
     * @param gate The reading task's gate.
     * @param task The reading task's recorded container id.
     * @param push The push as it waits now.
     * @throws PlaceUnreadable As {@code NotInProject} for the reading task's own push or one of
     *     another project, {@code NoSuchPush} when its mirror is gone, and {@code Unreachable} when
     *     it cannot be pinned; nothing is moved then.
     */
    public static void pin(
            final CommandRunner git,
            final Path mirror,
            final PullRequestPins.TaskGate gate,
            final String task,
            final Push push)
            throws PlaceUnreadable {
        if (push.task().equals(task)) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.NOT_IN_PROJECT,
                    "a task reads another task's waiting push, never its own");
        }
        if (!gate.project().equals(push.project())) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.NOT_IN_PROJECT,
                    "a task reads a waiting push of its own project only");
        }
        if (!Files.isDirectory(mirror)) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.NO_SUCH_PUSH,
                    "the gate of project " + push.project() + " holds no " + push.repository());
        }
        try {
            ReviewerPin.pin(
                    git,
                    mirror,
                    gate.state(),
                    gate.record(),
                    gate.served(),
                    gate.taskName(),
                    new RunPlace.Pin(push.head(), push.base(), null, push.task()));
        } catch (IOException | IllegalStateException ex) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.UNREACHABLE,
                    "the run could not be pinned to " + push.head());
        }
    }
}
