package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.PinnedRepository;
import org.jspecify.annotations.Nullable;

/**
 * Pins a reviewer's run to one state: builds the reviewer's own repository from the task's mirror,
 * holding only what the pinned head and base reach, moves its ref {@code refs/porelo/pinned/<task>}
 * in the mirror and records the state beside its role. Pinning afresh is a new run's start.
 */
final class ReviewerPin {

    private ReviewerPin() {}

    /**
     * Pins a reviewer's task to a head.
     *
     * <p>The served repository is replaced and the ref moved first, the record after: a record that
     * cannot be written puts the earlier repository and the ref back, so the gate and the record
     * never name two states.
     *
     * @param git Runs git on the host.
     * @param mirror The task's mirror.
     * @param state The task's state directory, where its role, place and served repository are
     *     kept.
     * @param task The task's name, which names its pinned ref.
     * @param pin The state.
     * @throws IOException When the mirror holds no such commit, an id names a tag rather than the
     *     commit itself, the repository cannot be built or the record cannot be written.
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
        commit(git, mirror, pin.head());
        final String base = pin.base();
        if (base != null) {
            commit(git, mirror, base);
        }
        final String ref = GitGate.PINNED + task;
        final RunPlace.Pin earlier = recorded.pinned();
        final String before = earlier == null ? null : earlier.head();
        final PinnedRepository.Replacement replaced =
                PinnedRepository.replace(
                        git, mirror, PinnedRepository.in(state), task, pin.head(), base);
        try {
            moved(git, mirror, ref, pin.head());
            recorded.pinnedTo(pin).writeTo(state);
        } catch (IOException | RuntimeException ex) {
            replaced.undo();
            putBack(git, mirror, ref, before);
            throw ex;
        }
        replaced.keep();
    }

    /** An id the mirror does not hold as that very commit, a tag's among them, is never pinned. */
    private static void commit(final CommandRunner git, final Path mirror, final String id)
            throws IOException {
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
                                        id + "^{commit}")));
        if (!commit.successful()) {
            throw new IOException("the mirror holds no commit " + id + " to pin");
        }
        if (!id.equals(commit.standardOutput().strip())) {
            throw new IOException("a pin names a commit itself, and " + id + " is none");
        }
    }

    private static void moved(
            final CommandRunner git, final Path mirror, final String ref, final String head)
            throws IOException {
        final CommandResult updated =
                git.run(
                        Command.of(
                                List.of("git", "-C", mirror.toString(), "update-ref", ref, head)));
        if (!updated.successful()) {
            throw new IOException("the pinned ref " + ref + " could not be moved to " + head);
        }
    }

    private static void putBack(
            final CommandRunner git,
            final Path mirror,
            final String ref,
            final @Nullable String before) {
        git.run(
                Command.of(
                        before == null
                                ? List.of("git", "-C", mirror.toString(), "update-ref", "-d", ref)
                                : List.of(
                                        "git",
                                        "-C",
                                        mirror.toString(),
                                        "update-ref",
                                        ref,
                                        before)));
    }
}
