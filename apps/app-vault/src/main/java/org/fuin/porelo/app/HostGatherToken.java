package org.fuin.porelo.app;

/** Owns the short-lived mutable token supplied by the privileged issuer. */
final class HostGatherToken implements java.util.function.Consumer<String>, AutoCloseable {
    private char[] value = new char[0];

    @Override
    public void accept(String secret) {
        java.util.Arrays.fill(value, (char) 0);
        value = secret.toCharArray();
    }

    char[] value() {
        return value;
    }

    @Override
    public void close() {
        java.util.Arrays.fill(value, (char) 0);
    }
}
