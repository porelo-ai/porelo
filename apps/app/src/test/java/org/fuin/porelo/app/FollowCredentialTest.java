package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.core.credential.CredentialRegistry;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link FollowCredential}: what git is given to reach a destination. */
class FollowCredentialTest {

    // A fetch must not accept another trust source or split the pin into relative file names.
    @Test
    void aFetchIsolatesSshTrustAndKeepsThePinPathWhole(@TempDir Path dir) throws Exception {
        final PoreloContext machine = context(dir.resolve("my  state"));
        final String effective =
                SshPolicyFixture.effectiveShell(dir, FollowCredential.sshCommand(machine, null));

        SshPolicyFixture.assertIsolated(effective);
        assertThat(effective.lines().filter(line -> line.startsWith("userknownhostsfile ")))
                .containsExactly("userknownhostsfile " + FollowCredential.knownHostsFile(machine));
    }

    @Test
    void anAgentDeclarationNamesOnlyTheChosenPublicKey(@TempDir Path dir) throws Exception {
        // Prevent a configured identity from silently authenticating as another account.
        final String publicKey =
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA chosen";
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
        try (FollowCredential lent =
                FollowCredential.open(
                        context,
                        "git@forge.example:team/repo.git",
                        "test",
                        "git",
                        new Credential(
                                publicKey,
                                Credential.Kind.SSH_KEY,
                                "ssh://forge.example/team/repo.git",
                                null,
                                "git",
                                Credential.Source.AGENT))) {
            final String command = lent.environment().get("GIT_SSH_COMMAND");
            assertThat(command)
                    .as("only the chosen identity may authenticate")
                    .contains("IdentitiesOnly=yes", "-F /dev/null", " -i ");
            final var arguments = new java.util.ArrayList<>(java.util.List.of("ssh", "-G"));
            arguments.addAll(lent.identityArguments());
            arguments.add("forge.example");
            final Process ssh = new ProcessBuilder(arguments).start();
            final String configured =
                    new String(
                            ssh.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertThat(ssh.waitFor())
                    .as("OpenSSH can read the selected public identity without connecting")
                    .isZero();
            assertThat(configured.lines().filter(line -> line.startsWith("identityfile ")).toList())
                    .as("OpenSSH has exactly one identity to offer")
                    .hasSize(1);
            assertThat(configured)
                    .as("OpenSSH refuses other agent identities")
                    .contains("identitiesonly yes");
        }
    }

    @Test
    void anUndeclaredSshForwardCannotChooseAnAccountsKey(@TempDir Path dir) {
        // A forward must not publish work through an unrelated implicit identity.
        final FakeCommandRunner runner =
                new FakeCommandRunner()
                        .answering("for-each-ref", "refs/porelo/incoming/work\n")
                        .answering("rev-parse", "a".repeat(40));
        final org.fuin.porelo.gate.GitGate gate =
                new org.fuin.porelo.gate.GitGate(
                                runner,
                                dir.resolve("mirror"),
                                org.fuin.porelo.gate.GateMode.GATEKEEPING,
                                "git@forge.example:team/repo.git")
                        .using(new VaultGitCredentials(context(dir), "test"));
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> gate.approve("work", "main")))
                .as("an undeclared forwarding identity fails before git runs")
                .isInstanceOf(org.fuin.porelo.gate.GateException.class);
    }

    @AfterEach
    void forgetThePassphrase() {
        PoreloContext.forgetAsked();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "git.ssh.forge.example",
                "git.ssh",
                "ssh.default",
                "git.token.forge.example"
            })
    void forgettingADeployDeclarationRestoresThePersonsCredential(String entry, @TempDir Path dir)
            throws Exception {
        // Forgetting a deploy declaration must not strand a repository the person can reach.
        final boolean token = entry.startsWith("git.token");
        final String upstream =
                token
                        ? "https://forge.example/team/repo.git"
                        : "ssh://git@forge.example/team/repo.git";
        final char[] passphrase = "test passphrase".toCharArray();
        final XdgPaths paths =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(paths, dir.resolve("bin")),
                                args -> 0)
                        .asking(prompt -> passphrase.clone());
        context.vault()
                .write(
                        Map.of(
                                entry,
                                token
                                        ? VaultEntry.of("test token")
                                        : new VaultEntry(
                                                SigningKey.generate("person").seedBase64(),
                                                "ssh-key",
                                                Map.of())),
                        passphrase);
        context.openerAsking(false, new PrintWriter(new StringWriter()));
        // git's helper is a process of its own: a token is read from a vault the keyring opens,
        // as after 'porelo vault unlock'; an ssh key is served from this process.
        final org.fuin.porelo.vault.KernelKeyring keyring =
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        if (token) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    org.fuin.porelo.vault.KernelKeyring.available(),
                    "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
            keyring.store(passphrase.clone());
        }
        try {
            lendsAfterForgetting(context, upstream, entry, token);
        } finally {
            if (token) {
                keyring.forget();
            }
        }
    }

    private static void lendsAfterForgetting(
            final PoreloContext context,
            final String upstream,
            final String entry,
            final boolean token)
            throws Exception {
        final CredentialDeclarations declarations = new CredentialDeclarations(context);
        final String oldUpstream = "ssh://git@forge.example/team/repo.git";
        declarations.declare(
                new Credential(
                        "deploy:planning:planning",
                        Credential.Kind.SSH_KEY,
                        CredentialRegistry.normalise(oldUpstream),
                        null,
                        Credential.ANY,
                        Credential.Source.VAULT));
        declarations.forget(CredentialRegistry.normalise(oldUpstream));

        try (FollowCredential lent = FollowCredential.open(context, upstream, "test")) {
            assertThat(lent.lent())
                    .as(
                            "the person's stored credential reaches git after the declaration is forgotten")
                    .isTrue();
            if (token) {
                assertThat(lent.arguments())
                        .as("git's helper is handed the token the lease read from the vault")
                        .anySatisfy(
                                argument ->
                                        assertThat(argument)
                                                .as("the helper reads the vault no second time")
                                                .contains("--env 'PORELO_GIT_CREDENTIAL_TOKEN'")
                                                .doesNotContain("--entry"));
                assertThat(lent.environment())
                        .as("the person's token, read once")
                        .containsEntry("PORELO_GIT_CREDENTIAL_TOKEN", "test token");
                assertThat(lent.secretsLent()).contains("test token");
            } else {
                assertThat(FollowCredential.sshKeyFor(context, upstream))
                        .as("the address selects the person's own ssh key")
                        .isEqualTo(entry);
                assertThat(lent.environment().get("GIT_SSH_COMMAND"))
                        .as(
                                "a vault key must not be supplemented by private files or configured identities")
                        .contains("-F /dev/null", "IdentityFile=none");
                assertThat(Path.of(lent.environment().get("SSH_AUTH_SOCK")))
                        .as("git can request signatures from the lent key")
                        .exists();
            }
        }
    }

    @Test
    void aFailedFetchWithAKeyNobodyDeclaredNamesTheEntryAndTheAgentDeclaration(@TempDir Path dir)
            throws Exception {
        // A deploy key kept as git.ssh.<host> answered a person's repository as another's.
        final String upstream = "git@forge.example:team/repo.git";
        final char[] passphrase = "test passphrase".toCharArray();
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(
                                        XdgPaths.of(
                                                name ->
                                                        switch (name) {
                                                            case "XDG_CONFIG_HOME" ->
                                                                    dir.resolve("config")
                                                                            .toString();
                                                            case "XDG_DATA_HOME" ->
                                                                    dir.resolve("data").toString();
                                                            case "XDG_STATE_HOME" ->
                                                                    dir.resolve("state").toString();
                                                            default -> null;
                                                        },
                                                dir),
                                        dir.resolve("bin")),
                                args -> 0)
                        .asking(prompt -> passphrase.clone());
        context.vault()
                .write(
                        Map.of(
                                "git.ssh.forge.example",
                                new VaultEntry(
                                        SigningKey.generate("deploy").seedBase64(),
                                        "ssh-key",
                                        Map.of())),
                        passphrase);
        context.openerAsking(false, new PrintWriter(new StringWriter()));

        assertThat(FollowCredential.impliedKeyNote(context, upstream))
                .contains("vault entry 'git.ssh.forge.example'")
                .contains("every repository on forge.example")
                .contains("'porelo credentials declare " + upstream + " --kind=ssh-key --agent'");

        // Once a declaration chooses the credential, even a vault key, somebody chose it.
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                "git.ssh.forge.example",
                                Credential.Kind.SSH_KEY,
                                CredentialRegistry.normalise("git@forge.example:team/"),
                                null,
                                Credential.ANY,
                                Credential.Source.VAULT));
        assertThat(FollowCredential.impliedKeyNote(context, upstream)).isEmpty();
    }

    private PoreloContext context(final Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                "XDG_STATE_HOME".equals(name)
                                        ? dir.resolve("state").toString()
                                        : null,
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void twoLendsForOneLabelNeverShareASocket(@TempDir Path dir) {

        // Each lend removed whatever lay at git-<label>.sock before it served its key there, so a
        // follow by the daemon and one at the command line at the same moment took each other's
        // socket away, and one fetch failed.
        final PoreloContext context = context(dir);

        final Path first =
                FollowCredential.socketFor(
                        context, "a-project-with-a-long-name-and-its-repository");
        final Path second =
                FollowCredential.socketFor(
                        context, "a-project-with-a-long-name-and-its-repository");

        assertThat(first).isNotEqualTo(second);
        assertThat(first.getParent()).isEqualTo(second.getParent());
        assertThat(first.toString().length()).as("a unix socket's path").isLessThan(108);
    }

    @Test
    void anSshDestinationWithNothingLentStillGetsTheMachinesHostKeyPolicy(@TempDir Path dir) {

        // The developer's own agent key reaches the origin, so nothing is lent - and ssh, given no
        // policy, checked the host against the user's own known hosts and refused one this machine
        // had vouched for.
        try (FollowCredential lent =
                FollowCredential.open(
                        context(dir), "ssh://dev@forge.example/team/repo.git", "test")) {
            assertThat(lent.environment().get("GIT_SSH_COMMAND"))
                    .contains("StrictHostKeyChecking=yes")
                    .contains(
                            "UserKnownHostsFile=\""
                                    + dir.resolve("state/porelo/known_hosts")
                                    + "\"");
            // A policy is not a credential: what failed is still answered as nothing stored for it.
            assertThat(lent.lent()).isFalse();
        }
    }

    @Test
    void aDestinationThatIsNotSshIsGivenNoSshPolicy(@TempDir Path dir) {

        try (FollowCredential lent =
                FollowCredential.open(context(dir), "/srv/git/repo.git", "test")) {
            assertThat(lent.environment()).doesNotContainKey("GIT_SSH_COMMAND");
        }
    }
}
