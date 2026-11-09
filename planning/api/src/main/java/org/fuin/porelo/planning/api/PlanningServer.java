package org.fuin.porelo.planning.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/** Serves an adapter over an owner-only varlink socket. Connections may call it concurrently. */
public final class PlanningServer implements AutoCloseable {
    private final VarlinkServer server;
    private volatile boolean open = true;

    /** Initializes the adapter with its caller-made project directory and binds the socket. */
    public PlanningServer(PlanningAdapter adapter, Path socket, Path directory) {
        if (!java.nio.file.Files.isDirectory(directory)) {
            throw new IllegalArgumentException(
                    "The planning directory must already exist: " + directory);
        }
        server = new VarlinkServer(socket, PlanningProtocol.INTERFACE).describedBy(idl());
        try {
            adapter.initialize(directory);
        } catch (RuntimeException | Error ex) {
            server.close();
            throw ex;
        }
        server.method("Describe", (parameters, replies) -> replies.last(description(adapter)));
        register(
                "GetWorkflow",
                (p, settings, token) -> {
                    final var result = adapter.getWorkflow(settings, token);
                    return result(result, "workflow", result.value().asMap());
                });
        register(
                "ListStories",
                (p, settings, token) -> {
                    final List<String> states =
                            p.get("states") == null
                                    ? null
                                    : parameter("states", () -> Wire.strings(p, "states"));
                    final var result = adapter.listStories(settings, token, states);
                    return result(
                            result,
                            "stories",
                            result.value().stream().map(StorySummary::asMap).toList());
                });
        register(
                "GetStory",
                (p, settings, token) -> {
                    final var result = adapter.getStory(settings, token, text(p, "story"));
                    return result(result, "story", result.value().asMap());
                });
        register(
                "ListStoryIssues",
                (p, settings, token) -> {
                    final var result = adapter.listStoryIssues(settings, token, text(p, "story"));
                    return result(
                            result,
                            "issues",
                            result.value().stream().map(IssueSummary::asMap).toList());
                });
        register(
                "GetIssue",
                (p, settings, token) -> {
                    final var result = adapter.getIssue(settings, token, text(p, "issue"));
                    return result(result, "issue", result.value().asMap());
                });
        register(
                "SetStates",
                (p, settings, token) -> {
                    final var changes =
                            parameter("changes", () -> Wire.list(p, "changes", StateChange::of));
                    return Map.of(
                            "revision",
                            adapter.setStates(settings, token, changes, text(p, "revision")));
                });
        register(
                "Refresh",
                (p, settings, token) -> Map.of("revision", adapter.refresh(settings, token)));
        server.method(
                "WatchChanges",
                (p, replies) -> {
                    try (var watch =
                            answering(() -> adapter.watchChanges(settings(p), text(p, "token")))) {
                        while (open && replies.open()) {
                            final Change change;
                            try {
                                change = watch.next(200);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            if (change == null) {
                                continue;
                            }
                            final var answer = Map.<String, Object>of("change", change.asMap());
                            if (!replies.streaming()) {
                                replies.last(answer);
                                return;
                            }
                            replies.more(answer);
                        }
                    } catch (PlanningRefused ex) {
                        throw refused(ex);
                    }
                });
    }

    /** Returns registered methods for comparison with the published interface. */
    public java.util.Set<String> methodNames() {
        return server.methodNames();
    }

    /** Returns the bound socket. */
    public Path socketPath() {
        return server.socketPath();
    }

    /** Serves until closed. */
    public void serve() {
        server.run();
    }

    /** Stops accepting connections and lets bounded watch waits end. */
    @Override
    public void close() {
        open = false;
        server.close();
    }

    static Map<String, Object> description(PlanningAdapter adapter) {
        return Map.of(
                "protocolVersion",
                PlanningProtocol.VERSION,
                "name",
                adapter.name(),
                "version",
                adapter.version(),
                "capabilities",
                adapter.capabilities(),
                "credentials",
                adapter.credentials());
    }

    static String idl() {
        try (InputStream input =
                PlanningServer.class.getResourceAsStream(PlanningProtocol.INTERFACE + ".varlink")) {
            if (input == null) {
                throw new IllegalStateException("The jar has no planning interface");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read the planning interface", ex);
        }
    }

    private interface Call {
        Map<String, Object> ask(
                Map<String, Object> parameters, Map<String, Object> settings, String token);
    }

    private void register(String name, Call call) {
        server.method(
                name,
                (p, replies) ->
                        replies.last(answering(() -> call.ask(p, settings(p), text(p, "token")))));
    }

    private static Map<String, Object> settings(Map<String, Object> parameters) {
        return parameter("settings", () -> Wire.object(parameters, "settings"));
    }

    private static String text(Map<String, Object> parameters, String name) {
        return parameter(
                name,
                () ->
                        name.equals("token")
                                ? Wire.text(parameters, name)
                                : Wire.required(parameters, name));
    }

    private static <T> T parameter(String name, Supplier<T> read) {
        try {
            return read.get();
        } catch (IllegalArgumentException ex) {
            throw new VarlinkException(
                    "org.varlink.service.InvalidParameter", Map.of("parameter", name));
        }
    }

    private static Map<String, Object> result(
            PlanningAdapter.Result<?> result, String name, Object value) {
        return Map.of(name, value, "revision", result.revision());
    }

    private static <T> T answering(Supplier<T> call) {
        try {
            return call.get();
        } catch (PlanningRefused ex) {
            throw refused(ex);
        }
    }

    private static VarlinkException refused(PlanningRefused ex) {
        return new VarlinkException(PlanningProtocol.error(ex.reason()), ex.parameters());
    }
}
