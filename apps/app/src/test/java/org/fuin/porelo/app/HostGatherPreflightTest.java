package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostGatherPreflightTest {
    @TempDir Path dir;
    private static final String TOKEN = "central-test-credential";
    private static final String ADVERTISEMENT = "001f# service=git-receive-pack\n0000";
    private final List<Command> commands = new ArrayList<>();
    private final List<String> probeInputs = new ArrayList<>();
    private int probes;
    private int refuseAt = 1;
    private String response = "401";
    private int probeExit;

    private HostGatherConfig config() {
        return new HostGatherConfig(
                "http://127.0.0.1:3001",
                "acme",
                Path.of("/etc/central.pem"),
                List.of(
                        new HostGatherConfig.Repository(
                                "first", "https://central.example/acme/first.git"),
                        new HostGatherConfig.Repository(
                                "second", "https://other.example/acme/second.git")));
    }

    private CommandResult run(Command command) {
        commands.add(command);
        if (command.program().endsWith("/curl")) {
            probeInputs.add(
                    command.binaryInput() == null
                            ? ""
                            : new String(
                                    command.binaryInput(),
                                    java.nio.charset.StandardCharsets.UTF_8));
            probes++;
            return new CommandResult(
                    command,
                    probeExit,
                    probes == refuseAt ? response : ADVERTISEMENT + "\n200",
                    "");
        }
        return new CommandResult(command, 0, "approved\n", "");
    }

    @ParameterizedTest
    @ValueSource(ints = {18, 22, 28, 63})
    void anObserved401SurvivesAFailedResponseBody(int exit) throws Exception {
        // A cut-short error body must not erase the authentication refusal or its wire evidence.
        probeExit = exit;
        final var failure =
                assertThrows(
                        HostGatherRefusal.class,
                        () ->
                                new HostGatherFlow(this::run, Path.of("/usr/bin/git"), dir)
                                        .transfer(
                                                false,
                                                config(),
                                                List.of(),
                                                "local-fixture".toCharArray(),
                                                TOKEN.toCharArray(),
                                                new PrintWriter(new StringWriter()),
                                                new PrintWriter(new StringWriter()),
                                                (r, s) -> {}));
        assertThat(failure.kind())
                .as("the status was observed even when the body failed")
                .isEqualTo(HostGatherRefusal.Kind.CENTRAL_TOKEN_REFUSED);
        assertThat(failure.details())
                .containsEntry("status", 401)
                .containsEntry("forge", "central.example");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void refusedCentralStopsEveryPushBeforeTransfer(int destination) {
        // A bad token on any destination must not leave an earlier repository released.
        refuseAt = destination;
        final var error =
                assertThrows(
                        HostGatherRefusal.class,
                        () ->
                                new HostGatherFlow(this::run, Path.of("/usr/bin/git"), dir)
                                        .transfer(
                                                false,
                                                config(),
                                                List.of(),
                                                "local-test-credential".toCharArray(),
                                                TOKEN.toCharArray(),
                                                new PrintWriter(new StringWriter()),
                                                new PrintWriter(new StringWriter()),
                                                (r, s) -> {}));
        assertThat(error.kind())
                .as("HTTP authentication is a typed refusal")
                .isEqualTo(HostGatherRefusal.Kind.CENTRAL_TOKEN_REFUSED);
        assertThat(error.details())
                .as("the refusal carries evidence and honest completion counts")
                .containsEntry("forge", destination == 1 ? "central.example" : "other.example")
                .containsEntry("status", 401)
                .containsEntry("pushed", List.of());
        assertThat(commands)
                .as("preflight cannot clone, push, or invoke a prompt")
                .allMatch(command -> command.program().endsWith("/curl"));
    }

    @Test
    void cliNamesTheRefusingForgeAndStatusWithoutLeakingCredentials() {
        // A refusal must not expose a token or trick a person into a credential prompt.
        response = TOKEN + " " + basic() + "\n401";
        final var out = new StringWriter();
        final var err = new StringWriter();
        final int code =
                new HostGatherFlow(this::run, Path.of("/usr/bin/git"), dir)
                        .run(
                                false,
                                config(),
                                List.of(),
                                "local-test-credential".toCharArray(),
                                TOKEN.toCharArray(),
                                new PrintWriter(out),
                                new PrintWriter(err));
        assertThat(code).as("central authentication failure is nonzero").isNotZero();
        assertThat(err.toString())
                .as("the refusal names its HTTP evidence")
                .contains("central.example", "401");
        assertThat(err.toString() + out + commands)
                .as("no process diagnostic or secret is rendered")
                .doesNotContain(TOKEN, basic(), "Username", "vault put");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"302", "403", "404", "000", "200", "<html>login</html>\n200", "401 extra"})
    void unusablePreflightCannotAuthorizeATransfer(String reply) {
        // Redirects, anonymous pages and malformed status must not authorize a release.
        response = reply;
        assertThrows(
                HostGatherRefusal.class,
                () ->
                        new HostGatherFlow(this::run, Path.of("/usr/bin/git"), dir)
                                .transfer(
                                        false,
                                        config(),
                                        List.of(),
                                        "local-test-credential".toCharArray(),
                                        TOKEN.toCharArray(),
                                        new PrintWriter(new StringWriter()),
                                        new PrintWriter(new StringWriter()),
                                        (r, s) -> {}));
        assertThat(commands)
                .as("unproven credentials cannot start a transfer")
                .noneMatch(command -> command.arguments().contains("push"));
    }

    @Test
    void allAuthenticationProbesPrecedeTheFirstPushAndCarryOnlyCentralTrust() {
        // A public read must not bypass checking write authentication or leak to another endpoint.
        refuseAt = 0;
        final int code =
                new HostGatherFlow(this::run, Path.of("/usr/bin/git"), dir)
                        .run(
                                false,
                                config(),
                                List.of(),
                                "local-test-credential".toCharArray(),
                                TOKEN.toCharArray(),
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(new StringWriter()));
        assertThat(code).as("both authenticated receive endpoints answered").isZero();
        assertThat(probeInputs)
                .as("each probe presents the same central credential as Git")
                .containsExactly(
                        "header = \"Authorization: Basic " + basic() + "\"\n",
                        "header = \"Authorization: Basic " + basic() + "\"\n");
        assertThat(commands.subList(0, 2))
                .as("all destinations are checked before any clone or push")
                .allSatisfy(
                        command -> {
                            assertThat(command.program()).endsWith("/curl");
                            assertThat(command.arguments())
                                    .contains(
                                            "--disable",
                                            "--globoff",
                                            "--cacert",
                                            "/etc/central.pem",
                                            "--proto",
                                            "=https",
                                            "--max-time",
                                            "--noproxy",
                                            "*")
                                    .noneMatch(
                                            arg ->
                                                    arg.equals("--location")
                                                            || arg.equals("--insecure"));
                            assertThat(command.arguments().getLast())
                                    .endsWith("/info/refs?service=git-receive-pack");
                            assertThat(command.inheritsEnvironment()).isFalse();
                            assertThat(command.binaryInput())
                                    .as("authorization travels through stdin")
                                    .isNotNull();
                            assertThat(
                                            new String(
                                                    command.binaryInput(),
                                                    java.nio.charset.StandardCharsets.UTF_8))
                                    .doesNotContain(TOKEN, "local-test-credential");
                        });
    }

    @Test
    void transferTimeRefusalKeepsEarlierCompletedPushes() {
        // A later token revocation must never pretend an earlier successful write rolled back.
        refuseAt = 0;
        final var error =
                assertThrows(
                        HostGatherRefusal.class,
                        () ->
                                new HostGatherFlow(
                                                command -> {
                                                    if (command.arguments().contains("push")
                                                            && command.arguments()
                                                                    .contains(
                                                                            "https://other.example/acme/second.git"))
                                                        return new CommandResult(
                                                                command,
                                                                128,
                                                                "",
                                                                "fatal: unable to access 'https://other.example/acme/second.git/': The requested URL returned error: 401");
                                                    return run(command);
                                                },
                                                Path.of("/usr/bin/git"),
                                                dir)
                                        .transfer(
                                                false,
                                                config(),
                                                List.of(),
                                                "local-test-credential".toCharArray(),
                                                TOKEN.toCharArray(),
                                                new PrintWriter(new StringWriter()),
                                                new PrintWriter(new StringWriter()),
                                                (r, s) -> {}));
        assertThat(error.details())
                .as("completed work and the later HTTP refusal survive")
                .containsEntry("pushed", List.of("first"))
                .containsEntry("status", 401)
                .containsEntry("forge", "other.example");
    }

    @Test
    void runnerDeliversPrivateProbeInputWithoutPuttingItInArguments() {
        // Discarding the probe's authorization could accept an anonymous answer as authenticated.
        try (var runner = new HostGatherRunner(java.time.Duration.ofSeconds(2), () -> true)) {
            final var answer =
                    runner.run(
                            Command.of("/usr/bin/cat")
                                    .withChosenEnvironment(Map.of())
                                    .withBinaryInput(
                                            "fixture-input"
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
            assertThat(answer.standardOutput())
                    .as("the checked child receives private input")
                    .isEqualTo("fixture-input");
        }
    }

    private static String basic() {
        return Base64.getEncoder()
                .encodeToString(
                        ("x-access-token:" + TOKEN)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
