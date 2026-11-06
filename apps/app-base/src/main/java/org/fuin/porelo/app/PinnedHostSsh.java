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
            return new PinnedHostSsh(
                    pin,
                    List.of(
                            "ssh",
                            "-F",
                            "/dev/null",
                            "-T",
                            "-o",
                            "ForwardAgent=no",
                            "-o",
                            "ClearAllForwardings=yes",
                            "-o",
                            "StrictHostKeyChecking=yes",
                            "-o",
                            "UserKnownHostsFile=" + pin,
                            "-o",
                            "GlobalKnownHostsFile=/dev/null",
                            "-o",
                            "UpdateHostKeys=no",
                            "-o",
                            "ControlPath=none",
                            "-o",
                            "ControlMaster=no",
                            "-o",
                            "KnownHostsCommand=none",
                            "-o",
                            "VerifyHostKeyDNS=no",
                            "-o",
                            "HostKeyAlgorithms=" + algorithms,
                            "-o",
                            "BatchMode=yes",
                            "-p",
                            Integer.toString(port),
                            login + "@" + host));
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(pin);
            throw ex;
        }
    }

    Command command(
            List<String> remote, byte @Nullable [] input, UnaryOperator<String> environment) {
        final var arguments = new ArrayList<>(connection);
        arguments.addAll(remote);
        final String agent = environment.apply("SSH_AUTH_SOCK");
        final Command command =
                Command.of(arguments)
                        .withChosenEnvironment(
                                agent == null || agent.isBlank()
                                        ? Map.of()
                                        : Map.of("SSH_AUTH_SOCK", agent));
        return input == null ? command : command.withBinaryInput(input);
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(pin);
    }
}
