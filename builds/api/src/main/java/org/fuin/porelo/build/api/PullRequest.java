package org.fuin.porelo.build.api;

/**
 * A pull request a reader opened or found open.
 *
 * @param url Where a person reads it.
 * @param opened Whether this call opened it; {@code false} when it was already open.
 */
public record PullRequest(String url, boolean opened) {

    /**
     * Refuses an answer without an address.
     *
     * @param url Where a person reads it.
     * @param opened Whether this call opened it.
     */
    public PullRequest {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("a pull request without an address");
        }
    }
}
