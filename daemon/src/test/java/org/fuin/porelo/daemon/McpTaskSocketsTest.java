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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
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

    private final HeldRunner runner = new HeldRunner();

    /** A fake runner that can hold each start at the machine's check, as a slow start waits. */
    private static final class HeldRunner extends FakeCommandRunner {
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger held = new AtomicInteger();
        private volatile boolean holding;

        @Override
        public CommandResult run(final Command command) {
            if (holding && command.describe().startsWith("podman version")) {
                held.incrementAndGet();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (this) {
                return super.run(command);
            }
        }

        void letGo() {
            holding = false;
            release.countDown();
        }
    }

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
        made(context.paths().tasks().containerState(task), "alpha", null, parent);
    }

    /** Writes a task's record where it is kept, as a start that made it with an agent wrote it. */
    private static void made(
            final Path where, final String agent, final String model, final String parent)
            throws IOException {
        new TaskProfile(
                        TaskProfile.VERSION,
                        agent,
                        TaskMode.UNATTENDED,
                        "work",
                        null,
                        "2026-11-08T00:00:00Z",
                        "deny",
                        null,
                        null,
                        model,
                        null,
                        0,
                        parent)
                .writeTo(Files.createDirectories(where));
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
        context(
                HEAD
                        + "mcp:\n  methods: [\"Start\", \"Panic\", \"Teleport\"]\n  agents:\n    alpha:\n");

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
                                Map.of(
                                        "text",
                                        HEAD
                                                + "mcp:\n  methods: [\"Start\", \"Panic\"]\n  agents:\n    alpha:\n"));
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
                Map.of("project", "uc", "repository", "uc", "minutes", 30, "prompt", "go"),
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

    @Test
    void aStartBringingBackATaskKeptOnlyInItsRecordIsRefusedUnlessItsOwn() throws IOException {
        // After a reboot the runtime directory is empty; the kept record still says whose it is.
        final Path socket = opened(HEAD + SECTION);
        final PoreloContext context = sockets.context();
        made(context.paths().tasks().taskRecord("porelo-uc-work"), "alpha", null, null);
        made(
                context.paths().tasks().taskRecord("porelo-uc-theirs"),
                "alpha",
                null,
                "porelo-uc-other");

        refused(
                socket,
                "Start",
                named("work", start("alpha", null, 30)),
                "NotItsOwn",
                Map.of("task", "porelo-uc-work", "parent", ""));
        refused(
                socket,
                "Start",
                named("theirs", start("alpha", null, 30)),
                "NotItsOwn",
                Map.of("task", "porelo-uc-theirs", "parent", "porelo-uc-other"));
        assertNothingStarted();
    }

    @Test
    void aStartBringingBackItsOwnTaskTakesOnlyTheAgentAndModelItWasMadeWith() throws IOException {
        final Path socket = opened(HEAD + SECTION);
        final PoreloContext context = sockets.context();
        made(context.paths().tasks().taskRecord("porelo-uc-small"), "beta", "small", LEAD);
        made(context.paths().tasks().containerState("porelo-uc-plain"), "beta", null, LEAD);
        made(context.paths().tasks().containerState("porelo-uc-gone"), "gamma", null, LEAD);

        refused(
                socket,
                "Start",
                named("small", start("alpha", null, 30)),
                "MadeWithAnother",
                Map.of("task", "porelo-uc-small", "agent", "beta", "model", "small"));
        refused(
                socket,
                "Start",
                named("small", start("beta", "large", 30)),
                "MadeWithAnother",
                Map.of("task", "porelo-uc-small", "agent", "beta", "model", "small"));
        // Naming none, the task's own count, and the file must still allow them.
        refused(
                socket,
                "Start",
                named("plain", start(null, null, 30)),
                "AgentNotAllowed",
                Map.of("agent", "beta", "model", ""));
        refused(
                socket,
                "Start",
                named("gone", start(null, null, 30)),
                "AgentNotAllowed",
                Map.of("agent", "gamma", "model", ""));
        assertNothingStarted();

        made(context.paths().tasks().taskRecord("porelo-uc-own"), "beta", "small", LEAD);
        assertThat(call(socket, "Start", named("own", start(null, null, 30))))
                .as("its own agent and model, named by nothing")
                .containsKey("exitCode");
        assertThat(call(socket, "Start", named("own", start("beta", "small", 30))))
                .as("its own agent and model, named")
                .containsKey("exitCode");
    }

    @Test
    void aStartRunsAPromptUnattendedForAtLeastAMinute() throws IOException {
        final Path socket = opened(HEAD + SECTION);

        final Map<String, Object> none = start("alpha", null, 30);
        none.remove("prompt");
        refused(socket, "Start", none, "PromptRequired", Map.of());
        final Map<String, Object> blank = start("alpha", null, 30);
        blank.put("prompt", "  ");
        refused(socket, "Start", blank, "PromptRequired", Map.of());
        for (final String mode : List.of("SHELL", "AGENT", "session", "")) {
            final Map<String, Object> start = start("alpha", null, 30);
            start.put("mode", mode);
            refused(socket, "Start", start, "UnattendedOnly", Map.of("mode", mode));
        }
        for (final int minutes : new int[] {0, -1}) {
            refused(
                    socket,
                    "Start",
                    start("alpha", null, minutes),
                    "RunTooShort",
                    Map.of("minutes", (long) minutes));
        }
        assertNothingStarted();

        final Map<String, Object> unattended = start("alpha", null, 30);
        unattended.put("mode", "UNATTENDED");
        assertThat(call(socket, "Start", unattended)).containsKey("exitCode");
    }

    @Test
    void theDefaultRunMustFitTheFileToo() throws IOException {
        final Path socket = opened(HEAD + SECTION.replace("minutes: 60", "minutes: 20"));
        final Map<String, Object> start = start("alpha", null, 30);
        start.remove("minutes");

        refused(socket, "Start", start, "RunTooLong", Map.of("minutes", 30L, "limit", 20L));
    }

    @Test
    void aStartIntoAnotherProjectIsRefused() throws IOException {
        final Path socket = opened(HEAD + SECTION);
        final Map<String, Object> other = start("alpha", null, 30);
        other.put("project", "other");
        final Map<String, Object> none = start("alpha", null, 30);
        none.remove("project");

        refused(
                socket,
                "Start",
                other,
                "ProjectNotItsOwn",
                Map.of("project", "other", "own", "uc"));
        refused(socket, "Start", none, "ProjectNotItsOwn", Map.of("project", "", "own", "uc"));
        assertNothingStarted();
    }

    @Test
    void twoUnnamedStartsAtOnceBothCountAgainstTheLimit() throws Exception {
        final Path socket = opened(HEAD + SECTION);
        final Map<String, Object> unnamed = start("alpha", null, 30);
        unnamed.remove("task");
        runner.holding = true;
        final CompletableFuture<Object> first = attempt(socket, unnamed);
        try {
            until(() -> runner.held.get() == 1);
            final CompletableFuture<Object> second = attempt(socket, unnamed);
            until(() -> second.isDone() || runner.held.get() > 1);

            assertThat(runner.held.get()).as("the second start was let through").isEqualTo(1);
            assertRefused(second.get(), "ChildLimitReached", Map.of("running", 1L, "limit", 1L));
        } finally {
            runner.letGo();
            first.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void twoStartsOfOneNameAtOnceAreOneAndTheRefusedOneFreesNoPlace() throws Exception {
        final Path socket = opened(HEAD + SECTION);
        runner.holding = true;
        final CompletableFuture<Object> first = attempt(socket, start("alpha", null, 30));
        try {
            until(() -> runner.held.get() == 1);
            final CompletableFuture<Object> again = attempt(socket, start("alpha", null, 30));
            until(() -> again.isDone() || runner.held.get() > 1);

            assertThat(runner.held.get()).as("the second start was let through").isEqualTo(1);
            assertRefused(again.get(), "AlreadyStarting", Map.of("task", "porelo-uc-child"));
            refused(
                    socket,
                    "Start",
                    named("other", start("alpha", null, 30)),
                    "ChildLimitReached",
                    Map.of("running", 1L, "limit", 1L));
        } finally {
            runner.letGo();
            first.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void aStartCarryingAParameterATaskMayNotSetIsRefusedNamingIt() throws IOException {
        final Path socket = opened(HEAD + SECTION);
        final Map<String, Object> refusedOnes = new java.util.LinkedHashMap<>();
        refusedOnes.put("clearance", "off");
        refusedOnes.put("noGate", true);
        refusedOnes.put("credentials", Map.of("forge", "elsewhere"));
        refusedOnes.put("upstream", "https://example.org/other.git");
        refusedOnes.put("tokenHours", 10000);
        refusedOnes.put("subagents", 50);
        refusedOnes.put("provider", "other");
        refusedOnes.put("credentialType", "api-key");
        refusedOnes.put("rm", true);
        refusedOnes.put("now", true);
        refusedOnes.put("certify", true);
        refusedOnes.put("parent", "porelo-uc-other");

        refusedOnes.forEach(
                (parameter, value) -> {
                    final Map<String, Object> start = start("alpha", null, 30);
                    start.put(parameter, value);
                    refused(
                            socket,
                            "Start",
                            start,
                            "ParameterNotAllowed",
                            Map.of("parameter", parameter));
                });
        assertNothingStarted();
    }

    @Test
    void aStartWithOnlyWhatATaskMaySetGoesOn() throws IOException {
        final Path socket = opened(HEAD + SECTION);
        final Map<String, Object> start = start("beta", "small", 30);
        start.put("branch", "porelo/child");
        start.put("from", "main");
        start.put("mode", "UNATTENDED");
        start.put("maxTurns", 5);
        start.put("dryRun", true);
        start.put("steps", true);
        start.put("parameters", Map.of());

        assertThat(call(socket, "Start", start)).containsKey("exitCode");
    }

    @Test
    void aSectionMayNameOnlyReadingMethodsStartAndStop() throws IOException {
        assertThat(
                        McpTaskSockets.refusals(
                                List.of("Start", "Stop", "List", "Logs", "Label", "Approve")))
                .hasSize(2)
                .anySatisfy(each -> assertThat(each).contains("'Label'").contains("reading"))
                .anySatisfy(each -> assertThat(each).contains("'Approve'").contains("reading"));
        final String file =
                HEAD + "mcp:\n  methods: [\"Start\", \"Label\"]\n  agents:\n    alpha:\n";
        assertThat(McpTaskSockets.checked(Map.of(), file).get("refused"))
                .asString()
                .contains("'Label'");
        context(file);

        assertThatThrownBy(() -> sockets.open(LEAD, "uc"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("'Label'");
    }

    @Test
    void aMethodTakingATaskActsOnlyOnTheCallerOrItsOwnChild() throws IOException {
        final Path socket =
                opened(HEAD + SECTION.replace("\"Stop\"]", "\"Stop\", \"Logs\", \"Held\"]"));
        final PoreloContext context = sockets.context();
        child(context, "porelo-uc-theirs", "porelo-uc-other");
        child(context, "porelo-uc-person", null);
        child(context, "porelo-uc-mine", LEAD);
        assertThat(McpTaskSockets.takingATask())
                .contains("Logs", "Tail", "Screen", "Usage", "HandIns", "Held", "Reach", "Peers")
                .doesNotContain("List", "Watch", "Projects", "Events", "Prompts");

        refused(
                socket,
                "Logs",
                Map.of("task", "porelo-uc-theirs"),
                "NotItsOwn",
                Map.of("task", "porelo-uc-theirs", "parent", "porelo-uc-other"));
        refused(
                socket,
                "Logs",
                Map.of("task", "porelo-uc-person"),
                "NotItsOwn",
                Map.of("task", "porelo-uc-person", "parent", ""));
        // Without one, every task's: not the caller's alone.
        refused(socket, "Held", Map.of(), "NotItsOwn", Map.of("task", "", "parent", ""));
        for (final String own : List.of(LEAD, "porelo-uc-mine")) {
            final Object answer = attempt(socket, Map.of("task", own), "Logs").join();
            if (answer instanceof VarlinkException ex) {
                assertThat(ex.getErrorName()).as(own).doesNotEndWith(".NotItsOwn");
            }
        }
    }

    @Test
    void aListShowsOnlyTheTasksOfTheCallersOwnProject() throws IOException {
        final Path socket = opened(HEAD + SECTION.replace("\"Stop\"]", "\"Stop\", \"List\"]"));
        runner.answering(
                "podman ps",
                "porelo-uc-lead\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n"
                        + "porelo-other-x\tUp 4 minutes\t1700000000\t0\tother\tguarded\n");

        final Map<String, Object> answer = call(socket, "List", Map.of());

        assertThat((List<?>) answer.get("tasks"))
                .as("a task's agent reads its own project's tasks, not the person's every one")
                .isNotEmpty()
                .allSatisfy(task -> assertThat(((Map<?, ?>) task).get("project")).isEqualTo("uc"));
    }

    @Test
    void aWatchShowsOnlyTheTasksOfTheCallersOwnProjectInEveryReply() throws IOException {
        final List<Map<String, Object>> sent = new java.util.ArrayList<>();
        final org.fuin.porelo.wire.varlink.VarlinkServer.Replies replies =
                McpTaskSockets.ownProject(
                        "uc",
                        new org.fuin.porelo.wire.varlink.VarlinkServer.Replies() {
                            @Override
                            public void more(final Map<String, Object> parameters) {
                                sent.add(parameters);
                            }

                            @Override
                            public void last(final Map<String, Object> parameters) {
                                sent.add(parameters);
                            }

                            @Override
                            public boolean streaming() {
                                return true;
                            }

                            @Override
                            public boolean open() {
                                return true;
                            }
                        });
        final Map<String, Object> both =
                Map.of(
                        "tasks",
                        List.of(Map.of("project", "uc"), Map.of("project", "other")),
                        "unrecognized",
                        List.of("porelo-uc-a", "porelo-other-b", "porelo-uc2-c"));

        replies.more(both);
        replies.last(both);

        assertThat(McpTaskSockets.TASK_LISTS).containsExactlyInAnyOrder("List", "Watch");
        assertThat(sent)
                .hasSize(2)
                .allSatisfy(
                        reply -> {
                            assertThat(reply.get("tasks"))
                                    .isEqualTo(List.of(Map.of("project", "uc")));
                            assertThat(reply.get("unrecognized")).isEqualTo(List.of("porelo-uc-a"));
                        });
    }

    @Test
    void aStartThroughASectionWithoutLogsOrTailCarriesNothingOfItsRun() throws IOException {
        final List<Map<String, Object>> more = new java.util.ArrayList<>();
        final List<Map<String, Object>> last = new java.util.ArrayList<>();
        final org.fuin.porelo.wire.varlink.VarlinkServer.Replies replies =
                McpTaskSockets.withoutTheRun(
                        new org.fuin.porelo.wire.varlink.VarlinkServer.Replies() {
                            @Override
                            public void more(final Map<String, Object> parameters) {
                                more.add(parameters);
                            }

                            @Override
                            public void last(final Map<String, Object> parameters) {
                                last.add(parameters);
                            }

                            @Override
                            public boolean streaming() {
                                return true;
                            }

                            @Override
                            public boolean open() {
                                return true;
                            }
                        });

        replies.more(Map.of("line", "running   stub (up to 5 minutes)"));
        replies.more(Map.of("runLine", "the child's own output"));
        replies.last(
                Map.of(
                        "container",
                        "porelo-uc-child",
                        "exitCode",
                        0,
                        "result",
                        "the child's answer",
                        "resultKind",
                        "ANSWER",
                        "resultCut",
                        false));

        assertThat(more).containsExactly(Map.of("line", "running   stub (up to 5 minutes)"));
        assertThat(last).containsExactly(Map.of("container", "porelo-uc-child", "exitCode", 0));
    }

    @Test
    void aListWithoutLogsOrTailCarriesNothingOfWhatARunSaid() throws IOException {
        final List<Map<String, Object>> sent = new java.util.ArrayList<>();
        final org.fuin.porelo.wire.varlink.VarlinkServer.Replies replies =
                McpTaskSockets.ownProject(
                        "uc",
                        false,
                        new org.fuin.porelo.wire.varlink.VarlinkServer.Replies() {
                            @Override
                            public void more(final Map<String, Object> parameters) {
                                sent.add(parameters);
                            }

                            @Override
                            public void last(final Map<String, Object> parameters) {
                                sent.add(parameters);
                            }

                            @Override
                            public boolean streaming() {
                                return false;
                            }

                            @Override
                            public boolean open() {
                                return true;
                            }
                        });

        replies.last(
                Map.of(
                        "tasks",
                        List.of(
                                Map.of(
                                        "project",
                                        "uc",
                                        "name",
                                        "porelo-uc-child",
                                        "lastMessage",
                                        "the child's answer",
                                        "agentEnded",
                                        Map.of("error", "what it printed"))),
                        "unrecognized",
                        List.of()));

        assertThat((List<Object>) sent.getFirst().get("tasks"))
                .containsExactly(Map.of("project", "uc", "name", "porelo-uc-child"));
    }

    private void assertNothingStarted() {
        assertThat(runner.lines())
                .as("no start reached the machine's check")
                .noneMatch(line -> line.startsWith("podman version"));
    }

    private static Map<String, Object> named(final String task, final Map<String, Object> start) {
        start.put("task", task);
        return start;
    }

    private static CompletableFuture<Object> attempt(
            final Path socket, final Map<String, Object> parameters) {
        return attempt(socket, parameters, "Start");
    }

    private static CompletableFuture<Object> attempt(
            final Path socket, final Map<String, Object> parameters, final String method) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return call(socket, method, parameters);
                    } catch (IOException | RuntimeException ex) {
                        return ex;
                    }
                });
    }

    private static void assertRefused(
            final Object answer, final String error, final Map<String, Object> said) {
        assertThat(answer)
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo(PoreloDaemon.INTERFACE + "." + error);
                            said.forEach(
                                    (key, value) ->
                                            assertThat(String.valueOf(ex.getParameters().get(key)))
                                                    .as(key)
                                                    .isEqualTo(String.valueOf(value)));
                        });
    }

    private static void until(final java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
    }

    private static Map<String, Object> start(
            final String agent, final String model, final int minutes) {
        final Map<String, Object> start = new java.util.HashMap<>();
        start.put("project", "uc");
        start.put("repository", "uc");
        start.put("task", "child");
        if (agent != null) {
            start.put("agent", agent);
        }
        if (model != null) {
            start.put("model", model);
        }
        start.put("minutes", minutes);
        start.put("prompt", "go");
        return start;
    }
}
