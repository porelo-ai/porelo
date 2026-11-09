package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnattendedGitTest {
    @TempDir Path directory;
    private final List<Command> commands = new ArrayList<>();
    private final String key = PersonCard.keyLine(SigningKey.generate("signer"));
    private String configured;

    private PoreloContext context() {
        return new PoreloContext(
                command -> {
                    commands.add(command);
                    String answer = "";
                    if (command.program().equals("ssh-add")) answer = key;
                    if (command.arguments().contains("gpg.format")) answer = "ssh";
                    if (command.arguments().contains("user.signingkey")) answer = configured;
                    return new CommandResult(command, 0, answer, "");
                },
                new PoreloPaths(XdgPaths.of(name -> null, directory), directory.resolve("bin")),
                args -> 0,
                name -> name.equals("SSH_AUTH_SOCK") ? "/checked-agent" : null);
    }

    private Command merge() {
        return Command.of("git", "merge", "-S", "topic");
    }

    @Test
    void signingUsesTheCheckedSocketAndStripsEnvironmentConfig() {
        configured = "key::" + key;
        UnattendedGit.runner(context())
                .run(
                        merge().withEnvironment(
                                        Map.of(
                                                "SSH_AUTH_SOCK",
                                                "/another-agent",
                                                "GIT_CONFIG_COUNT",
                                                "1",
                                                "GIT_CONFIG_KEY_0",
                                                "user.signingkey",
                                                "GIT_CONFIG_VALUE_0",
                                                "other",
                                                "GIT_CONFIG_PARAMETERS",
                                                "'gpg.ssh.program=unexpected'")));
        final Command signed = commands.getLast();
        assertThat(signed.environment())
                .containsEntry("SSH_AUTH_SOCK", "/checked-agent")
                .doesNotContainKeys(
                        "GIT_CONFIG_COUNT",
                        "GIT_CONFIG_KEY_0",
                        "GIT_CONFIG_VALUE_0",
                        "GIT_CONFIG_PARAMETERS");
        assertThat(signed.inheritsEnvironment()).isFalse();
    }

    @Test
    void aPrivateFileCanNameItsAgentServedPublicCompanion() throws Exception {
        configured = directory.resolve("private-key").toString();
        Files.writeString(Path.of(configured + ".pub"), key + "\n");
        UnattendedGit.runner(context()).run(merge());
        assertThat(commands.getLast().arguments()).contains("user.signingkey=key::" + key);
    }

    @Test
    void aDefaultKeyCommandIsRefusedWithTheExplicitConfigurationNamed() {
        configured = "";
        assertThatThrownBy(() -> UnattendedGit.runner(context()).run(merge()))
                .isInstanceOf(UnattendedGit.Unavailable.class)
                .hasMessageContaining("user.signingkey");
        assertThat(commands).noneMatch(command -> command.arguments().contains("merge"));
    }

    @Test
    void aFetchKeepsTheSocketAndConfigurationLentForItsRemote() {
        UnattendedGit.runner(context())
                .run(
                        Command.of("git", "fetch", "origin")
                                .withEnvironment(
                                        Map.of(
                                                "SSH_AUTH_SOCK",
                                                "/lent-for-remote",
                                                "GIT_CONFIG_COUNT",
                                                "1",
                                                "GIT_CONFIG_KEY_0",
                                                "http.extraHeader",
                                                "GIT_CONFIG_VALUE_0",
                                                "X-Fixture: present")));
        assertThat(commands.getLast().environment())
                .containsEntry("SSH_AUTH_SOCK", "/lent-for-remote")
                .containsEntry("GIT_CONFIG_COUNT", "1")
                .containsEntry("GIT_CONFIG_VALUE_0", "X-Fixture: present");
    }

    @Test
    void pipingOutputKeepsGitUnattendedEvenWithTerminalInput() {
        final var machine =
                context()
                        .reading(
                                new StandardInput(
                                        java.io.InputStream.nullInputStream(), true, false));
        UnattendedGit.runner(machine).run(Command.of("git", "fetch", "origin"));
        assertThat(commands.getLast().environment())
                .as("Git must not open a hidden prompt when output goes to a pipe")
                .containsEntry("GIT_TERMINAL_PROMPT", "0");
    }

    @Test
    void theHelperResetAlsoClearsUrlScopedHelpers() throws Exception {
        final Path global = directory.resolve("gitconfig");
        Files.writeString(
                global,
                "[credential \"https://forge.example\"]\n helper = !echo username=unexpected; echo password=unexpected\n");
        final var machine =
                new PoreloContext(
                        new org.fuin.porelo.core.process.ProcessCommandRunner(),
                        context().paths(),
                        arguments -> 0);
        final var result =
                UnattendedGit.runner(machine)
                        .run(
                                Command.of("git", "credential", "fill")
                                        .withEnvironment(
                                                Map.of(
                                                        "GIT_CONFIG_GLOBAL",
                                                        global.toString(),
                                                        "GIT_CONFIG_NOSYSTEM",
                                                        "1"))
                                        .withInput("protocol=https\nhost=forge.example\n\n"));
        assertThat(result.successful())
                .as("the scoped inherited helper cannot supply a credential")
                .isFalse();
        assertThat(result.standardOutput()).doesNotContain("unexpected");
    }

    @Test
    void aTerminalBypassesAllUnattendedPreparation() {
        final var machine =
                context().reading(new StandardInput(java.io.InputStream.nullInputStream(), true));
        final var command = merge().withEnvironment(Map.of("GIT_CONFIG_COUNT", "1"));
        UnattendedGit.runner(machine).run(command);
        assertThat(commands).containsExactly(command);
    }
}
