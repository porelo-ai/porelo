package org.fuin.porelo.app;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.credential.CredentialException;
import org.fuin.porelo.core.process.Command;

/** Reads only the public identities offered by this account's SSH agent. */
public final class AgentPublicKeys {
    private AgentPublicKeys() {}

    /** Returns public key lines; an unavailable agent is a failure, not an empty agent. */
    public static List<String> all(PoreloContext context) {
        final String socket = context.environment().apply("SSH_AUTH_SOCK");
        if (socket == null || socket.isBlank()) return List.of();
        final var result =
                context.runner()
                        .run(
                                Command.of("ssh-add", "-L")
                                        .withEnvironment(Map.of("SSH_AUTH_SOCK", socket)));
        if (result.exitCode() == 1) return List.of();
        if (!result.successful())
            throw new CredentialException(
                    "Cannot list the SSH agent's keys; set SSH_AUTH_SOCK to a running agent that serves your key.");
        return result.standardOutput()
                .lines()
                .map(
                        line -> {
                            validated(line);
                            return line.strip().replaceAll("[\\p{Cntrl}]", "?");
                        })
                .toList();
    }

    /** Validates and returns one public key, without allowing SSH options or extra lines. */
    public static String validated(String line) {
        final String[] fields = line.strip().split("\\s+", 3);
        if (line.indexOf('\n') >= 0
                || line.indexOf('\r') >= 0
                || fields.length < 2
                || !fields[0].matches("(?:sk-)?(?:ssh|ecdsa)-[A-Za-z0-9@._+-]+")
                || fingerprint(line).isEmpty())
            throw new CredentialException("Name one SSH public key or its SHA256 fingerprint.");
        return fields[0] + " " + fields[1];
    }

    /** Returns the SHA256 fingerprint of a public key line, or an empty string if malformed. */
    public static String fingerprint(String line) {
        final String[] fields = line.strip().split("\\s+", 3);
        if (fields.length < 2) return "";
        try {
            final byte[] blob = java.util.Base64.getDecoder().decode(fields[1]);
            final var input = java.nio.ByteBuffer.wrap(blob);
            final int size = input.getInt();
            if (size <= 0 || size > input.remaining()) return "";
            final byte[] type = new byte[size];
            input.get(type);
            if (!fields[0].equals(new String(type, java.nio.charset.StandardCharsets.US_ASCII)))
                return "";
            return "SHA256:"
                    + java.util.Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(blob));
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException ex) {
            return "";
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    /** Resolves a fingerprint from the agent, or validates an explicitly supplied public half. */
    public static String select(PoreloContext context, String key) {
        if (!key.startsWith("SHA256:")) return validated(key);
        return all(context).stream()
                .filter(line -> key.equals(fingerprint(line)))
                .map(AgentPublicKeys::validated)
                .findFirst()
                .orElseThrow(
                        () ->
                                new CredentialException(
                                        "The SSH agent has no key with that fingerprint; load the key into your agent and set SSH_AUTH_SOCK to its socket."));
    }
}
