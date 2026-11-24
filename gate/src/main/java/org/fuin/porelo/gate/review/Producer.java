package org.fuin.porelo.gate.review;

/**
 * Who gave an item, named by the channel it came through and never by its content. A forge account
 * and a scanner are later cases.
 */
public sealed interface Producer permits OwnerAccount, Task {

    /**
     * Returns how the producer is shown.
     *
     * @return For example {@code owner account} or {@code task shop-2}.
     */
    String words();
}
