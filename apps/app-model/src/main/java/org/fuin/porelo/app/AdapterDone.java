package org.fuin.porelo.app;

import java.util.Map;

/**
 * One ecosystem adapter's answer while a proposal is made: what {@code porelo project init} lists
 * and {@code ProposeProject} streams.
 *
 * @param name The adapter's name.
 * @param state {@code recognized}; {@code refused} when it could not be started or asked; or its
 *     own refusal as its contract names it.
 * @param why Why, for any state but {@code recognized}; else "".
 */
public record AdapterDone(String name, String state, String why) {

    /** The state of an adapter that answered. */
    public static final String RECOGNIZED = "recognized";

    /** The state of an adapter that could not be started or asked. */
    public static final String REFUSED = "refused";

    /**
     * Returns this as plain values, for a wire and for the command's own answer.
     *
     * @return The answer.
     */
    public Map<String, Object> asMap() {
        return Map.of("name", name, "state", state, "why", why);
    }
}
