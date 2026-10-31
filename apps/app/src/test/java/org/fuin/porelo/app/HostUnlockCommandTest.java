package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.HostVaultShare;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshAgentServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Guards the local key and the share against disclosure to a remote command line. */
class HostUnlockCommandTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"yes", "no", "absent"})
    void resetRequiresTerminalConsentAndSupportsAnAccountExecutable(
            String consent, @TempDir Path dir) throws Exception {
        final SigningKey person = SigningKey.generate("person");
        final SigningKey host = SigningKey.generate("host");
        final FakeCommandRunner runner =
                new FakeCommandRunner()
                        .answering(
                                "ssh-keyscan",
                                "example.org ssh-ed25519 "
                                        + Base64.getEncoder().encodeToString(host.keyBlob())
                                        + "\n")
                        .failing("vault unlock", 77, "{\"vault\":\"other-key\"}", "")
                        .answering("vault clear", cleared())
                        .answering("vault init", "{\"vault\":\"open\"}\n");
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0,
                        name ->
                                name.equals("SSH_AUTH_SOCK")
                                        ? dir.resolve("agent.sock").toString()
                                        : null);
        final CommandLine cli = PoreloCli.commandLine(context);
        final var output = new StringWriter();
        cli.setOut(new PrintWriter(output));
        final HostUnlockCommand unlock =
                (HostUnlockCommand)
                        cli.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("unlock")
                                .getCommand();
        unlock.identities = socket -> List.of(person);
        unlock.asker = question -> consent.equals("absent") ? null : consent;
        final int exit =
                cli.execute(
                        "host",
                        "unlock",
                        "example.org",
                        "--login",
                        "alice",
                        "--host-key",
                        fingerprint(host),
                        "--remote-porelo",
                        "~/.local/bin/porelo",
                        "--json");
        assertThat(exit)
                .as("only a yes on the terminal permits reset")
                .isEqualTo(consent.equals("yes") ? 0 : 77);
        assertThat(org.fuin.porelo.wire.Json.parse(output.toString()))
                .as("JSON preserves the other-key status")
                .isEqualTo(java.util.Map.of("vault", consent.equals("yes") ? "open" : "other-key"));
        final var ssh =
                runner.invocations().stream().filter(c -> c.program().equals("ssh")).toList();
        assertThat(ssh)
                .as("the account executable is used for each remote operation")
                .allSatisfy(c -> assertThat(c.arguments()).contains("~/.local/bin/porelo"));
        assertThat(ssh.size())
                .as("a declined reset sends no destructive command")
                .isEqualTo(consent.equals("yes") ? 3 : 1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "66,false,init",
        "0,false,unlock",
        "77,false,refuse",
        "77,true,reset"
    })
    void followsTheRemoteVaultOutcomeWithoutAnUnapprovedReset(
            int answer, boolean yes, String expected, @TempDir Path dir) throws Exception {
        final SigningKey person = SigningKey.generate("person");
        final SigningKey host = SigningKey.generate("host");
        final var commands = new java.util.ArrayList<org.fuin.porelo.core.process.Command>();
        final FakeCommandRunner runner =
                new FakeCommandRunner() {
                    @Override
                    public org.fuin.porelo.core.process.CommandResult run(
                            org.fuin.porelo.core.process.Command command) {
                        if (!command.program().equals("ssh")) return super.run(command);
                        commands.add(command);
                        final int code = commands.size() == 1 ? answer : 0;
                        return new org.fuin.porelo.core.process.CommandResult(
                                command,
                                code,
                                command.arguments().contains("clear")
                                        ? cleared()
                                        : org.fuin.porelo.wire.Json.write(
                                                        java.util.Map.of(
                                                                "vault",
                                                                code == 66
                                                                        ? "absent"
                                                                        : code == 77
                                                                                ? "other-key"
                                                                                : "open"))
                                                + "\n",
                                "");
                    }
                }.answering(
                        "ssh-keyscan",
                        "example.org ssh-ed25519 "
                                + Base64.getEncoder().encodeToString(host.keyBlob())
                                + "\n");
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0,
                        name ->
                                name.equals("SSH_AUTH_SOCK")
                                        ? dir.resolve("agent.sock").toString()
                                        : null);
        final CommandLine cli = PoreloCli.commandLine(context);
        final var output = new StringWriter();
        final var errors = new StringWriter();
        cli.setOut(new PrintWriter(output));
        cli.setErr(new PrintWriter(errors));
        final HostUnlockCommand unlock =
                (HostUnlockCommand)
                        cli.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("unlock")
                                .getCommand();
        unlock.identities = socket -> List.of(person);
        final var args =
                new java.util.ArrayList<>(
                        List.of(
                                "host",
                                "unlock",
                                "example.org",
                                "--login",
                                "alice",
                                "--host-key",
                                fingerprint(host)));
        if (yes) args.add("--yes");
        final int exit = cli.execute(args.toArray(String[]::new));
        assertThat(exit)
                .as("only refusal keeps exit 77")
                .isEqualTo(expected.equals("refuse") ? 77 : 0);
        final List<String> verbs =
                commands.stream()
                        .map(c -> c.arguments().get(c.arguments().indexOf("vault") + 1))
                        .toList();
        assertThat(verbs)
                .as("the remote outcome selects the operation and reset requires consent")
                .containsExactlyElementsOf(
                        switch (expected) {
                            case "init" -> List.of("unlock", "init");
                            case "reset" -> List.of("unlock", "clear", "init");
                            default -> List.of("unlock");
                        });
        if (expected.equals("refuse"))
            assertThat(errors.toString())
                    .as("scripts can explicitly authorize loss")
                    .contains("--yes", "clear");
        if (expected.equals("reset")) {
            assertThat(output.toString()).as("reset requires new sign-ins").contains("sign-ins");
            assertThat(commands.get(1).arguments())
                    .as("clear must answer a machine-readable status")
                    .endsWith("vault", "clear", "--yes", "--force", "--json");
        }
    }

    private static String cleared() {
        return org.fuin.porelo.wire.Json.write(
                        VaultClearCommand.asJson(
                                new Clearing.Result(
                                        List.of(
                                                new Clearing.Item(
                                                        "vault",
                                                        "vault.bin",
                                                        Clearing.Status.REMOVED,
                                                        "")),
                                        List.of(),
                                        "",
                                        false)))
                + "\n";
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"selected", "ambiguous", "unknown"})
    void selectsOnlyTheRequestedEd25519KeyAndRefusesAmbiguousKeys(String choice, @TempDir Path dir)
            throws Exception {
        final SigningKey first = SigningKey.generate("first");
        final SigningKey second = SigningKey.generate("second");
        final SigningKey host = SigningKey.generate("host");
        final var sent = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        final FakeCommandRunner runner =
                new FakeCommandRunner() {
                    @Override
                    public org.fuin.porelo.core.process.CommandResult run(
                            org.fuin.porelo.core.process.Command command) {
                        if (command.program().equals("ssh")) {
                            sent.set(command.binaryInput().clone());
                        }
                        return super.run(command);
                    }
                }.answering("vault unlock", "{\"vault\":\"open\"}\n")
                        .answering(
                                "ssh-keyscan",
                                "example.org ssh-ed25519 "
                                        + Base64.getEncoder().encodeToString(host.keyBlob())
                                        + "\n");
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0,
                        name ->
                                name.equals("SSH_AUTH_SOCK")
                                        ? dir.resolve("unused.sock").toString()
                                        : null);
        final CommandLine cli = PoreloCli.commandLine(context);
        final HostUnlockCommand command =
                (HostUnlockCommand)
                        cli.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("unlock")
                                .getCommand();
        command.identities = socket -> List.of(first, second);
        final var arguments =
                new java.util.ArrayList<>(
                        List.of(
                                "host",
                                "unlock",
                                "example.org",
                                "--login",
                                "alice",
                                "--host-key",
                                fingerprint(host)));
        if (!choice.equals("ambiguous")) {
            arguments.addAll(
                    List.of(
                            "--key",
                            choice.equals("selected")
                                    ? fingerprint(second)
                                    : fingerprint(SigningKey.generate("unknown"))));
        }
        final int exit = cli.execute(arguments.toArray(String[]::new));
        if (choice.equals("selected")) {
            assertThat(exit)
                    .as("--key selects one key even when several Ed25519 keys are offered")
                    .isZero();
            assertThat(
                            java.util.Arrays.equals(
                                    sent.get(),
                                    HostVaultShare.derive(second, fingerprint(host), "alice")))
                    .as("the selected key alone derives the transported share")
                    .isTrue();
        } else {
            assertThat(exit)
                    .as("an ambiguous or unknown key is an operational refusal")
                    .isNotZero();
            assertThat(sent.get()).as("a refused key sends no share").isNull();
        }
    }

    @Test
    void recognizesHostUnlockWithoutAnAgent(@TempDir Path dir) {
        final StringWriter err = new StringWriter();
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0);
        final CommandLine cli = PoreloCli.commandLine(context);
        cli.setErr(new PrintWriter(err));
        assertThat(
                        cli.execute(
                                "host",
                                "unlock",
                                "example.org",
                                "--login",
                                "alice",
                                "--host-key",
                                "SHA256:" + "A".repeat(43)))
                .as("missing SSH_AUTH_SOCK is an operational refusal, not an unknown command")
                .isEqualTo(69);
    }

    @Test
    void transportsBinaryInputAndErasesItAfterTheRunnerReturns(@TempDir Path dir) throws Exception {
        final SigningKey person = SigningKey.generate("person");
        final SigningKey host = SigningKey.generate("host");
        final String fingerprint = fingerprint(host);
        final java.util.concurrent.atomic.AtomicReference<byte[]> input =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> known =
                new java.util.concurrent.atomic.AtomicReference<>();
        final FakeCommandRunner runner =
                new FakeCommandRunner() {
                    @Override
                    public org.fuin.porelo.core.process.CommandResult run(
                            org.fuin.porelo.core.process.Command command) {
                        if (command.program().equals("ssh")) {
                            input.set(
                                    command.binaryInput() == null
                                            ? null
                                            : command.binaryInput().clone());
                            for (String argument : command.arguments()) {
                                if (argument.startsWith("UserKnownHostsFile=")) {
                                    try {
                                        known.set(
                                                java.nio.file.Files.readString(
                                                        Path.of(
                                                                argument.substring(
                                                                        "UserKnownHostsFile="
                                                                                .length()))));
                                    } catch (java.io.IOException ex) {
                                        throw new AssertionError(ex);
                                    }
                                }
                            }
                        }
                        return super.run(command);
                    }
                }.answering("vault unlock", "{\"vault\":\"open\"}\n")
                        .answering(
                                "ssh-keyscan",
                                "example.org "
                                        + host.authorizedKeysLine().split(" ")[0]
                                        + " "
                                        + Base64.getEncoder().encodeToString(host.keyBlob())
                                        + "\n");
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0,
                        name ->
                                name.equals("SSH_AUTH_SOCK")
                                        ? dir.resolve("agent.sock").toString()
                                        : null);
        final CommandLine cli = PoreloCli.commandLine(context);
        final HostUnlockCommand unlock =
                (HostUnlockCommand)
                        cli.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("unlock")
                                .getCommand();
        unlock.identities = socket -> List.of(person);
        assertThat(
                        cli.execute(
                                "host",
                                "unlock",
                                "example.org",
                                "--login",
                                "alice",
                                "--host-key",
                                fingerprint))
                .as("unlock sends through the runner")
                .isZero();
        assertThat(input.get())
                .as("exactly the 32 binary bytes derived for the host account")
                .isEqualTo(HostVaultShare.derive(person, fingerprint, "alice"));
        final var ssh =
                runner.invocations().stream()
                        .filter(c -> c.program().equals("ssh"))
                        .findFirst()
                        .orElseThrow();
        assertThat(ssh.arguments())
                .as("the pin is enforced on a fresh connection with no forwarding")
                .contains(
                        "-T",
                        "ForwardAgent=no",
                        "ClearAllForwardings=yes",
                        "StrictHostKeyChecking=yes",
                        "ControlPath=none")
                .endsWith(
                        "alice@example.org",
                        "porelo",
                        "vault",
                        "unlock",
                        "--share-stdin",
                        "--json");
        final Path sshConfig = dir.resolve("ssh_config");
        java.nio.file.Files.writeString(
                sshConfig, "Host *\n KnownHostsCommand /bin/true\n VerifyHostKeyDNS yes\n");
        final java.util.List<String> effectiveArguments =
                new java.util.ArrayList<>(List.of("ssh", "-G", "-F", sshConfig.toString()));
        effectiveArguments.addAll(
                ssh.arguments().subList(1, ssh.arguments().indexOf("alice@example.org") + 1));
        final var baseline =
                new org.fuin.porelo.core.process.ProcessCommandRunner()
                        .run(
                                org.fuin.porelo.core.process.Command.of(
                                        "ssh", "-G", "-F", sshConfig.toString(), "example.org"));
        assertThat(baseline.standardOutput())
                .as("the fixture really enables additional trust")
                .contains("knownhostscommand /bin/true", "verifyhostkeydns true");
        final var effective =
                new org.fuin.porelo.core.process.ProcessCommandRunner()
                        .run(org.fuin.porelo.core.process.Command.of(effectiveArguments));
        assertThat(effective.standardOutput())
                .as("inherited trust sources cannot accept another host key")
                .doesNotContain("knownhostscommand /bin/true")
                .contains("verifyhostkeydns false");
        assertThat(ssh.binaryInput())
                .as("the sender clears its borrowed input")
                .containsOnly((byte) 0);
        assertThat(ssh.describe())
                .as("neither share nor signature is an argument")
                .doesNotContain(Base64.getEncoder().encodeToString(input.get()));
        assertThat(known.get())
                .as("only the matching public host key is trusted")
                .isEqualTo(
                        "example.org ssh-ed25519 "
                                + Base64.getEncoder().encodeToString(host.keyBlob())
                                + "\n");
        final String pin =
                ssh.arguments().stream()
                        .filter(a -> a.startsWith("UserKnownHostsFile="))
                        .findFirst()
                        .orElseThrow()
                        .substring("UserKnownHostsFile=".length());
        assertThat(java.nio.file.Files.exists(Path.of(pin)))
                .as("the temporary public pin is removed")
                .isFalse();
    }

    @Test
    void refusesAChangedHostBeforeAskingTheLocalKey(@TempDir Path dir) {
        final SigningKey other = SigningKey.generate("other host");
        final FakeCommandRunner runner =
                new FakeCommandRunner()
                        .answering(
                                "ssh-keyscan",
                                "example.org ssh-ed25519 "
                                        + Base64.getEncoder().encodeToString(other.keyBlob())
                                        + "\n");
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0,
                        name ->
                                name.equals("SSH_AUTH_SOCK")
                                        ? dir.resolve("agent.sock").toString()
                                        : null);
        final CommandLine cli = PoreloCli.commandLine(context);
        final HostUnlockCommand unlock =
                (HostUnlockCommand)
                        cli.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("unlock")
                                .getCommand();
        unlock.identities =
                socket -> {
                    throw new AssertionError("an untrusted host must never ask the key");
                };
        assertThat(
                        cli.execute(
                                "host",
                                "unlock",
                                "example.org",
                                "--login",
                                "alice",
                                "--host-key",
                                "SHA256:" + "A".repeat(43)))
                .as("changed host is refused")
                .isEqualTo(70);
        assertThat(runner.invocations())
                .as("no SSH session carries a share")
                .noneMatch(c -> c.program().equals("ssh"));
    }

    private static String fingerprint(SigningKey key) throws Exception {
        return "SHA256:"
                + Base64.getEncoder()
                        .withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(key.keyBlob()));
    }

    @Test
    void sendsOnlyTheDerivedBytesToStandardInput(@TempDir Path dir) throws Exception {
        final SigningKey person = SigningKey.generate("person");
        final SigningKey host = SigningKey.generate("host");
        final String fingerprint =
                "SHA256:"
                        + Base64.getEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(host.keyBlob()));
        final java.util.concurrent.atomic.AtomicReference<byte[]> input =
                new java.util.concurrent.atomic.AtomicReference<>();
        final FakeCommandRunner runner =
                new FakeCommandRunner() {
                    @Override
                    public org.fuin.porelo.core.process.CommandResult run(
                            org.fuin.porelo.core.process.Command command) {
                        if (command.binaryInput() != null) {
                            input.set(command.binaryInput().clone());
                        }
                        return super.run(command);
                    }
                }.answering("vault unlock", "{\"vault\":\"open\"}\n")
                        .answering(
                                "ssh-keyscan",
                                "example.org ssh-ed25519 "
                                        + Base64.getEncoder().encodeToString(host.keyBlob())
                                        + "\n");
        try (SshAgentServer server =
                new SshAgentServer(dir.resolve("agent.sock"), List.of(person))) {
            Thread.ofVirtual().start(server);
            final PoreloContext context =
                    new PoreloContext(
                            runner,
                            new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                            args -> 0,
                            name ->
                                    name.equals("SSH_AUTH_SOCK")
                                            ? server.socketPath().toString()
                                            : null);
            final CommandLine cli = PoreloCli.commandLine(context);
            assertThat(
                            cli.execute(
                                    "host",
                                    "unlock",
                                    "example.org",
                                    "--login",
                                    "alice",
                                    "--host-key",
                                    fingerprint))
                    .as("the remote unlock succeeds")
                    .isZero();
            final var ssh =
                    runner.invocations().stream()
                            .filter(c -> c.program().equals("ssh"))
                            .findFirst()
                            .orElseThrow();
            assertThat(ssh.arguments())
                    .as("no agent forwarding, terminal, share or signature in argv")
                    .contains(
                            "-T",
                            "ForwardAgent=no",
                            "alice@example.org",
                            "porelo",
                            "vault",
                            "unlock",
                            "--share-stdin")
                    .doesNotContain("-A", "-t");
            final byte[] expected = HostVaultShare.derive(person, fingerprint, "alice");
            assertThat(input.get())
                    .as("exactly the share, not its text encoding")
                    .isEqualTo(expected);
            assertThat(ssh.binaryInput())
                    .as("the sender erases its input after SSH returns")
                    .containsOnly((byte) 0);
            assertThat(ssh.describe())
                    .as("argv never contains the share")
                    .doesNotContain(Base64.getEncoder().encodeToString(expected));
        }
    }
}
