package org.fuin.porelo.app;

import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.gate.review.TaskRights;
import org.jspecify.annotations.Nullable;

/**
 * Decides what a task may write into a waiting push's threads from what its start recorded: a task
 * holding {@code openThreads} opens a thread, one holding {@code reply} answers in any thread, and
 * either only in its own project. A task that works on its own writes only in its own push, the one
 * its own container pushed, whether its recorded place is that push or its own pull request; one
 * that reads another task's work only in the one gate push its recorded place names, and in none
 * when its place is a pull request. Everything is looked up by the task's container name.
 *
 * <p>Fail closed: a task whose parts, place or owner cannot be read writes nothing, and a reply in
 * a forge's thread never reaches a push.
 */
public final class RecordedTaskWrites implements TaskWrites {

    /** Reads the place a task's start recorded, by its container name. */
    @FunctionalInterface
    public interface Places {

        /**
         * Returns it.
         *
         * @param container The task's container name.
         * @return Its place, or null when it recorded none.
         */
        @Nullable RunPlace of(String container);
    }

    /** Reads the project and the recorded container id of a task, by its container name. */
    @FunctionalInterface
    public interface Owners {

        /**
         * Returns them.
         *
         * @param container The task's container name.
         * @return The task's identity as recorded, or empty when none is.
         */
        Optional<TaskIdentity> of(String container);
    }

    private final TaskRights rights;

    private final Places places;

    private final Owners owners;

    /**
     * Constructor.
     *
     * @param rights The task's recorded parts.
     * @param places The task's recorded place.
     * @param owners The task's recorded project and container id.
     */
    public RecordedTaskWrites(final TaskRights rights, final Places places, final Owners owners) {
        this.rights = Objects.requireNonNull(rights, "rights");
        this.places = Objects.requireNonNull(places, "places");
        this.owners = Objects.requireNonNull(owners, "owners");
    }

    /**
     * Returns a machine's: each task's parts and place from its state, else its durable record, and
     * its owner as its reservation kept it.
     *
     * @param context The machine.
     * @param rights The machine's recorded rights.
     * @return The decision.
     */
    public static RecordedTaskWrites of(final PoreloContext context, final TaskRights rights) {
        return new RecordedTaskWrites(rights, places(context), owners(context));
    }

    /**
     * Returns a machine's recorded places: each task's from its state, else its durable record.
     *
     * @param context The machine.
     * @return The places.
     */
    public static Places places(final PoreloContext context) {
        return container -> {
            final RunPlace inState =
                    RunPlace.readFrom(context.paths().tasks().containerState(container));
            return inState != null
                    ? inState
                    : RunPlace.readFrom(context.paths().tasks().taskRecord(container));
        };
    }

    /**
     * Returns a machine's recorded owners: each task's project and container id as its reservation
     * kept them.
     *
     * @param context The machine.
     * @return The owners.
     */
    public static Owners owners(final PoreloContext context) {
        return container -> {
            try {
                return TaskReservations.historical(context.paths(), container);
            } catch (java.io.IOException ex) {
                // A claim that cannot be read names no owner: nothing is written.
                return Optional.empty();
            }
        };
    }

    @Override
    public boolean may(final String container, final Push push, final ReviewItem item) {
        try {
            final TaskParts parts = rights.parts(container);
            final boolean allowed =
                    switch (item) {
                        case ReviewComment _ -> parts.holds(Right.OPEN_THREADS);
                        case ReviewReply _ -> parts.holds(Right.REPLY);
                        // A forge's thread is never one of a push's.
                        case ForgeReply _ -> false;
                        case org.fuin.porelo.gate.review.ThreadResolve _ ->
                                parts.holds(Right.RESOLVE_THREADS);
                    };
            return allowed && isPlace(container, push, parts);
        } catch (RuntimeException ex) {
            // A record that cannot be read gives no right.
            return false;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>By the parts its start recorded; a record that cannot be read is left to {@link #may},
     * which refuses everything then.
     */
    @Override
    public boolean holds(final String container, final Right right) {
        try {
            return rights.parts(container).holds(right);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /**
     * Whether the push is one the task may write in, in its own project: for own work its own push,
     * whether its recorded place is that push or its own pull request; for a reader exactly the
     * gate push its recorded place names.
     */
    private boolean isPlace(final String container, final Push push, final TaskParts parts) {
        final RunPlace place = places.of(container);
        if (place == null || !place.name().equals(push.name())) {
            return false;
        }
        final Optional<TaskIdentity> owner = owners.of(container);
        if (owner.isEmpty() || !owner.get().project().equals(push.project())) {
            return false;
        }
        // A pull request's place names the task's own name and no repository; a gate push's both.
        final boolean recordedRepository =
                Objects.equals(
                        place.repository(),
                        RunPlace.otherThanOwn(push.project(), push.repository()));
        if (parts.worksOnItsOwn()) {
            // Its own push alone, pushed by its own container: the threads of its own work stay
            // its to answer when its place moved to its own pull request.
            return push.task().equals(owner.get().recordedId())
                    && (place.pullRequest() != null || recordedRepository);
        }
        // A reader has exactly the one gate push it reads, another task's, pushed by the task its
        // pin recorded, and none at a pull request; a task of any other workspace has no push.
        final RunPlace.Pin pin = place.pinned();
        return parts.readsAnothersWork()
                && place.pullRequest() == null
                && recordedRepository
                && !push.task().equals(owner.get().recordedId())
                && pin != null
                && push.task().equals(pin.pushedBy());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The gate push its recorded place names, in its own project, pushed by the task its pin
     * recorded; before its first pin, by any task but itself, whose push the pin then records.
     */
    @Override
    public boolean reads(final String container, final Push push) {
        try {
            final RunPlace place = places.of(container);
            final Optional<TaskIdentity> owner = owners.of(container);
            if (place == null
                    || !place.parts().readsAnothersWork()
                    || place.pullRequest() != null
                    || !place.name().equals(push.name())
                    || !Objects.equals(
                            place.repository(),
                            RunPlace.otherThanOwn(push.project(), push.repository()))
                    || owner.isEmpty()
                    || !owner.get().project().equals(push.project())) {
                return false;
            }
            final RunPlace.Pin pin = place.pinned();
            return pin == null
                    ? !push.task().equals(owner.get().recordedId())
                    : push.task().equals(pin.pushedBy());
        } catch (RuntimeException ex) {
            // A record that cannot be read reads nothing.
            return false;
        }
    }
}
