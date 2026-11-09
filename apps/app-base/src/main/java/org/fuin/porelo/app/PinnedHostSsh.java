package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.fuin.porelo.core.process.Command;
import org.jspecify.annotations.Nullable;

/** One fresh SSH connection trusting only a supplied public host key. */
record PinnedHostSsh(Path pin, List<String> connection) implements AutoCloseable {
    static PinnedHostSsh open(String host, int port, String login, String key) throws IOException {
        if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")
                || port < 1
                || port > 65535
                || !login.matches("[a-z][a-z0-9-]{0,31}")
                || !key.matches(
                        "(ssh-ed25519|ecdsa-sha2-nistp(?:256|384|521)|ssh-rsa) [A-Za-z0-9+/]+={0,2}")) {
            throw new IllegalArgumentException(
                    "SSH needs a valid host, port, login and public host key");
        }
        final String type = key.substring(0, key.indexOf(' '));
        final String algorithms = type.equals("ssh-rsa") ? "rsa-sha2-512,rsa-sha2-256" : type;
        final Path pin =
                Files.createTempFile(
                        "porelo-host-",
                        ".known-hosts",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(
                    pin, (port == 22 ? host : "[" + host + "]:" + port) + " " + key + "\n");
            final var connection = new ArrayList<>(List.of("ssh"));
            connection.addAll(options(pin));
            connection.addAll(
                    List.of(
                            "-T",
                            "-o",
                            "HostKeyAlgorithms=" + algorithms,
                            "-p",
                            Integer.toString(port),
                            login + "@" + host));
            return new PinnedHostSsh(pin, connection);
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(pin);
            throw ex;
        }
    }

    /** The same isolated trust policy for a direct SSH call or git's SSH child. */
    static List<String> options(Path knownHosts) {
        final String path = knownHosts.toString();
        if (path.contains("\"") || path.contains("\n") || path.contains("\r")) {
            throw new IllegalArgumentException("The known-hosts path contains a quote or newline");
        }
        return List.of(
                "-F", "/dev/null",
                "-o", "ForwardAgent=no",
                "-o", "ClearAllForwardings=yes",
                "-o", "StrictHostKeyChecking=yes",
                "-o", "UserKnownHostsFile=\"" + path + "\"",
                "-o", "GlobalKnownHostsFile=/dev/null",
                "-o", "UpdateHostKeys=no",
                "-o", "ControlPath=none",
                "-o", "ControlMaster=no",
                "-o", "KnownHostsCommand=none",
                "-o", "VerifyHostKeyDNS=no",
                "-o", "BatchMode=yes");
    }

    /** Only the selected machine's agent can reach SSH; a desktop can never ask a question. */
    static Map<String, String> environment(UnaryOperator<String> source) {
        final Map<String, String> chosen = new java.util.LinkedHashMap<>();
        final String path = source.apply("PATH");
        chosen.put("PATH", path == null || path.isBlank() ? "/usr/bin:/bin" : path);
        chosen.put("SSH_ASKPASS_REQUIRE", "never");
        final String agent = source.apply("SSH_AUTH_SOCK");
        if (agent != null && !agent.isBlank()) chosen.put("SSH_AUTH_SOCK", agent);
        return Map.copyOf(chosen);
    }

    Command command(
            List<String> remote, byte @Nullable [] input, UnaryOperator<String> environment) {
        final var arguments = new ArrayList<>(connection);
        arguments.addAll(remote);
        final Command command =
                Command.of(arguments).withChosenEnvironment(environment(environment));
        return input == null ? command : command.withBinaryInput(input);
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(pin);
    }
}
