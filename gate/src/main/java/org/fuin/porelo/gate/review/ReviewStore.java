package org.fuin.porelo.gate.review;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a push's items and its threads' states.
 *
 * <p>The store mints each guid and each thread id, random UUIDs. A comment opens a new thread; a
 * reply joins its thread, open or resolved, without changing its state, or is refused with {@code
 * NoSuchThread} when the thread is not this push's (same project, repository, name and task
 * container). A retry id is scoped to the push: the same id with an equal item and origin answers
 * the first kept item again, with anything else {@code IdReused}. Each call is atomic against
 * concurrent calls. A stand-in does the same in memory.
 */
public interface ReviewStore {

    /**
     * Keeps one checked item.
     *
     * @param push The push.
     * @param item The item, already checked.
     * @param origin The host's stamps.
     * @param id The caller's retry id, or null. The same id with the same item answers the same
     *     kept item again.
     * @return The item as kept.
     * @throws ReviewRefused {@code NoSuchThread} or {@code IdReused}.
     */
    Kept keep(Push push, ReviewItem item, Origin origin, @Nullable String id) throws ReviewRefused;

    /**
     * Returns a push's threads, the oldest first.
     *
     * @param push The push.
     * @return Its threads; empty when it has none.
     */
    List<ReviewThread> threads(Push push);

    /**
     * Returns the push a task's container last recorded under a name, with the head and base of its
     * latest entry, so the threads of a push that no longer waits stay reachable.
     *
     * @param project The project.
     * @param repository The repository in the project.
     * @param name The push's name at the gate.
     * @param task The recorded container id; no other container's journal is read.
     * @return The push as last recorded, or empty when that container kept nothing under the name.
     */
    Optional<Push> recorded(String project, String repository, String name, String task);

    /**
     * Sets a thread open or resolved.
     *
     * @param push The push.
     * @param thread The thread's id.
     * @param state The state to set.
     * @return The thread as it now stands.
     * @throws ReviewRefused {@code NoSuchThread}.
     */
    ReviewThread state(Push push, String thread, ThreadState state) throws ReviewRefused;
}
