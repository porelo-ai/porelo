package org.fuin.porelo.planning.api;

import java.io.Serial;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** An executable, transport or malformed answer failed, distinct from a contract refusal. */
public final class PlanningException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    private final @Nullable String errorName;
    private final Map<String, Object> parameters;

    /** Names the failed adapter and the failed operation without request credentials. */
    public PlanningException(String message) {
        this(message, null, Map.of());
    }

    /** Names a local failure with its cause. */
    public PlanningException(String message, Throwable cause) {
        super(message, cause);
        errorName = null;
        parameters = Map.of();
    }

    /**
     * Preserves a newer adapter's unknown named error without putting its fields in a diagnostic.
     */
    public PlanningException(
            String message, @Nullable String errorName, Map<String, Object> parameters) {
        super(message);
        this.errorName = errorName;
        this.parameters =
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(parameters));
    }

    /** Returns the remote name, or null for a local failure. */
    public @Nullable String errorName() {
        return errorName;
    }

    /** Returns remote fields unchanged; render diagnostic text with terminal escaping. */
    public Map<String, Object> parameters() {
        return parameters;
    }
}
