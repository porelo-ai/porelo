package org.fuin.porelo.app;

import java.util.Map;
import org.fuin.porelo.gate.GateException;

/** A forwarding refusal with public credential metadata and an executable next action. */
public final class PushRefused extends GateException {
    private static final long serialVersionUID = 1L;
    private final Map<String, Object> details;

    /** Carries the same explanation to the terminal and the daemon. */
    public PushRefused(String message, Map<String, Object> details) {
        super(message);
        this.details = Map.copyOf(details);
    }

    /** Returns only public metadata, never the remote's raw diagnostic. */
    public Map<String, Object> details() {
        return details;
    }
}
