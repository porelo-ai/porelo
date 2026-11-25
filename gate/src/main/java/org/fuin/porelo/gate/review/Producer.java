package org.fuin.porelo.gate.review;

/**
 * Who gave an item, named by the channel it came through and never by its content. A scanner is a
 * later case.
 */
public sealed interface Producer permits OwnerAccount, Task, ForgeAccount {

    /**
     * Returns how the producer is shown.
     *
     * @return For example {@code owner account}, {@code task shop-2} or {@code forge account
     *     octocat}.
     */
    String words();
}
