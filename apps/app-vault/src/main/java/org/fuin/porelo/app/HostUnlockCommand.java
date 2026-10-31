package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.HostVaultShare;
import org.fuin.porelo.vault.SshAgentClient;
import org.fuin.porelo.vault.VaultException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Derives a host's opening share locally and sends only that share through SSH. */
@picocli.CommandLine.Command(
        name = "unlock",
        mixinStandardHelpOptions = true,
        description = "Opens a host vault with a local SSH agent key; no agent is forwarded.")
public class HostUnlockCommand implements Callable<Integer>, PoreloFactory.ContextAware {
    @Parameters(index = "0", paramLabel = "<host>", description = "Host name or IPv4 address.")
    private String host;

    @Option(
            names = "--host-key",
            required = true,
            paramLabel = "SHA256:...",
            description =
                    "Expected Ed25519 host key fingerprint, checked before the share is sent.")
    private String hostKey;

    @Option(
            names = "--login",
            required = true,
            description = "The account that owns the host vault.")
    private String login;

    @Option(
            names = "--key",
            paramLabel = "SHA256:...",
            description =
                    "Person's key fingerprint. Required if the local agent offers several Ed25519 keys.")
    private @Nullable String key;

    @Option(
            names = "--previous-key",
            paramLabel = "SHA256:...",
            description = "Previous local SSH key; changes keys without clearing entries.")
    private @Nullable String previousKey;

    @Option(names = "--json", description = "Reports vault open or other-key as JSON.")
    private boolean json;

    @Option(
            names = "--yes",
            description =
                    "On another key, clears the vault and creates it again; sign in again afterwards.")
    private boolean yes;

    @Option(
            names = "--remote-porelo",
            defaultValue = "porelo",
            paramLabel = "PATH",
            description = "Remote porelo executable, for example ~/.local/bin/porelo.")
    private String remotePorelo;

    Offer.Asker asker = Offer.Asker.terminal();

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    java.util.function.Function<Path, List<AgentKey>> identities =
            path -> new SshAgentClient(path).identities();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter err = spec.commandLine().getErr();
        try {
            final Target target = target();
            final String socket = context.environment().apply("SSH_AUTH_SOCK");
            if (socket == null || socket.isBlank()) {
                err.println(
                        "porelo: SSH_AUTH_SOCK is unset; start a local SSH agent and add your key");
                err.flush();
                return 69;
            }
            final String known = knownHost(target);
            final AgentKey signingKey = signingKey(Path.of(socket), key);
            final AgentKey previous =
                    previousKey == null ? null : signingKey(Path.of(socket), previousKey);
            return send(target, known, signingKey, previous);
        } catch (IllegalArgumentException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 64;
        } catch (VaultException | CommandException | IOException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }

    private Target target() {
        if (!remotePorelo.matches("[A-Za-z0-9_./~-]+") || remotePorelo.startsWith("-")) {
            throw new IllegalArgumentException(
                    "--remote-porelo needs a command name or path without shell metacharacters");
        }
        return new Target(host, hostKey, login);
    }

    private record Target(String host, String fingerprint, String login) {
        private Target {
            if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")
                    || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")
                    || !login.matches("[a-z][a-z0-9-]{0,31}")) {
                throw new IllegalArgumentException(
                        "Host unlock needs a host name, SHA256 host key fingerprint and login");
            }
        }
    }

    private String knownHost(Target target) {
        final var scanned =
                context.runner()
                        .runOrFail(
                                Command.of(
                                        "ssh-keyscan",
                                        "-T",
                                        "10",
                                        "-t",
                                        "ed25519",
                                        "--",
                                        target.host()));
        for (final String line : scanned.standardOutput().lines().toList()) {
            final String[] fields = line.trim().split("\\s+");
            if (fields.length != 3 || !fields[1].equals("ssh-ed25519")) {
                continue;
            }
            final byte[] blob;
            try {
                blob = Base64.getDecoder().decode(fields[2]);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (target.fingerprint().equals(fingerprint(blob))) {
                return target.host() + " ssh-ed25519 " + fields[2] + "\n";
            }
        }
        throw new VaultException(
                "The host key does not match --host-key; verify the host before unlocking");
    }

    private AgentKey signingKey(Path socket, @Nullable String fingerprint) {
        final List<AgentKey> offered =
                identities.apply(socket).stream()
                        .filter(
                                candidate ->
                                        candidate.authorizedKeysLine().startsWith("ssh-ed25519 "))
                        .filter(
                                candidate ->
                                        fingerprint == null
                                                || fingerprint.equals(
                                                        fingerprint(candidate.keyBlob())))
                        .toList();
        if (offered.size() != 1) {
            throw new VaultException(
                    "Choose one Ed25519 key from your local SSH agent with --key SHA256:...");
        }
        return offered.getFirst();
    }

    private static String fingerprint(byte[] blob) {
        try {
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private int send(Target target, String known, AgentKey signingKey, @Nullable AgentKey previous)
            throws IOException {
        final Path pinned =
                Files.createTempFile(
                        "porelo-host-",
                        ".known-hosts",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        byte[] share = null;
        byte[] old = null;
        byte[] input = null;
        try {
            Files.writeString(pinned, known);
            share = HostVaultShare.derive(signingKey, target.fingerprint(), target.login());
            if (previous == null) input = share;
            else {
                old = HostVaultShare.derive(previous, target.fingerprint(), target.login());
                input = new byte[64];
                System.arraycopy(old, 0, input, 0, 32);
                System.arraycopy(share, 0, input, 32, 32);
            }
            final java.util.ArrayList<String> connection =
                    new java.util.ArrayList<>(
                            List.of(
                                    "ssh",
                                    "-T",
                                    "-o",
                                    "ForwardAgent=no",
                                    "-o",
                                    "ClearAllForwardings=yes",
                                    "-o",
                                    "StrictHostKeyChecking=yes",
                                    "-o",
                                    "UserKnownHostsFile=" + pinned,
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
                                    "HostKeyAlgorithms=ssh-ed25519",
                                    "-o",
                                    "BatchMode=yes",
                                    target.login() + "@" + target.host()));
            var result =
                    remote(
                            connection,
                            input,
                            previous == null ? "unlock" : "rekey",
                            "--share-stdin",
                            "--json");
            var status = RemoteVault.answer(result);
            if (previous == null && status == RemoteVault.ABSENT) {
                result = remote(connection, share, "init", "--share-stdin", "--json");
                status = RemoteVault.answer(result, RemoteVault.OPEN);
            } else if (previous == null && status == RemoteVault.OTHER_KEY) {
                final String consent =
                        yes
                                ? "yes"
                                : asker.ask(
                                        "This key does not open the vault. Clear it and sign in again? [y/N]");
                if (!"yes".equalsIgnoreCase(consent) && !"y".equalsIgnoreCase(consent)) {
                    spec.commandLine()
                            .getErr()
                            .println(
                                    "porelo: the vault was kept; --yes clears it, creates it with this key, and requires all sign-ins again");
                    spec.commandLine().getErr().flush();
                    return report(status, result.standardError());
                }
                result = remote(connection, null, "clear", "--yes", "--force", "--json");
                RemoteVault.answer(result, RemoteVault.CLEARED);
                result = remote(connection, share, "init", "--share-stdin", "--json");
                status = RemoteVault.answer(result, RemoteVault.OPEN);
                if (status == RemoteVault.OPEN) {
                    final PrintWriter notice =
                            json ? spec.commandLine().getErr() : spec.commandLine().getOut();
                    notice.println(
                            "The vault was created with this key; all sign-ins must be made once more.");
                    notice.flush();
                }
            }
            return report(status, result.standardError());
        } finally {
            if (share != null) {
                Arrays.fill(share, (byte) 0);
            }
            if (old != null) Arrays.fill(old, (byte) 0);
            if (input != null) Arrays.fill(input, (byte) 0);
            Files.deleteIfExists(pinned);
        }
    }

    private int report(RemoteVault status, String error) {
        final var out = spec.commandLine().getOut();
        final var err = spec.commandLine().getErr();
        if (json)
            out.println(org.fuin.porelo.wire.Json.write(java.util.Map.of("vault", status.value)));
        else if (status == RemoteVault.OPEN) out.println("unlocked  host vault held by porelod");
        if (status == RemoteVault.NOT_HOST)
            err.println("porelo: This is not a host vault; it has passphrase or other slots");
        if (status == RemoteVault.ABSENT)
            err.println(
                    "porelo: there is no host vault; unlock without --previous-key to initialize it");
        if (status == RemoteVault.OTHER_KEY && previousKey != null)
            err.println(
                    "porelo: the previous key does not open this host vault; nothing was changed");
        if (!error.isBlank()) err.println(clean(error));
        out.flush();
        err.flush();
        return status.exit;
    }

    private static String clean(String message) {
        return message.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "");
    }

    private enum RemoteVault {
        OPEN("open", 0),
        ABSENT("absent", 66),
        OTHER_KEY("other-key", 77),
        NOT_HOST("not-a-host-vault", 65),
        CLEARED("cleared", 0);

        private final String value;
        private final int exit;

        RemoteVault(String value, int exit) {
            this.value = value;
            this.exit = exit;
        }

        static RemoteVault answer(org.fuin.porelo.core.process.CommandResult result) {
            return answer(result, OPEN, ABSENT, OTHER_KEY, NOT_HOST);
        }

        static RemoteVault answer(
                org.fuin.porelo.core.process.CommandResult result, RemoteVault... expected) {
            for (var status : expected) {
                if (status == CLEARED) {
                    if (result.exitCode() == 0 && cleared(result.standardOutput())) return CLEARED;
                    continue;
                }
                if (result.exitCode() == status.exit
                        && result.standardOutput()
                                .matches(
                                        "\\{[ \\t]*\"vault\"[ \\t]*:[ \\t]*\""
                                                + status.value
                                                + "\"[ \\t]*\\}\\r?\\n?")) return status;
            }
            throw new VaultException(
                    "Remote porelo returned an invalid vault answer: "
                            + clean(result.standardError()));
        }

        private static boolean cleared(String output) {
            if (!output.matches("[^\\r\\n]+\\r?\\n?")) return false;
            final Object parsed;
            try {
                parsed = org.fuin.porelo.wire.Json.parse(output);
            } catch (org.fuin.porelo.wire.JsonException ex) {
                return false;
            }
            return parsed instanceof java.util.Map<?, ?> answer
                    && answer.keySet().equals(java.util.Set.of("items", "refused", "kept"))
                    && Boolean.FALSE.equals(answer.get("refused"))
                    && "".equals(answer.get("kept"))
                    && answer.get("items") instanceof List<?> items
                    && items.stream()
                            .allMatch(
                                    item ->
                                            item instanceof java.util.Map<?, ?> entry
                                                    && ("REMOVED".equals(entry.get("status"))
                                                            || "FOR_A_PERSON"
                                                                    .equals(entry.get("status"))));
        }
    }

    private org.fuin.porelo.core.process.CommandResult remote(
            List<String> connection, byte @Nullable [] share, String... arguments) {
        final var command = new java.util.ArrayList<>(connection);
        command.add(remotePorelo);
        command.add("vault");
        command.addAll(List.of(arguments));
        return context.runner()
                .run(
                        share == null
                                ? Command.of(command)
                                : Command.of(command).withBinaryInput(share));
    }
}
