package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An upstream read as the gate's clone reads it, before a start makes anything.
 *
 * <p><strong>What is measured is that the cause is named with its fix.</strong> A key the forge
 * refused surfaced from the clone as "no git gate for this task" with git's raw words, after the
 * task was half made (found on 2026-10-13).
 */
class UpstreamProbeTest {

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void aHostForgeProbeDisablesPromptsWithoutDroppingItsLentHeader(@TempDir Path dir)
            throws Exception {
        final var commands = new java.util.ArrayList<org.fuin.porelo.core.process.Command>();
        final var machine =
                new PoreloContext(
                        command -> {
                            commands.add(command);
                            return new org.fuin.porelo.core.process.CommandResult(
                                    command,
                                    0,
                                    command.program().equals(HostForgeLending.SUDO.toString())
                                            ? "lent-token"
                                            : "",
                                    "");
                        },
                        context(dir).paths(),
                        arguments -> 0);
        final Path conf = dir.resolve("forge.conf");
        Files.writeString(conf, "web_port=3000\ngit_port=3001\n");
        final var lending =
                new HostForgeLending(
                        machine, org.fuin.porelo.gate.GitCredentials.NONE, conf, () -> true);
        assertThat(UpstreamProbe.probe(machine, lending, "http://127.0.0.1:3001/acme/repo.git"))
                .as("the bounded probe still gets its successful answer")
                .isNull();
        assertThat(commands)
                .filteredOn(command -> command.arguments().contains("ls-remote"))
                .singleElement()
                .satisfies(
                        command -> {
                            assertThat(command.environment())
                                    .containsEntry("GIT_ASKPASS", "/bin/false")
                                    .containsEntry("GIT_TERMINAL_PROMPT", "0")
                                    .containsEntry("GIT_CONFIG_COUNT", "2");
                            assertThat(command.arguments())
                                    .containsSequence("-c", "credential.helper=");
                            assertThat(command.environment().get("GIT_CONFIG_VALUE_0"))
                                    .startsWith("Authorization: Basic ");
                        });
    }

    @Test
    void theProbesConnectBoundIsTwiceAsLongAtServices2(@TempDir Path dir) throws Exception {
        final var commands = new java.util.ArrayList<org.fuin.porelo.core.process.Command>();
        final PoreloContext plain =
                new PoreloContext(
                        command -> {
                            commands.add(command);
                            return new org.fuin.porelo.core.process.CommandResult(
                                    command, 0, "", "");
                        },
                        context(dir).paths(),
                        arguments -> 0);
        final PoreloContext slow =
                plain.withAccount(
                        AccountConfig.read(
                                new java.io.StringReader("limits:\n  services: 2\n"), "t"));
        final String url = "ssh://git@forge.example.org/acme/repo.git";

        UpstreamProbe.probe(plain, org.fuin.porelo.gate.GitCredentials.NONE, url);
        UpstreamProbe.probe(slow, org.fuin.porelo.gate.GitCredentials.NONE, url);

        assertThat(commands)
                .extracting(command -> command.environment().get("GIT_HTTP_LOW_SPEED_TIME"))
                .as("10 s at 1.0, 20 s at services 2")
                .containsExactly("10", "20");
        assertThat(commands.getLast().environment().get("GIT_SSH_COMMAND"))
                .contains("ConnectTimeout=20");
    }

    @Test
    void aTaskDoesNotOfferToCopyADeclaredExternalKeyIntoTheVault(@TempDir final Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        new CredentialDeclarations(context)
                .declare(
                        new org.fuin.porelo.core.credential.Credential(
                                "",
                                org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY,
                                "ssh://forge.example.org",
                                null,
                                "git",
                                org.fuin.porelo.core.credential.Credential.Source.AGENT));
        final var questions = new java.util.ArrayList<String>();
        final var launch =
                new TaskLaunch(
                                context,
                                new TaskLaunch.Request(
                                        "task",
                                        dir.resolve("project.yml"),
                                        null,
                                        null,
                                        null,
                                        8,
                                        UPSTREAM,
                                        false,
                                        false,
                                        "deny",
                                        true))
                        .offering(
                                new Offer(
                                        question -> {
                                            questions.add(question);
                                            return null;
                                        },
                                        false,
                                        false,
                                        new java.io.PrintWriter(new java.io.StringWriter())));
        final var finding =
                new UpstreamProbe.Finding(
                        new UpstreamProbe.Answer(UpstreamProbe.Cause.KEY_REFUSED, "no agent", ""),
                        true);

        launch.storeRefusedKey(
                finding,
                project(dir, "guarded"),
                new java.io.PrintWriter(new java.io.StringWriter()));

        assertThat(questions)
                .as("the person chose an agent, so vault storage cannot repair it")
                .isEmpty();
    }

    @Test
    void aMissingDeclaredFileIsNotReplacedByAdviceToCreateAVaultCopy(@TempDir final Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final Path missing = dir.resolve("missing-key");
        new CredentialDeclarations(context)
                .declare(
                        new org.fuin.porelo.core.credential.Credential(
                                missing.toString(),
                                org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY,
                                "ssh://forge.example.org",
                                null,
                                "git",
                                org.fuin.porelo.core.credential.Credential.Source.FILE));

        final var answer =
                UpstreamProbe.answer(context, UPSTREAM, "Permission denied (publickey).");

        assertThat(answer.detail())
                .as("the recorded source is the one that needs repair")
                .contains(missing.toString(), "cannot be read")
                .doesNotContain("nothing is stored");
        assertThat(answer.fix())
                .as("the owner chose a file, not a vault copy")
                .doesNotContain("vault put");
    }

    @Test
    void aCleanupFailureDoesNotClaimTheRemoteWasNeverRead(@TempDir final Path dir) {
        final UpstreamProbe.Answer answer =
                UpstreamProbe.probe(
                        context(dir),
                        url ->
                                new org.fuin.porelo.gate.GitCredentials.Lease() {
                                    @Override
                                    public java.util.Map<String, String> environment() {
                                        return java.util.Map.of();
                                    }

                                    @Override
                                    public java.util.List<String> arguments() {
                                        return java.util.List.of();
                                    }

                                    @Override
                                    public void close() {
                                        throw new IllegalStateException("cleanup failed");
                                    }
                                },
                        UPSTREAM);

        assertThat(runner.only("ls-remote"))
                .as("the read happened before cleanup failed")
                .isNotNull();
        assertThat(answer.detail())
                .as("a cleanup failure says nothing about whether reading happened")
                .doesNotContain("nothing was read", "could not be lent");
    }

    @Test
    void aFailedLeaseCannotExposeACredentialInItsException(@TempDir final Path dir) {
        final String privateValue = "credential-from-a-failed-helper";
        final UpstreamProbe.Answer answer =
                UpstreamProbe.probe(
                        context(dir),
                        url -> {
                            throw new IllegalStateException(privateValue);
                        },
                        UPSTREAM);

        assertThat(answer.asMap().toString())
                .as("a helper's exception can contain the credential it was handed")
                .doesNotContain(privateValue);
        assertThat(answer.fix())
                .as("the owner can inspect declarations without connecting")
                .isEqualTo("porelo credentials list");
        assertThat(runner.invocations()).as("no remote read follows a failed lease").isEmpty();
    }

    @Test
    void unrecognizedToolOutputCannotExposeACredential(@TempDir final Path dir) {
        for (final String said :
                java.util.List.of(
                        "fatal: private-value-from-git",
                        "ssh: connection refused private-value-from-git")) {
            final UpstreamProbe.Answer answer = UpstreamProbe.answer(context(dir), UPSTREAM, said);
            assertThat(answer.asMap().toString())
                    .as("untrusted tool output may echo a credential")
                    .doesNotContain("private-value-from-git");
            assertThat(answer.detail()).as("the owner gets a next action").contains("Check");
        }
    }

    @Test
    void aRefusedHttpsTokenDoesNotSendItsOwnerToInstallAPublicKey(@TempDir final Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        final Path tokenFile = Files.createFile(dir.resolve("token"));
        final String url = "https://forge.example.org/project/backend.git";
        new CredentialDeclarations(context)
                .declare(
                        new org.fuin.porelo.core.credential.Credential(
                                tokenFile.toString(),
                                org.fuin.porelo.core.credential.Credential.Kind.TOKEN,
                                url,
                                null,
                                "git",
                                org.fuin.porelo.core.credential.Credential.Source.FILE));

        final UpstreamProbe.Answer answer =
                UpstreamProbe.answer(context, url, "fatal: Authentication failed");

        assertThat(answer.cause())
                .as("authentication failure is preserved")
                .isEqualTo(UpstreamProbe.Cause.KEY_REFUSED);
        assertThat(answer.detail())
                .as("HTTPS needs rights or a replacement credential")
                .contains("token", "access")
                .doesNotContain("public half");
        assertThat(answer.fix())
                .as("the existing file is managed by its owner")
                .isEqualTo("porelo credentials list");
    }

    private static final String UPSTREAM = "ssh://git@forge.example.org:7999/project/backend.git";

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context(final Path dir) {
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
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static Project project(final Path dir, final String securityClass) throws Exception {
        final Path file = dir.resolve("project/project.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                """
                project:
                  name: "uc"
                  security_class: "%s"
                image:
                  from: "ubuntu:24.04"
                repositories:
                  backend:
                    upstream: "%s"
                  local:
                    upstream: "/srv/git/local.git"
                """
                        .formatted(securityClass, UPSTREAM));
        return ProjectReader.read(file);
    }

    private void forgeSays(final String said) {
        runner.failing("ls-remote", 128, said);
    }

    @Test
    void aKeyTheForgeRefusedIsNamedWithWhatWasOfferedAndWhatStoresOne(@TempDir final Path dir) {

        forgeSays(
                "git@forge.example.org: Permission denied (publickey).\n"
                        + "fatal: Could not read from remote repository.");

        final UpstreamProbe.Answer answer =
                UpstreamProbe.probe(
                        context(dir),
                        GateSupport.credentials(context(dir), "uc", "backend"),
                        UPSTREAM);

        assertThat(answer).isNotNull();
        assertThat(answer.cause()).isEqualTo(UpstreamProbe.Cause.KEY_REFUSED);
        assertThat(answer.detail())
                .contains("forge.example.org:7999 refused this machine")
                .contains("default key files")
                .contains("Through the daemon only a key in the vault");
        assertThat(answer.fix()).startsWith("porelo vault put git.ssh.forge.example.org");
    }

    @Test
    void eachCauseIsToldFromWhatGitAndSshSaid(@TempDir final Path dir) {

        final PoreloContext context = context(dir);

        assertThat(
                        UpstreamProbe.answer(
                                        context,
                                        UPSTREAM,
                                        "No ED25519 host key is known for [forge.example.org]:7999"
                                                + " and you have requested strict checking.\nHost key verification failed.")
                                .fix())
                .isEqualTo("porelo credentials trust-host forge.example.org:7999");
        assertThat(UpstreamProbe.answer(context, UPSTREAM, "ERROR: Repository not found.").cause())
                .isEqualTo(UpstreamProbe.Cause.NOT_FOUND);
        assertThat(
                        UpstreamProbe.answer(
                                        context,
                                        UPSTREAM,
                                        "ssh: Could not resolve hostname forge.example.org: Name or service not known")
                                .cause())
                .isEqualTo(UpstreamProbe.Cause.UNREACHABLE);
        assertThat(
                        UpstreamProbe.answer(
                                        context,
                                        UPSTREAM,
                                        "ssh: connect to host 192.0.2.10 port 7999: Connection timed out")
                                .cause())
                .isEqualTo(UpstreamProbe.Cause.UNREACHABLE);
        assertThat(
                        UpstreamProbe.answer(
                                        context,
                                        "https://forge.example.org/project/backend.git",
                                        "fatal: could not read Username for 'https://forge.example.org': terminal prompts disabled")
                                .cause())
                .isEqualTo(UpstreamProbe.Cause.KEY_REFUSED);
        final UpstreamProbe.Answer other =
                UpstreamProbe.answer(context, UPSTREAM, "fatal: something else\n");
        assertThat(other.cause()).isEqualTo(UpstreamProbe.Cause.UNREADABLE);
    }

    @Test
    void neverAsksOnATerminalAndNeverWaitsWithoutEnd(@TempDir final Path dir) {

        // A start has no terminal for ssh to ask a passphrase on, and an unreachable host must cost
        // seconds.
        UpstreamProbe.probe(
                context(dir), GateSupport.credentials(context(dir), "uc", "backend"), UPSTREAM);

        final var asked = runner.only("ls-remote");
        assertThat(asked.arguments())
                .containsSubsequence("ls-remote", "--heads", "--end-of-options", UPSTREAM);
        assertThat(asked.environment().get("GIT_SSH_COMMAND"))
                .startsWith("ssh -o BatchMode=yes -o ConnectTimeout=10 ")
                .contains("StrictHostKeyChecking=yes");
        assertThat(asked.environment()).containsEntry("GIT_TERMINAL_PROMPT", "0");
    }

    @Test
    void withoutAMirrorTheStartIsRefusedAndWithOneItGoesOnFromIt(@TempDir final Path dir)
            throws Exception {

        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");
        forgeSays("git@forge.example.org: Permission denied (publickey).");

        assertThat(
                        UpstreamProbe.forStart(
                                        context, project, project.repository("backend"), null)
                                .refuses())
                .as("the clone needs the upstream")
                .isTrue();

        Files.createDirectories(
                GateSupport.mirror(context, project, project.repository("backend"))
                        .resolve("objects"));
        assertThat(
                        UpstreamProbe.forStart(
                                        context, project, project.repository("backend"), null)
                                .refuses())
                .as("a mirror is there to start from")
                .isFalse();
    }

    @Test
    void aLocalPathThisAccountReadsIsNoQuestionAndAsksNoTool(@TempDir final Path dir)
            throws Exception {

        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");
        final Path readable = Files.createDirectories(dir.resolve("readable.git"));

        assertThat(
                        UpstreamProbe.forStart(
                                context, project, project.repository("local"), readable.toString()))
                .as("a directory this account can read is no question")
                .isNull();
        assertThat(
                        UpstreamProbe.forStart(
                                context,
                                project,
                                project.repository("local"),
                                readable.toUri().toString()))
                .as("nor is it as a file:// address")
                .isNull();
        assertThat(runner.invocations()).as("a local path is read without git").isEmpty();
    }

    @Test
    void anUnreadableLocalUpstreamIsNamedAsSuchWithoutAGitCommandLine(@TempDir final Path dir)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                "root".equals(System.getProperty("user.name")), "root reads every directory");
        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");
        final Path locked = Files.createDirectories(dir.resolve("locked.git"));
        Files.setPosixFilePermissions(locked, java.util.Set.of());
        try {
            for (final String address : java.util.List.of(locked.toString(), "file://" + locked)) {
                final UpstreamProbe.Finding finding =
                        UpstreamProbe.forStart(
                                context, project, project.repository("local"), address);

                assertThat(finding).as(address + " is in the way").isNotNull();
                assertThat(finding.answer().cause())
                        .as(address + " is unreadable, not missing")
                        .isEqualTo(UpstreamProbe.Cause.UNREADABLE);
                assertThat(finding.answer().detail())
                        .as(address + " is named with how to give access")
                        .contains(locked.toString())
                        .contains("cannot be read by this account")
                        .contains("give it read access")
                        .doesNotContain("does not exist")
                        .doesNotContain("clone");
                assertThat(finding.refuses()).as("there is no mirror to start from").isTrue();
            }
            final Path inside = locked.resolve("inner.git");
            assertThat(
                            UpstreamProbe.forStart(
                                            context,
                                            project,
                                            project.repository("local"),
                                            inside.toString())
                                    .answer()
                                    .cause())
                    .as("a place behind a directory this account cannot enter is unreadable")
                    .isEqualTo(UpstreamProbe.Cause.UNREADABLE);
            assertThat(runner.invocations()).as("no git was asked").isEmpty();
        } finally {
            Files.setPosixFilePermissions(
                    locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aMissingLocalUpstreamIsNamedAsMissing(@TempDir final Path dir) throws Exception {

        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");
        final Path gone = dir.resolve("gone").resolve("local.git");
        Files.createDirectories(gone.getParent());

        final UpstreamProbe.Finding finding =
                UpstreamProbe.forStart(
                        context, project, project.repository("local"), gone.toString());

        assertThat(finding).as("%s is not there", gone).isNotNull();
        assertThat(finding.answer().cause())
                .as("it is named as missing")
                .isEqualTo(UpstreamProbe.Cause.NOT_FOUND);
        assertThat(finding.answer().detail())
                .as("with its path and without git's words")
                .contains(gone.toString())
                .contains("does not exist")
                .doesNotContain("clone");
        assertThat(runner.invocations()).as("no git was asked").isEmpty();
    }

    /** Gives a directory a mode while a check runs, and its owner's rwx back after. */
    private static void withMode(
            final Path directory, final String mode, final ThrowingRunnable check)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                "root".equals(System.getProperty("user.name")), "root reads every directory");
        Files.setPosixFilePermissions(
                directory, java.nio.file.attribute.PosixFilePermissions.fromString(mode));
        try {
            check.run();
        } finally {
            Files.setPosixFilePermissions(
                    directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertUnreadable(final Path upstream, final String why) {
        final UpstreamProbe.Answer answer = UpstreamProbe.local(upstream.toString());
        assertThat(answer).as(why).isNotNull();
        assertThat(answer.cause()).as(why).isEqualTo(UpstreamProbe.Cause.UNREADABLE);
        assertThat(answer.detail())
                .as("%s: named with how to give access", why)
                .contains(upstream.toString())
                .contains("cannot be read by this account");
    }

    @Test
    void aBareRepositoryWhoseObjectsCannotBeReadIsUnreadable(@TempDir final Path dir)
            throws Exception {
        final Path bare = Files.createDirectories(dir.resolve("x.git"));
        final Path objects = Files.createDirectories(bare.resolve("objects"));

        assertThat(UpstreamProbe.local(bare.toString()))
                .as("a bare repository this account reads whole is no question")
                .isNull();
        withMode(
                objects,
                "---------",
                () -> assertUnreadable(bare, "objects/ of mode 000, which git's clone reads"));
        withMode(
                objects,
                "r--------",
                () -> assertUnreadable(bare, "objects/ readable but not searchable"));
    }

    @Test
    void aWorkingTreeWhoseGitDirectoryCannotBeReadIsUnreadable(@TempDir final Path dir)
            throws Exception {
        final Path tree = Files.createDirectories(dir.resolve("tree"));
        final Path dotGit = Files.createDirectories(tree.resolve(".git"));
        final Path objects = Files.createDirectories(dotGit.resolve("objects"));

        assertThat(UpstreamProbe.local(tree.toString()))
                .as("a working tree this account reads whole is no question")
                .isNull();
        withMode(dotGit, "---------", () -> assertUnreadable(tree, ".git of mode 000"));
        withMode(objects, "---------", () -> assertUnreadable(tree, ".git/objects of mode 000"));
        withMode(
                objects,
                "r--------",
                () -> assertUnreadable(tree, ".git/objects readable but not searchable"));
    }

    @Test
    void aTopDirectoryReadableButNotSearchableIsUnreadable(@TempDir final Path dir)
            throws Exception {
        final Path bare = Files.createDirectories(dir.resolve("x.git"));
        Files.createDirectories(bare.resolve("objects"));

        withMode(bare, "r--------", () -> assertUnreadable(bare, "a top directory of mode r--"));
    }

    @Test
    void aPathInTheWordsCarriesNoControlOrFormatCharacter(@TempDir final Path dir) {
        // A project.yml names the path, and the words reach a terminal.
        final Path gone = dir.resolve("a\u001b[31mb\u202ec\u0007.git");

        final UpstreamProbe.Answer answer = UpstreamProbe.local(gone.toString());

        assertThat(answer).as("the path is not there").isNotNull();
        assertThat(answer.detail())
                .as("no escape, bell or direction mark reaches the terminal")
                .doesNotContain("\u001b", "\u202e", "\u0007")
                .contains("a?[31mb?c?.git");
    }

    @Test
    void anUpstreamThatIsReadIsNoQuestion(@TempDir final Path dir) throws Exception {

        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");
        runner.answering(
                "ls-remote", "0123456789abcdef0123456789abcdef01234567\trefs/heads/main\n");

        assertThat(UpstreamProbe.forStart(context, project, project.repository("backend"), null))
                .isNull();
    }

    @Test
    void aReadCountsForAWhile(@TempDir final Path dir) throws Exception {

        // A start dialog asks every few seconds while a person chooses; each ask reached the
        // network.
        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");
        runner.answering(
                "ls-remote", "0123456789abcdef0123456789abcdef01234567\trefs/heads/main\n");

        UpstreamProbe.forStart(context, project, project.repository("backend"), null);
        UpstreamProbe.forStart(context, project, project.repository("backend"), null);

        assertThat(runner.invocations()).as("read once").hasSize(1);
    }

    @Test
    void aRefusalIsAskedAgainSoAFixIsSeenAtOnce(@TempDir final Path dir) throws Exception {

        final PoreloContext context = context(dir);
        final Project project = project(dir, "guarded");
        forgeSays("git@forge.example.org: Permission denied (publickey).");

        UpstreamProbe.forStart(context, project, project.repository("backend"), null);
        UpstreamProbe.forStart(context, project, project.repository("backend"), null);

        assertThat(runner.invocations()).hasSize(2);
    }
}
