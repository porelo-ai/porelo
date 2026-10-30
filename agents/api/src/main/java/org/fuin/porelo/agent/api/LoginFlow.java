package org.fuin.porelo.agent.api;

import org.jspecify.annotations.Nullable;

/** How an agent's sign-in reaches the person authorizing it. */
public enum LoginFlow {

    /** The person pastes the returned authorization code into the sign-in. */
    PASTE_CODE("paste-code"),

    /** The person authorizes a device code in a browser. */
    DEVICE_CODE("device-code"),

    /** The browser redirects to a listener on the same machine. */
    LOOPBACK("loopback");

    private final String value;

    LoginFlow(String value) {
        this.value = value;
    }

    /**
     * Returns the value used in definitions and reports.
     *
     * @return The declared spelling.
     */
    public String value() {
        return value;
    }

    /**
     * Reads a declared flow.
     *
     * @param value The declaration.
     * @param origin Where it was read, for a refusal.
     * @return The flow.
     * @throws AgentException If the declaration is not a supported flow.
     */
    public static LoginFlow of(@Nullable Object value, String origin) {
        for (final LoginFlow flow : values()) {
            if (flow.value.equals(value)) {
                return flow;
            }
        }
        throw new AgentException(
                origin + ": login.flow must be paste-code, device-code or loopback");
    }
}
