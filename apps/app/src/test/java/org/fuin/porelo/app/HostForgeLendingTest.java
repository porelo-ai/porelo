package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.GitCredentials;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** On a host, its own forge is read with a token asked for then, and nothing else gets it. */
class HostForgeLendingTest {

    private static final GitCredentials.Lease FROM_THE_VAULT =
            new GitCredentials.Lease() {
                @Override
                public Map<String, String> environment() {
                    return Map.of("FROM", "vault");
                }

                @Override
                public List<String> arguments() {
                    return List.of();
                }

                @Override
                public void close() {}
            };

    @TempDir private Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path conf;

    private GitCredentials lending;
    private PoreloContext context;

    @BeforeEach
    void host() throws Exception {
        conf = dir.resolve("forge.conf");
        Files.writeString(conf, "web_port=3000\ngit_port=3001\n");
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        lending = new HostForgeLending(context, url -> FROM_THE_VAULT, conf, () -> true);
    }

    @Test
    void itsForgeIsLentATokenInGitsEnvironmentAndNeverAnArgument() {
        runner.answering("sudo", "a-token\n");

        final GitCredentials.Lease lease = lending.forUrl("http://127.0.0.1:3001/demo/first.git");

        assertThat(lease.arguments()).isEmpty();
        assertThat(runner.lines())
                .as("a token of its own for this command, which leaves the person's other tokens")
                .containsExactly(
                        HostForgeLending.SUDO
                                + " -n "
                                + context.paths().hostProgram()
                                + " forge lend-token");
        final var asked = runner.only("lend-token");
        assertThat(asked.inheritsEnvironment())
                .as("the session's own variables, which the host refuses")
                .isFalse();
        assertThat(asked.environment()).isEqualTo(HostForgeLending.HOST_ENVIRONMENT);
        assertThat(asked.workingDirectory()).isEqualTo(Path.of("/"));
        assertThat(lease.environment())
                .containsEntry("GIT_CONFIG_KEY_0", "http.http://127.0.0.1:3001/.extraHeader")
                .containsEntry(
                        "GIT_CONFIG_VALUE_0",
                        "Authorization: Basic "
                                + Base64.getEncoder()
                                        .encodeToString(
                                                "x-access-token:a-token"
                                                        .getBytes(StandardCharsets.UTF_8)))
                .containsEntry("GIT_CONFIG_VALUE_1", "false");
    }

    @Test
    void gitSendsTheTokenToThatForgeAloneEvenWhenItsSettingsRewriteTheAddress() throws Exception {
        runner.answering("sudo", "a-token\n");

        final Map<String, String> lent =
                lending.forUrl("http://127.0.0.1:3001/demo/first.git").environment();

        assertThat(sentTo(lent, "http://127.0.0.1:3001/demo/first.git"))
                .startsWith("Authorization");
        assertThat(sentTo(lent, "http://elsewhere.example/demo/first.git")).isEmpty();
        assertThat(sentTo(lent, "http://127.0.0.1:3002/demo/first.git")).isEmpty();
    }

    private static String sentTo(final Map<String, String> lent, final String address)
            throws Exception {
        final ProcessBuilder git =
                new ProcessBuilder("git", "config", "--get-urlmatch", "http.extraHeader", address);
        git.environment().putAll(lent);
        final Process asked = git.redirectErrorStream(true).start();
        final String said =
                new String(asked.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        asked.waitFor();
        return said.strip();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1:3002/demo/first.git",
                "http://127.0.0.1:30011/demo/first.git",
                "http://127.0.0.1:3001@evil.example/demo/first.git",
                "http://localhost:3001/demo/first.git",
                "https://127.0.0.1:3001/demo/first.git",
                "https://forge.example/demo/first.git"
            })
    void anythingElseAsksTheHostForNothing(final String address) {
        runner.answering("sudo", "a-token\n");

        assertThat(lending.forUrl(address).environment()).containsEntry("FROM", "vault");
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void aMachineThatIsNoHostLendsWhatItAlwaysDid() throws Exception {
        Files.delete(conf);

        assertThat(lending.forUrl("http://127.0.0.1:3001/demo/first.git").environment())
                .containsEntry("FROM", "vault");
    }

    @Test
    void aMissingHostToolRefusesBeforeBorrowingOtherCredentials() {
        lending =
                new HostForgeLending(
                        context,
                        url -> {
                            throw new AssertionError("no fallback");
                        },
                        conf,
                        () -> false);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> lending.forUrl("http://127.0.0.1:3001/demo/first.git"))
                .isInstanceOf(org.fuin.porelo.gate.GateException.class)
                .hasMessageContaining("/usr/sbin/porelo-host");
        assertThat(runner.invocations()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {64, 65, 69, 73, 77, 78})
    void aHostThatGivesNoTokenLeavesTheVaultsAnswer(int code) {
        runner.failing("sudo", code, "refused");

        assertThat(lending.forUrl("http://127.0.0.1:3001/demo/first.git").environment())
                .containsEntry("FROM", "vault");
    }
}
