package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

        assertThat(arguments).hasSize(2);
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
}
