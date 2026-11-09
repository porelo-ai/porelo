package org.fuin.porelo.host;

/** A refusal with a sysexits status and a diagnostic safe to print. */
final class HostRefusal extends RuntimeException {
    private final int exitCode;

    HostRefusal(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    int exitCode() {
        return exitCode;
    }
}
