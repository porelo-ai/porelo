package org.fuin.porelo.app;

import java.io.IOException;
import java.time.Clock;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.gate.review.PushEnds;
import org.fuin.porelo.gate.review.ReviewStore;
import org.fuin.porelo.gate.review.ThreadState;
import org.jspecify.annotations.Nullable;

/**
 * Keeps how a task's push stopped waiting at the gate, for the task that pushed it, from the
 * command line and the daemon alike: each end is kept under the container id recorded for the task,
 * never its reusable name, and forgotten when the task's next push waits.
 *
 * <p>A push no recorded task is known for keeps nothing: there is no task to keep it for.
 */
public final class GateEnds {

    private final PoreloContext context;

    private final Clock clock;

    /**
     * Constructor for a machine, with the time read from the system's clock in UTC.
     *
     * @param context The machine.
     */
    public GateEnds(final PoreloContext context) {
        this(context, Clock.systemUTC());
    }

    /**
     * Constructor for a machine and a clock.
     *
     * @param context The machine.
     * @param clock What the time of an end is read from.
     */
    public GateEnds(final PoreloContext context, final Clock clock) {
        this.context = context;
        this.clock = clock;
    }

    /**
     * Returns the store the ends are kept in, beside the review journals.
     *
     * @return The store.
     */
    public PushEnds store() {
        return new PushEnds(context.paths().gate().reviewComments());
    }

    /**
     * Keeps a reject, with the open threads of the push it discarded.
     *
     * @param project The project's name.
     * @param name The push's name.
     * @param rejected The push as it waited, read before the reject.
     * @param threads The review's threads.
     * @throws IOException When the push or its threads could not be read, or nothing written.
     */
    public void rejected(
            final String project,
            final String name,
            final PersonNote.Rejected rejected,
            final ReviewStore threads)
            throws IOException {
        final String task = recorded(project, name);
        if (task == null) {
            return;
        }
        final Push push;
        final int open;
        try {
            push = rejected.push(task);
            if (push == null) {
                // Nothing waited, so nothing stopped waiting.
                return;
            }
            open =
                    (int)
                            threads.threads(push).stream()
                                    .filter(thread -> thread.state() == ThreadState.OPEN)
                                    .count();
        } catch (RuntimeException ex) {
            throw new IOException("the rejected push's review could not be read", ex);
        }
        store().keep(task, PushEnd.rejected(name, push.head(), open, clock.instant()));
    }

    /**
     * Keeps an approval, with the commit the gate forwarded as it recorded it.
     *
     * @param project The project's name.
     * @param gate The gate that forwarded it.
     * @param name The push's name.
     * @param reviewed The commit reviewed and approved.
     * @param branch The branch it was forwarded onto.
     * @throws IOException When the forwarded commit is not recorded, or nothing written.
     */
    public void approved(
            final String project,
            final GitGate gate,
            final String name,
            final String reviewed,
            final String branch)
            throws IOException {
        final String task = recorded(project, name);
        if (task == null) {
            return;
        }
        // A signed approval forwards a merge of the reviewed commit, recorded where it landed.
        final String forwarded =
                gate.landed(name).stream()
                        .filter(landed -> landed.branch().equals(branch))
                        .map(GitGate.Landed::commit)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IOException(
                                                "the gate recorded no forwarded commit of "
                                                        + name
                                                        + " on "
                                                        + branch));
        store().keep(task, PushEnd.approved(name, reviewed, forwarded, branch, clock.instant()));
    }

    /**
     * Keeps a push found merged on the upstream.
     *
     * @param project The project's name.
     * @param name The push's name.
     * @param landing What the gate found; nothing is kept when it did not land.
     * @param branch The branch it was found on.
     * @throws IOException When nothing could be written.
     */
    public void landed(
            final String project,
            final String name,
            final GitGate.Landing landing,
            final String branch)
            throws IOException {
        if (!landing.landed()) {
            return;
        }
        final String task = recorded(project, name);
        if (task == null) {
            return;
        }
        store().keep(task, PushEnd.landed(name, landing.commit(), branch, clock.instant()));
    }

    /**
     * Forgets the end of a task's last push, as its next push waits.
     *
     * @param project The project's name.
     * @param name The task's name, as its push is named at the gate.
     * @throws IOException When the end could not be removed.
     */
    public void waiting(final String project, final String name) throws IOException {
        final TaskIdentity identity;
        try {
            // From the records alone: asked at each push, before it is answered.
            identity = TaskHistory.forPush(context.paths(), project, name);
        } catch (RuntimeException ex) {
            throw new IOException("the task of " + name + " could not be read", ex);
        }
        if (identity != null) {
            store().clear(identity.recordedId());
        }
    }

    private @Nullable String recorded(final String project, final String name) throws IOException {
        if (name.endsWith(GitGate.RESCUED)) {
            // What was rescued is the person's to decide, never the task's own push.
            return null;
        }
        final TaskIdentity identity;
        try {
            identity = TaskHistory.forPush(context, project, name);
        } catch (RuntimeException ex) {
            throw new IOException("the task of " + name + " could not be read", ex);
        }
        return identity == null ? null : identity.recordedId();
    }
}
