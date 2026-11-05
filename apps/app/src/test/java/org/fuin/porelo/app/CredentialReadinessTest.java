package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prevents readiness checks from promising credentials that cannot be offered. */
class CredentialReadinessTest {
    @TempDir private Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private CredentialDeclarations declarations(final boolean socket) {
        return new CredentialDeclarations(
                new PoreloContext(
                        runner,
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0,
                        name ->
                                socket && name.equals("SSH_AUTH_SOCK")
                                        ? "/test/agent.sock"
                                        : null));
    }

    private Credential agent() {
        return new Credential(
                "",
                Credential.Kind.SSH_KEY,
                "ssh://forge.example.org",
                null,
                "git",
                Credential.Source.AGENT);
    }

    @Test
    void anAbsentAgentDoesNotPromiseAKey() throws Exception {
        final CredentialDeclarations declarations = declarations(false);
        declarations.declare(agent());

        final var check = declarations.check("git@forge.example.org:team/repo.git", "git");

        assertThat(check.outcome())
                .as("no agent can offer a key")
                .isNotEqualTo(CredentialDeclarations.Outcome.READY);
        assertThat(check.detail())
                .as("the person can reconnect the agent")
                .contains("SSH_AUTH_SOCK");
        assertThat(declarations.holdsValue(agent()))
                .as("a listing must not promise an absent agent")
                .isFalse();
        assertThat(runner.invocations()).as("no socket means no identity request").isEmpty();
    }

    @Test
    void anEmptyOrLockedAgentDoesNotPromiseAKey() throws Exception {
        runner.failing("ssh-add -l", 1, "untrusted agent output");
        final CredentialDeclarations declarations = declarations(true);
        declarations.declare(agent());

        final var check = declarations.check("git@forge.example.org:team/repo.git", "git");

        assertThat(check.outcome())
                .as("an identity request with no keys is not ready")
                .isNotEqualTo(CredentialDeclarations.Outcome.READY);
        assertThat(check.detail())
                .as("empty and locked agents are indistinguishable")
                .contains("locked", "ssh-add")
                .doesNotContain("untrusted agent output");
        assertThat(declarations.wouldDeclare(agent()).outcome())
                .as("a proposed declaration gets the same check")
                .isEqualTo(check.outcome());
    }

    @Test
    void anAgentThatCannotBeInspectedDoesNotExposeToolOutput() throws Exception {
        runner.failing("ssh-add -l", 2, "untrusted agent output");
        final CredentialDeclarations declarations = declarations(true);
        declarations.declare(agent());

        final var check = declarations.check("git@forge.example.org:team/repo.git", "git");

        assertThat(check.outcome())
                .as("a failed check cannot establish readiness")
                .isNotEqualTo(CredentialDeclarations.Outcome.READY);
        assertThat(check.detail())
                .as("owned advice replaces tool output")
                .contains("SSH_AUTH_SOCK")
                .doesNotContain("untrusted agent output");
    }

    @Test
    void aFailedIdentityCommandDoesNotPromiseAKey() {
        runner.missing("ssh-add");

        final var check = declarations(true).wouldDeclare(agent());

        assertThat(check.outcome())
                .as("a missing checker cannot establish readiness")
                .isNotEqualTo(CredentialDeclarations.Outcome.READY);
        assertThat(check.detail()).as("the checker is named for inspection").contains("ssh-add");
    }

    @Test
    void aProposedExpiredCredentialIsRefusedEvenWhenItsFileExists() throws Exception {
        final Path file = Files.createFile(dir.resolve("token"));
        final Credential expired =
                new Credential(
                        file.toString(),
                        Credential.Kind.TOKEN,
                        "https://forge.example.org",
                        null,
                        "git",
                        Credential.Source.FILE,
                        "2020-01-01T00:00:00Z");

        final var check = declarations(false).wouldDeclare(expired);

        assertThat(check.outcome())
                .as("a readable file does not undo expiration")
                .isEqualTo(CredentialDeclarations.Outcome.EXPIRED);
        assertThat(check.detail())
                .as("the value must be replaced at its source")
                .contains("replace", file.toString());
    }
}
