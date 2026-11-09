package org.fuin.porelo.planning.api;

import java.io.Serial;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A named contract refusal, with its typed wire fields preserved. Never contains a token. */
public final class PlanningRefused extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    /** Every refusal defined by Planning1. */
    public enum Reason {
        /** The caller's revision is no longer current. */
        STALE_REVISION("StaleRevision"),
        /** The story does not exist. */
        NO_SUCH_STORY("NoSuchStory"),
        /** The issue does not exist. */
        NO_SUCH_ISSUE("NoSuchIssue"),
        /** The transition is refused. */
        STATE_NOT_ALLOWED("StateNotAllowed"),
        /** The adapter lacks this capability. */
        NOT_SUPPORTED("NotSupported"),
        /** The project has no workflow. */
        WORKFLOW_MISSING("WorkflowMissing"),
        /** A story with issues has no independently writable state. */
        STORY_HAS_ISSUES("StoryHasIssues"),
        /** The store refused the supplied credential. */
        CREDENTIAL_REFUSED("CredentialRefused"),
        /** The store limited requests. */
        RATE_LIMITED("RateLimited"),
        /** The configured store is absent or hidden from this credential. */
        NO_SUCH_STORE("NoSuchStore"),
        /** The store did not provide an answer. */
        UNREACHABLE("Unreachable"),
        /** The adapter cannot use the supplied settings. */
        INVALID_SETTINGS("InvalidSettings"),
        /** A safe explanation of another adapter failure. */
        FAILED("Failed");
        private final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        /** Returns the unqualified varlink error name. */
        public String wire() {
            return wire;
        }

        static @Nullable Reason of(@Nullable String name) {
            for (Reason reason : values()) {
                if (PlanningProtocol.error(reason).equals(name)) {
                    return reason;
                }
            }
            return null;
        }
    }

    private final Reason reason;
    private final Map<String, Object> parameters;

    /** Constructs a refusal with exactly the fields its contract declares. */
    public PlanningRefused(Reason reason, Map<String, Object> parameters) {
        super(reason.wire());
        this.reason = reason;
        this.parameters =
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(parameters));
        switch (reason) {
            case STALE_REVISION -> Wire.required(parameters, "revision");
            case NO_SUCH_STORY, STORY_HAS_ISSUES -> Wire.required(parameters, "story");
            case NO_SUCH_ISSUE -> Wire.required(parameters, "issue");
            case STATE_NOT_ALLOWED -> {
                Wire.required(parameters, "item");
                Wire.required(parameters, "reason");
                Wire.text(parameters, "from");
                Wire.required(parameters, "to");
            }
            case NOT_SUPPORTED -> Wire.text(parameters, "capability");
            case WORKFLOW_MISSING, INVALID_SETTINGS -> Wire.text(parameters, "detail");
            case FAILED -> Wire.text(parameters, "message");
            case CREDENTIAL_REFUSED, RATE_LIMITED, NO_SUCH_STORE, UNREACHABLE -> {
                Wire.text(parameters, "detail");
                if (Wire.whole(parameters.get("retryAfter")).orElse(-1) < 0) {
                    throw new IllegalArgumentException("retryAfter must be nonnegative seconds");
                }
            }
        }
    }

    /** Returns the named reason. */
    public Reason reason() {
        return reason;
    }

    /** Returns the refusal's wire fields. */
    public Map<String, Object> parameters() {
        return parameters;
    }

    /** Refuses an operation requiring a capability the adapter lacks. */
    public static PlanningRefused notSupported(String capability) {
        return new PlanningRefused(Reason.NOT_SUPPORTED, Map.of("capability", capability));
    }
}
