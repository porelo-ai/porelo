package org.fuin.porelo.build.api;

import java.util.Objects;

/**
 * The forge's API and the token lent for one review call; an adapter keeps neither.
 *
 * @param api The forge's REST base URL; "" for the forge's public one.
 * @param token The token, lent for this call alone.
 */
public record ForgeAccess(String api, String token) {

    /**
     * Refuses a blank token.
     *
     * @param api The API, or "".
     * @param token The token.
     */
    public ForgeAccess {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(token, "token");
        if (token.isBlank()) {
            throw new IllegalArgumentException(
                    "A review call is made with a token; none was given");
        }
    }

    /** Never the token: an access ends up in a message, and a message in a log. */
    @Override
    public String toString() {
        return "ForgeAccess[api=" + (api.isEmpty() ? "the forge's own" : api) + "]";
    }
}
