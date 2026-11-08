package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the one {@code porelo-mcp} a task the project allows an endpoint gets: whether it gets
 * one, what it is told, how it is handed its token, and what its exit says.
 *
 * <p>Against a stand-in {@code porelo-mcp}, a shell script that writes down what it was given and
 * then behaves as each test asks.
 */
class McpWiringTest {

    private static final String TASK = "porelo-p-task";

    private static final String PROJECT = "p";

    /** Ready, then running until stopped, which ends it with 0 as porelo-mcp's SIGTERM does. */
    private static final String RUNS = "trap 'exit 0' TERM; echo ready; sleep 30 & wait $!";

    @TempDir Path dir;

    private final List<String> opened = new ArrayList<>();

    private final List<String> closed = new ArrayList<>();

    private McpDaemonSockets.Opener before = McpDaemonSockets.NONE;

    private String binaryBefore;

    private Path daemonSocket;

    @BeforeEach
    void install() {
        daemonSocket = dir.resolve("run/porelo/mcp/" + TASK + ".daemon.sock");
        before = McpDaemonSockets.install(opening(Optional.of(daemonSocket)));
        binaryBefore = System.getProperty(McpWiring.BINARY_PROPERTY);
    }

    @AfterEach
    void restore() {
        McpDaemonSockets.install(before);
        if (binaryBefore == null) {
            System.clearProperty(McpWiring.BINARY_PROPERTY);
        } else {
            System.setProperty(McpWiring.BINARY_PROPERTY, binaryBefore);
        }
        // Whatever a test left running, by the pid file it was recorded in, never by name.
        TaskLifecycle.stopHelpers(context().paths().tasks().containerState(TASK));
    }

    @Test
    void aProjectThatAllowsNoEndpointGetsNoProcessAndNothingChanges() throws Exception {
        McpDaemonSockets.install(opening(Optional.empty()));
        stand(RUNS);
        final PoreloContext context = context();

        assertThat(McpWiring.open(context, TASK, PROJECT)).isEmpty();

        assertThat(opened).containsExactly(TASK + "@" + PROJECT);
        assertThat(context.paths().xdg().runtime().resolve("mcp")).doesNotExist();
        assertThat(McpWiring.statusLine(context, TASK)).isNull();
    }

    @Test
    void theCommandNamesTheTaskItsSocketAndTheDaemonSocketAndNoToken() throws Exception {
        stand(RUNS);
        final PoreloContext context = context();
        final McpWiring.Endpoint endpoint = McpWiring.open(context, TASK, PROJECT).orElseThrow();

        McpWiring.start(context, endpoint);

        final List<String> arguments = Files.readAllLines(dir.resolve("args"));
        assertThat(arguments)
                .containsExactly(
                        "serve",
                        "--task",
                        TASK,
                        "--socket",
                        context.paths().xdg().runtime().resolve("mcp/" + TASK + ".sock").toString(),
                        "--daemon-socket",
                        daemonSocket.toString());
        assertThat(String.join(" ", arguments)).doesNotContain(endpoint.token());
        assertThat(Files.readString(dir.resolve("env"))).doesNotContain(endpoint.token());
        assertThat(endpoint.toString()).doesNotContain(endpoint.token());
        assertThat(context.paths().xdg().runtime().resolve("mcp"))
                .satisfies(
                        made ->
                                assertThat(
                                                PosixFilePermissions.toString(
                                                        Files.getPosixFilePermissions(made)))
                                        .isEqualTo("rwx------"));
    }

    @Test
    void theTokenIsOneLineOfSixtyFourHexCharactersOnStandardInput() throws Exception {
        stand(RUNS);
        final PoreloContext context = context();
        final McpWiring.Endpoint endpoint = McpWiring.open(context, TASK, PROJECT).orElseThrow();

        McpWiring.start(context, endpoint);

        assertThat(endpoint.token()).matches("[0-9a-f]{64}");
        assertThat(Files.readString(dir.resolve("token"))).isEqualTo(endpoint.token() + "\n");
        assertThat(McpWiring.open(context, "porelo-p-other", PROJECT).orElseThrow().token())
                .as("a token of its own for every start")
                .isNotEqualTo(endpoint.token());
    }

    @Test
    void theStartWaitsForReadyBeforeTheTaskCountsAsStartedWithIt() throws Exception {
        // Ready only after a second: a start that did not wait returns before the line is there.
        stand("sleep 1; " + RUNS);
        final PoreloContext context = context();
        final McpWiring.Endpoint endpoint = McpWiring.open(context, TASK, PROJECT).orElseThrow();

        McpWiring.start(context, endpoint);

        final Path state = context.paths().tasks().containerState(TASK);
        assertThat(Files.readString(state.resolve(McpWiring.OUT))).contains("ready");
        assertThat(TaskLifecycle.alive(state.resolve(McpWiring.HELPER + ".pid"))).isTrue();
        assertThat(McpWiring.statusLine(context, TASK)).isEqualTo("running, " + endpoint.socket());
    }

    @Test
    void anExitTwoBeforeReadyIsRefusedAsRefusedAtStart() throws Exception {
        stand("exit 2");
        final PoreloContext context = context();
        final McpWiring.Endpoint endpoint = McpWiring.open(context, TASK, PROJECT).orElseThrow();

        assertThatThrownBy(() -> McpWiring.start(context, endpoint))
                .isInstanceOfSatisfying(
                        McpWiring.Refused.class,
                        refused -> {
                            assertThat(refused.refusal())
                                    .isEqualTo(McpWiring.Refusal.REFUSED_AT_START);
                            assertThat(refused.getMessage()).contains("refused", "exit 2");
                        });
        assertThat(McpWiring.statusLine(context, TASK)).isEqualTo("failed, exit 2");
    }

    @Test
    void anExitThreeBeforeReadyIsRefusedAsItsDaemonSocketUnreachable() throws Exception {
        stand("exit 3");
        final PoreloContext context = context();
        final McpWiring.Endpoint endpoint = McpWiring.open(context, TASK, PROJECT).orElseThrow();

        assertThatThrownBy(() -> McpWiring.start(context, endpoint))
                .isInstanceOfSatisfying(
                        McpWiring.Refused.class,
                        refused -> {
                            assertThat(refused.refusal())
                                    .isEqualTo(McpWiring.Refusal.DAEMON_UNREACHABLE);
                            assertThat(refused.getMessage()).contains("daemon socket", "exit 3");
                        });
        assertThat(McpWiring.statusLine(context, TASK))
                .isEqualTo("could not reach its daemon socket, exit 3");
    }

    @Test
    void anyOtherEndBeforeReadyIsAFailedStart() throws Exception {
        stand("exit 1");
        final PoreloContext context = context();
        final McpWiring.Endpoint endpoint = McpWiring.open(context, TASK, PROJECT).orElseThrow();

        assertThatThrownBy(() -> McpWiring.start(context, endpoint))
                .isInstanceOfSatisfying(
                        McpWiring.Refused.class,
                        refused ->
                                assertThat(refused.refusal()).isEqualTo(McpWiring.Refusal.FAILED));
    }

    @Test
    void anExitOneAfterReadyShowsAsFailedWithItsCode() throws Exception {
        stand("echo ready; sleep 1; exit 1");
        final PoreloContext context = context();
        final McpWiring.Endpoint endpoint = McpWiring.open(context, TASK, PROJECT).orElseThrow();

        McpWiring.start(context, endpoint);

        awaitStatus(context, "failed, exit 1");
    }

    @Test
    void anExitZeroShowsAsStopped() throws Exception {
        stand("echo ready; sleep 1; exit 0");
        final PoreloContext context = context();
        final McpWiring.Endpoint endpoint = McpWiring.open(context, TASK, PROJECT).orElseThrow();

        McpWiring.start(context, endpoint);

        awaitStatus(context, "stopped");
    }

    @Test
    void aMachineWithoutTheBinaryIsRefusedNamingThePackage() throws Exception {
        System.setProperty(McpWiring.BINARY_PROPERTY, dir.resolve("absent/porelo-mcp").toString());
        final PoreloContext context = context();

        assertThatThrownBy(() -> McpWiring.open(context, TASK, PROJECT))
                .isInstanceOfSatisfying(
                        McpWiring.Refused.class,
                        refused -> {
                            assertThat(refused.refusal())
                                    .isEqualTo(McpWiring.Refusal.NOT_INSTALLED);
                            assertThat(refused.getMessage()).contains("porelo-mcp package");
                        });
        assertThat(closed).as("the socket opened for it is closed again").containsExactly(TASK);
    }

    @Test
    void theStopEndsTheProcessClosesTheDaemonSocketAndRemovesItsOwn() throws Exception {
        // The stand-in binds nothing, so it makes the file the real one would bind.
        stand("touch \"$5\"; " + RUNS);
        final PoreloContext context = context();
        final McpWiring.Endpoint endpoint = McpWiring.open(context, TASK, PROJECT).orElseThrow();
        McpWiring.start(context, endpoint);
        final Path state = context.paths().tasks().containerState(TASK);
        final ProcessHandle process =
                org.fuin.porelo.wire.HelperPid.verified(state.resolve(McpWiring.HELPER + ".pid"))
                        .orElseThrow();
        assertThat(endpoint.socket()).exists();

        McpWiring.stop(context, TASK);

        process.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(process.isAlive()).isFalse();
        assertThat(closed).containsExactly(TASK);
        assertThat(endpoint.socket()).doesNotExist();
        assertThat(McpWiring.statusLine(context, TASK)).isEqualTo("stopped");
    }

    @Test
    void aStartThatFailsAfterItsEndpointEndsItsProcessAndClosesItsDaemonSocket() throws Exception {
        // What a launch's clean-up runs once its container exists, when the gateway, the hooks,
        // 'podman create' or 'podman start' failed after porelo-mcp said ready.
        stand(RUNS);
        final PoreloContext context = context();
        McpWiring.start(context, McpWiring.open(context, TASK, PROJECT).orElseThrow());
        final ProcessHandle process =
                org.fuin.porelo.wire.HelperPid.verified(
                                context.paths()
                                        .tasks()
                                        .containerState(TASK)
                                        .resolve(McpWiring.HELPER + ".pid"))
                        .orElseThrow();

        TaskLaunch.reapOrphans(context, TASK);

        process.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(process.isAlive()).isFalse();
        assertThat(closed).as("the daemon socket opened for it").containsExactly(TASK);
    }

    @Test
    void aResumeOpensTheSocketAgainAndStartsItWithTheKeptKey() throws Exception {
        stand(RUNS);
        final PoreloContext context = context();
        final Path again = dir.resolve("run/porelo/mcp/" + TASK + ".daemon.sock.2");
        McpDaemonSockets.install(opening(Optional.of(again)));

        final String problem =
                McpWiring.resume(context, TASK, PROJECT, () -> Optional.of("a".repeat(64)));

        assertThat(problem).isNull();
        assertThat(opened).containsExactly(TASK + "@" + PROJECT);
        assertThat(Files.readAllLines(dir.resolve("args"))).contains(again.toString());
        assertThat(Files.readString(dir.resolve("token"))).isEqualTo("a".repeat(64) + "\n");
        assertThat(McpWiring.statusLine(context, TASK)).startsWith("running, ");
    }

    @Test
    void aResumeWithoutTheKeptKeyStartsNothingAndSaysWhy() throws Exception {
        stand(RUNS);
        final PoreloContext context = context();

        final String problem = McpWiring.resume(context, TASK, PROJECT, Optional::empty);

        assertThat(problem).contains("vault");
        assertThat(dir.resolve("args")).doesNotExist();
        assertThat(closed).containsExactly(TASK);
    }

    @Test
    void aResumeThatStartsNothingSaysWhyInTheStatusLine() throws Exception {
        stand(RUNS);
        final PoreloContext context = context();
        Files.createDirectories(context.paths().tasks().containerState(TASK));

        McpWiring.resume(context, TASK, PROJECT, Optional::empty);
        assertThat(McpWiring.statusLine(context, TASK))
                .isEqualTo("absent - its key is not in the vault, which may be locked");

        McpDaemonSockets.install(opening(Optional.empty()));
        McpWiring.resume(context, TASK, PROJECT, Optional::empty);
        assertThat(McpWiring.statusLine(context, TASK))
                .isEqualTo("absent - its project allows it none now");

        McpWiring.resume(context, TASK, null, Optional::empty);
        assertThat(McpWiring.statusLine(context, TASK))
                .isEqualTo("absent - its records name no project");
    }

    @Test
    void anAbsentRecordThatSaysNoReasonPrintsNone() throws Exception {
        final PoreloContext context = context();
        final Path state = Files.createDirectories(context.paths().tasks().containerState(TASK));
        Files.writeString(
                state.resolve("mcp.json"),
                "{\"state\":\"absent\",\"socket\":\"/x.sock\",\"code\":-1}",
                StandardCharsets.UTF_8);

        assertThat(McpWiring.statusLine(context, TASK)).isEqualTo("absent");
    }

    @Test
    void theStatusLineSaysWhyItIsAbsentWhenItsProcessIsGone() throws Exception {
        stand(RUNS);
        final PoreloContext context = context();
        McpWiring.start(context, McpWiring.open(context, TASK, PROJECT).orElseThrow());
        final Path pidFile =
                context.paths().tasks().containerState(TASK).resolve(McpWiring.HELPER + ".pid");
        final ProcessHandle process =
                org.fuin.porelo.wire.HelperPid.verified(pidFile).orElseThrow();
        try {
            // What a restart of the machine leaves: a record of it running, and no such process.
            Files.delete(pidFile);

            assertThat(McpWiring.statusLine(context, TASK))
                    .isEqualTo("absent - its process is gone");
        } finally {
            org.fuin.porelo.wire.HelperPid.stop(process);
        }
    }

    private void awaitStatus(PoreloContext context, String expected) throws Exception {
        final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        String seen = McpWiring.statusLine(context, TASK);
        while (!expected.equals(seen) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            seen = McpWiring.statusLine(context, TASK);
        }
        assertThat(seen).isEqualTo(expected);
    }

    private McpDaemonSockets.Opener opening(Optional<Path> answer) {
        return new McpDaemonSockets.Opener() {
            @Override
            public Optional<Path> open(String task, String project) {
                opened.add(task + "@" + project);
                return answer;
            }

            @Override
            public void close(String task) {
                closed.add(task);
            }
        };
    }

    /**
     * Writes the stand-in: it records its arguments, its environment and what came on standard
     * input, then runs the given lines.
     */
    private void stand(String then) throws IOException {
        final Path binary = dir.resolve("bin/porelo-mcp");
        Files.createDirectories(binary.getParent());
        Files.writeString(
                binary,
                "#!/bin/bash\n"
                        + "printf '%s\\n' \"$@\" > '"
                        + dir.resolve("args")
                        + "'\n"
                        + "env > '"
                        + dir.resolve("env")
                        + "'\n"
                        + "IFS= read -r line; printf '%s\\n' \"$line\" > '"
                        + dir.resolve("token")
                        + "'\n"
                        + then
                        + "\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        System.setProperty(McpWiring.BINARY_PROPERTY, binary.toString());
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        try {
            Files.createDirectories(context.paths().tasks().containerState(TASK));
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return context;
    }
}
