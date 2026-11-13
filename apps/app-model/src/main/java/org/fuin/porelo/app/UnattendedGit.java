package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.credential.CredentialException;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.gate.GateException;

/** Keeps Git from starting credential dialogs or signing prompts when nobody can answer. */
final class UnattendedGit {
    private UnattendedGit() {}

    static Map<String, String> environment() {
        return Map.of(
                "GIT_TERMINAL_PROMPT",
                "0",
                "GIT_ASKPASS",
                "/bin/false",
                "SSH_ASKPASS_REQUIRE",
                "never");
    }

    /** An absent unattended signer, kept distinct from a failed Git operation. */
    static final class Unavailable extends GateException {
        private static final long serialVersionUID = 1L;

        Unavailable() {
            super(
                    "there is no terminal to ask for signing; unattended signing needs an SSH key served by "
                            + "SSH_AUTH_SOCK; configure gpg.format=ssh and user.signingkey with its public key");
        }
    }

    static CommandRunner runner(PoreloContext context) {
        if (context.input().canPrompt()) return context.runner();
        return command -> {
            if (!command.program().equals("git")) return context.runner().run(command);
            final boolean signing =
                    command.arguments().contains("merge") && command.arguments().contains("-S");
            Command prepared = prepare(context, command, signing);
            if (signing) {
                final Signer signer = servedSigningKey(context, command);
                final List<String> arguments = new ArrayList<>(prepared.arguments());
                arguments.addAll(
                        arguments.indexOf("merge"),
                        List.of("-c", "user.signingkey=key::" + signer.key()));
                final Map<String, String> variables =
                        new java.util.LinkedHashMap<>(prepared.environment());
                variables.put("SSH_AUTH_SOCK", signer.socket());
                prepared = copy(prepared, arguments, variables);
            }
            return context.runner().run(prepared);
        };
    }

    private record Signer(String key, String socket) {}

    private static Signer servedSigningKey(PoreloContext context, Command command) {
        final String socket = context.environment().apply("SSH_AUTH_SOCK");
        if (!configured(context, command, "gpg.format").equals("ssh")
                || socket == null
                || socket.isBlank()) {
            throw new Unavailable();
        }
        try {
            final String publicKey =
                    publicKey(context, command, configured(context, command, "user.signingkey"));
            final String served =
                    AgentPublicKeys.all(context).stream()
                            .map(AgentPublicKeys::validated)
                            .filter(
                                    key ->
                                            AgentPublicKeys.fingerprint(key)
                                                    .equals(AgentPublicKeys.fingerprint(publicKey)))
                            .findFirst()
                            .orElseThrow(Unavailable::new);
            return new Signer(served, socket);
        } catch (CredentialException
                | CommandException
                | IOException
                | java.nio.file.InvalidPathException ex) {
            throw new Unavailable();
        }
    }

    private static String publicKey(PoreloContext context, Command command, String configured)
            throws IOException {
        if (configured.startsWith("key::"))
            return AgentPublicKeys.validated(configured.substring(5));
        if (configured.matches("(?:sk-)?(?:ssh|ecdsa)-\\S+\\s+.*"))
            return AgentPublicKeys.validated(configured);
        if (configured.isBlank()) throw new Unavailable();
        // Read only the public companion; the private file never reaches ssh-keygen.
        final String publicFile = configured.endsWith(".pub") ? configured : configured + ".pub";
        Path file =
                publicFile.startsWith("~/")
                        ? context.paths().xdg().home().resolve(publicFile.substring(2))
                        : Path.of(publicFile);
        if (!file.isAbsolute()) {
            final int directory = command.arguments().indexOf("-C");
            final Path base =
                    directory >= 0
                            ? Path.of(command.arguments().get(directory + 1))
                            : command.workingDirectory() != null
                                    ? command.workingDirectory()
                                    : Path.of("").toAbsolutePath();
            file = base.resolve(file);
        }
        if (!Files.isRegularFile(file) || Files.size(file) > 64 * 1024) throw new Unavailable();
        return AgentPublicKeys.validated(Files.readString(file).strip());
    }

    private static String configured(PoreloContext context, Command command, String property) {
        // Ask in the merge's scratch repository, where includeIf may choose a different signer.
        final List<String> arguments =
                new ArrayList<>(
                        command.arguments().subList(0, command.arguments().indexOf("merge")));
        arguments.addAll(List.of("config", "--get", property));
        final var configured =
                context.runner()
                        .run(
                                prepare(
                                        context,
                                        new Command(
                                                arguments,
                                                command.workingDirectory(),
                                                command.environment(),
                                                null,
                                                command.inheritsEnvironment()),
                                        true));
        return configured.successful() ? configured.trimmedOutput() : "";
    }

    private static Command prepare(PoreloContext context, Command command, boolean signing) {
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "credential.helper=",
                                "-c",
                                "gpg.ssh.program=ssh-keygen"));
        arguments.addAll(command.arguments().subList(1, command.arguments().size()));
        final Map<String, String> variables = new java.util.LinkedHashMap<>();
        if (command.inheritsEnvironment()) variables.putAll(System.getenv());
        // Inherited overrides must not replace the checked agent or start an interactive helper.
        variables.keySet().removeIf(UnattendedGit::environmentConfiguration);
        variables.put("GIT_SSH_COMMAND", FollowCredential.sshCommand(context, null));
        final String socket = context.environment().apply("SSH_AUTH_SOCK");
        variables.put("SSH_AUTH_SOCK", socket == null ? "" : socket);
        // A lease may supply its own agent, SSH policy or authorization header.
        variables.putAll(command.environment());
        if (signing) {
            variables.keySet().removeIf(UnattendedGit::environmentConfiguration);
        }
        variables.putAll(environment());
        variables.put("HOME", context.paths().xdg().home().toString());
        variables.put(
                "XDG_CONFIG_HOME", String.valueOf(context.paths().xdg().config().getParent()));
        return copy(command, arguments, variables);
    }

    private static boolean environmentConfiguration(String name) {
        return name.equals("GIT_CONFIG_COUNT")
                || name.equals("GIT_CONFIG_PARAMETERS")
                || name.startsWith("GIT_CONFIG_KEY_")
                || name.startsWith("GIT_CONFIG_VALUE_");
    }

    private static Command copy(
            Command command, List<String> arguments, Map<String, String> variables) {
        final Command prepared =
                new Command(
                        arguments, command.workingDirectory(), variables, command.input(), false);
        final byte[] binary = command.binaryInput();
        final Command bounded = binary == null ? prepared : prepared.withBinaryInput(binary);
        // A command's own limit holds through the preparation.
        final java.time.Duration limit = command.timeout();
        return limit == null ? bounded : bounded.withTimeout(limit);
    }
}
