package org.fuin.porelo.app;

/** A safe gathered-transfer refusal, never carrying a credential. */
final class HostGatherRefusal extends RuntimeException {
    private final int code;

    HostGatherRefusal(int code, String message) {
        super(message);
        this.code = code;
    }

    int code() {
        return code;
    }
}
