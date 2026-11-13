package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards selection and trial of an identity without publishing or recording it. */
class CredentialPushTest {
    private static final String PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String ADDRESS = "git@forge.example:team/repo.git";

    private PoreloContext isolated(Path dir, FakeCommandRunner runner) {
        return new PoreloContext(
                runner,
                new PoreloPaths(
                        XdgPaths.of(
                                name ->
                                        "XDG_RUNTIME_DIR".equals(name)
                                                ? dir.resolve("run").toString()
                                                : null,
                                dir),
                        dir.resolve("bin")),
                args -> 0,
                name ->
                        "SSH_AUTH_SOCK".equals(name)
                                ? dir.resolve("fixture.sock").toString()
                                : null);
    }

    private org.fuin.porelo.core.credential.Credential agentCredential(String key) {
        return new org.fuin.porelo.core.credential.Credential(
                key,
                org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY,
                "ssh://forge.example/team/repo.git",
                null,
                "git",
                org.fuin.porelo.core.credential.Credential.Source.AGENT);
    }

    @Test
    void changingTheDeclarationCannotReplaceTheValidatedIdentity(@TempDir Path dir)
            throws Exception {
        // A concurrent credential edit must not make a forward authenticate as another account.
        final PoreloContext context = isolated(dir, new FakeCommandRunner());
        final var declarations = new CredentialDeclarations(context);
        declarations.declare(agentCredential(PUBLIC_KEY));
        final var credentials =
                new VaultGitCredentials(
                        context,
                        "test",
                        () -> {
                            final var snapshot = context.credentialRegistry();
                            try {
                                declarations.declare(agentCredential(""));
                            } catch (java.io.IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                            return snapshot;
                        });
        try (var lease = credentials.forPush(ADDRESS)) {
            assertThat(lease.environment().get("GIT_SSH_COMMAND"))
                    .as("the already validated identity is offered despite a concurrent edit")
                    .contains("IdentitiesOnly=yes", " -i ");
        }
    }

    @Test
    void aMissingSavedAgentFingerprintIsANamedRefusal(@TempDir Path dir) throws Exception {
        // An unavailable key still needs the chooser's identity metadata and next action.
        final PoreloContext context =
                isolated(dir, new FakeCommandRunner().answering("ssh-add -L", PUBLIC_KEY));
        new CredentialDeclarations(context).declare(agentCredential("SHA256:missing"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new VaultGitCredentials(context, "test").forPush(ADDRESS))
                .as("key selection failure reaches both interfaces as the same named refusal")
                .isInstanceOfSatisfying(
                        PushRefused.class,
                        failure ->
                                assertThat(failure.details())
                                        .as("the chosen fingerprint remains visible when absent")
                                        .containsEntry("outcome", "KEY_UNAVAILABLE")
                                        .containsEntry("id", "SHA256:missing"));
    }

    @Test
    void securityKeysDoNotHideOtherAgentIdentities(@TempDir Path dir) throws Exception {
        // A person's security key must not disable selection of every loaded identity.
        final byte[] algorithm =
                "sk-ssh-ed25519@openssh.com".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final var blob = java.nio.ByteBuffer.allocate(4 + algorithm.length + 4 + 32 + 4 + 3);
        blob.putInt(algorithm.length)
                .put(algorithm)
                .putInt(32)
                .put(new byte[32])
                .putInt(3)
                .put(new byte[] {'s', 's', 'h'});
        final String securityKey =
                "sk-ssh-ed25519@openssh.com "
                        + java.util.Base64.getEncoder().encodeToString(blob.array());
        final PoreloContext context =
                isolated(
                        dir,
                        new FakeCommandRunner()
                                .answering(
                                        "ssh-add -L",
                                        securityKey + " security\n" + PUBLIC_KEY + " ordinary"));
        assertThat(AgentPublicKeys.all(context)).as("both identities can be selected").hasSize(2);
    }

    @Test
    void aSignedRefusalNeverSuggestsARemovedScratchCheckout(@TempDir Path dir) throws Exception {
        // Signed approval deletes its scratch checkout before returning its refusal.
        final PoreloContext context = isolated(dir, new FakeCommandRunner());
        new CredentialDeclarations(context).declare(agentCredential(PUBLIC_KEY));
        final Path scratch = java.nio.file.Files.createDirectory(dir.resolve("scratch"));
        final var command =
                org.fuin.porelo.core.process.Command.of(
                        "git",
                        "-C",
                        scratch.toString(),
                        "push",
                        "--end-of-options",
                        ADDRESS,
                        "HEAD:refs/heads/work");
        final org.fuin.porelo.gate.GateException refusal;
        try (var lease = new VaultGitCredentials(context, "test").forPush(ADDRESS)) {
            refusal =
                    lease.failure(
                            new org.fuin.porelo.core.process.CommandException(
                                    command,
                                    new org.fuin.porelo.core.process.CommandResult(
                                            command, 128, "", "denied to deploy key")));
        }
        java.nio.file.Files.delete(scratch);
        assertThat(((PushRefused) refusal).details().get("command"))
                .as("the next action remains executable after signed-merge cleanup")
                .isEqualTo(java.util.List.of("porelo", "credentials", "keys"));
    }

    @Test
    void aForwardingRefusalCarriesAnExecutableTrialCommand(@TempDir Path dir) throws Exception {
        // A client must not parse git's sentence or guess paths to offer a trial of the refused
        // key.
        final String key =
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(
                                XdgPaths.of(
                                        name ->
                                                "XDG_RUNTIME_DIR".equals(name)
                                                        ? dir.resolve("run").toString()
                                                        : null,
                                        dir),
                                dir.resolve("bin")),
                        args -> 0);
        new CredentialDeclarations(context)
                .declare(
                        new org.fuin.porelo.core.credential.Credential(
                                key,
                                org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY,
                                "ssh://forge.example/team/repo.git",
                                null,
                                "git",
                                org.fuin.porelo.core.credential.Credential.Source.AGENT));
        try (var lease =
                new VaultGitCredentials(context, "test")
                        .forPush("git@forge.example:team/repo.git")) {
            final var command =
                    org.fuin.porelo.core.process.Command.of(
                            "git",
                            "--git-dir",
                            dir.toString(),
                            "push",
                            "--end-of-options",
                            "git@forge.example:team/repo.git",
                            "HEAD:refs/heads/work");
            final var failed =
                    new org.fuin.porelo.core.process.CommandException(
                            command,
                            new org.fuin.porelo.core.process.CommandResult(
                                    command, 128, "", "denied to deploy key"));
            final var refusal = lease.failure(failed);
            assertThat(refusal)
                    .as("a client receives named public details")
                    .isInstanceOf(PushRefused.class);
            assertThat(((PushRefused) refusal).details().get("command"))
                    .as("the trial uses the actual repository, refspec, and selected public key")
                    .isEqualTo(
                            java.util.List.of(
                                    "porelo",
                                    "credentials",
                                    "try-push",
                                    "git@forge.example:team/repo.git",
                                    "--repository",
                                    dir.toString(),
                                    "--refspec",
                                    "HEAD:refs/heads/work",
                                    "--agent",
                                    "--key",
                                    key));
            assertThat(refusal.getMessage())
                    .as("the terminal receives a complete executable action")
                    .doesNotContain("<repository>", "<fingerprint>");
        }
    }

    @Test
    void aTokenRefusedOnAPushSaysWhatToDoAndNeverEchoesGit(@TempDir Path dir) throws Exception {
        // An https push the forge refused came back as git's own line - the command, the address
        // and whatever the server echoed - with nothing to do about the token.
        final java.nio.file.Path token = dir.resolve("token");
        java.nio.file.Files.writeString(token, "not-a-real-token\n");
        final PoreloContext context = isolated(dir, new FakeCommandRunner());
        new CredentialDeclarations(context)
                .declare(
                        new org.fuin.porelo.core.credential.Credential(
                                token.toString(),
                                org.fuin.porelo.core.credential.Credential.Kind.TOKEN,
                                "https://forge.example/team/repo.git",
                                null,
                                "git",
                                org.fuin.porelo.core.credential.Credential.Source.FILE));

        final RuntimeException refusal =
                refusedPush(context, dir, "https://forge.example/team/repo.git");

        assertThat(refusal).as("a client receives named details").isInstanceOf(PushRefused.class);
        final java.util.Map<String, Object> details = ((PushRefused) refusal).details();
        assertThat(details)
                .containsEntry("outcome", "PUSH_REFUSED")
                .containsEntry("source", "FILE")
                .containsEntry("id", token.toString())
                .containsEntry("fingerprint", "");
        assertThat(details.get("command"))
                .as("an action a person can take: the token is replaced where it is declared")
                .isEqualTo(java.util.List.of("porelo", "credentials", "list"));
        assertThat(refusal.getMessage())
                .as("what may be wrong with a token, without claiming which")
                .contains("expired, revoked or missing access", "porelo' 'credentials' 'list")
                .doesNotContain(
                        "echoed-private-value", "public half", "exit code", "git --git-dir");
    }

    @Test
    void anHttpsPushWithNothingDeclaredSaysNoTokenWasOffered(@TempDir Path dir) throws Exception {
        final PoreloContext context = isolated(dir, new FakeCommandRunner());

        final RuntimeException refusal =
                refusedPush(context, dir, "https://forge.example/team/repo.git");

        assertThat(refusal).isInstanceOf(PushRefused.class);
        assertThat(((PushRefused) refusal).details())
                .containsEntry("outcome", "PUSH_REFUSED")
                .containsEntry("source", "")
                .containsEntry("id", "");
        assertThat(refusal.getMessage())
                .contains("no token was offered")
                .doesNotContain("echoed-private-value", "exit code");
    }

    @Test
    void anHttpsPushFailingForAnotherReasonIsNotCalledACredentialRefusal(@TempDir Path dir)
            throws Exception {
        // A rejected non-fast-forward is not a credential's failure: git's words stay what the
        // person reads, and no token is blamed.
        final PoreloContext context = isolated(dir, new FakeCommandRunner());
        try (var lease =
                new VaultGitCredentials(context, "test")
                        .forPush("https://forge.example/team/repo.git")) {
            final var command =
                    org.fuin.porelo.core.process.Command.of(
                            "git", "--git-dir", dir.toString(), "push", "origin", "HEAD:main");
            final var refusal =
                    lease.failure(
                            new org.fuin.porelo.core.process.CommandException(
                                    command,
                                    new org.fuin.porelo.core.process.CommandResult(
                                            command,
                                            1,
                                            "",
                                            " ! [rejected] HEAD -> main (fetch first)")));
            assertThat(refusal).isNotInstanceOf(PushRefused.class);
            assertThat(refusal.getMessage()).contains("fetch first");
        }
    }

    /** What a lease makes of a push the forge refused with an authentication failure. */
    private static RuntimeException refusedPush(PoreloContext context, Path dir, String url)
            throws Exception {
        try (var lease = new VaultGitCredentials(context, "test").forPush(url)) {
            final var command =
                    org.fuin.porelo.core.process.Command.of(
                            "git",
                            "--git-dir",
                            dir.toString(),
                            "push",
                            "--end-of-options",
                            url,
                            "HEAD:refs/heads/work");
            return lease.failure(
                    new org.fuin.porelo.core.process.CommandException(
                            command,
                            new org.fuin.porelo.core.process.CommandResult(
                                    command,
                                    128,
                                    "",
                                    "remote: Invalid username or password. echoed-private-value\n"
                                            + "fatal: Authentication failed for '"
                                            + url
                                            + "/'")));
        }
    }

    @Test
    void anUnavailableDeclaredKeyCannotFallBackToAnotherIdentity(@TempDir Path dir)
            throws Exception {
        // A missing chosen key must never publish through another key from the account.
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        args -> 0);
        new CredentialDeclarations(context)
                .declare(
                        new org.fuin.porelo.core.credential.Credential(
                                dir.resolve("absent-key").toString(),
                                org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY,
                                "ssh://forge.example/team/repo.git",
                                null,
                                "git",
                                org.fuin.porelo.core.credential.Credential.Source.FILE));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new VaultGitCredentials(context, "trial")
                                        .forPush("git@forge.example:team/repo.git"))
                .as("a missing credential refuses before the remote command")
                .isInstanceOf(org.fuin.porelo.gate.GateException.class);
    }

    @Test
    void aRefusedTrialNamesItsOfferedKeyAndNeverEchoesTheRemoteOutput(@TempDir Path dir) {
        // A diagnostic must not report a saved identity instead of the key being tried or leak an
        // echoed value.
        final String key =
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        final FakeCommandRunner runner =
                new FakeCommandRunner()
                        .failing(
                                "push --dry-run",
                                128,
                                "ERROR: denied to deploy key; echoed-private-value");
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                XdgPaths.of(
                                        name ->
                                                "XDG_RUNTIME_DIR".equals(name)
                                                        ? dir.resolve("run").toString()
                                                        : null,
                                        dir),
                                dir.resolve("bin")),
                        args -> 0);
        final var reply =
                PushCredentialHelp.tryPush(
                        context,
                        dir,
                        "git@forge.example:team/repo.git",
                        "HEAD:refs/heads/main",
                        true,
                        key);
        assertThat(reply.get("accepted"))
                .as("a refused key cannot pass the trial")
                .isEqualTo(false);
        assertThat(reply.get("message").toString())
                .as(
                        "only the offered public identity and a known forge classification are reported")
                .contains(AgentPublicKeys.fingerprint(key), "deploy key")
                .doesNotContain("echoed-private-value", "SSH chose by itself");
    }

    @Test
    void keepingAnAgentKeySurvivesReadingTheRecordAgain(@TempDir Path dir) throws Exception {
        // Saving a choice must not fall back to offering every agent identity on the next push.
        final String publicKey =
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(
                                XdgPaths.of(
                                        name ->
                                                name.equals("XDG_RUNTIME_DIR")
                                                        ? dir.resolve("run").toString()
                                                        : null,
                                        dir),
                                dir.resolve("bin")),
                        args -> 0);
        new CredentialDeclarations(context)
                .declare(
                        new org.fuin.porelo.core.credential.Credential(
                                publicKey,
                                org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY,
                                "ssh://forge.example/team/repo.git",
                                null,
                                "git",
                                org.fuin.porelo.core.credential.Credential.Source.AGENT));
        assertThat(
                        context.credentialRegistry()
                                .forUrl("git", "git@forge.example:team/repo.git")
                                .id())
                .as("the exact public identity is kept in the readable record")
                .isEqualTo(publicKey);
    }

    @Test
    void keyDiscoveryReadsOnlyTheAgentsPublicListing(@TempDir Path dir) {
        // Discovery must not sign, export a private key, or mistake an unavailable agent for no
        // keys.
        final String publicKey =
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA chosen";
        final FakeCommandRunner runner = new FakeCommandRunner().answering("ssh-add -L", publicKey);
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                XdgPaths.of(
                                        name ->
                                                name.equals("XDG_RUNTIME_DIR")
                                                        ? dir.resolve("run").toString()
                                                        : null,
                                        dir),
                                dir.resolve("bin")),
                        args -> 0,
                        name ->
                                name.equals("SSH_AUTH_SOCK")
                                        ? dir.resolve("fixture.sock").toString()
                                        : null);
        final var cli = PoreloCli.commandLine(context);
        final StringWriter output = new StringWriter();
        cli.setOut(new PrintWriter(output));
        assertThat(cli.execute("credentials", "keys"))
                .as("public identities can be listed")
                .isZero();
        assertThat(runner.lines())
                .as("only the public listing is requested")
                .containsExactly("ssh-add -L");
        assertThat(output.toString())
                .as("the agent identity is offered for selection")
                .contains("SHA256:", "chosen");
    }

    @Test
    void aTrialNeverPublishesOrDeclaresTheKey(@TempDir Path dir) {
        // A key trial must not forward work or replace the saved credential.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                XdgPaths.of(
                                        name ->
                                                name.equals("XDG_RUNTIME_DIR")
                                                        ? dir.resolve("run").toString()
                                                        : null,
                                        dir),
                                dir.resolve("bin")),
                        args -> 0);
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(new StringWriter()));
        cli.setErr(new PrintWriter(new StringWriter()));
        final int result =
                cli.execute(
                        "credentials",
                        "try-push",
                        "git@forge.example:team/repo.git",
                        "--repository",
                        dir.toString(),
                        "--refspec",
                        "HEAD:refs/heads/main",
                        "--agent",
                        "--key",
                        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA chosen");
        assertThat(result).as("a trial is available without storing a credential").isZero();
        assertThat(runner.lines())
                .as("every push is a dry run")
                .anyMatch(line -> line.contains("push --dry-run"))
                .allMatch(line -> !line.contains(" push ") || line.contains("--dry-run"));
        assertThat(context.credentialRegistry().forUrl("git", "git@forge.example:team/repo.git"))
                .as("trying a key does not keep it")
                .isNull();
    }
}
