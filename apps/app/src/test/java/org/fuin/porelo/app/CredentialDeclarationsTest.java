package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What this machine is configured to connect out with.
 *
 * <p><strong>The point of the file is what it can answer with the vault shut.</strong> A machine
 * whose only record of a credential is the credential itself cannot tell "a credential for this
 * host is configured, unlock the vault" from "nothing is configured, store one" - and those two
 * send a person to opposite places.
 */
class CredentialDeclarationsTest {

    private Path root;

    private PoreloContext context(final Path dir) {
        root = dir;
        final XdgPaths xdg =
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
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void writesARecordAndReadsItBack(@TempDir final Path dir) throws IOException {

        final PoreloContext context = context(dir);
        final CredentialDeclarations declarations = new CredentialDeclarations(context);

        declarations.declare(
                new Credential(
                        "github-work",
                        Credential.Kind.SSH_KEY,
                        "ssh://github.com",
                        null,
                        "git",
                        Credential.Source.VAULT));

        // Through the reader, not through the object it was given: the file is the interface.
        final Credential read =
                context.credentialRegistry().forUrl("git", "git@github.com:acme/x.git");
        assertThat(read).isNotNull();
        assertThat(read.id()).isEqualTo("github-work");
        assertThat(read.kind()).isEqualTo(Credential.Kind.SSH_KEY);
        // git@host:path and ssh://host/path are one destination written two ways.
        assertThat(read.match()).isEqualTo("ssh://github.com");
    }

    @Test
    void aBackslashOrAQuoteInAValueLosesNoOtherDeclaration(@TempDir final Path dir)
            throws IOException {

        // A file that cannot be read is read as no declarations, so the next one written kept only
        // itself: a domain user 'CORP\jdoe' took every other record with it.
        final PoreloContext context = context(dir);
        final CredentialDeclarations declarations = new CredentialDeclarations(context);
        declarations.declare(
                new Credential(
                        "forge",
                        Credential.Kind.BASIC,
                        "https://forge.corp.example",
                        "CORP\\jdoe",
                        "git",
                        Credential.Source.VAULT));
        declarations.declare(
                new Credential(
                        "quoted\"name",
                        Credential.Kind.TOKEN,
                        "https://other.example",
                        null,
                        "git",
                        Credential.Source.VAULT));
        declarations.declare(
                new Credential(
                        "github-work",
                        Credential.Kind.SSH_KEY,
                        "ssh://github.com",
                        null,
                        "git",
                        Credential.Source.VAULT));

        assertThat(context.credentialRegistry().all())
                .as("every record, through the reader")
                .extracting(Credential::id)
                .containsExactly("forge", "quoted\"name", "github-work");
        final Credential forge =
                context.credentialRegistry().forUrl("git", "https://forge.corp.example/x.git");
        assertThat(forge).isNotNull();
        assertThat(forge.user()).as("the user as it was given").isEqualTo("CORP\\jdoe");
    }

    @Test
    void aFileThatCannotBeReadIsRefusedRatherThanWrittenOver(@TempDir final Path dir)
            throws IOException {

        // A lookup reads such a file as no declarations; a declaration that wrote it back from that
        // kept only itself, and a typo made by hand lost every record in the file.
        final PoreloContext context = context(dir);
        final Path file = context.paths().vault().credentialRegistry();
        Files.createDirectories(file.getParent());
        final String typed =
                """
                credentials:
                  - match: "https://forge.corp.example"
                    kind: token
                    vault: "forge
                """;
        Files.writeString(file, typed, StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new CredentialDeclarations(context)
                                        .declare(
                                                new Credential(
                                                        "github-work",
                                                        Credential.Kind.SSH_KEY,
                                                        "ssh://github.com",
                                                        null,
                                                        "git",
                                                        Credential.Source.VAULT)))
                .as("refused, naming the file")
                .isInstanceOf(org.fuin.porelo.core.credential.CredentialException.class)
                .hasMessageContaining(file.toString());
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("the file as the person left it")
                .isEqualTo(typed);
    }

    @Test
    void theLongestMatchWins(@TempDir final Path dir) throws IOException {

        // One credential for a forge and another for one group on it, without an ordering rule
        // nobody can remember.
        final PoreloContext context = context(dir);
        final CredentialDeclarations declarations = new CredentialDeclarations(context);
        declarations.declare(
                new Credential(
                        "forge",
                        Credential.Kind.TOKEN,
                        "https://gitlab.example",
                        null,
                        "git",
                        Credential.Source.VAULT));
        declarations.declare(
                new Credential(
                        "acme-only",
                        Credential.Kind.TOKEN,
                        "https://gitlab.example/acme/",
                        null,
                        "git",
                        Credential.Source.VAULT));

        assertThat(
                        context.credentialRegistry()
                                .forUrl("git", "https://gitlab.example/acme/x.git")
                                .id())
                .isEqualTo("acme-only");
        assertThat(
                        context.credentialRegistry()
                                .forUrl("git", "https://gitlab.example/other/x.git")
                                .id())
                .isEqualTo("forge");
    }

    @Test
    void aKeyInTheUsersOwnDirectoryIsUsedWhereItIs(@TempDir final Path dir) throws IOException {

        // The case the operator named: somebody on their own computer has a key in ~/.ssh and no
        // wish to keep a second copy in a vault. It is READY, and it is not protected - both facts,
        // neither of them a refusal.
        final PoreloContext context = context(dir);
        final Path key = dir.resolve("id_ed25519");
        Files.writeString(key, "-----BEGIN OPENSSH PRIVATE KEY-----\n", StandardCharsets.UTF_8);
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                key.toString(),
                                Credential.Kind.SSH_KEY,
                                "ssh://github.com",
                                null,
                                "git",
                                Credential.Source.FILE));

        final CredentialDeclarations.Check check =
                new CredentialDeclarations(context).check("git@github.com:acme/x.git", "git");

        assertThat(check.outcome()).isEqualTo(CredentialDeclarations.Outcome.READY);
        assertThat(check.credential().protectedHere()).isFalse();
        assertThat(check.detail()).contains("does not protect it");
        // And nothing is offered to store: telling somebody to store it would be telling them to
        // make the copy this record exists to avoid.
        assertThat(check.storeCommand()).isEmpty();
    }

    @Test
    void aDeclaredValueThatIsGoneIsNotTheSameAsNoneDeclared(@TempDir final Path dir)
            throws IOException {

        final PoreloContext context = context(dir);
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                dir.resolve("went-away").toString(),
                                Credential.Kind.SSH_KEY,
                                "ssh://github.com",
                                null,
                                "git",
                                Credential.Source.FILE));

        final CredentialDeclarations.Check check =
                new CredentialDeclarations(context).check("git@github.com:acme/x.git", "git");

        assertThat(check.outcome()).isEqualTo(CredentialDeclarations.Outcome.MISSING_VALUE);
        assertThat(check.detail()).contains("cannot be read");
    }

    @Test
    void aPathOnThisMachineNeedsNothingAndIsToldSo(@TempDir final Path dir) {

        // Offering to store a credential for a directory would waste somebody's afternoon.
        final CredentialDeclarations.Check check =
                new CredentialDeclarations(context(dir)).check(dir.toString(), "git");

        assertThat(check.outcome()).isEqualTo(CredentialDeclarations.Outcome.NOT_NEEDED);
        assertThat(check.storeCommand()).isEmpty();
    }

    @Test
    void forgettingARecordLeavesTheSecretAndSaysSo(@TempDir final Path dir) throws IOException {

        final PoreloContext context = context(dir);
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                "github-work",
                                Credential.Kind.SSH_KEY,
                                "ssh://github.com",
                                null,
                                "git",
                                Credential.Source.VAULT));

        final String left = new CredentialDeclarations(context).forget("ssh://github.com");

        assertThat(left).contains("still in the vault").contains("porelo vault remove github-work");
        assertThat(context.credentialRegistry().forUrl("git", "git@github.com:acme/x.git"))
                .isNull();
        // Forgetting one that was never there is not an error, and says nothing was forgotten.
        assertThat(new CredentialDeclarations(context).forget("ssh://github.com")).isNull();
    }

    @Test
    void anExpiredTokenIsRefusedHereRatherThanByTheForge(@TempDir final Path dir)
            throws IOException {

        // A 401 from a forge reads like a revoked account. This machine knows better and says so.
        final PoreloContext context = context(dir);
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                "oauth-token",
                                Credential.Kind.OAUTH,
                                "https://forge.example",
                                null,
                                "git",
                                Credential.Source.VAULT,
                                "2020-01-01T00:00:00Z"));

        final CredentialDeclarations.Check check =
                new CredentialDeclarations(context).check("https://forge.example/x.git", "git");

        assertThat(check.outcome()).isEqualTo(CredentialDeclarations.Outcome.EXPIRED);
        assertThat(check.detail()).contains("nothing here can renew it without you");
    }

    @Test
    void namesARecordNobodyNamed_whicheverWayItWasDeclared(@TempDir final Path dir)
            throws IOException {

        // Fixed over the socket and not at the terminal, so 'credentials declare --vault ""' still
        // wrote an empty name and still offered 'porelo vault put' with nothing after it. Found by
        // running a PUBLISHED snapshot rather than by a test - so the naming lives in one place
        // now, and this measures that place.
        final PoreloContext context = context(dir);

        final Credential named =
                new CredentialDeclarations(context)
                        .declare(
                                new Credential(
                                        "",
                                        Credential.Kind.TOKEN,
                                        "https://forge.invalid/",
                                        null,
                                        "git",
                                        Credential.Source.VAULT));

        assertThat(named.id()).isEqualTo("git.token.forge.invalid");
        assertThat(CredentialDeclarations.storeCommandFor(named))
                .isEqualTo("porelo vault put git.token.forge.invalid --type token");
        // And what was written is what is read back.
        assertThat(context.credentialRegistry().forUrl("git", "https://forge.invalid/x.git").id())
                .isEqualTo("git.token.forge.invalid");
    }

    @Test
    void refusesToNameADestinationWithNoHost(@TempDir final Path dir) {

        // Nothing to build a name from, and a nameless record is the one thing that cannot be
        // stored into, removed, or reported about.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new CredentialDeclarations(context(dir))
                                        .declare(
                                                new Credential(
                                                        "",
                                                        Credential.Kind.TOKEN,
                                                        "/a/path",
                                                        null,
                                                        "git",
                                                        Credential.Source.VAULT)))
                .isInstanceOf(org.fuin.porelo.core.credential.CredentialException.class)
                .hasMessageContaining("names no host")
                .hasMessageContaining("give the credential a name");
    }

    @Test
    void refusesAKindThatCannotOpenThatDestination(@TempDir final Path dir) {

        // git over https never asks an ssh agent anything, and an ssh destination never asks for a
        // password - so these records could only ever fail, at the moment somebody is trying to get
        // work done. Two of them were accepted onto a machine before this. The machine judges it,
        // because an interface has no business working out which combinations are possible.
        final CredentialDeclarations declarations = new CredentialDeclarations(context(dir));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                declarations.declare(
                                        new Credential(
                                                "k",
                                                Credential.Kind.SSH_KEY,
                                                "https://github.com/acme/",
                                                null,
                                                "git",
                                                Credential.Source.FILE)))
                .isInstanceOf(org.fuin.porelo.core.credential.CredentialException.class)
                .hasMessageContaining("reached over https")
                .hasMessageContaining("token, basic or oauth");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                declarations.declare(
                                        new Credential(
                                                "t",
                                                Credential.Kind.TOKEN,
                                                "ssh://github.com",
                                                null,
                                                "git",
                                                Credential.Source.VAULT)))
                .isInstanceOf(org.fuin.porelo.core.credential.CredentialException.class)
                .hasMessageContaining("reached over ssh")
                .hasMessageContaining("ssh-key");
    }

    @Test
    void refusesACredentialForSomethingNothingConnectsTo(@TempDir final Path dir) {

        // A path on this machine. A record for it would never be read by anything.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new CredentialDeclarations(context(dir))
                                        .declare(
                                                new Credential(
                                                        "k",
                                                        Credential.Kind.SSH_KEY,
                                                        "/home/me/git/acme",
                                                        null,
                                                        "git",
                                                        Credential.Source.FILE)))
                .isInstanceOf(org.fuin.porelo.core.credential.CredentialException.class)
                .hasMessageContaining("nothing authenticates to");
    }
}
