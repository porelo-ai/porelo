package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.TransportDirectory;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The room dialog receives live members or a named failure, never a guessed empty list. */
class RoomMembersTest {
    @TempDir Path dir;
    private final FakeCommandRunner runner = new FakeCommandRunner();

    @Test
    void theDaemonAdapterReturnsTheJudgedRoomAndMembers() throws Exception {
        // A locally remembered join list cannot show helpers or current invitations.
        final PoreloContext context = context();

        assertThat(MessagingMethods.roomMembers(context, Map.of("project", "p")))
                .as("the live answer is projected into the reply")
                .isEqualTo(
                        Map.of(
                                "room",
                                "!p",
                                "members",
                                List.of(
                                        Map.of(
                                                "id", "@alice", "shown", "Alice", "state", "invite",
                                                "kind", "person"))));
    }

    @Test
    void aMissingTransportHasItsOwnError() throws Exception {
        // Missing software must not be displayed as a room that nobody joined.
        final PoreloContext context = context();
        Files.delete(dir.resolve("data/porelo/transports/" + TransportDirectory.PREFIX + "room"));

        error(context, "NoTransport");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"members\":[]}", "{\"room\":\"\",\"members\":[]}"})
    void anUnsetRoomIsDistinctFromAnEmptyRoom(String answer) throws Exception {
        // An absent room offers setup; an existing empty room offers joining instead.
        final PoreloContext context = context();
        runner.answering(" members ", answer);

        error(context, "{}".equals(answer) ? "ConversationRefused" : "NoRoom");
    }

    @Test
    void aProjectWithoutAConversationHasNoRoom() throws Exception {
        // No configured room must not be mistaken for an unavailable transport.
        final PoreloContext context = context();
        Files.writeString(
                dir.resolve("project.yml"),
                "project:\n  name: p\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\n");

        error(context, "NoRoom");
    }

    @Test
    void anExistingEmptyRoomIsASuccess() throws Exception {
        // An empty room is a valid snapshot and must not claim that setup is missing.
        final PoreloContext context = context();
        runner.answering(" members ", "{\"room\":\"!p\",\"members\":[]}");

        assertThat(MessagingMethods.roomMembers(context, Map.of("project", "p")))
                .as("the empty room retains its identity")
                .isEqualTo(Map.of("room", "!p", "members", List.of()));
    }

    @ParameterizedTest
    @ValueSource(ints = {75, 78})
    void transportFailuresKeepTheirMeaningAndCode(int code) throws Exception {
        // A network outage is retryable; missing settings require a person's action.
        final PoreloContext context = context();
        runner.failing(" members ", code, "cannot read room");

        assertThatThrownBy(() -> MessagingMethods.roomMembers(context, Map.of("project", "p")))
                .as("the transport's failure crosses the boundary")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .as("the typed failure")
                                    .isEqualTo(
                                            PoreloDaemon.INTERFACE
                                                    + (code == 75
                                                            ? ".HomeserverUnreachable"
                                                            : ".ConversationRefused"));
                            assertThat(ex.getParameters())
                                    .as("the transport's exit code is retained")
                                    .containsEntry("code", code);
                        });
    }

    @Test
    void malformedJsonIsARefusalThatNamesTheAnswer() throws Exception {
        // Corrupt output must not masquerade as a successful empty membership snapshot.
        final PoreloContext context = context();
        runner.answering(" members ", "not-json");

        assertThatThrownBy(() -> MessagingMethods.roomMembers(context, Map.of("project", "p")))
                .as("the malformed answer is refused")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .as("malformed output is a typed refusal")
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".ConversationRefused");
                            assertThat(ex.getParameters().get("message").toString())
                                    .as("the answer's problem is named")
                                    .contains("JSON object");
                        });
    }

    @Test
    void tasksAreNeverOfferedTheLiveRoomQuery() {
        // Server-chosen display names must not bypass the task's filtered message boundary.
        assertThat(McpMethods.NEVER)
                .as("the operator's live room query is not a task tool")
                .contains("RoomMembers");
    }

    @Test
    void theRegisteredMethodAnswersOverVarlink() throws Exception {
        // An adapter-only implementation leaves every actual daemon client without the method.
        final PoreloContext context = context();
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("room.sock"))) {
            final Thread serving = Thread.ofPlatform().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThat(
                                client.call(
                                        PoreloDaemon.INTERFACE + ".RoomMembers",
                                        Map.of("project", "p")))
                        .as("the registered method returns the same snapshot as its adapter")
                        .isEqualTo(MessagingMethods.roomMembers(context, Map.of("project", "p")));
            } finally {
                server.close();
                serving.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    private void error(PoreloContext context, String name) {
        assertThatThrownBy(() -> MessagingMethods.roomMembers(context, Map.of("project", "p")))
                .as("the named failure is " + name)
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .as("the typed failure")
                                        .isEqualTo(PoreloDaemon.INTERFACE + "." + name));
    }

    private PoreloContext context() throws Exception {
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
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        final Path transports = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path executable =
                Files.writeString(transports.resolve(TransportDirectory.PREFIX + "room"), "");
        assertThat(executable.toFile().setExecutable(true))
                .as("fixture transport is executable")
                .isTrue();
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"secrets\":\"input\",\"lifecycle\":[\"members\"]}");
        runner.answering(
                " members ",
                "{\"room\":\"!p\",\"members\":[{\"id\":\"@alice\",\"shown\":\"Alice\",\"state\":\"invite\",\"kind\":\"person\"}]}");
        Files.writeString(
                dir.resolve("project.yml"),
                "project:\n  name: p\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\nmail:\n  transports:\n    room: {}\n");
        final Path registry = Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(registry.resolve("p"), dir.resolve("project.yml") + "\n");
        return context;
    }
}
