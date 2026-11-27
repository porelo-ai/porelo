package org.fuin.porelo.app;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.gate.review.PushEnds;
import org.fuin.porelo.gate.review.ReviewStore;
import org.fuin.porelo.gate.review.ThreadState;
import org.jspecify.annotations.Nullable;

/**
 * Keeps where a task's push stands at the gate, for the task that pushed it, from the command line
 * and the daemon alike: when the gate took it, then how it stopped waiting, each kept under the
 * container id recorded for the task, never its reusable name. The task's next push replaces its
 * last end, and an end carries the time its push was taken when that is the commit that ended.
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
        keep(task, PushEnd.rejected(name, push.head(), open, clock.instant()));
    }

    /**
     * What a task's reject kept before its ref went: the end the pushing task had, and the end
     * written in its place.
     *
     * @param before The end before, or empty when it had none.
     * @param written The reject's end, as it was kept.
     */
    public record Written(java.util.Optional<PushEnd> before, PushEnd written) {}

    /**
     * Keeps a reject a task holding {@code reject} made, under the pushing task's recorded
     * container id as the pinned push names it, never found anew by the push's reusable name.
     *
     * @param pinned The push as the rejecting task's run was pinned to it.
     * @param by The rejecting task's container name.
     * @param threads The review's threads.
     * @return The end before and the one written, which {@link #restoreIf} puts back.
     * @throws IOException When the threads or the last end could not be read, or nothing written.
     */
    public Written rejectedBy(final Push pinned, final String by, final ReviewStore threads)
            throws IOException {
        final java.util.Optional<PushEnd> before = store().read(pinned.task());
        final int open;
        try {
            open =
                    (int)
                            threads.threads(pinned).stream()
                                    .filter(thread -> thread.state() == ThreadState.OPEN)
                                    .count();
        } catch (RuntimeException ex) {
            throw new IOException("the rejected push's review could not be read", ex);
        }
        keep(
                pinned.task(),
                PushEnd.rejected(pinned.name(), pinned.head(), open, clock.instant()).by(by));
        return new Written(
                before,
                store().read(pinned.task())
                        .orElseThrow(() -> new IOException("the reject's end was not kept")));
    }

    /**
     * Puts back the end a task had before a reject that did not happen, only while its end is still
     * the one that reject wrote: an end kept since, by an approval or the next push, stays.
     *
     * @param task The pushing task's recorded container id.
     * @param written What the reject kept.
     * @return Whether its end was put back; false when a newer end stands.
     * @throws IOException When the end could not be read or written.
     */
    public boolean restoreIf(final String task, final Written written) throws IOException {
        if (!store().read(task).equals(java.util.Optional.of(written.written()))) {
            return false;
        }
        if (written.before().isPresent()) {
            store().keep(task, written.before().get());
        } else {
            store().clear(task);
        }
        return true;
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
        keep(task, PushEnd.approved(name, reviewed, forwarded, branch, clock.instant()));
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
        keep(task, PushEnd.landed(name, landing.commit(), branch, clock.instant()));
    }

    /**
     * Keeps when the gate took a task's push, in place of its last push's end.
     *
     * @param project The project's name.
     * @param name The task's name, as its push is named at the gate.
     * @param commit The commit the push's ref points at now.
     * @throws IOException When it could not be written.
     */
    public void waiting(final String project, final String name, final String commit)
            throws IOException {
        if (name.endsWith(GitGate.RESCUED)) {
            // What was rescued is the person's to decide, never the task's own push.
            return;
        }
        final TaskIdentity identity;
        try {
            // From the records alone: asked at each push, before it is answered.
            identity = TaskHistory.forPush(context.paths(), project, name);
        } catch (RuntimeException ex) {
            throw new IOException("the task of " + name + " could not be read", ex);
        }
        if (identity != null) {
            store().keep(identity.recordedId(), PushEnd.waiting(name, commit, clock.instant()));
        }
    }

    /** Keeps an end, with the time its push was taken when the gate kept it for that commit. */
    private void keep(final String task, final PushEnd end) throws IOException {
        Instant since = null;
        try {
            since =
                    store().read(task)
                            .filter(entry -> entry.end() == PushEnd.End.WAITING)
                            .filter(entry -> entry.name().equals(end.name()))
                            .filter(entry -> entry.commit().equals(end.commit()))
                            .map(PushEnd::waitingSince)
                            .orElse(null);
        } catch (IOException ex) {
            // A waiting record that cannot be read leaves the end without its waiting time;
            // the end itself is still kept, and a reader says the time is missing.
            since = null;
        }
        store().keep(task, end.waitedSince(since));
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
