package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.gate.GitGate;

/**
 * Pins a reviewer's run to one state: moves its ref {@code refs/porelo/pinned/<task>} in the task's
 * mirror and records the state beside its role. Pinning afresh is a new run's start.
 */
final class ReviewerPin {

    private ReviewerPin() {}

    /**
     * Pins a reviewer's task to a head.
     *
     * <p>The ref moves first and the record after: a record that cannot be written puts the ref
     * back where it was, so the gate and the record never name two states.
     *
     * @param git Runs git on the host.
     * @param mirror The task's mirror.
     * @param state The task's state directory, where its role and place are kept.
     * @param task The task's name, which names its pinned ref.
     * @param pin The state.
     * @throws IOException When git refuses the head or the record cannot be written.
     * @throws IllegalStateException When the task's recorded role is not a reviewer's.
     */
    static void pin(
            final CommandRunner git,
            final Path mirror,
            final Path state,
            final String task,
            final RunPlace.Pin pin)
            throws IOException {
        final RunPlace recorded = RunPlace.readFrom(state);
        if (recorded == null || !RunPlace.REVIEWER.equals(recorded.role())) {
            throw new IllegalStateException(
                    "only a reviewer's run is pinned; " + task + " is none");
        }
        final String ref = GitGate.PINNED + task;
        final RunPlace.Pin earlier = recorded.pinned();
        final String before = earlier == null ? null : earlier.head();
        moved(git, mirror, ref, pin.head());
        try {
            recorded.pinnedTo(pin).writeTo(state);
        } catch (IOException ex) {
            if (before == null) {
                git.run(
                        Command.of(
                                List.of("git", "-C", mirror.toString(), "update-ref", "-d", ref)));
            } else {
                git.run(
                        Command.of(
                                List.of(
                                        "git",
                                        "-C",
                                        mirror.toString(),
                                        "update-ref",
                                        ref,
                                        before)));
            }
            throw ex;
        }
    }

    private static void moved(
            final CommandRunner git, final Path mirror, final String ref, final String head)
            throws IOException {
        // A head the mirror does not hold as a commit is never pinned.
        final CommandResult commit =
                git.run(
                        Command.of(
                                List.of(
                                        "git",
                                        "-C",
                                        mirror.toString(),
                                        "rev-parse",
                                        "--verify",
                                        "--quiet",
                                        head + "^{commit}")));
        if (!commit.successful()) {
            throw new IOException("the mirror holds no commit " + head + " to pin");
        }
        final CommandResult updated =
                git.run(
                        Command.of(
                                List.of("git", "-C", mirror.toString(), "update-ref", ref, head)));
        if (!updated.successful()) {
            throw new IOException("the pinned ref " + ref + " could not be moved to " + head);
        }
    }
}
