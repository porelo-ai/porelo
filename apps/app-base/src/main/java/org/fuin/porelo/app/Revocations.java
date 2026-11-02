package org.fuin.porelo.app;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;

/**
 * A revocation list checked before anything rests on it: a damaged list must refuse, never read as
 * one that revokes nothing.
 */
final class Revocations {

    private Revocations() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Refuses a revocation list that cannot be read, even when no signature is needed. */
    static void requireRevocations(
            final CommandRunner runner, final @org.jspecify.annotations.Nullable Path krl)
            throws java.io.IOException {
        if (krl == null) {
            return;
        }
        try {
            requireRevocation(runner, krl, null);
        } catch (final java.io.IOException | RuntimeException ex) {
            throw new java.io.IOException(
                    "the revocation list cannot be read: " + krl + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Refuses a revocation list that cannot be read, asking ssh-keygen in a chosen environment.
     *
     * @param runner Runs ssh-keygen.
     * @param krl The revocation list, or {@code null} when none is named.
     * @param environment All ssh-keygen gets of the environment.
     * @throws java.io.IOException If the list is missing, empty or no KRL.
     * @throws org.fuin.porelo.core.process.CommandException If ssh-keygen cannot be run.
     */
    static void requireRevocations(
            final CommandRunner runner,
            final @org.jspecify.annotations.Nullable Path krl,
            final java.util.Map<String, String> environment)
            throws java.io.IOException {
        if (krl == null) {
            return;
        }
        try {
            requireRevocation(runner, krl, environment);
        } catch (final java.io.IOException ex) {
            throw new java.io.IOException(
                    "the revocation list cannot be read: " + krl + ": " + ex.getMessage(), ex);
        }
    }

    private static void requireRevocation(
            final CommandRunner runner,
            final Path krl,
            final java.util.@org.jspecify.annotations.Nullable Map<String, String> environment)
            throws java.io.IOException {
        if (!Files.isRegularFile(krl)) {
            throw new java.io.IOException("not a regular file");
        }
        try (var input = Files.newInputStream(krl)) {
            if (input.readNBytes(1).length == 0) {
                throw new java.io.IOException("empty KRL");
            }
        }
        final Command asked = Command.of("ssh-keygen", "-Q", "-f", krl.toAbsolutePath().toString());
        final CommandResult checked =
                runner.run(environment == null ? asked : asked.withChosenEnvironment(environment));
        if (checked.exitCode() != 0 && checked.exitCode() != 1) {
            throw new java.io.IOException("invalid KRL: " + checked.standardError().strip());
        }
    }
}
