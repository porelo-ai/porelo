package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prevents a locked vault from sending its owner to replace an unread credential. */
class CredentialVaultReadinessTest {
    @TempDir private Path dir;

    private PoreloContext context() {
        return new PoreloContext(
                new FakeCommandRunner(),
                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                command -> 0);
    }

    private Credential credential() {
        return new Credential(
                "forge",
                Credential.Kind.TOKEN,
                "https://forge.example.org",
                null,
                "git",
                Credential.Source.VAULT);
    }

    @Test
    void declaringACredentialDoesNotSendItsOwnerToStoreIntoAShutVault() {
        final PoreloContext context = context();
        context.vault().write(Map.of(), "unit-test-passphrase".toCharArray());
        final var cli = PoreloCli.commandLine(context);
        final var output = new java.io.StringWriter();
        cli.setOut(new java.io.PrintWriter(output, true));

        assertThat(
                        cli.execute(
                                "credentials",
                                "declare",
                                "https://forge.example.org",
                                "--kind",
                                "token",
                                "--vault",
                                "forge"))
                .as("the declaration itself is written without needing a value")
                .isZero();
        assertThat(output.toString())
                .as("unlock before deciding whether to store")
                .contains("porelo vault unlock")
                .doesNotContain("porelo vault put");
    }

    @Test
    void aPassphraseVaultMustBeUnlockedBeforeTheCredentialCanBeJudged() throws Exception {
        final PoreloContext context = context();
        context.vault().write(Map.of(), "unit-test-passphrase".toCharArray());
        final CredentialDeclarations declarations = new CredentialDeclarations(context);
        declarations.declare(credential());

        final var check = declarations.check("https://forge.example.org/repo.git", "git");

        assertThat(check.outcome())
                .as("an unread vault proves no entry is missing")
                .isEqualTo(CredentialDeclarations.Outcome.VAULT_LOCKED);
        assertThat(check.storeArguments())
                .as("unlock before judging or replacing the credential")
                .containsExactly("porelo", "vault", "unlock");
        assertThat(declarations.wouldDeclare(credential()).storeArguments())
                .as("the proposed declaration has the same unlock action")
                .isEqualTo(check.storeArguments());
    }

    @Test
    void anUndeclaredCredentialInALockedVaultAlsoNamesUnlock() {
        final PoreloContext context = context();
        context.vault().write(Map.of(), "unit-test-passphrase".toCharArray());

        final var check =
                new CredentialDeclarations(context)
                        .check("https://forge.example.org/repo.git", "git");

        assertThat(check.storeArguments())
                .as("inspection cannot proceed before unlocking")
                .containsExactly("porelo", "vault", "unlock");
    }

    @Test
    void aHostVaultSendsThePersonToTheirClientComputer() throws Exception {
        final PoreloContext context =
                context()
                        .withHostVault(
                                new HostVaultAccess() {
                                    public void keep(byte[] share) {
                                        throw new AssertionError("no share is sent");
                                    }

                                    public boolean isOpen() {
                                        return false;
                                    }

                                    public byte[] share() {
                                        throw new AssertionError("no share is read");
                                    }

                                    public boolean forget() {
                                        throw new AssertionError("nothing is locked");
                                    }
                                });
        context.vault().write(Map.of(), new byte[32]);
        final CredentialDeclarations declarations = new CredentialDeclarations(context);
        declarations.declare(credential());

        final var check = declarations.check("https://forge.example.org/repo.git", "git");

        assertThat(check.outcome())
                .as("the owner's share is absent")
                .isEqualTo(CredentialDeclarations.Outcome.VAULT_LOCKED);
        assertThat(check.storeCommand())
                .as("the host cannot run an incomplete client command")
                .isEmpty();
        assertThat(check.detail())
                .as("a host vault cannot take a passphrase")
                .contains("client computer", "porelo host unlock", "--host-key", "--login");
    }
}
