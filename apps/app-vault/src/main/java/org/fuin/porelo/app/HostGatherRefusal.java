package org.fuin.porelo.app;

/** A safe gathered-transfer refusal, never carrying a credential. */
public final class HostGatherRefusal extends RuntimeException {
    public enum Kind {
        HOST,
        VAULT_SHUT,
        MISSING_CENTRAL_TOKEN,
        CENTRAL_TOKEN_REFUSED,
        NOTHING_TO_PUSH,
        HOST_TOKEN,
        PUSH,
        CANCELLED
    }

    private final int code;
    private final Kind kind;
    private final java.util.Map<String, Object> details;

    HostGatherRefusal(int code, String message) {
        this(Kind.HOST, code, message, java.util.Map.of());
    }

    public HostGatherRefusal(
            Kind kind, int code, String message, java.util.Map<String, Object> details) {
        super(message);
        this.code = code;
        this.kind = kind;
        this.details = java.util.Map.copyOf(details);
    }

    public Kind kind() {
        return kind;
    }

    public java.util.Map<String, Object> details() {
        return details;
    }

    public static HostGatherRefusal host(String reason, int code, String message) {
        return new HostGatherRefusal(Kind.HOST, code, message, java.util.Map.of("reason", reason));
    }

    HostGatherRefusal withDetails(java.util.Map<String, Object> extra) {
        final var values = new java.util.LinkedHashMap<String, Object>(details);
        values.putAll(extra);
        return new HostGatherRefusal(kind, code, String.valueOf(getMessage()), values);
    }

    HostGatherRefusal redacted(char[] first, char[] second) {
        return new HostGatherRefusal(
                kind,
                code,
                HostGatherFlow.redact(String.valueOf(getMessage()), first, second),
                details);
    }

    public int code() {
        return code;
    }
}
