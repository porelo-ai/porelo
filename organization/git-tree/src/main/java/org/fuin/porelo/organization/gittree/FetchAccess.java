package org.fuin.porelo.organization.gittree;

import java.util.List;
import java.util.Map;

/**
 * What a fetch of the organization's repository needs, asked for by address and lent for the fetch
 * alone. Where it comes from stays with the caller: a person's vault on their machine, a key file
 * on a host.
 */
public interface FetchAccess {

    /** Lends nothing: what a public repository needs. */
    FetchAccess NONE = address -> Lease.EMPTY;

    /**
     * Lends what this address needs, for as long as the lease is open.
     *
     * @param address The repository's address.
     * @return The lease, which may hold nothing.
     */
    Lease lend(String address);

    /** What one fetch may use, and the undoing of it. */
    interface Lease extends AutoCloseable {

        /** A lease that holds nothing. */
        Lease EMPTY = new Lease() {};

        /**
         * Returns the variables the fetch runs with beside its own.
         *
         * @return The variables.
         */
        default Map<String, String> environment() {
            return Map.of();
        }

        /**
         * Returns git's own arguments, placed before the fetch and after its sealing settings.
         *
         * @return The arguments.
         */
        default List<String> arguments() {
            return List.of();
        }

        @Override
        default void close() {}
    }
}
