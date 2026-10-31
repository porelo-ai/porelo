package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.HostVaultShare;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Remote statuses authorize creation or reset only when both the JSON and status agree. */
class HostUnlockReplyTest {
    @ParameterizedTest
    @CsvSource({"0,garbage", "1,valid", "0,refused", "0,not-removed"})
    void anUnverifiedClearNeverReceivesTheShareAgain(int code, String reply, @TempDir Path dir)
            throws Exception {
        final var scenario = new Scenario(dir);
        scenario.remote =
                command -> {
                    if (command.arguments().contains("unlock"))
                        return new CommandResult(command, 77, "{\"vault\":\"other-key\"}\n", "");
                    if (command.arguments().contains("clear"))
                        return new CommandResult(
                                command,
                                code,
                                switch (reply) {
                                    case "garbage" -> "unexpected output\n";
                                    case "refused" ->
                                            "{\"items\":[],\"refused\":true,\"kept\":\"locked\"}\n";
                                    case "not-removed" ->
                                            "{\"items\":[{\"status\":\"NOT_REMOVED\"}],\"refused\":false,\"kept\":\"\"}\n";
                                    default -> "{\"items\":[],\"refused\":false,\"kept\":\"\"}\n";
                                },
                                "clear\u001b[31m failed\u0007\r\n");
                    return new CommandResult(command, 0, "{\"vault\":\"open\"}\n", "");
                };
        assertThat(scenario.execute("--yes", "--json"))
                .as("only a verified clearing permits sending the share a second time")
                .isEqualTo(70);
        assertThat(scenario.sent).as("no init follows an unverified clear").hasSize(2);
        assertThat(scenario.sent.getLast().binaryInput()).as("clear receives no share").isNull();
        assertThat(scenario.output.toString()).isEmpty();
        assertThat(scenario.errors.toString())
                .contains("clear", "failed")
                .doesNotContain("\u001b", "\u0007", "\r");
    }

    @ParameterizedTest
    @CsvSource({"66,absent", "77,other-key", "65,not-a-host-vault"})
    void anInitAnswersOnlyThatTheVaultIsOpen(int code, String status, @TempDir Path dir)
            throws Exception {
        // A remote that creates a vault and answers anything else is not believed.
        final var scenario = new Scenario(dir);
        scenario.remote =
                command ->
                        command.arguments().contains("init")
                                ? new CommandResult(
                                        command, code, "{\"vault\":\"" + status + "\"}\n", "")
                                : new CommandResult(command, 66, "{\"vault\":\"absent\"}\n", "");
        assertThat(scenario.execute("--json")).isEqualTo(70);
        assertThat(scenario.output.toString()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"0,empty", "66,garbage", "77,garbage", "0,extra", "0,multiple", "0,other-key"})
    void rejectsUnrecognizedRemoteAnswersBeforeAnySecondCommand(
            int code, String reply, @TempDir Path dir) throws Exception {
        final var scenario = new Scenario(dir);
        scenario.remote =
                command ->
                        new CommandResult(
                                command,
                                code,
                                switch (reply) {
                                    case "empty" -> "";
                                    case "garbage" -> "some other command\n";
                                    case "extra" -> "{\"vault\":\"open\",\"extra\":true}\n";
                                    case "multiple" ->
                                            "{\"vault\":\"open\"}\n{\"vault\":\"open\"}\n";
                                    default -> "{\"vault\":\"other-key\"}\n";
                                },
                                "remote\u001b[31m failure\u0007\r\n");
        assertThat(scenario.execute("--yes", "--json")).isEqualTo(70);
        assertThat(scenario.sent).hasSize(1);
        assertThat(scenario.output.toString()).isEmpty();
        assertThat(scenario.errors.toString())
                .contains("remote", "failure")
                .doesNotContain("\u001b", "\u0007", "\r");
    }

    @Test
    void aValidOtherKeyAnswerKeepsItsExitAndPrintsWords(@TempDir Path dir) throws Exception {
        final var scenario = new Scenario(dir);
        scenario.remote =
                command -> new CommandResult(command, 77, "{\"vault\":\"other-key\"}\n", "");
        assertThat(scenario.execute()).isEqualTo(77);
        assertThat(scenario.sent).hasSize(1);
        assertThat(scenario.output.toString()).doesNotContain("{", "\"vault\"");
        assertThat(scenario.errors.toString()).contains("--yes");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aPassphraseVaultCannotBeResetByHostUnlock(boolean json, @TempDir Path dir)
            throws Exception {
        final var scenario = new Scenario(dir);
        try (var owner = new HostVaultOwner()) {
            final var remote =
                    HostVaultKindTest.context(dir.resolve("remote")).withHostVault(owner);
            remote.vault()
                    .write(
                            Map.of("service", VaultEntry.of("example-value")),
                            "passphrase".toCharArray());
            final byte[] before = Files.readAllBytes(remote.vault().path());
            scenario.remote =
                    command -> {
                        final var cli =
                                PoreloCli.commandLine(
                                        remote.reading(
                                                new StandardInput(
                                                        new ByteArrayInputStream(
                                                                command.binaryInput() == null
                                                                        ? new byte[0]
                                                                        : command.binaryInput()),
                                                        false)));
                        final var out = new StringWriter();
                        final var err = new StringWriter();
                        cli.setOut(new PrintWriter(out));
                        cli.setErr(new PrintWriter(err));
                        final int start = command.arguments().indexOf("vault");
                        final int exit =
                                cli.execute(
                                        command.arguments()
                                                .subList(start, command.arguments().size())
                                                .toArray(String[]::new));
                        return new CommandResult(command, exit, out.toString(), err.toString());
                    };
            assertThat(json ? scenario.execute("--yes", "--json") : scenario.execute("--yes"))
                    .isEqualTo(65);
            assertThat(scenario.sent).hasSize(1);
            assertThat(Files.readAllBytes(remote.vault().path())).isEqualTo(before);
            assertThat(scenario.errors.toString()).contains("passphrase or other slots");
            if (json)
                assertThat(org.fuin.porelo.wire.Json.parse(scenario.output.toString()))
                        .isEqualTo(Map.of("vault", "not-a-host-vault"));
            else assertThat(scenario.output.toString()).doesNotContain("{", "\"vault\"");
        }
    }

    @Test
    void previousKeySendsBothDerivedSharesOnlyToRekey(@TempDir Path dir) throws Exception {
        final var scenario = new Scenario(dir);
        scenario.remote = command -> new CommandResult(command, 0, "{\"vault\":\"open\"}\n", "");
        assertThat(scenario.execute("--previous-key", fingerprint(scenario.previous), "--json"))
                .isZero();
        assertThat(scenario.sent)
                .singleElement()
                .satisfies(
                        command -> {
                            assertThat(command.arguments())
                                    .endsWith("vault", "rekey", "--share-stdin", "--json");
                            assertThat(command.binaryInput()).containsOnly((byte) 0);
                        });
        final byte[] expected = new byte[64];
        System.arraycopy(
                HostVaultShare.derive(scenario.previous, fingerprint(scenario.host), "alice"),
                0,
                expected,
                0,
                32);
        System.arraycopy(
                HostVaultShare.derive(scenario.person, fingerprint(scenario.host), "alice"),
                0,
                expected,
                32,
                32);
        assertThat(java.util.Arrays.equals(scenario.input, expected)).isTrue();
        assertThat(org.fuin.porelo.wire.Json.parse(scenario.output.toString()))
                .isEqualTo(Map.of("vault", "open"));
    }

    @Test
    void rejectedPreviousKeyNeverResetsEvenWithYes(@TempDir Path dir) throws Exception {
        final var scenario = new Scenario(dir);
        scenario.remote =
                command -> new CommandResult(command, 77, "{\"vault\":\"other-key\"}\n", "");
        assertThat(scenario.execute("--previous-key", fingerprint(scenario.previous), "--yes"))
                .isEqualTo(77);
        assertThat(scenario.sent).hasSize(1);
        assertThat(scenario.errors.toString()).doesNotContain("--yes clears");
    }

    private static String fingerprint(SigningKey key) throws Exception {
        return "SHA256:"
                + Base64.getEncoder()
                        .withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(key.keyBlob()));
    }

    private static final class Scenario {
        final SigningKey person = SigningKey.generate("person");
        final SigningKey previous = SigningKey.generate("previous");
        final SigningKey host = SigningKey.generate("host");
        final List<Command> sent = new ArrayList<>();
        final StringWriter output = new StringWriter();
        final StringWriter errors = new StringWriter();
        final picocli.CommandLine cli;
        byte[] input;
        Function<Command, CommandResult> remote;

        Scenario(Path dir) throws Exception {
            final var runner =
                    new FakeCommandRunner() {
                        @Override
                        public CommandResult run(Command command) {
                            if (!command.program().equals("ssh")) return super.run(command);
                            sent.add(command);
                            if (command.binaryInput() != null)
                                input = command.binaryInput().clone();
                            return remote.apply(command);
                        }
                    }.answering(
                            "ssh-keyscan",
                            "example.org ssh-ed25519 "
                                    + Base64.getEncoder().encodeToString(host.keyBlob())
                                    + "\n");
            final var context =
                    new PoreloContext(
                            runner,
                            new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                            args -> 0,
                            name ->
                                    name.equals("SSH_AUTH_SOCK")
                                            ? dir.resolve("agent.sock").toString()
                                            : null);
            cli = PoreloCli.commandLine(context);
            cli.setOut(new PrintWriter(output));
            cli.setErr(new PrintWriter(errors));
            final HostUnlockCommand command =
                    (HostUnlockCommand)
                            cli.getSubcommands()
                                    .get("host")
                                    .getSubcommands()
                                    .get("unlock")
                                    .getCommand();
            command.identities = socket -> List.of(person, previous);
            command.asker = question -> null;
        }

        int execute(String... extra) throws Exception {
            final var args =
                    new ArrayList<>(
                            List.of(
                                    "host",
                                    "unlock",
                                    "example.org",
                                    "--login",
                                    "alice",
                                    "--host-key",
                                    fingerprint(host),
                                    "--key",
                                    fingerprint(person)));
            args.addAll(List.of(extra));
            return cli.execute(args.toArray(String[]::new));
        }
    }
}
