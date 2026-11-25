package org.fuin.porelo.gate.review;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * The forge instances this machine knows, by host.
 *
 * <p>A stand-in answers a fixed list.
 */
public interface ForgeInstances {

    /**
     * Finds the instance an address belongs to, by its host.
     *
     * @param url An https address at a forge.
     * @return The instance, an unreadable entry included, or empty when the machine knows no forge
     *     of that host.
     * @throws ForgeFileUnreadable When the file cannot be read as a whole.
     */
    Optional<ForgeInstance> of(URI url) throws ForgeFileUnreadable;

    /**
     * Returns every instance the machine knows, unreadable entries included.
     *
     * @return In the order they were added.
     * @throws ForgeFileUnreadable When the file cannot be read as a whole.
     */
    List<ForgeInstance> all() throws ForgeFileUnreadable;

    /**
     * Adds an instance, or changes the adapter or reading of one.
     *
     * @param host The host, a DNS name.
     * @param adapter An installed adapter's name.
     * @param reading How its comments are read.
     * @throws ForgeFileUnreadable When the file cannot be read or written; nothing was changed.
     */
    void set(String host, String adapter, CommentReading reading) throws ForgeFileUnreadable;
}
