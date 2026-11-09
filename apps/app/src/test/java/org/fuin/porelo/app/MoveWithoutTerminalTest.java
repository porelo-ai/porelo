package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.GitTreeWrites;
import org.fuin.porelo.organization.gittree.HttpForgeDirectory.TokenAccount;
import org.fuin.porelo.organization.gittree.HttpForgeDirectory.TokenState;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/** A script gets either its answer or an actionable refusal, without a terminal or a reader. */
@Timeout(5)
class MoveWithoutTerminalTest {
    private static final char[] PASSPHRASE = "a rehearsal passphrase".toCharArray();
    private static Path sealed;

    @TempDir Path directory;
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private final FakeCommandRunner runner = new FakeCommandRunner();

    @BeforeAll
    static void seal(@TempDir Path shared) {
        sealed = shared.resolve("sealed");
        new VaultFile(sealed).write(Map.of("forge", VaultEntry.of("test token")), PASSPHRASE);
    }

    @AfterEach
    void forget() {
        PoreloContext.forgetAsked();
    }

    private PoreloContext machine(String stdin) throws Exception {
        final var paths =
                new PoreloPaths(XdgPaths.of(name -> null, directory), directory.resolve("bin"));
        final var context =
                new PoreloContext(
                        runner,
                        paths,
                        args -> {
                            throw new AssertionError("no terminal program may be started");
                        },
                        name -> null,
                        false,
                        VaultPrompt.NOBODY,
                        new StandardInput(
                                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                                false));
        Files.createDirectories(context.vault().path().getParent());
        Files.copy(sealed, context.vault().path());
        return context;
    }

    private int execute(PoreloContext context, String... args) {
        final CommandLine cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        return cli.execute(args);
    }

    @Test
    void anEmptyCredentialIsRefusedAsWrongInputWithoutAskingForAPassphrase() throws Exception {
        assertThat(execute(machine(""), "vault", "put", "forge"))
                .as("empty credential input keeps its usage code")
                .isEqualTo(2);
        assertThat(err.toString())
                .as("how to provide the credential")
                .contains("standard input", "porelo vault put forge");
    }

    @Test
    void aTerminalWithoutAConsoleNeverReadsASecretWithEchoOn() throws Exception {
        final var context =
                machine("")
                        .reading(
                                new StandardInput(
                                        new java.io.InputStream() {
                                            @Override
                                            public int read() {
                                                throw new AssertionError(
                                                        "terminal must not be read with echo on");
                                            }
                                        },
                                        true));
        assertThat(execute(context, "vault", "put", "forge")).isEqualTo(2);
        assertThat(err.toString()).contains("pipe", "standard input");
    }

    @Test
    void aTerminalWithoutAConsoleCannotFallBackToAnUnlockPrompt() throws Exception {
        final var context =
                machine("")
                        .reading(
                                new StandardInput(
                                        new java.io.InputStream() {
                                            @Override
                                            public int read() {
                                                throw new AssertionError(
                                                        "terminal input must stay unread");
                                            }
                                        },
                                        true));
        assertThat(execute(context, "vault", "unlock")).isEqualTo(70);
        assertThat(err.toString())
                .contains("--passphrase-stdin", "--passphrase-command")
                .doesNotContain("$$Lambda", "/0x");
    }

    @Test
    void aShutVaultDoesNotHideAMissingCard() throws Exception {
        assertThat(
                        execute(
                                machine(""),
                                "org",
                                "create",
                                "https://forge.example/acme/organization.git"))
                .isEqualTo(65);
        assertThat(err.toString()).contains("no card");
    }

    @Test
    void aShutVaultNamesTheUnattendedUnlockBeforeStoringACredential() throws Exception {
        assertThat(execute(machine("test token\n"), "vault", "put", "forge"))
                .as("a shut vault keeps its vault-failure code")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("the missing prerequisite and remedy")
                .contains("vault", "no terminal", "porelo vault unlock --passphrase-command");
        assertThat(out.toString()).as("no credential is printed").doesNotContain("test token");
    }

    @Test
    void aCredentialWithoutAVaultNamesInitializationInsteadOfUnlocking() throws Exception {
        final var context = machine("test token\n");
        Files.delete(context.vault().path());
        assertThat(execute(context, "vault", "put", "forge"))
                .as("an absent vault keeps its vault-failure code")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("initialization is the available remedy")
                .contains("no vault", "porelo vault init --passphrase-command")
                .doesNotContain("porelo vault unlock");
    }

    @Test
    void aCredentialComesFromTheContextPipeWhenTheVaultWasOpenedBeforehand() throws Exception {
        final var context = machine("the piped token\n");
        context.requireOpener(() -> Optional.of(PASSPHRASE.clone()));

        assertThat(execute(context, "vault", "put", "forge"))
                .as("a preopened vault and piped credential need no person")
                .isZero();
        assertThat(context.vault().read(PASSPHRASE).get("forge").value())
                .as("the context's bytes were stored")
                .isEqualTo("the piped token");
        assertThat(out.toString() + err)
                .as("the secret stays off the terminal")
                .doesNotContain("the piped token");
    }

    @Test
    void unlockingWithoutASourceNamesThePassphraseCommandInsteadOfReadingStdin() throws Exception {
        assertThat(execute(machine(""), "vault", "unlock"))
                .as("a missing passphrase source keeps its failure code")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("the missing source and its flags")
                .contains("no terminal", "--passphrase-command", "--passphrase-stdin");
    }

    @Test
    void anExplicitEmptyPassphrasePipeIsWrongInput() throws Exception {
        assertThat(execute(machine(""), "vault", "unlock", "--passphrase-stdin"))
                .as("an explicit source gave no data")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("the empty source is named")
                .contains("standard input gave no passphrase");
    }

    @Test
    void aWrongPassphraseFromACommandIsRefusedWithoutPromptingAgain() throws Exception {
        runner.answering("sh -c rehearsal-passphrase", "wrong\n");
        assertThat(
                        execute(
                                machine(""),
                                "vault",
                                "unlock",
                                "--passphrase-command",
                                "rehearsal-passphrase"))
                .as("the supplied passphrase is wrong")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("the refusal identifies the failed source")
                .contains("passphrase", "does not open", "--passphrase-command");
        assertThat(out.toString() + err).as("no passphrase is printed").doesNotContain("wrong");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void declaringAnAgentKeyThatIsNotServedKeepsItsExistingFailureCode(boolean terminal)
            throws Exception {
        assertThat(
                        execute(
                                machine("")
                                        .reading(
                                                new StandardInput(
                                                        java.io.InputStream.nullInputStream(),
                                                        terminal)),
                                "credentials",
                                "declare",
                                "git@forge.example:acme/",
                                "--kind",
                                "ssh-key",
                                "--agent",
                                "--key",
                                "SHA256:" + "a".repeat(43)))
                .as("agent-key selection keeps the former unhandled-failure code")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("how to make the key available")
                .contains("SSH_AUTH_SOCK", "key", "agent");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void declaringAMalformedPublicKeyKeepsItsExistingFailureCode(boolean terminal)
            throws Exception {
        assertThat(
                        execute(
                                machine("")
                                        .reading(
                                                new StandardInput(
                                                        java.io.InputStream.nullInputStream(),
                                                        terminal)),
                                "credentials",
                                "declare",
                                "git@forge.example:acme/",
                                "--kind",
                                "ssh-key",
                                "--agent",
                                "--key",
                                "no public key"))
                .as("public-key selection keeps the former unhandled-failure code")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("the expected public input")
                .contains("public key", "fingerprint");
    }

    @Test
    void aShutVaultKeepsTheOrganizationChecksNeedsAnswer() throws Exception {
        final var cli = PoreloCli.commandLine(machine(""));
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final OrgCreateCommand create =
                cli.getSubcommands().get("org").getSubcommands().get("create").getCommand();
        create.asker =
                (context, address) ->
                        new OrgCreateCheck.Forge() {
                            @Override
                            public GitTreeWrites.RepositoryState repository() {
                                return GitTreeWrites.RepositoryState.EMPTY;
                            }

                            @Override
                            public boolean vaultShut() {
                                return true;
                            }

                            @Override
                            public TokenAccount token() {
                                return new TokenAccount(TokenState.NONE, null);
                            }

                            @Override
                            public Optional<Boolean> owns(String login) {
                                return Optional.empty();
                            }

                            @Override
                            public Optional<List<String>> authenticationKeys(String login) {
                                return Optional.empty();
                            }

                            @Override
                            public Optional<List<String>> signingKeys(String login) {
                                return Optional.empty();
                            }
                        };
        assertThat(
                        cli.execute(
                                "org",
                                "create",
                                "https://forge.example/acme/organization.git",
                                "--check",
                                "--json"))
                .as("the check answered needs, rather than refusing, before unattended refusal")
                .isZero();
        assertThat(out.toString()).contains("needs", "cannot-tell", "token", "vault is shut");
        assertThat(err.toString()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://forge.example/acme/organization.git",
                "git@forge.example:acme/organization.git"
            })
    void anOrganizationCheckSuppressesCredentialDialogsEvenWhenNothingCanBeLent(String address)
            throws Exception {
        final var context = machine("");
        context.requireOpener(() -> Optional.of(PASSPHRASE.clone()));
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final var environments = new java.util.ArrayList<Map<String, String>>();
        final var arguments = new java.util.ArrayList<java.util.List<String>>();
        final OrgCreateCommand create =
                cli.getSubcommands().get("org").getSubcommands().get("create").getCommand();
        create.asker =
                (machine, at) -> {
                    try (var lease = OrganizationAccess.lent(machine).lend(at)) {
                        environments.add(lease.environment());
                        arguments.add(lease.arguments());
                    }
                    throw new Refused(
                            Refused.CANNOT_SAY,
                            "the forge is unavailable; retry the check when it answers",
                            null);
                };

        assertThat(cli.execute("org", "create", address, "--check"))
                .as("a transport refusal returns without input")
                .isEqualTo(69);
        assertThat(err.toString())
                .as("the refusal crosses the CLI boundary")
                .contains("forge is unavailable");
        assertThat(environments)
                .as("even an empty lease suppresses prompts")
                .singleElement()
                .satisfies(
                        environment -> {
                            assertThat(environment)
                                    .as("no credential dialog may start")
                                    .containsEntry("GIT_TERMINAL_PROMPT", "0")
                                    .containsEntry("GIT_ASKPASS", "/bin/false")
                                    .containsEntry("SSH_ASKPASS_REQUIRE", "never");
                            if (address.startsWith("git@"))
                                assertThat(environment.get("GIT_SSH_COMMAND"))
                                        .as("ssh never asks for a password or host confirmation")
                                        .contains("BatchMode=yes");
                        });
        assertThat(arguments)
                .as("no inherited credential helper can ask")
                .singleElement()
                .satisfies(all -> assertThat(all).containsSequence("-c", "credential.helper="));
    }

    @Test
    void creatingAKeyInKeePassXCWithoutATerminalKeepsItsRefusalBeforeItStarts() throws Exception {
        assertThat(
                        execute(
                                machine(""),
                                "identity",
                                "create",
                                "--login",
                                "alice",
                                "--name",
                                "Alice",
                                "--keepassxc",
                                directory.resolve("not-opened.kdbx").toString()))
                .as("KeePassXC keeps its no-terminal refusal code")
                .isEqualTo(64);
        assertThat(err.toString())
                .as("the unattended way to name an existing agent key")
                .contains("terminal", "--key", "agent");
    }
}
