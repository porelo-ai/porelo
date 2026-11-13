package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests that the credential helper {@link FollowCredential} names for an https address is one git
 * runs, asked of git itself rather than compared as a string.
 *
 * <p>Quoted as a bare word, the helper read as the name of a {@code git credential-<name>} command,
 * so git never ran it and every token lent over https was lost: a start refused a forge whose token
 * was valid (found on the sprint-5 host, 2026-10-27).
 */
class FollowCredentialHelperTest {

    private static final String URL = "https://forge.example.org/team/repo.git";

    @AfterEach
    void forgetTheBinary() {
        System.clearProperty(PoreloBinary.NAMED);
    }

    @ParameterizedTest
    @EnumSource(
            value = Credential.Source.class,
            names = {"FILE", "ENVIRONMENT"})
    void gitRunsTheHelperAndGetsItsToken(final Credential.Source source, @TempDir Path dir)
            throws IOException, InterruptedException {

        // A Porelo whose path has a space in it, standing in for 'porelo vault credential': it
        // answers what the real one answers, whatever it is asked.
        final Path binary = dir.resolve("my bin").resolve("porelo");
        Files.createDirectories(binary.getParent());
        Files.writeString(
                binary,
                "#!/bin/sh\necho username=x-access-token\necho password=the-token\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        System.setProperty(PoreloBinary.NAMED, binary.toString());
        final Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "unused\n", StandardCharsets.UTF_8);
        final String id = source == Credential.Source.FILE ? tokenFile.toString() : "PATH";
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0,
                        name -> name.equals("PATH") ? System.getenv(name) : null);

        final List<String> arguments;
        try (FollowCredential lent =
                FollowCredential.open(
                        context,
                        URL,
                        "test",
                        "git",
                        new Credential(id, Credential.Kind.TOKEN, URL, null, "git", source))) {
            arguments = lent.arguments();
        }

        assertThat(arguments)
                .as("the inherited helper is reset before the sole scoped helper is installed")
                .hasSize(4)
                .startsWith("-c", "credential.helper=", "-c");
        assertThat(arguments.get(3)).startsWith("credential.https://forge.example.org.helper=");
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(arguments);
        command.addAll(List.of("credential", "fill"));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        final Process git = builder.start();
        git.getOutputStream()
                .write(
                        "protocol=https\nhost=forge.example.org\n\n"
                                .getBytes(StandardCharsets.UTF_8));
        git.getOutputStream().close();
        final String said = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(git.waitFor()).as(said).isZero();
        assertThat(said).contains("username=x-access-token").contains("password=the-token");
    }

    /**
     * A {@code user: $VAR} is read from the machine's environment, as the token's variable is: a
     * caller that runs git in an environment of its own names the user it gives, or git sends the
     * default and the forge refuses the token.
     */
    @org.junit.jupiter.api.Test
    void aUserNamedByAVariableIsReadFromTheMachinesEnvironment(@TempDir Path dir)
            throws IOException, InterruptedException {
        final Path binary = dir.resolve("bin").resolve("porelo");
        Files.createDirectories(binary.getParent());
        Files.writeString(
                binary,
                "#!/bin/sh\nuser=none\nwhile [ $# -gt 0 ]; do\n"
                        + "  [ \"$1\" = --username ] && user=$2\n  shift\ndone\n"
                        + "echo username=$user\necho password=the-token\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        System.setProperty(PoreloBinary.NAMED, binary.toString());
        final Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "unused\n", StandardCharsets.UTF_8);
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0,
                        name -> name.equals("PORELO_TEST_FORGE_USER") ? "alice" : null);

        final List<String> arguments;
        try (FollowCredential lent =
                FollowCredential.open(
                        context,
                        URL,
                        "test",
                        "git",
                        new Credential(
                                tokenFile.toString(),
                                Credential.Kind.TOKEN,
                                URL,
                                "$PORELO_TEST_FORGE_USER",
                                "git",
                                Credential.Source.FILE))) {
            arguments = lent.arguments();
        }

        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(arguments);
        command.addAll(List.of("credential", "fill"));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().remove("PORELO_TEST_FORGE_USER");
        final Process git = builder.start();
        git.getOutputStream()
                .write(
                        "protocol=https\nhost=forge.example.org\n\n"
                                .getBytes(StandardCharsets.UTF_8));
        git.getOutputStream().close();
        final String said = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(git.waitFor()).as(said).isZero();
        assertThat(said).as("the user the machine's environment names").contains("username=alice");
    }

    /** The address a forge on its own port is fetched from. */
    private static final String ON_ITS_PORT = "https://forge.example.org:8443/team/repo.git";

    /**
     * A forge on its own port is answered by what is declared for it or stored under its host:
     * missed, the fetch goes out without the token and reads as an unreachable repository.
     */
    @ParameterizedTest
    @ValueSource(strings = {"record-without-port", "record-with-port", "vault-entry"})
    void gitGetsTheTokenForAForgeOnItsOwnPort(final String source, @TempDir Path dir)
            throws IOException, InterruptedException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !source.equals("vault-entry") || KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        PoreloContext context = contextWithHelperStandIn(dir);
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            final String lentFrom;
            if (source.equals("vault-entry")) {
                lentFrom = "git.token.forge.example.org";
                context = context.asking(prompt -> null);
                Files.createDirectories(context.vault().path().getParent());
                context.vault()
                        .write(
                                Map.of(lentFrom, VaultEntry.of("unused")),
                                "test-passphrase".toCharArray());
                keyring.store("test-passphrase".toCharArray());
            } else {
                lentFrom =
                        declareFile(
                                context,
                                dir,
                                source.equals("record-with-port")
                                        ? "https://forge.example.org:8443"
                                        : "https://forge.example.org");
            }

            final GitAnswer answer =
                    credentialFill(leasedArguments(context, ON_ITS_PORT), "forge.example.org:8443");

            assertThat(answer.exit()).as(answer.said()).isZero();
            assertThat(answer.said())
                    .as("the credential lent for it")
                    .contains("password=lent:PORELO_GIT_CREDENTIAL_TOKEN");
        } finally {
            PoreloContext.forgetAsked();
            if (source.equals("vault-entry")) {
                keyring.forget();
            }
        }
    }

    /** An address without a port and one on the scheme's own port are one destination to git. */
    @ParameterizedTest
    @CsvSource({
        "https://forge.example.org/team/repo.git,      forge.example.org",
        "https://forge.example.org/team/repo.git,      forge.example.org:443",
        "https://forge.example.org:443/team/repo.git,  forge.example.org:443",
        "https://forge.example.org:443/team/repo.git,  forge.example.org",
        "https://[2001:db8::1]:8443/team/repo.git,     [2001:db8::1]:8443"
    })
    void gitGetsTheTokenForTheAddressItWasLentFor(
            final String url, final String asked, @TempDir Path dir)
            throws IOException, InterruptedException {
        final PoreloContext context = contextWithHelperStandIn(dir);
        declareFile(context, dir, "https://" + GitCredentialNames.hostOf(url));

        final GitAnswer answer = credentialFill(leasedArguments(context, url), asked);

        assertThat(answer.exit()).as(answer.said()).isZero();
        assertThat(answer.said())
                .as("the credential lent for it")
                .contains("password=lent:PORELO_GIT_CREDENTIAL_TOKEN");
    }

    /** A token lent to one port must not reach what else answers on that host. */
    @ParameterizedTest
    @ValueSource(strings = {"forge.example.org:9443", "forge.example.org"})
    void gitGetsNoTokenForAnotherPortOfTheSameHost(final String asked, @TempDir Path dir)
            throws IOException, InterruptedException {
        final PoreloContext context = contextWithHelperStandIn(dir);
        declareFile(context, dir, "https://forge.example.org");

        final GitAnswer answer = credentialFill(leasedArguments(context, ON_ITS_PORT), asked);

        assertThat(answer.exit()).as(answer.said()).isNotZero();
        assertThat(answer.said()).as("another port is another service").doesNotContain("password=");
    }

    /**
     * The credential is chosen by the host after the '@' and git connects to the one before the '#'
     * or '?', so a project file naming such an address would be handed another forge's token.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://forge.example.org#@other.example.org/team/repo.git",
                "https://forge.example.org?@other.example.org/team/repo.git"
            })
    void aHostTheAddressOnlySeemsToNameLendsItsTokenToNobody(final String url, @TempDir Path dir)
            throws IOException, InterruptedException {
        final PoreloContext context = contextWithHelperStandIn(dir);
        declareFile(context, dir, "https://other.example.org");

        final GitAnswer answer = credentialFill(leasedArguments(context, url), "forge.example.org");

        assertThat(answer.said())
                .as("the host git connects to gets no token")
                .doesNotContain("password=");
    }

    /**
     * A stand-in for ssh prints where git would connect and which key it was told to offer. git
     * decodes an {@code ssh://} address before it ends the host, and the key is chosen by the host
     * after the '@', so a project file could have one forge's key offered to another.
     */
    @ParameterizedTest
    @CsvSource({
        "ssh://forge.example%2F@other.example/x.git, forge.example, false",
        "ssh://git@forge.example%2F@other.example/x.git, git@forge.example, false",
        "ssh://git@other.example/x.git, git@other.example, true",
        "ssh://forge.example#@other.example/x.git, forge.example#@other.example, true"
    })
    void aKeyIsOfferedOnlyToTheHostItWasChosenFor(
            final String url, final String connectsTo, final boolean offered, @TempDir Path dir)
            throws IOException, InterruptedException {
        final PoreloContext context = contextWithHelperStandIn(dir);
        final Path key = Files.writeString(dir.resolve("a-key-file"), "unused\n");
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                key.toString(),
                                Credential.Kind.SSH_KEY,
                                "ssh://other.example",
                                null,
                                "git",
                                Credential.Source.FILE));
        final Path said = dir.resolve("said");
        final Path ssh = dir.resolve("own").resolve("ssh-stand-in");
        Files.writeString(
                ssh,
                "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + said + "'\nexit 1\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwx------"));

        final String command;
        try (FollowCredential lent = FollowCredential.open(context, url, "test")) {
            command = lent.environment().get("GIT_SSH_COMMAND");
        }
        assertThat(command).as("an ssh destination always has its policy").startsWith("ssh ");
        final ProcessBuilder builder =
                new ProcessBuilder("git", "ls-remote", "--end-of-options", url)
                        .directory(dir.toFile())
                        .redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_SSH_VARIANT", "ssh");
        builder.environment().put("GIT_SSH_COMMAND", ssh + command.substring("ssh".length()));
        final Process git = builder.start();
        git.getOutputStream().close();
        final String gitSaid =
                new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        git.waitFor();

        final List<String> arguments = Files.readAllLines(said);
        assertThat(arguments).as("where git would connect: %s", gitSaid).contains(connectsTo);
        assertThat(arguments.contains(key.toString()))
                .as("whether the key chosen for other.example is offered: %s", arguments)
                .isEqualTo(offered);
    }

    /** Over plain http a token would cross the wire in the clear. */
    @org.junit.jupiter.api.Test
    void aTokenIsNotLentOverPlainHttp(@TempDir Path dir) throws IOException {
        final PoreloContext context = contextWithHelperStandIn(dir);
        declareFile(context, dir, "http://forge.example.org:8080");

        try (FollowCredential lent =
                FollowCredential.open(
                        context, "http://forge.example.org:8080/team/repo.git", "test")) {
            assertThat(lent.arguments())
                    .as("only inherited helpers are cleared for an http address")
                    .containsExactly("-c", "credential.helper=");
            assertThat(lent.lent()).as("nothing was lent").isFalse();
        }
    }

    /** A Porelo standing in for 'porelo vault credential', saying which credential it was given. */
    private static PoreloContext contextWithHelperStandIn(final Path dir) throws IOException {
        final Path binary = dir.resolve("own").resolve("porelo");
        Files.createDirectories(binary.getParent());
        Files.writeString(
                binary,
                "#!/bin/sh\necho username=x-access-token\necho \"password=lent:$4\"\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        System.setProperty(PoreloBinary.NAMED, binary.toString());
        return new PoreloContext(
                new FakeCommandRunner(),
                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                arguments -> 0,
                name -> null);
    }

    /** Declares a token file for a destination and returns where the helper is told to read it. */
    private static String declareFile(final PoreloContext context, final Path dir, final String to)
            throws IOException {
        final Path token = Files.writeString(dir.resolve("token"), "unused\n");
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                token.toString(),
                                Credential.Kind.TOKEN,
                                to,
                                null,
                                "git",
                                Credential.Source.FILE));
        return token.toString();
    }

    private static List<String> leasedArguments(final PoreloContext context, final String url) {
        try (FollowCredential lent = FollowCredential.open(context, url, "test")) {
            return lent.arguments();
        }
    }

    /** Asks real git for the credential of a host, as it asks when it connects there. */
    private static GitAnswer credentialFill(final List<String> arguments, final String host)
            throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(arguments);
        command.addAll(List.of("credential", "fill"));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("LC_ALL", "C");
        final Process git = builder.start();
        git.getOutputStream()
                .write(("protocol=https\nhost=" + host + "\n\n").getBytes(StandardCharsets.UTF_8));
        git.getOutputStream().close();
        final String said = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitAnswer(git.waitFor(), said);
    }

    private record GitAnswer(int exit, String said) {}
}
