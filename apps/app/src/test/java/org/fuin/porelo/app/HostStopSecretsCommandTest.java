package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/** Tests for {@link HostStopSecretsCommand} ({@code porelo host stop-secrets [--stdin]}). */
class HostStopSecretsCommandTest {

    private static final String VAULT_PASSPHRASE = "correct-horse-battery-staple";

    private static final String KEY_SECRET = "glpat-secret-deploy-token";

    @TempDir Path dir;

    private java.util.function.Consumer<org.fuin.porelo.core.process.Command> conversion =
            command -> {};

    private final FakeCommandRunner runner =
            new FakeCommandRunner() {
                @Override
                public org.fuin.porelo.core.process.CommandResult run(
                        org.fuin.porelo.core.process.Command command) {
                    if (command.program().equals("ssh-keygen")) conversion.accept(command);
                    return super.run(command);
                }
            };

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private PoreloContext context;

    @AfterEach
    void tearDown() {
        PoreloContext.forgetAsked();
        if (context != null) {
            try {
                VaultLockCommand.forget(context);
            } catch (final RuntimeException ignored) {
                // Nothing cached.
            }
        }
    }

    private static String b64(final String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private CommandLine cli(
            final String stdinText,
            final boolean stdinIsTerminal,
            final boolean interactiveTerminal,
            final List<String> prompts,
            final List<String> retries) {
        final Deque<String> promptQueue = new ArrayDeque<>(prompts);
        final Deque<String> retryQueue = new ArrayDeque<>(retries);
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir.resolve("home"));
        context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        args -> 0,
                        name -> null,
                        false,
                        question ->
                                promptQueue.isEmpty()
                                        ? null
                                        : promptQueue.removeFirst().toCharArray(),
                        new StandardInput(
                                new ByteArrayInputStream(
                                        stdinText.getBytes(StandardCharsets.UTF_8)),
                                stdinIsTerminal));
        final CommandLine line = PoreloCli.commandLine(context);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        final HostStopSecretsCommand command =
                (HostStopSecretsCommand)
                        line.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("stop-secrets")
                                .getCommand();
        command.cache = passphrase -> true;
        command.terminal = () -> interactiveTerminal;
        command.retryReader = () -> retryQueue.isEmpty() ? null : retryQueue.removeFirst();
        return line;
    }

    @Test
    void stdinInitializesVaultAndStoresKeysWithoutEchoingOrSpawningProcesses() {
        final String input =
                "vault-new " + b64(VAULT_PASSPHRASE) + "\nkey anthropic " + b64(KEY_SECRET) + "\n";

        final int exit =
                cli(input, false, false, List.of(), List.of())
                        .execute("host", "stop-secrets", "--stdin", "vault-new", "keys");

        assertThat(exit).as(err.toString()).isZero();
        assertThat(out.toString()).isEqualTo("Your stop is done.\n");
        assertThat(err.toString()).isEmpty();
        assertThat(out.toString() + err.toString())
                .as("no secret ever appears in output")
                .doesNotContain(
                        VAULT_PASSPHRASE, b64(VAULT_PASSPHRASE), KEY_SECRET, b64(KEY_SECRET));
        assertThat(runner.invocations()).as("no external command is spawned").isEmpty();
        final Map<String, VaultEntry> entries =
                context.vault().read(VaultFile.Opener.passphrase(VAULT_PASSPHRASE.toCharArray()));
        assertThat(entries).containsKey("anthropic");
        assertThat(entries.get("anthropic").value()).isEqualTo(KEY_SECRET);
    }

    @Test
    void stdinUnlocksExistingVaultAndStoresKeys() {
        // Prepare an existing shut vault first.
        cli("", false, false, List.of(), List.of());
        context.vault().write(Map.of(), VAULT_PASSPHRASE.toCharArray());
        final String input =
                "vault " + b64(VAULT_PASSPHRASE) + "\nkey github.token " + b64(KEY_SECRET) + "\n";

        final int exit =
                cli(input, false, false, List.of(), List.of())
                        .execute("host", "stop-secrets", "--stdin", "vault", "keys");

        assertThat(exit).as(err.toString()).isZero();
        assertThat(out.toString()).isEqualTo("Your stop is done.\n");
        final Map<String, VaultEntry> entries =
                context.vault().read(VaultFile.Opener.passphrase(VAULT_PASSPHRASE.toCharArray()));
        assertThat(entries).containsKey("github.token");
        assertThat(entries.get("github.token").value()).isEqualTo(KEY_SECRET);
    }

    @Test
    void stdinChecksEveryLineBeforeUsingAnySecretSoABadSecondLineLeavesNoVaultCreated() {
        final String input =
                "vault-new " + b64(VAULT_PASSPHRASE) + "\nkey anthropic not-valid-base64!\n";

        final int exit =
                cli(input, false, false, List.of(), List.of())
                        .execute("host", "stop-secrets", "--stdin", "vault-new", "keys");

        assertThat(exit).as("invalid second line exits 64").isEqualTo(64);
        assertThat(context.vault().exists())
                .as("vault-new must not run when a later line is invalid")
                .isFalse();
        assertThat(out.toString() + err.toString())
                .doesNotContain(VAULT_PASSPHRASE, b64(VAULT_PASSPHRASE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/w==", "IA==", "LS0tLS1CRUdJTiBPUEVOU1NIIFBSSVZBVEUgS0VZLS0tLS0="})
    void everyDecodedKeyIsCheckedBeforeTheVaultIsCreated(String encoded) {
        final String input =
                "vault-new " + b64(VAULT_PASSPHRASE) + "\nkey deploy " + encoded + "\n";
        final int exit =
                cli(input, false, false, List.of(), List.of())
                        .execute("host", "stop-secrets", "--stdin", "vault-new", "keys");
        assertThat(exit).isNotZero();
        assertThat(context.vault().exists())
                .as("no passphrase used before every key is checked")
                .isFalse();
        assertThat(out.toString() + err.toString()).doesNotContain(VAULT_PASSPHRASE, encoded);
    }

    @Test
    void aPipedTokenStoresOnlyItsFirstLineAsVaultPutDoes() {
        final String input =
                "vault-new "
                        + b64(VAULT_PASSPHRASE)
                        + "\nkey deploy "
                        + b64(KEY_SECRET + "\nignored")
                        + "\n";
        assertThat(
                        cli(input, false, false, List.of(), List.of())
                                .execute("host", "stop-secrets", "--stdin", "vault-new", "keys"))
                .isZero();
        assertThat(
                        context.vault()
                                .read(VaultFile.Opener.passphrase(VAULT_PASSPHRASE.toCharArray()))
                                .get("deploy")
                                .value())
                .isEqualTo(KEY_SECRET);
    }

    @Test
    void anUnlockThatCannotCacheRefusesBeforeStoringKeys() {
        final CommandLine line =
                cli(
                        "vault " + b64(VAULT_PASSPHRASE) + "\nkey deploy " + b64(KEY_SECRET) + "\n",
                        false,
                        false,
                        List.of(),
                        List.of());
        context.vault().write(Map.of(), VAULT_PASSPHRASE.toCharArray());
        final HostStopSecretsCommand command =
                (HostStopSecretsCommand)
                        line.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("stop-secrets")
                                .getCommand();
        command.cache = passphrase -> false;
        assertThat(line.execute("host", "stop-secrets", "--stdin", "vault", "keys")).isEqualTo(65);
        assertThat(
                        context.vault()
                                .read(VaultFile.Opener.passphrase(VAULT_PASSPHRASE.toCharArray())))
                .isEmpty();
        assertThat(out.toString() + err.toString()).doesNotContain(VAULT_PASSPHRASE, KEY_SECRET);
    }

    @Test
    void aPassphraseOnStdinUsesTheSameFirstLineAsVaultInit() {
        final String input = "vault-new " + b64(VAULT_PASSPHRASE + "\nignored") + "\n";
        assertThat(
                        cli(input, false, false, List.of(), List.of())
                                .execute("host", "stop-secrets", "--stdin", "vault-new"))
                .isZero();
        assertThat(context.vault().accepts(VAULT_PASSPHRASE.toCharArray())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "vault-new",
                "key deploy !!!",
                "vault-new YQ==\nvault-new Yg==",
                "key deploy YQ==\nkey deploy Yg==",
                "key /private YQ==",
                "key deploy YQ==\nvault Yg=="
            })
    void malformedOrUnexpectedStdinNeverCreatesAVault(String input) {
        assertThat(
                        cli(input, false, false, List.of(), List.of())
                                .execute("host", "stop-secrets", "--stdin", "vault-new", "keys"))
                .isEqualTo(64);
        assertThat(context.vault().exists()).isFalse();
    }

    @Test
    void stdinAtATerminalIsRefusedBeforeReadingAnything() {
        assertThat(
                        cli("vault-new " + b64(VAULT_PASSPHRASE), true, false, List.of(), List.of())
                                .execute("host", "stop-secrets", "--stdin", "vault-new"))
                .isEqualTo(64);
        assertThat(context.vault().exists()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void openSshRsaKeysUseVaultPutsPrivateConversionWithoutLeakingOnFailure(boolean succeeds)
            throws Exception {
        final Path source = dir.resolve("source");
        assertThat(
                        new ProcessBuilder(
                                        "ssh-keygen",
                                        "-q",
                                        "-t",
                                        "rsa",
                                        "-b",
                                        "2048",
                                        "-N",
                                        "",
                                        "-f",
                                        source.toString())
                                .start()
                                .waitFor())
                .isZero();
        final String original = java.nio.file.Files.readString(source);
        assertThat(
                        new ProcessBuilder(
                                        "ssh-keygen",
                                        "-q",
                                        "-p",
                                        "-m",
                                        "PEM",
                                        "-N",
                                        "",
                                        "-P",
                                        "",
                                        "-f",
                                        source.toString())
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .isZero();
        final String pem = java.nio.file.Files.readString(source);
        final java.util.List<Path> converted = new java.util.ArrayList<>();
        conversion =
                command -> {
                    final Path copy = Path.of(command.arguments().getLast());
                    converted.add(copy);
                    try {
                        assertThat(java.nio.file.Files.getPosixFilePermissions(copy))
                                .isEqualTo(
                                        java.nio.file.attribute.PosixFilePermissions.fromString(
                                                "rw-------"));
                        assertThat(java.nio.file.Files.getPosixFilePermissions(copy.getParent()))
                                .isEqualTo(
                                        java.nio.file.attribute.PosixFilePermissions.fromString(
                                                "rwx------"));
                        assertThat(java.nio.file.Files.readString(copy)).isEqualTo(original);
                        java.nio.file.Files.writeString(copy, pem);
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                };
        if (!succeeds) runner.failing("ssh-keygen", 1, original);
        final String input =
                "vault-new " + b64(VAULT_PASSPHRASE) + "\nkey deploy " + b64(original) + "\n";
        final int code =
                cli(input, false, false, List.of(), List.of())
                        .execute("host", "stop-secrets", "--stdin", "vault-new", "keys");
        assertThat(code).isEqualTo(succeeds ? 0 : 65);
        assertThat(converted)
                .hasSize(1)
                .allSatisfy(copy -> assertThat(copy.getParent()).doesNotExist());
        assertThat(out.toString() + err.toString())
                .doesNotContain(original, pem, b64(original), VAULT_PASSPHRASE);
        assertThat(runner.lines())
                .noneMatch(line -> line.contains(b64(original)) || line.contains(VAULT_PASSPHRASE));
        if (succeeds) {
            final VaultEntry stored =
                    context.vault()
                            .read(VaultFile.Opener.passphrase(VAULT_PASSPHRASE.toCharArray()))
                            .get("deploy");
            assertThat(stored.type()).isEqualTo("ssh-key");
            assertThat(
                            org.fuin.porelo.vault.StoredKey.of(stored.value(), "")
                                    .authorizedKeysLine()
                                    .strip())
                    .isEqualTo(
                            org.fuin.porelo.vault.StoredKey.of(pem, "")
                                    .authorizedKeysLine()
                                    .strip());
        } else assertThat(context.vault().exists()).isFalse();
    }

    @Test
    void anOversizedStdinBatchIsRefusedBeforeCreatingAVault() {
        final String input =
                "vault-new " + b64(VAULT_PASSPHRASE) + "\nkey deploy " + "YWFh".repeat(270000);
        assertThat(
                        cli(input, false, false, List.of(), List.of())
                                .execute("host", "stop-secrets", "--stdin", "vault-new", "keys"))
                .isEqualTo(64);
        assertThat(context.vault().exists()).isFalse();
    }

    @Test
    void aDamagedVaultRefusesWithoutSavingSecretsInAFailureLog() throws Exception {
        final CommandLine line =
                cli("vault " + b64(VAULT_PASSPHRASE), false, false, List.of(), List.of());
        java.nio.file.Files.createDirectories(context.vault().path().getParent());
        java.nio.file.Files.writeString(context.vault().path(), "damaged");
        assertThat(line.execute("host", "stop-secrets", "--stdin", "vault")).isEqualTo(65);
        assertThat(out.toString() + err.toString())
                .doesNotContain(VAULT_PASSPHRASE, b64(VAULT_PASSPHRASE));
        try (var files = java.nio.file.Files.walk(dir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .doesNotContain("failures.log");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wireguard", "forge-certificate", "identities", "drive"})
    void retiredNeedsAreRefusedWithExit64(final String retired) {
        final int exit =
                cli("", false, true, List.of(), List.of())
                        .execute("host", "stop-secrets", "--stdin", retired);

        assertThat(exit).isEqualTo(64);
        assertThat(err.toString()).contains(retired + " is no need of the host any more");
    }

    @Test
    void refusesAtOnceWithExit69WithoutATerminalAndWithoutStdin() {
        final int exit =
                cli("", false, false, List.of(), List.of())
                        .execute("host", "stop-secrets", "vault-new");

        assertThat(exit).as("without terminal and without --stdin exits 69").isEqualTo(69);
        assertThat(err.toString()).contains("interactive terminal");
    }

    @Test
    void interactiveTerminalRetriesMismatchedPassphraseAndCompletesOrCancelsWithExit75() {
        final int successExit =
                cli(
                                "",
                                false,
                                true,
                                List.of(
                                        "first-try",
                                        "mismatch",
                                        VAULT_PASSPHRASE,
                                        VAULT_PASSPHRASE),
                                List.of("y"))
                        .execute("host", "stop-secrets", "vault-new");

        assertThat(successExit).as(err.toString()).isZero();
        assertThat(context.vault().exists()).isTrue();
        assertThat(out.toString())
                .contains("The host's stop is done; close this terminal, and setup goes on.");
        assertThat(out.toString() + err.toString()).doesNotContain(VAULT_PASSPHRASE);

        PoreloContext.forgetAsked();
        VaultLockCommand.forget(context);
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final int cancelExit =
                cli("", false, true, List.of("wrong-passphrase"), List.of("n"))
                        .execute("host", "stop-secrets", "vault");

        assertThat(cancelExit).as("declining retry exits 75").isEqualTo(75);
        assertThat(err.toString())
                .contains(
                        "Cancelled; the host waits at this stop. Running up again comes back here.");
    }
}
