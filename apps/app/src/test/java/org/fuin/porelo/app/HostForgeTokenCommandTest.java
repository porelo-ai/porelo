package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultFile;
import org.fuin.porelo.wire.RootOwnedFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/** Tests for {@link HostForgeTokenCommand} ({@code porelo host forge-token}). */
class HostForgeTokenCommandTest {

    private static final char[] PASSPHRASE = "unit-test-passphrase".toCharArray();

    private static final String ISSUED_TOKEN = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b";

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final AtomicInteger prompts = new AtomicInteger();

    private Path forgeConf;

    private PoreloContext context;

    @BeforeEach
    void setUp() throws Exception {
        forgeConf = dir.resolve("forge.conf");
        Files.writeString(forgeConf, "name=forge.example.org\naccount=porelo-forge\n");
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
                        prompt -> {
                            prompts.incrementAndGet();
                            return PASSPHRASE.clone();
                        },
                        new StandardInput(new ByteArrayInputStream(new byte[0]), false));
    }

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

    private void openVault() {
        context.asking(prompt -> PASSPHRASE.clone())
                .openIfShut(true, true, new PrintWriter(Writer.nullWriter()));
    }

    private static RootOwnedFile.Attributes rootOwnedAttributes() {
        return path -> {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchFileException(path.toString());
            }
            return Map.of(
                    "uid",
                    0,
                    "mode",
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? 0040755 : 0100640);
        };
    }

    private CommandLine cli(final boolean atTerminal) {
        final CommandLine line = PoreloCli.commandLine(context);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        final HostForgeTokenCommand command =
                (HostForgeTokenCommand)
                        line.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("forge-token")
                                .getCommand();
        command.forgeConf = forgeConf;
        command.attributes = rootOwnedAttributes();
        command.taken = path -> path;
        command.found = name -> Path.of("/usr/bin", name);
        command.terminal = () -> atTerminal;
        return line;
    }

    // Optional setup must not mint a token on a host without a configured forge.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ifForgeSkipsAnAbsentOrUnconfiguredForge(boolean absent) throws Exception {
        if (absent) Files.delete(forgeConf);
        else Files.writeString(forgeConf, "name=forge.example.org\naccount=\n");
        assertThat(cli(false).execute("host", "forge-token", "--if-forge")).isZero();
        assertThat(runner.invocations()).isEmpty();
        assertThat(prompts.get()).isZero();
    }

    // Optional setup must not suppress a configured forge's token or trust failures.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ifForgeStillChecksAndUsesAConfiguredForge(boolean unsafe) {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", ISSUED_TOKEN + "\n");
        final var line = cli(false);
        final HostForgeTokenCommand command =
                (HostForgeTokenCommand)
                        line.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("forge-token")
                                .getCommand();
        if (unsafe) command.attributes = path -> Map.of("uid", 1000, "mode", 0100640);
        assertThat(line.execute("host", "forge-token", "--if-forge")).isEqualTo(unsafe ? 69 : 0);
        if (unsafe) assertThat(runner.invocations()).isEmpty();
        else
            assertThat(context.vault().read(VaultFile.Opener.passphrase(PASSPHRASE)))
                    .containsKey("forge.example.org.person");
    }

    @Test
    void sudoRunsFromItsCheckedSystemPath() {
        // A sudo found through PATH can substitute an attacker's forge credential in the vault.
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", ISSUED_TOKEN + "\n");
        final CommandLine line = cli(false);
        final HostForgeTokenCommand command =
                (HostForgeTokenCommand)
                        line.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("forge-token")
                                .getCommand();
        final AtomicReference<String> searched = new AtomicReference<>();
        command.found =
                name -> {
                    searched.set(name);
                    return Path.of("/usr/bin", name);
                };

        assertThat(line.execute("host", "forge-token")).as(err.toString()).isZero();
        assertThat(runner.lines())
                .as("sudo comes from the checked system directory")
                .containsExactly("/usr/bin/sudo -n /usr/sbin/porelo-host forge issue-token");
        assertThat(searched.get()).as("the resolver checks sudo").isEqualTo("sudo");
        assertThat(
                        context.vault()
                                .read(VaultFile.Opener.passphrase(PASSPHRASE))
                                .get("forge.example.org.person")
                                .value())
                .as("the issued token is stored")
                .isEqualTo(ISSUED_TOKEN);
    }

    @Test
    void anUnsafeSudoCannotMintOrReplaceThePersonsToken() {
        // An unsafe sudo must not rotate a working token or supply a replacement credential.
        context.vault()
                .write(
                        Map.of("forge.example.org.person", VaultEntry.of("previous-token")),
                        PASSPHRASE.clone());
        openVault();
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", ISSUED_TOKEN + "\n");
        final CommandLine line = cli(false);
        final HostForgeTokenCommand command =
                (HostForgeTokenCommand)
                        line.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("forge-token")
                                .getCommand();
        command.found =
                name -> {
                    throw new UncheckedIOException(new IOException("writable by group"));
                };

        assertThat(line.execute("host", "forge-token")).as("unsafe sudo refuses").isEqualTo(69);
        assertThat(runner.invocations()).as("nothing can rotate the token").isEmpty();
        assertThat(err.toString())
                .as("names the system program that needs repair")
                .contains("sudo");
        assertThat(
                        context.vault()
                                .read(VaultFile.Opener.passphrase(PASSPHRASE))
                                .get("forge.example.org.person")
                                .value())
                .as("the stored token stays intact")
                .isEqualTo("previous-token");
    }

    @Test
    void aCanonicalHostPathStillRunsByThePathAllowedBySudo() {
        // Resolving a merged /usr/sbin to /usr/bin must not make the authorized command unusable.
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.answering("forge issue-token", ISSUED_TOKEN + "\n");
        final CommandLine line = cli(false);
        final HostForgeTokenCommand command =
                (HostForgeTokenCommand)
                        line.getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("forge-token")
                                .getCommand();
        final AtomicReference<Path> checked = new AtomicReference<>();
        command.taken =
                path -> {
                    checked.set(path);
                    return Path.of("/usr/bin/porelo-host");
                };

        assertThat(line.execute("host", "forge-token")).as(err.toString()).isZero();
        assertThat(checked.get())
                .as("the sudo rule's path is checked before execution")
                .isEqualTo(HostForgeTokenCommand.HOST_PROGRAM);
        assertThat(runner.only("forge issue-token").arguments().get(2))
                .as("sudo receives its authorized path even when its target differs")
                .isEqualTo("/usr/sbin/porelo-host");
        assertThat(
                        context.vault()
                                .read(VaultFile.Opener.passphrase(PASSPHRASE))
                                .get("forge.example.org.person")
                                .value())
                .as("the issued token is stored")
                .isEqualTo(ISSUED_TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stale-passphrase", "damaged-ciphertext"})
    void anUnusableVaultNeverRotatesThePersonsExistingForgeToken(String damage) throws Exception {
        // Rotation revokes the existing forge token, so a vault that cannot open must stop it.
        context.vault()
                .write(
                        Map.of("forge.example.org.person", VaultEntry.of("previous-token")),
                        PASSPHRASE.clone());
        openVault();
        if (damage.equals("stale-passphrase")) {
            context.vault().rekey(PASSPHRASE.clone(), "changed-passphrase".toCharArray());
        } else {
            final byte[] bytes = Files.readAllBytes(context.vault().path());
            bytes[bytes.length - 1] ^= (byte) 0xFF;
            Files.write(context.vault().path(), bytes);
        }
        final byte[] before = Files.readAllBytes(context.vault().path());
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", ISSUED_TOKEN + "\n");

        assertThat(cli(false).execute("host", "forge-token"))
                .as("unusable vault refuses")
                .isEqualTo(65);
        assertThat(runner.invocations())
                .as("no rotation occurs before the vault can open")
                .isEmpty();
        assertThat(Files.readAllBytes(context.vault().path()))
                .as("the vault is preserved")
                .isEqualTo(before);
        assertThat(out.toString() + err.toString())
                .as("no issued credential is exposed")
                .doesNotContain(ISSUED_TOKEN);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMissingVaultNamesItsInitializationBeforeAnyRotation(boolean atTerminal) {
        // Unlock cannot create a missing vault, leaving the person unable to store a replacement
        // token.
        assertThat(cli(atTerminal).execute("host", "forge-token"))
                .as("missing vault refuses")
                .isEqualTo(69);
        assertThat(err.toString())
                .as("names the available way to create the vault")
                .contains("porelo vault init")
                .doesNotContain("porelo vault unlock");
        assertThat(prompts.get()).as("no passphrase can open an absent vault").isZero();
        assertThat(context.vault().exists()).as("the command does not create a vault").isFalse();
        assertThat(runner.invocations()).as("no token is revoked or minted").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aShutHostVaultNamesHostUnlockWithoutAskingForAPassphrase(boolean atTerminal) {
        // A host vault has no passphrase slot, so a passphrase prompt cannot restore token storage.
        context.vault().write(Map.of(), new byte[32]);
        assertThat(cli(atTerminal).execute("host", "forge-token"))
                .as("shut host vault refuses")
                .isEqualTo(69);
        assertThat(err.toString())
                .as("names the host vault's actual unlock command")
                .contains("porelo host unlock <host>")
                .doesNotContain("porelo vault unlock");
        assertThat(prompts.get()).as("a host vault never asks for a passphrase").isZero();
        assertThat(runner.invocations()).as("no token is revoked or minted").isEmpty();
    }

    @Test
    void storesTheIssuedForgeTokenInAnAlreadyOpenVaultWithoutTerminalOrPassphrasePrompt() {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", ISSUED_TOKEN + "\n");

        final int exit = cli(false).execute("host", "forge-token");

        assertThat(exit).as(err.toString()).isZero();
        assertThat(prompts.get()).as("open vault never prompts for a passphrase").isZero();
        assertThat(runner.lines())
                .as("invokes /usr/sbin/porelo-host by its absolute path through sudo -n")
                .containsExactly("/usr/bin/sudo -n /usr/sbin/porelo-host forge issue-token");
        final Map<String, VaultEntry> stored =
                context.vault().read(VaultFile.Opener.passphrase(PASSPHRASE.clone()));
        assertThat(stored).containsKey("forge.example.org.person");
        final VaultEntry entry = stored.get("forge.example.org.person");
        assertThat(entry.value()).isEqualTo(ISSUED_TOKEN);
    }

    @Test
    void opensAShutVaultAtATerminalAndStoresTheIssuedForgeToken() {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", ISSUED_TOKEN + "\n");

        final int exit = cli(true).execute("host", "forge-token");

        assertThat(exit).as(err.toString()).isZero();
        assertThat(prompts.get()).as("shut vault prompts once at a terminal").isEqualTo(1);
        final Map<String, VaultEntry> stored =
                context.vault().read(VaultFile.Opener.passphrase(PASSPHRASE.clone()));
        assertThat(stored).containsKey("forge.example.org.person");
        assertThat(stored.get("forge.example.org.person").value()).isEqualTo(ISSUED_TOKEN);
    }

    @Test
    void refusesWithExit69NamingVaultUnlockWhenTheVaultIsShutAndNoTerminalIsPresent() {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", ISSUED_TOKEN + "\n");

        final int exit = cli(false).execute("host", "forge-token");

        assertThat(exit).as("shut vault without terminal exits 69").isEqualTo(69);
        assertThat(err.toString()).as("names porelo vault unlock").contains("porelo vault unlock");
        assertThat(runner.invocations())
                .as("does not mint or rotate the token when the vault is shut")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sudo: a password is required",
                "sudo: a terminal is required to read the password",
                "user is not in the sudoers file"
            })
    void sudoFailureRefusesWithExit69NamingTheSudoRule(final String sudoStderr) {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.failing("sudo -n /usr/sbin/porelo-host forge issue-token", 1, "", sudoStderr);

        final int exit = cli(false).execute("host", "forge-token");

        assertThat(exit).as("sudo failure exits 69").isEqualTo(69);
        assertThat(err.toString()).as("names the sudo rule").contains("sudo rule");
    }

    @Test
    void commandExceptionWhenRunningSudoRefusesWithExit69NamingTheSudoRule() {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.missing("/usr/bin/sudo");

        final int exit = cli(false).execute("host", "forge-token");

        assertThat(exit).as("missing or failed sudo exits 69").isEqualTo(69);
        assertThat(err.toString()).as("names the sudo rule").contains("sudo rule");
    }

    @Test
    void passesOnPoreloHostNonZeroExitCodeAndWords() {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.failing(
                "sudo -n /usr/sbin/porelo-host forge issue-token",
                75,
                "",
                "porelo-host: this account is locked on the host\n");

        final int exit = cli(false).execute("host", "forge-token");

        assertThat(exit).as("passes on porelo-host exit code").isEqualTo(75);
        assertThat(err.toString())
                .as("passes on porelo-host words")
                .contains("porelo-host: this account is locked on the host");
    }

    @Test
    void aFailingHostKeepsItsWordsAndExitCodeButRedactsAnyIssuedToken() {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.failing(
                "sudo -n /usr/sbin/porelo-host forge issue-token",
                75,
                ISSUED_TOKEN + "\n",
                "porelo-host: refused token " + ISSUED_TOKEN);
        assertThat(cli(false).execute("host", "forge-token")).isEqualTo(75);
        assertThat(err.toString())
                .contains("porelo-host: refused token")
                .doesNotContain(ISSUED_TOKEN);
        assertThat(out.toString()).isEmpty();
        assertThat(runner.lines()).noneMatch(line -> line.contains(ISSUED_TOKEN));
        assertThat(context.vault().read(VaultFile.Opener.passphrase(PASSPHRASE))).isEmpty();
    }

    @Test
    void aSilentHostFailurePassesOnItsExitCode() {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.failing("sudo -n /usr/sbin/porelo-host forge issue-token", 1, "", "");
        assertThat(cli(false).execute("host", "forge-token")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"not-a-token", "0123456789", "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"})
    void aMalformedTokenCannotReplaceThePersonsCredential(String token) {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", token + "\n");
        assertThat(cli(false).execute("host", "forge-token")).isEqualTo(69);
        assertThat(context.vault().read(VaultFile.Opener.passphrase(PASSPHRASE))).isEmpty();
        assertThat(out.toString() + err.toString()).doesNotContain(token);
    }

    @Test
    void anUntrustedHostProgramOrForgeConfRefusesWithExit69BeforeMinting() throws Exception {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", ISSUED_TOKEN + "\n");

        final CommandLine badProgramCli = cli(false);
        final HostForgeTokenCommand badProgram =
                (HostForgeTokenCommand)
                        badProgramCli
                                .getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("forge-token")
                                .getCommand();
        badProgram.taken =
                path -> {
                    throw new UncheckedIOException(new IOException("writable by group"));
                };

        assertThat(badProgramCli.execute("host", "forge-token"))
                .as("untrusted /usr/sbin/porelo-host exits 69")
                .isEqualTo(69);
        assertThat(runner.invocations()).isEmpty();

        err.getBuffer().setLength(0);
        final CommandLine badConfCli = cli(false);
        final HostForgeTokenCommand badConf =
                (HostForgeTokenCommand)
                        badConfCli
                                .getSubcommands()
                                .get("host")
                                .getSubcommands()
                                .get("forge-token")
                                .getCommand();
        badConf.attributes = path -> Map.of("uid", 1000, "mode", 0100644);

        assertThat(badConfCli.execute("host", "forge-token"))
                .as("non-root forge.conf exits 69")
                .isEqualTo(69);
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void theTokenNeverAppearsInOutputExceptionMessagesOrRecordedCommandLines() throws Exception {
        context.vault().write(Map.of(), PASSPHRASE.clone());
        openVault();
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", ISSUED_TOKEN + "\n");

        assertThat(cli(false).execute("host", "forge-token")).isZero();
        assertThat(out.toString())
                .as("stdout never contains the token")
                .doesNotContain(ISSUED_TOKEN);
        assertThat(err.toString())
                .as("stderr never contains the token")
                .doesNotContain(ISSUED_TOKEN);
        assertThat(runner.lines())
                .as("recorded command lines never contain the token")
                .noneMatch(line -> line.contains(ISSUED_TOKEN));

        // Now force a malformed multi-line answer containing the token and a vault write failure.
        final String malformed = ISSUED_TOKEN + "\nextra-line\n";
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", malformed);
        assertThat(cli(false).execute("host", "forge-token")).isEqualTo(69);
        assertThat(out.toString()).doesNotContain(ISSUED_TOKEN);
        assertThat(err.toString()).doesNotContain(ISSUED_TOKEN);

        // A held opener must be verified before rotation can revoke the person's existing token.
        runner.answering("sudo -n /usr/sbin/porelo-host forge issue-token", ISSUED_TOKEN + "\n");
        final byte[] bytes = Files.readAllBytes(context.paths().vault().vaultFile());
        bytes[bytes.length - 1] ^= (byte) 0xFF;
        Files.write(context.paths().vault().vaultFile(), bytes);
        assertThat(cli(false).execute("host", "forge-token")).isEqualTo(65);
        assertThat(out.toString()).doesNotContain(ISSUED_TOKEN);
        assertThat(err.toString()).doesNotContain(ISSUED_TOKEN);
        assertThat(runner.lines()).noneMatch(line -> line.contains(ISSUED_TOKEN));
    }
}
