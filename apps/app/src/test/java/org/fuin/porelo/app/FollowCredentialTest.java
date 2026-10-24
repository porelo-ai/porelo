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
                        .as("git's helper reads the person's token from the vault")
                        .anySatisfy(
                                argument ->
                                        assertThat(argument)
                                                .as("the helper names the host's entry")
                                                .contains("--entry '" + entry + "'"));
            } else {
                assertThat(FollowCredential.sshKeyFor(context, upstream))
                        .as("the address selects the person's own ssh key")
                        .isEqualTo(entry);
                assertThat(Path.of(lent.environment().get("SSH_AUTH_SOCK")))
                        .as("git can request signatures from the lent key")
                        .exists();
            }
        }
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
                            "UserKnownHostsFile='" + dir.resolve("state/porelo/known_hosts") + "'");
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
