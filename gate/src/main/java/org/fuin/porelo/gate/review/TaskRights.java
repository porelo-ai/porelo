package org.fuin.porelo.gate.review;

/**
 * What a task may do at a place, by the parts its start recorded: whether it pushes, the account
 * its items are written as, and its rights in threads. Implemented once over the recorded parts;
 * every other part of Porelo asks it and names no kind, account or right.
 *
 * <p>A stand-in answers fixed rights and a fixed account.
 */
public interface TaskRights {

    /**
     * Returns the parts the task's start recorded; a task that recorded none is given no right.
     *
     * @param task The task's name.
     * @return Its parts.
     */
    TaskParts parts(String task);

    /**
     * Whether the task may push to its gate at all.
     *
     * @param task The task's name.
     * @return True for a task that holds {@code push}.
     */
    boolean mayPush(String task);

    /**
     * Returns who the task's released items are written as at a place: at a gate push the task
     * itself, at a pull request the account its start recorded.
     *
     * @param task The task's name.
     * @param place The place.
     * @return The producer its items are written as.
     */
    Producer postingAccount(String task, ReviewPlace place);

    /**
     * Returns the vault entry of the write credential its released items are written with at a
     * place, the one of {@link #postingAccount}'s account.
     *
     * @param task The task's name.
     * @param place The place.
     * @return The entry's name; never the credential itself.
     * @throws PlaceUnreadable As {@code MissingForgeToken}, naming the entry, when the vault holds
     *     none.
     */
    String postingEntry(String task, ReviewPlace place) throws PlaceUnreadable;

    /**
     * Whether the task may open a thread at a place.
     *
     * @param task The task's name.
     * @param place The place.
     * @return True for a task that holds {@code openThreads}.
     */
    boolean mayOpenThread(String task, ReviewPlace place);

    /**
     * Whether the task may answer in a thread at a place, whoever opened it.
     *
     * @param task The task's name.
     * @param place The place.
     * @return True for a task that holds {@code reply}.
     */
    boolean mayReply(String task, ReviewPlace place);

    /**
     * Whether the task may mark a thread at a place resolved, whoever opened it.
     *
     * @param task The task's name.
     * @param place The place.
     * @return True for a task that holds {@code resolveThreads}.
     */
    boolean mayResolve(String task, ReviewPlace place);

    /**
     * Whether the task may discard the waiting push that is its place.
     *
     * @param task The task's name.
     * @param place The place.
     * @return True for a task that holds {@code reject}.
     */
    boolean mayReject(String task, ReviewPlace place);

    /**
     * Whether the task may pin a run to that head.
     *
     * @param task The task's name.
     * @param head The commit to pin.
     * @return True for any head of another task's work a task reads; for own work only a head its
     *     own gate took.
     */
    boolean mayPin(String task, String head);
}
