package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Discovery's SSH policy, interpreted by OpenSSH without connecting to a host. */
class IdentityProposeSshTest {
    @TempDir Path dir;
    private final List<Command> commands = new ArrayList<>();
    private Path state;
    private String socket;
    private Map<?, ?> answer;

    // Extra trust or agent forwarding can expose the person's identity to another host.
    @Test
    void proposalUsesOnlyPorelosTrustAndDisablesForwarding() throws Exception {
        propose("trusted", "my  state", false, "http://127.0.0.1:1");
        SshPolicyFixture.assertIsolated(SshPolicyFixture.effective(dir, sshArguments()));
        assertThat(answer.get("login")).isEqualTo(Map.of("value", "alice", "source", "ssh"));
    }

    // Inherited desktop variables or an unselected socket can ask or offer another person's key.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void proposalChoosesItsEnvironmentAndNeverAsks(boolean withSocket) throws Exception {
        socket = withSocket ? dir.resolve("selected-agent.sock").toString() : null;
        propose("trusted", "state", false, "http://127.0.0.1:1");
        final Command ssh = commands.getFirst();
        assertThat(ssh.inheritsEnvironment())
                .as("no ambient agent or desktop reaches SSH")
                .isFalse();
        assertThat(ssh.environment())
                .containsEntry("SSH_ASKPASS_REQUIRE", "never")
                .containsEntry("PATH", "/usr/bin:/bin");
        assertThat(ssh.environment().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        withSocket
                                ? List.of("PATH", "SSH_ASKPASS_REQUIRE", "SSH_AUTH_SOCK")
                                : List.of("PATH", "SSH_ASKPASS_REQUIRE"));
        if (withSocket) assertThat(ssh.environment()).containsEntry("SSH_AUTH_SOCK", socket);
    }

    // Splitting the pin path can trust a relative known-hosts file in an untrusted directory.
    @Test
    void openSshReadsTheWholePinPathWithConsecutiveSpaces() throws Exception {
        propose("trusted", "my  state", false, "http://127.0.0.1:1");
        assertThat(
                        SshPolicyFixture.effective(dir, sshArguments())
                                .lines()
                                .filter(line -> line.startsWith("userknownhostsfile ")))
                .containsExactly("userknownhostsfile " + state.resolve("known_hosts"));
    }

    // An unrepresentable path can turn one pinned file into several SSH options or paths.
    @ParameterizedTest
    @ValueSource(strings = {"state\"quoted", "state\nline", "state\rline"})
    void proposalRefusesAnUnsafePinPathBeforeStartingSsh(String path) throws Exception {
        assertThat(propose("trusted", path, false, "http://127.0.0.1:1")).isEqualTo(64);
        assertThat(commands).isEmpty();
    }

    // Asking port 22 about a forge on another port can identify the person to the wrong service.
    @Test
    void proposalUsesTheConfiguredSshPortAndNamesItsPin() throws Exception {
        propose("unknown", "state", true, "https://127.0.0.1");
        assertThat(sshArguments()).containsSubsequence("-p", "7999");
        assertThat(String.valueOf(answer.get("notes")))
                .contains("host key for 127.0.0.1:7999", "trust-host 127.0.0.1:7999");
    }

    // Brackets belong to the URL and pin entry, and prevent SSH from resolving an IPv6 host.
    @Test
    void proposalPassesAnUnbracketedIpv6HostOnTheSshPort() throws Exception {
        propose("unknown", "state", false, "http://[::1]:1");
        assertThat(sshArguments()).contains("git@::1").doesNotContain("git@[::1]");
        assertThat(SshPolicyFixture.effective(dir, sshArguments())).contains("port 22\n");
        assertThat(String.valueOf(answer.get("notes")))
                .contains("host key for ::1", "trust-host ::1");
    }

    // Appending a changed key leaves the old key trusted, so the proposal must not suggest it.
    @Test
    void proposalExplainsAChangedKeyWithoutATrustHostInstruction() throws Exception {
        propose("changed", "state", false, "http://127.0.0.1:1");
        assertThat(answer.get("login")).isNull();
        assertThat(String.valueOf(answer.get("notes")))
                .contains("host key", "changed", "administrator")
                .doesNotContain("trust-host");
    }

    // An unknown key must explain the missing login and the comparison needed to confirm it.
    @Test
    void proposalExplainsAnUnknownKeyAndHowToConfirmIt() throws Exception {
        propose("unknown", "state", false, "http://127.0.0.1:1");
        assertThat(answer.get("login")).isNull();
        assertThat(String.valueOf(answer.get("notes")))
                .contains("not trusted", "administrator", "trust-host 127.0.0.1");
    }

    private List<String> sshArguments() {
        final List<String> arguments = commands.getFirst().arguments();
        return arguments.subList(arguments.indexOf("ssh") + 1, arguments.size());
    }

    private int propose(String response, String stateName, boolean configured, String forge)
            throws Exception {
        state = dir.resolve(stateName).resolve("porelo");
        final XdgPaths xdg =
                new XdgPaths(
                        dir.resolve("config/porelo"),
                        dir.resolve("data/porelo"),
                        state,
                        dir.resolve("run/porelo"),
                        dir);
        if (configured) {
            Files.createDirectories(xdg.config());
            Files.writeString(
                    xdg.config().resolve("organization.yml"),
                    "adapter: git\nsettings:\n  repository: ssh://git@127.0.0.1:7999/team/repo\n");
        }
        final String signingKey =
                "key::"
                        + new org.fuin.porelo.vault.SigningKey(new byte[32], "fixture")
                                .authorizedKeysLine();
        final PoreloContext context =
                new PoreloContext(
                        command -> {
                            if (command.program().equals("timeout")) {
                                commands.add(command);
                                return new CommandResult(
                                        command,
                                        response.equals("trusted") ? 1 : 255,
                                        "",
                                        switch (response) {
                                            case "unknown" -> "Host key verification failed.";
                                            case "changed" ->
                                                    "REMOTE HOST IDENTIFICATION HAS CHANGED!";
                                            default -> "Hi alice!";
                                        });
                            }
                            final String value =
                                    switch (command.arguments().getLast()) {
                                        case "user.name" -> "Alice";
                                        case "user.email" -> "alice@example.org";
                                        case "user.signingkey" -> signingKey;
                                        default -> "";
                                    };
                            return new CommandResult(command, value.isEmpty() ? 1 : 0, value, "");
                        },
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0,
                        name ->
                                switch (name) {
                                    case "PATH" -> "/usr/bin:/bin";
                                    case "SSH_AUTH_SOCK" -> socket;
                                    case "SSH_ASKPASS_REQUIRE" -> "force";
                                    case "SSH_ASKPASS" -> dir.resolve("askpass").toString();
                                    case "DISPLAY" -> ":575";
                                    default -> null;
                                });
        final var cli = PoreloCli.commandLine(context);
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        final List<String> arguments = new ArrayList<>(List.of("identity", "propose", "--json"));
        if (!configured) arguments.addAll(List.of("--forge", forge));
        final int exit = cli.execute(arguments.toArray(String[]::new));
        if (exit == 0) answer = (Map<?, ?>) Json.parse(out.toString());
        return exit;
    }
}
