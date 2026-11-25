package org.fuin.porelo.gate.review;

/**
 * What a task's role allows it at a place: push rights, the account its items are written as, and
 * its rights in threads. Implemented once over the role the task's run recorded; every other part
 * asks it and names no role.
 *
 * <p>A stand-in answers fixed rights and a fixed account.
 */
public interface ReviewRole {

    /**
     * Returns the role the task's run recorded; a task that recorded none is an author.
     *
     * @param task The task's name.
     * @return Its role.
     */
    Role role(String task);

    /**
     * Whether the task may push to its gate at all.
     *
     * @param task The task's name.
     * @return False for a reviewer, which reads one pinned state and pushes nothing.
     */
    boolean mayPush(String task);

    /**
     * Returns who the task's released items are written as at a place: the owner account's forge
     * account, the account that opened the pull request, or the gate's producer.
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
     * Whether the task may open a thread at a place, or only reply.
     *
     * @param task The task's name.
     * @param place The place.
     * @return True for a reviewer; an author only replies.
     */
    boolean mayOpenThread(String task, ReviewPlace place);

    /**
     * Whether the task may pin a run to that head.
     *
     * @param task The task's name.
     * @param head The commit to pin.
     * @return False for an author's own work pinned as a reviewer's.
     */
    boolean mayPin(String task, String head);
}
