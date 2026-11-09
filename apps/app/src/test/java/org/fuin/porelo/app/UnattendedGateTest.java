package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reviews and approvals use Git without terminal prompts, helpers or pinentry. */
@Timeout(5)
class UnattendedGateTest {
    @TempDir Path directory;
    private final List<Command> asked = new ArrayList<>();
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private final ProcessCommandRunner processes = new ProcessCommandRunner();
    private PoreloContext context;
    private WorkOrigin.Choice choice;
    private Path checkout;
    private GitGate gate;
    private final Map<String, String> variables = new java.util.HashMap<>();
    private String offeredKey;
    private String mergeFailure;
    private boolean ready;

    private Map<String, String> environment() {
        return Map.of(
                "HOME",
                directory.toString(),
                "XDG_CONFIG_HOME",
                directory.resolve("config").toString(),
                "PATH",
                System.getenv("PATH"),
                "GIT_CONFIG_NOSYSTEM",
                "1",
                "GIT_CONFIG_GLOBAL",
                directory.resolve(".gitconfig").toString());
    }

    @BeforeEach
    void prepare() throws Exception {
        final CommandRunner runner =
                command -> {
                    asked.add(command);
                    if (command.program().equals("ssh-add") && offeredKey != null) {
                        return new CommandResult(command, 0, offeredKey + "\n", "");
                    }
                    if (command.arguments().contains("merge")
                            && command.arguments().contains("-S")
                            && mergeFailure != null) {
                        return new CommandResult(command, 1, "", mergeFailure);
                    }
                    if (ready) {
                        if (command.program().equals("git")) {
                            assertThat(command.environment())
                                    .containsEntry("HOME", directory.toString());
                            assertThat(command.inheritsEnvironment()).isFalse();
                        }
                        // Surefire points global Git at /dev/null; use this fixture's file,
                        // preserving every other prepared variable and the inheritance policy.
                        return processes.run(
                                command.withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                directory.resolve(".gitconfig").toString())));
                    }
                    return processes.run(command.withChosenEnvironment(environment()));
                };
        context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                XdgPaths.of(name -> null, directory), directory.resolve("bin")),
                        args -> {
                            throw new AssertionError("no terminal program may start");
                        },
                        variables::get);
        final Path origin = directory.resolve("origin.git");
        git(directory, "init", "--bare", "-q", "-b", "main", origin.toString());
        checkout = directory.resolve("checkout");
        git(directory, "clone", "-q", origin.toString(), checkout.toString());
        git(checkout, "config", "user.name", "Developer");
        git(checkout, "config", "user.email", "developer@example.org");
        Files.writeString(checkout.resolve("first"), "first\n");
        git(checkout, "add", ".");
        git(checkout, "commit", "-qm", "first");
        git(checkout, "push", "-q", "origin", "main");
        choice = WorkOrigin.fromCheckout(context, checkout);
        final var project = GateSupport.byName(context, choice.project());
        gate =
                GateSupport.gate(
                        context,
                        project,
                        project.repository(choice.repository()),
                        null,
                        checkout.toString());
        gate.initialize();
        Files.writeString(checkout.resolve("second"), "second\n");
        git(checkout, "add", ".");
        git(checkout, "commit", "-qm", "second");
        git(
                checkout,
                "push",
                "-q",
                gate.mirror().toString(),
                "HEAD:" + GitGate.INCOMING + "writer");
        git(checkout, "reset", "--hard", "HEAD~1");
        asked.clear();
        ready = true;
    }

    private String git(Path where, String... args) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(args));
        return processes
                .runOrFail(Command.of(all).in(where).withChosenEnvironment(environment()))
                .trimmedOutput();
    }

    private int execute(String verb, String... options) {
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final List<String> args =
                new ArrayList<>(
                        List.of(
                                "gate",
                                verb,
                                "-p",
                                choice.project(),
                                "--repository",
                                choice.repository(),
                                "writer"));
        args.addAll(List.of(options));
        return cli.execute(args.toArray(String[]::new));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void inheritedGitConfigCannotReachGit(boolean signed) throws Exception {
        final var builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        InheritedSigning.class.getName(),
                        directory.toString(),
                        Boolean.toString(signed));
        builder.environment().remove("HOME");
        builder.environment().put("GIT_SSH_COMMAND", "unwanted-ssh");
        builder.environment().put("SSH_AUTH_SOCK", "/inherited-agent");
        builder.environment().put("GIT_CONFIG_COUNT", "1");
        builder.environment().put("GIT_CONFIG_KEY_0", "gpg.ssh.program");
        builder.environment().put("GIT_CONFIG_VALUE_0", "unwanted-signer");
        builder.environment().put("GIT_CONFIG_PARAMETERS", "'user.signingkey=unwanted-key'");
        builder.redirectErrorStream(true);
        final Process child = builder.start();
        child.getOutputStream().close();
        final String result =
                new String(
                        child.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertThat(child.waitFor()).as(result).isZero();
    }

    /** A separate process supplies actual inherited Git configuration, with no HOME. */
    public static final class InheritedSigning {
        public static void main(String[] args) {
            final Path home = Path.of(args[0]);
            final boolean signed = Boolean.parseBoolean(args[1]);
            final String key =
                    AgentPublicKeys.validated(PersonCard.keyLine(SigningKey.generate("signer")));
            final boolean[] merged = {false};
            final var machine =
                    new PoreloContext(
                            command -> {
                                if (command.arguments().contains("merge")
                                        || command.arguments().contains("fetch")) {
                                    merged[0] = true;
                                    assertThat(command.inheritsEnvironment()).isFalse();
                                    assertThat(command.environment())
                                            .containsEntry("HOME", home.toString());
                                    assertThat(command.environment().keySet())
                                            .noneMatch(
                                                    name ->
                                                            name.equals("GIT_CONFIG_COUNT")
                                                                    || name.equals(
                                                                            "GIT_CONFIG_PARAMETERS")
                                                                    || name.startsWith(
                                                                            "GIT_CONFIG_KEY_")
                                                                    || name.startsWith(
                                                                            "GIT_CONFIG_VALUE_"));
                                    assertThat(command.environment())
                                            .containsEntry("SSH_AUTH_SOCK", "/checked-agent");
                                    assertThat(command.environment().get("GIT_SSH_COMMAND"))
                                            .contains("BatchMode=yes")
                                            .doesNotContain("unwanted-ssh");
                                    if (signed)
                                        assertThat(command.arguments())
                                                .contains("user.signingkey=key::" + key);
                                }
                                String value = "";
                                if (command.program().equals("ssh-add")) value = key;
                                if (command.arguments().contains("gpg.format")) value = "ssh";
                                if (command.arguments().contains("user.signingkey"))
                                    value = "key::" + key;
                                return new CommandResult(command, 0, value, "");
                            },
                            new PoreloPaths(XdgPaths.of(name -> null, home), home.resolve("bin")),
                            argsToRun -> 0,
                            name -> name.equals("SSH_AUTH_SOCK") ? "/checked-agent" : null);
            UnattendedGit.runner(machine)
                    .run(
                            signed
                                    ? Command.of("git", "merge", "-S", "topic")
                                    : Command.of("git", "fetch", "origin"));
            assertThat(merged[0]).isTrue();
        }
    }

    @Test
    void aReviewDisablesAskpassAndInheritedCredentialHelpers() {
        assertThat(execute("review")).as(err.toString()).isZero();
        assertThat(out.toString()).as("the unattended review is complete").contains("+second");
        assertThat(asked).as("the gate uses the supplied machine runner").isNotEmpty();
        assertThat(asked)
                .filteredOn(command -> command.program().equals("git"))
                .allSatisfy(
                        command -> {
                            assertThat(command.environment())
                                    .as("no credential UI can be started")
                                    .containsEntry("GIT_TERMINAL_PROMPT", "0")
                                    .containsEntry("GIT_ASKPASS", "/bin/false")
                                    .containsEntry("SSH_ASKPASS_REQUIRE", "never");
                            assertThat(command.arguments())
                                    .as("inherited credential helpers are cleared")
                                    .containsSequence("-c", "credential.helper=");
                        });
    }

    @Test
    void aTakenBranchKeepsItsRefusalCodeAndNamesTheBranchOption() throws Exception {
        git(checkout, "checkout", "-qb", "other-work");
        Files.writeString(checkout.resolve("other"), "another writer\n");
        git(checkout, "add", ".");
        git(checkout, "commit", "-qm", "other work");
        git(checkout, "branch", "-f", "main", "HEAD");
        git(checkout, "push", "-q", "origin", "HEAD:main");

        assertThat(execute("approve", "--branch", "main"))
                .as("no person can choose another branch")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("how to choose an available target")
                .contains("already holds", "--branch");
    }

    @Test
    void aPrivateSigningKeyIsRefusedBeforeGitCouldAskForItsPassphrase() {
        variables.put("SSH_AUTH_SOCK", directory.resolve("not-opened-agent.sock").toString());
        git(directory, "config", "--global", "gpg.format", "ssh");
        git(
                directory,
                "config",
                "--global",
                "user.signingkey",
                directory.resolve("private-key").toString());
        git(directory, "config", "--global", "user.name", "Developer");
        git(directory, "config", "--global", "user.email", "developer@example.org");

        assertThat(execute("approve", "--signed"))
                .as("a public agent key must be configured")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("the available unattended signing route")
                .contains("public key", "SSH_AUTH_SOCK");
        assertThat(asked)
                .as("Git never attempts to sign with a private file")
                .noneMatch(
                        command ->
                                command.arguments().contains("merge")
                                        && command.arguments().contains("-S"));
    }

    @Test
    void aDefaultKeyCommandCannotRunWithoutAnExplicitSigningKey() throws Exception {
        final Path marker = directory.resolve("default-key-command-was-run");
        git(directory, "config", "--global", "gpg.format", "ssh");
        git(directory, "config", "--global", "gpg.ssh.defaultKeyCommand", "touch '" + marker + "'");
        variables.put("SSH_AUTH_SOCK", directory.resolve("unused-agent").toString());
        assertThat(execute("approve", "--signed")).isEqualTo(70);
        assertThat(err.toString()).contains("user.signingkey");
        assertThat(marker).doesNotExist();
        assertThat(asked).noneMatch(command -> command.arguments().contains("merge"));
    }

    @Test
    void aPublicKeyNotServedByTheAgentIsRefusedBeforeSigning() {
        offeredKey = PersonCard.keyLine(SigningKey.generate("another key"));
        variables.put("SSH_AUTH_SOCK", directory.resolve("stand-in-agent.sock").toString());
        git(directory, "config", "--global", "gpg.format", "ssh");
        git(
                directory,
                "config",
                "--global",
                "user.signingkey",
                "key::" + PersonCard.keyLine(SigningKey.generate("wanted key")));

        assertThat(execute("approve", "--signed"))
                .as("the configured public key is absent from the agent")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("the absent signer and available remedy")
                .contains("SSH_AUTH_SOCK", "public key");
        assertThat(asked)
                .as("an absent agent key is refused before the signing request")
                .noneMatch(
                        command ->
                                command.arguments().contains("merge")
                                        && command.arguments().contains("-S"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anOrdinarySignedMergeFailureKeepsItsDiagnostic(boolean publicFile) throws Exception {
        offeredKey = PersonCard.keyLine(SigningKey.generate("test signer"));
        variables.put("SSH_AUTH_SOCK", directory.resolve("stand-in-agent.sock").toString());
        git(directory, "config", "--global", "gpg.format", "ssh");
        final String configured;
        if (publicFile) {
            configured =
                    Files.writeString(directory.resolve("public key.pub"), offeredKey + "\n")
                            .toString();
        } else {
            configured = "key::" + offeredKey;
        }
        git(directory, "config", "--global", "user.signingkey", configured);
        mergeFailure = "the merge has a conflict";

        assertThat(execute("approve", "--signed"))
                .as("a merge error is not an absent signer")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("the actual Git diagnostic stays available")
                .contains("merge has a conflict")
                .doesNotContain("there is no terminal to ask for signing");
        assertThat(asked)
                .filteredOn(
                        command ->
                                command.arguments().contains("merge")
                                        && command.arguments().contains("-S"))
                .as("the configured key is replaced by the agent's public value")
                .singleElement()
                .satisfies(
                        command ->
                                assertThat(command.arguments())
                                        .contains("user.signingkey=key::" + offeredKey));
    }

    @Test
    void anAgentServedPublicKeySignsAndForwardsTheReviewedWork() throws Exception {
        final Path key = directory.resolve("signing-key");
        final Path socket = directory.resolve("agent.sock");
        processes.runOrFail(
                Command.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key.toString())
                        .withChosenEnvironment(environment()));
        try (var agents = new TestSshAgent()) {
            agents.start(socket);
            variables.put("SSH_AUTH_SOCK", socket.toString());
            processes.runOrFail(
                    Command.of("ssh-add", key.toString())
                            .withChosenEnvironment(environment())
                            .withEnvironment(Map.of("SSH_AUTH_SOCK", socket.toString())));
            git(directory, "config", "--global", "gpg.format", "ssh");
            git(directory, "config", "--global", "user.signingkey", key + ".pub");
            git(directory, "config", "--global", "user.name", "Developer");
            git(directory, "config", "--global", "user.email", "developer@example.org");
            // This fixture's upstream is the checkout itself; a forge's is bare.
            git(checkout, "config", "receive.denyCurrentBranch", "updateInstead");

            assertThat(execute("approve", "--signed")).as(err.toString()).isZero();
            assertThat(git(checkout, "cat-file", "commit", "main"))
                    .as("the forwarded merge is actually signed")
                    .contains("gpgsig -----BEGIN SSH SIGNATURE-----");
            assertThat(git(checkout, "show", "main:second"))
                    .as("the reviewed work arrived")
                    .isEqualTo("second");
            final String publicKey =
                    Files.readString(Path.of(key + ".pub")).strip().replaceAll(" [^ ]+$", "");
            assertThat(asked)
                    .filteredOn(
                            command ->
                                    command.arguments().contains("merge")
                                            && command.arguments().contains("-S"))
                    .as("the merge is pinned to an agent-served public value")
                    .singleElement()
                    .satisfies(
                            command ->
                                    assertThat(command.arguments())
                                            .contains("user.signingkey=key::" + publicKey));
        }
    }

    @Test
    void signedApprovalWithoutAnUnattendedSignerRefusesBeforeStartingPinentry() throws Exception {
        final Path invoked = directory.resolve("pinentry-was-run");
        final Path signer =
                Files.writeString(
                        directory.resolve("signer"),
                        "#!/bin/sh\ntouch '" + invoked + "'\nexit 1\n");
        assertThat(signer.toFile().setExecutable(true)).as("the stand-in can run").isTrue();
        git(directory, "config", "--global", "gpg.program", signer.toString());
        git(directory, "config", "--global", "user.name", "Developer");
        git(directory, "config", "--global", "user.email", "developer@example.org");

        assertThat(execute("approve", "--signed"))
                .as("no unattended signing key is available")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("how to provide a signer without a terminal")
                .contains("no terminal", "SSH_AUTH_SOCK", "gpg.format", "user.signingkey");
        assertThat(invoked).as("the configured signer never got a chance to ask").doesNotExist();
        assertThat(
                        git(
                                directory,
                                "--git-dir",
                                gate.mirror().toString(),
                                "rev-parse",
                                GitGate.INCOMING + "writer"))
                .as("the refused work is still pending")
                .isNotEmpty();
    }
}
