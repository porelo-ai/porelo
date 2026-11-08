package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-task daemon socket porelod serves a task's {@code porelo-mcp} on: only the methods the
 * project's signed file allows, the caller taken from the socket, and the file's limits on a start.
 */
class McpTaskSocketsTest {

    private static final String LEAD = "porelo-uc-lead";

    private static final String HEAD =
            "project:\n  name: \"uc\"\n  security_class: \"guarded\"\nimage:\n  from:"
                    + " \"ubuntu:24.04\"\n";

    private static final String SECTION =
            "mcp:\n"
                    + "  methods: [\"ProjectFileSchema\", \"Start\", \"Stop\"]\n"
                    + "  agents:\n"
                    + "    alpha:\n"
                    + "    beta: [\"small\"]\n"
                    + "  tasks: 1\n"
                    + "  minutes: 60\n";

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private McpTaskSockets sockets;

    @AfterEach
    void closeAll() {
        if (sockets != null) {
            sockets.close();
        }
    }

    private PoreloContext context(final String file) throws IOException {
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
        final Path project = dir.resolve("project.yml");
        Files.writeString(project, file);
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(dir.resolve("data/porelo/projects/uc"), project + "\n");
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        sockets = new McpTaskSockets(context);
        return context;
    }

    private Path opened(final String file) throws IOException {
        context(file);
        return sockets.open(LEAD, "uc").orElseThrow();
    }

    private void child(final PoreloContext context, final String task, final String parent)
            throws IOException {
        final Path state = Files.createDirectories(context.paths().tasks().containerState(task));
        new TaskProfile(
                        TaskProfile.VERSION,
                        "alpha",
                        TaskMode.UNATTENDED,
                        "work",
                        null,
                        "2026-11-08T00:00:00Z",
                        "deny",
                        null,
                        null,
                        null,
                        null,
                        0,
                        parent)
                .writeTo(state);
    }

    private static Map<String, Object> call(
            final Path socket, final String method, final Map<String, Object> parameters)
            throws IOException {
        try (VarlinkClient client = new VarlinkClient(socket)) {
            return client.call(PoreloDaemon.INTERFACE + "." + method, parameters);
        }
    }

    private static void refused(
            final Path socket,
            final String method,
            final Map<String, Object> parameters,
            final String error,
            final Map<String, Object> said) {
        assertThatThrownBy(() -> call(socket, method, parameters))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo(
                                            error.contains(".")
                                                    ? error
                                                    : PoreloDaemon.INTERFACE + "." + error);
                            // Numbers as text: the wire hands back the kind of number it read.
                            said.forEach(
                                    (key, value) ->
                                            assertThat(String.valueOf(ex.getParameters().get(key)))
                                                    .as(key)
                                                    .isEqualTo(String.valueOf(value)));
                        });
    }

    @Test
    void aProjectWhoseFileHasNoSectionGetsNoSocket() throws IOException {
        context(HEAD);

        assertThat(sockets.open(LEAD, "uc")).isEmpty();
        assertThat(dir.resolve("run/porelo/mcp")).doesNotExist();
    }

    @Test
    void theSocketLiesInAPrivateDirectoryAndGoesWithClose() throws IOException {
        final Path socket = opened(HEAD + SECTION);

        assertThat(socket).isEqualTo(dir.resolve("run/porelo/mcp/" + LEAD + ".daemon.sock"));
        assertThat(socket).exists();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.getParent())))
                .isEqualTo("rwx------");

        sockets.close(LEAD);

        assertThat(socket).doesNotExist();
    }

    @Test
    void aStaleSocketIsReplaced() throws IOException {
        final PoreloContext context = context(HEAD + SECTION);
        final Path stale = context.paths().daemonSocket().resolveSibling("mcp");
        Files.createDirectories(stale);
        Files.setPosixFilePermissions(stale, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(stale.resolve(LEAD + ".daemon.sock"), "left over");

        final Path socket = sockets.open(LEAD, "uc").orElseThrow();

        assertThat(call(socket, "ProjectFileSchema", Map.of())).containsKey("sections");
    }

    @Test
    void aMethodNotAllowedIsNotFoundWhileAnAllowedOneAnswers() throws IOException {
        final Path socket = opened(HEAD + SECTION);

        assertThat(call(socket, "ProjectFileSchema", Map.of())).containsKey("sections");
        refused(
                socket,
                "Projects",
                Map.of(),
                "org.varlink.service.MethodNotFound",
                Map.of("method", PoreloDaemon.INTERFACE + ".Projects"));
        refused(
                socket,
                "Panic",
                Map.of(),
                "org.varlink.service.MethodNotFound",
                Map.of("method", PoreloDaemon.INTERFACE + ".Panic"));
    }

    @Test
    void theDescriptionIsTasks1CutDownToTheAllowedMethods() throws IOException {
        final Path socket = opened(HEAD + SECTION);

        final String full = PoreloDaemon.description();
        final String cut;
        try (VarlinkClient client = new VarlinkClient(socket)) {
            cut =
                    String.valueOf(
                            client.call(
                                            "org.varlink.service.GetInterfaceDescription",
                                            Map.of("interface", PoreloDaemon.INTERFACE))
                                    .get("description"));
        }

        assertThat(names("method", cut)).containsExactly("ProjectFileSchema", "Start", "Stop");
        assertThat(names("type", cut))
                .as("every type kept, so it parses whole")
                .isEqualTo(names("type", full));
        assertThat(names("error", cut))
                .as("every error kept, so one a method raises is declared")
                .isEqualTo(names("error", full));
        assertThat(cut).containsPattern("(?m)^interface " + PoreloDaemon.INTERFACE + "$");
        assertThat(cut).doesNotContain("Stops every running task");
    }

    private static Set<String> names(final String kind, final String idl) {
        final Set<String> names = new TreeSet<>();
        final Matcher matcher =
                Pattern.compile("(?m)^" + kind + "\\s+([A-Za-z][A-Za-z0-9]*)").matcher(idl);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Test
    void aSectionNamingAMethodNeverOfferedOrUnknownIsRefusedWhenRead() throws IOException {
        context(HEAD + "mcp:\n  methods: [\"Start\", \"Panic\", \"Teleport\"]\n");

        assertThatThrownBy(() -> sockets.open(LEAD, "uc"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("'Panic'")
                .hasMessageContaining("'Teleport'");
        assertThat(dir.resolve("run/porelo/mcp/" + LEAD + ".daemon.sock")).doesNotExist();
        assertThat(McpTaskSockets.refusals(List.of("Start", "Panic", "Teleport")))
                .hasSize(2)
                .anySatisfy(each -> assertThat(each).contains("Panic").contains("never"))
                .anySatisfy(each -> assertThat(each).contains("Teleport").contains("no method"));
    }

    @Test
    void theDaemonsCheckOfADraftRefusesAMethodNeverOffered() throws Exception {
        final PoreloContext context = context(HEAD);
        final Path socket = dir.resolve("porelod.sock");
        try (var server = PoreloDaemon.serving(context, socket)) {
            final Thread thread = Thread.ofVirtual().start(server);
            try {
                final Map<String, Object> checked =
                        call(
                                socket,
                                "CheckProjectFile",
                                Map.of("text", HEAD + "mcp:\n  methods: [\"Start\", \"Panic\"]\n"));
                assertThat(checked.get("refused")).asString().contains("'Panic'");
            } finally {
                server.close();
                thread.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    @Test
    void theCallerIsTheSocketsTaskWhateverTheCallSays() throws IOException {
        final PoreloContext context = context(HEAD + SECTION);

        assertThat(McpTaskSockets.caller()).as("outside a per-task socket, none").isNull();
        final TaskRequestSeen seen = new TaskRequestSeen();
        McpTaskSockets.as(
                LEAD,
                () ->
                        seen.parent =
                                PoreloDaemon.request(
                                                Map.of(
                                                        "project",
                                                        "uc",
                                                        "task",
                                                        "child",
                                                        "repository",
                                                        "uc",
                                                        "parent",
                                                        "porelo-uc-other"),
                                                context)
                                        .parent());
        assertThat(seen.parent).isEqualTo(LEAD);
        assertThat(
                        PoreloDaemon.request(
                                        Map.of(
                                                "project",
                                                "uc",
                                                "task",
                                                "child",
                                                "repository",
                                                "uc",
                                                "parent",
                                                LEAD),
                                        context)
                                .parent())
                .as("a field never makes a parent")
                .isNull();
    }

    private static final class TaskRequestSeen {
        private String parent;
    }

    @Test
    void aStopOfAnotherTasksChildIsRefusedNamingItsParent() throws IOException {
        final Path socket = opened(HEAD + SECTION);
        final PoreloContext context = sockets.context();
        child(context, "porelo-uc-theirs", "porelo-uc-other");
        child(context, "porelo-uc-person", null);

        refused(
                socket,
                "Stop",
                Map.of("task", "porelo-uc-theirs", "caller", "porelo-uc-other"),
                "NotItsOwn",
                Map.of("task", "porelo-uc-theirs", "parent", "porelo-uc-other"));
        refused(
                socket,
                "Stop",
                Map.of("task", "porelo-uc-person"),
                "NotItsOwn",
                Map.of("task", "porelo-uc-person", "parent", ""));
        assertThat(runner.lines()).as("nothing was stopped").noneMatch(l -> l.contains("stop"));
    }

    @Test
    void aStopOfItsOwnChildGoesThrough() throws IOException {
        final Path socket = opened(HEAD + SECTION);
        child(sockets.context(), "porelo-uc-mine", LEAD);

        final Map<String, Object> stopped = call(socket, "Stop", Map.of("task", "porelo-uc-mine"));

        assertThat(stopped).containsKey("outcome");
    }

    @Test
    void aStartOverTheLongestRunIsRefused() throws IOException {
        final Path socket = opened(HEAD + SECTION);

        refused(
                socket,
                "Start",
                start("alpha", null, 61),
                "RunTooLong",
                Map.of("minutes", 61L, "limit", 60L));
    }

    @Test
    void aStartWithAnAgentOrModelNotAllowedIsRefused() throws IOException {
        final Path socket = opened(HEAD + SECTION);

        refused(
                socket,
                "Start",
                start("gamma", null, 30),
                "AgentNotAllowed",
                Map.of("agent", "gamma", "model", "", "allowed", List.of("alpha", "beta:small")));
        refused(
                socket,
                "Start",
                start("beta", "large", 30),
                "AgentNotAllowed",
                Map.of("agent", "beta", "model", "large"));
        refused(
                socket,
                "Start",
                start("beta", null, 30),
                "AgentNotAllowed",
                Map.of("agent", "beta", "model", ""));
        refused(
                socket,
                "Start",
                Map.of("project", "uc", "repository", "uc", "minutes", 30),
                "AgentNotAllowed",
                Map.of("agent", ""));
    }

    @Test
    void aStartPastTheTasksAtOnceIsRefused() throws IOException {
        final Path socket = opened(HEAD + SECTION);
        child(sockets.context(), "porelo-uc-first", LEAD);
        child(sockets.context(), "porelo-uc-theirs", "porelo-uc-other");
        runner.answering(
                "ps",
                "porelo-uc-first\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n"
                        + "porelo-uc-theirs\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n");

        refused(
                socket,
                "Start",
                start("alpha", null, 30),
                "ChildLimitReached",
                Map.of("parent", LEAD, "running", 1L, "limit", 1L));
    }

    @Test
    void aStartBringingBackAnotherTasksChildIsRefused() throws IOException {
        final Path socket = opened(HEAD + SECTION);
        child(sockets.context(), "porelo-uc-theirs", "porelo-uc-other");

        final Map<String, Object> start = new java.util.HashMap<>(start("alpha", null, 30));
        start.put("task", "theirs");
        refused(
                socket,
                "Start",
                start,
                "NotItsOwn",
                Map.of("task", "porelo-uc-theirs", "parent", "porelo-uc-other"));
    }

    private static Map<String, Object> start(
            final String agent, final String model, final int minutes) {
        final Map<String, Object> start = new java.util.HashMap<>();
        start.put("project", "uc");
        start.put("repository", "uc");
        start.put("task", "child");
        start.put("agent", agent);
        if (model != null) {
            start.put("model", model);
        }
        start.put("minutes", minutes);
        return start;
    }
}
