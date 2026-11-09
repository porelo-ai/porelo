package org.fuin.porelo.planning.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planning.api.PlanningMain;
import org.fuin.porelo.planning.api.PlanningProtocol;
import org.fuin.porelo.planning.api.PlanningServer;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
class PlanningServerTest {
    @TempDir Path directory;

    @Test
    void ambiguousOrMissingStateChangeIdentifiersAreInvalidParameters() throws Exception {
        var adapter = new StubPlanning();
        var before = adapter.getStory(Map.of(), "", "empty");
        try (var server = new PlanningServer(adapter, directory.resolve("s"), directory)) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (var client = new VarlinkClient(server.socketPath())) {
                for (var move :
                        List.of(
                                Map.of("to", "Done"),
                                Map.of("story", "empty", "issue", "one", "to", "Done"))) {
                    assertThatThrownBy(
                                    () ->
                                            client.call(
                                                    PlanningProtocol.SET_STATES,
                                                    Map.of(
                                                            "settings",
                                                            Map.of(),
                                                            "token",
                                                            "",
                                                            "revision",
                                                            before.revision(),
                                                            "changes",
                                                            List.of(
                                                                    Map.of(
                                                                            "story", "empty", "to",
                                                                            "Done"),
                                                                    move))))
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex -> {
                                        assertThat(ex.getErrorName())
                                                .isEqualTo("org.varlink.service.InvalidParameter");
                                        assertThat(ex.getParameters())
                                                .containsEntry("parameter", "changes");
                                    });
                }
            }
        }
        assertThat(adapter.getStory(Map.of(), "", "empty")).isEqualTo(before);
    }

    @Test
    void registeredMethodsMatchThePublishedInterface() throws Exception {
        String idl;
        try (var resource =
                PlanningServer.class.getResourceAsStream(PlanningProtocol.INTERFACE + ".varlink")) {
            idl = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var declared =
                java.util.regex.Pattern.compile("(?m)^method (\\w+)\\(")
                        .matcher(idl)
                        .results()
                        .map(match -> PlanningProtocol.INTERFACE + "." + match.group(1))
                        .toList();
        try (var server =
                new PlanningServer(new StubPlanning(), directory.resolve("s"), directory)) {
            assertThat(
                            server.methodNames().stream()
                                    .filter(
                                            name ->
                                                    name.startsWith(
                                                            PlanningProtocol.INTERFACE + ".")))
                    .containsExactlyInAnyOrderElementsOf(declared);
        }
    }

    @Test
    void invalidDirectoriesAndSocketsCannotInitializeAnAdapter() throws Exception {
        var initialized = new java.util.concurrent.atomic.AtomicInteger();
        var adapter =
                new StubPlanning() {
                    @Override
                    public void initialize(Path project) {
                        initialized.incrementAndGet();
                    }
                };
        assertThatThrownBy(
                        () ->
                                new PlanningServer(
                                        adapter,
                                        directory.resolve("s"),
                                        directory.resolve("absent")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(initialized).hasValue(0);
        var occupied = java.nio.file.Files.createDirectory(directory.resolve("occupied"));
        java.nio.file.Files.writeString(occupied.resolve("keep"), "keep");
        assertThatThrownBy(() -> new PlanningServer(adapter, occupied, directory))
                .isInstanceOf(RuntimeException.class);
        assertThat(initialized).hasValue(0);
    }

    @Test
    void describePrintsTheSameFiveFieldsAsTheSocketDescription() throws Exception {
        var adapter = new StubPlanning();
        var output = new ByteArrayOutputStream();
        try (var text = new PrintStream(output)) {
            assertThat(PlanningMain.execute(adapter, new String[] {"describe"}, text, text))
                    .as("describe succeeds without any workflow or credential")
                    .isZero();
        }
        try (var server = new PlanningServer(adapter, directory.resolve("s"), directory)) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (var client = new VarlinkClient(server.socketPath())) {
                assertThat(client.call(PlanningProtocol.DESCRIBE, Map.of()))
                        .as("command-line discovery and the running handshake are identical")
                        .isEqualTo(Json.parseExact(output.toString()));
            }
        }
    }

    @Test
    void serveRequiresBothTheSocketAndTheProjectDirectory() {
        var output = new ByteArrayOutputStream();
        try (var text = new PrintStream(output)) {
            assertThat(
                            PlanningMain.execute(
                                    new StubPlanning(),
                                    new String[] {"serve", directory.resolve("s").toString()},
                                    text,
                                    text))
                    .as("omitting the project directory cannot start an adapter in the wrong place")
                    .isEqualTo(2);
        }
    }

    @Test
    void watchWithoutMoreAnswersOnceAndClosesItsSubscription() throws Exception {
        var adapter = new StubPlanning();
        try (var server = new PlanningServer(adapter, directory.resolve("s"), directory)) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (var client = new VarlinkClient(server.socketPath())) {
                assertThat(
                                client.call(
                                        PlanningProtocol.WATCH_CHANGES,
                                        Map.of("settings", Map.of(), "token", "synthetic")))
                        .as("without more the current revision is a final reply")
                        .containsEntry(
                                "change",
                                Map.of("revision", "0", "stories", List.of(), "issues", List.of()));
                client.call(PlanningProtocol.DESCRIBE, Map.of());
                assertThat(adapter.subscribers())
                        .as("a one-shot watch releases its subscription before the next request")
                        .isZero();
            }
        }
    }

    @Test
    void malformedCallFieldsAreInvalidParameters() throws Exception {
        try (var server =
                new PlanningServer(new StubPlanning(), directory.resolve("s"), directory)) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (var client = new VarlinkClient(server.socketPath())) {
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                PlanningProtocol.GET_WORKFLOW,
                                                Map.of(
                                                        "settings",
                                                        List.of(),
                                                        "token",
                                                        "synthetic")))
                        .as("an array cannot masquerade as settings")
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                ex -> {
                                    assertThat(ex.getErrorName())
                                            .as("standard varlink parameter refusal")
                                            .isEqualTo("org.varlink.service.InvalidParameter");
                                    assertThat(ex.getParameters())
                                            .as("the faulty field is named")
                                            .isEqualTo(Map.of("parameter", "settings"));
                                });
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                PlanningProtocol.GET_WORKFLOW,
                                                Map.of("settings", Map.of(), "token", true)))
                        .as("a boolean cannot become a credential string")
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                ex ->
                                        assertThat(ex.getParameters())
                                                .as("the token field is named without its value")
                                                .isEqualTo(Map.of("parameter", "token")));
            }
        }
    }

    @Test
    void disconnectingAStreamingClientReleasesItsSubscription() throws Exception {
        var adapter = new StubPlanning();
        try (var server = new PlanningServer(adapter, directory.resolve("s"), directory)) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (var client = new VarlinkClient(server.socketPath())) {
                client.callMore(
                        PlanningProtocol.WATCH_CHANGES,
                        Map.of("settings", Map.of(), "token", "synthetic"),
                        answer -> false);
            }
            for (int attempt = 0; attempt < 500 && adapter.subscribers() != 0; attempt++) {
                Thread.sleep(10);
            }
            assertThat(adapter.subscribers())
                    .as("a gone client cannot leave a watcher behind")
                    .isZero();
        }
    }
}
