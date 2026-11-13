package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.TaskIdeas;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A task proposes an idea for itself over its own socket, within its project file's bound. */
class TaskIdeaMethodsTest {
    private static final String TASK = "porelo-uc-work";
    private static final String OTHER = "porelo-uc-other";
    @TempDir Path dir;
    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context() throws IOException {
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
        final var context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        recorded(context, TASK, "a".repeat(64));
        recorded(context, OTHER, "b".repeat(64));
        running(Map.of(TASK, "a".repeat(64), OTHER, "b".repeat(64)));
        return context;
    }

    /** Records a task of project 'uc' under a container id, as its start does. */
    private static void recorded(final PoreloContext context, final String task, final String id)
            throws IOException {
        final Path state = context.paths().tasks().taskRecord(task);
        Sidecar.recordId(state, id);
        new Sidecar(
                        Sidecar.VERSION,
                        "uc",
                        "guarded",
                        "/rules",
                        "/dns",
                        "/porelo",
                        context.paths().tasks().containerState(task).toString())
                .writeTo(state.resolve("sidecar.json"));
    }

    /** Makes the runtime list these containers, by name and id. */
    private void running(final Map<String, String> containers) {
        final StringBuilder listed = new StringBuilder();
        new java.util.TreeMap<>(containers)
                .forEach(
                        (task, id) ->
                                listed.append(task)
                                        .append(
                                                "\tExited (0) 1 minute ago\t1788500000\t1788509999\tuc\tguarded\tr\t\t")
                                        .append(id)
                                        .append('\n'));
        runner.answering("ps", listed.toString());
    }

    /** Records the project 'uc' with a file allowing what {@code limits} says. */
    private void project(final String limits, final String methods) throws IOException {
        final Path file =
                Files.writeString(
                        dir.resolve("project.yml"),
                        "project:\n  name: uc\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\n"
                                + limits
                                + "mcp:\n  methods: ["
                                + methods
                                + "]\n  agents:\n    example:\n  tasks: 1\n  minutes: 60\n");
        Files.writeString(
                Files.createDirectories(dir.resolve("data/porelo/projects")).resolve("uc"),
                file + "\n");
    }

    private static Map<String, Object> idea(
            final String task, final String title, final String whatMustBeTrue) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("task", task);
        parameters.put("title", title);
        parameters.put("whatMustBeTrue", whatMustBeTrue);
        return parameters;
    }

    private static Map<String, Object> proposedAs(
            final String caller, final PoreloContext context, final Map<String, Object> call) {
        final List<Map<String, Object>> answer = new ArrayList<>();
        McpTaskSockets.as(
                caller,
                () -> {
                    try {
                        answer.add(TaskMethods.proposeIdea(context, call));
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
        return answer.getFirst();
    }

    private static void refusedAs(
            final String caller,
            final PoreloContext context,
            final Map<String, Object> call,
            final String error) {
        McpTaskSockets.as(
                caller,
                () ->
                        assertThatThrownBy(() -> TaskMethods.proposeIdea(context, call))
                                .as("refused: " + call.keySet())
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        ex ->
                                                assertThat(ex.getErrorName())
                                                        .as("typed refusal")
                                                        .isEqualTo(error)));
    }

    /** A row or an idea as the daemon sent it, keyed by name. */
    private static Map<String, Object> strings(final Object map) {
        final Map<String, Object> keyed = new LinkedHashMap<>();
        ((Map<?, ?>) map).forEach((key, value) -> keyed.put(String.valueOf(key), value));
        return keyed;
    }

    private static Map<?, ?> row(final PoreloContext context, final String task) {
        return ((List<?>) TaskMethods.list(new TaskInventory(context)).get("tasks"))
                .stream()
                        .map(Map.class::cast)
                        .filter(each -> task.equals(each.get("name")))
                        .findFirst()
                        .orElseThrow();
    }

    @Test
    void twoIdeasAreKeptAndListedWithTheirIdsAndAThirdIsRefused() throws Exception {
        // Acceptance: a project with limits.ideas: 2, over the task's own socket, as the window
        // sees it.
        final PoreloContext context = context();
        project("limits:\n  ideas: 2\n", "ProposeIdea, List, Tail");
        try (McpTaskSockets sockets = new McpTaskSockets(context)) {
            final Path socket = sockets.open(TASK, "uc").orElseThrow();
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final String method = PoreloDaemon.INTERFACE + ".ProposeIdea";
                assertThat(
                                client.call(
                                        method,
                                        idea(
                                                TASK,
                                                "Cache the mirror's refs",
                                                "A second fetch of an unchanged mirror asks the"
                                                        + " forge nothing.")))
                        .as("the first idea's id")
                        .containsEntry("id", "idea-1");
                assertThat(
                                client.call(
                                        method,
                                        idea(
                                                TASK,
                                                "Name the slow step",
                                                "A start that takes a minute says which step took"
                                                        + " it.\nThe step is named by its title.")))
                        .as("the second idea's id")
                        .containsEntry("id", "idea-2");
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                method,
                                                idea(TASK, "One more", "It is past the bound.")))
                        .as("a third is past the project's bound")
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                ex -> {
                                    assertThat(ex.getErrorName())
                                            .isEqualTo(PoreloDaemon.INTERFACE + ".NotAllowed");
                                    assertThat(String.valueOf(ex.getParameters().get("message")))
                                            .as("the refusal names the bound")
                                            .contains("limits.ideas")
                                            .contains("2");
                                });
                final Map<?, ?> listed =
                        ((List<?>)
                                        client.call(PoreloDaemon.INTERFACE + ".List", Map.of())
                                                .get("tasks"))
                                .stream()
                                        .map(Map.class::cast)
                                        .filter(each -> TASK.equals(each.get("name")))
                                        .findFirst()
                                        .orElseThrow();
                final List<?> ideas = (List<?>) listed.get("ideas");
                assertThat(ideas).as("both ideas, oldest first").hasSize(2);
                assertThat(strings(ideas.get(0)))
                        .containsEntry("id", "idea-1")
                        .containsEntry("title", "Cache the mirror's refs")
                        .containsEntry(
                                "whatMustBeTrue",
                                "A second fetch of an unchanged mirror asks the forge nothing.")
                        .containsKey("at");
                assertThat(strings(ideas.get(1)))
                        .containsEntry("id", "idea-2")
                        .containsEntry(
                                "whatMustBeTrue",
                                "A start that takes a minute says which step took it.\n"
                                        + "The step is named by its title.");
            }
        }
        assertThat(row(context, OTHER).get("ideas"))
                .as("another task's row carries none of them")
                .isEqualTo(List.of());
        assertThat(row(context, TASK).get("ideasHeldBack"))
                .as("nothing is held back of a task whose ideas are all listed")
                .isEqualTo(0L);
    }

    @Test
    void aTaskCannotProposeAnIdeaForAnotherTask() throws Exception {
        // An idea filed under a sibling's name would put words in its mouth for the window.
        final PoreloContext context = context();
        project("limits:\n  ideas: 2\n", "ProposeIdea, List, Tail");
        for (final String other : List.of(OTHER, "porelo-uc-child", "porelo-other-work", "../x")) {
            refusedAs(
                    TASK,
                    context,
                    idea(other, "Not mine", "It names another task."),
                    PoreloDaemon.INTERFACE + ".NotItsOwn");
        }
        assertThatThrownBy(
                        () ->
                                TaskMethods.proposeIdea(
                                        context, idea(TASK, "No socket", "A name alone.")))
                .as("a call on the person's socket is no task's own")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".NotItsOwn"));
        assertThat(new TaskIdeas(context.paths()).read(OTHER)).as("nothing kept").isEmpty();
        assertThat(new TaskIdeas(context.paths()).read(TASK)).as("nothing kept").isEmpty();
    }

    @Test
    void aProjectWithoutLimitsIdeasAllowsNone() throws Exception {
        // A task proposing work nobody asked it to propose is a channel the file did not open.
        final PoreloContext context = context();
        project("", "ProposeIdea, List, Tail");
        refusedAs(
                TASK,
                context,
                idea(TASK, "Unasked", "The project allows none."),
                PoreloDaemon.INTERFACE + ".NotAllowed");
        project("limits:\n  ideas: 0\n", "ProposeIdea, List, Tail");
        refusedAs(
                TASK,
                context,
                idea(TASK, "Unasked", "The project allows none."),
                PoreloDaemon.INTERFACE + ".NotAllowed");
        assertThat(row(context, TASK).get("ideas")).as("none kept").isEqualTo(List.of());
    }

    @Test
    void anUnreadableProjectFileAllowsNoneAndIsNotDescribedToTheTask() throws Exception {
        // Why a file cannot be read names where it lies and this machine's other projects.
        final PoreloContext context = context();
        project("limits:\n  ideas: 2\n", "ProposeIdea");
        Files.writeString(dir.resolve("project.yml"), "project: [\n");
        McpTaskSockets.as(
                TASK,
                () ->
                        assertThatThrownBy(
                                        () ->
                                                TaskMethods.proposeIdea(
                                                        context, idea(TASK, "Any", "It is.")))
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        ex -> {
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE + ".NotAllowed");
                                            assertThat(
                                                            String.valueOf(
                                                                    ex.getParameters()
                                                                            .get("message")))
                                                    .as("no path of the host's")
                                                    .doesNotContain(dir.toString());
                                        }));
    }

    @Test
    void theBoundIsReadAtEachCall() throws Exception {
        // A person who lowers the bound expects the next proposal to be held to it.
        final PoreloContext context = context();
        project("limits:\n  ideas: 1\n", "ProposeIdea");
        assertThat(proposedAs(TASK, context, idea(TASK, "First", "One idea.")))
                .containsEntry("id", "idea-1");
        refusedAs(
                TASK,
                context,
                idea(TASK, "Second", "Past one."),
                PoreloDaemon.INTERFACE + ".NotAllowed");
        project("limits:\n  ideas: 2\n", "ProposeIdea");
        assertThat(proposedAs(TASK, context, idea(TASK, "Second", "Now within two.")))
                .containsEntry("id", "idea-2");
    }

    @Test
    void aTitleOverOneLineOrTooLongOrAnEmptyStatementIsRefused() throws Exception {
        // Task text reaches a person's window: unbounded or multi-line titles would spoof its rows.
        final PoreloContext context = context();
        project("limits:\n  ideas: 50\n", "ProposeIdea");
        final List<Map<String, Object>> calls = new ArrayList<>();
        for (final String title :
                List.of(
                        "",
                        "   ",
                        "two\nlines",
                        "carriage\rreturn",
                        "tab\there",
                        "line separator",
                        "x".repeat(121))) {
            calls.add(idea(TASK, title, "Something must be true."));
        }
        for (final String what :
                List.of("", "  \n ", "bell\u0007", "tab\there", "x".repeat(1001))) {
            calls.add(idea(TASK, "A title", what));
        }
        final Map<String, Object> notText = idea(TASK, "A title", "It is.");
        notText.put("title", 42);
        calls.add(notText);
        final Map<String, Object> unknown = idea(TASK, "A title", "It is.");
        unknown.put("priority", "High");
        calls.add(unknown);
        final Map<String, Object> missing = new LinkedHashMap<>(Map.of("task", TASK));
        missing.put("title", "A title");
        calls.add(missing);
        for (final Map<String, Object> call : calls) {
            refusedAs(TASK, context, call, "org.varlink.service.InvalidParameter");
        }
        assertThat(new TaskIdeas(context.paths()).read(TASK))
                .as("a refused idea is not kept and takes no number")
                .isEmpty();
        assertThat(proposedAs(TASK, context, idea(TASK, "y".repeat(120), "z".repeat(999) + "\n")))
                .as("the bounds themselves are kept")
                .containsEntry("id", "idea-1");
    }

    @Test
    void theRecordIsTheHostsOwnAndTheIdeasChangeWatch() throws Exception {
        // A record the task could write would let it number or rewrite its own ideas.
        final PoreloContext context = context();
        project("limits:\n  ideas: 2\n", "ProposeIdea");
        final String before = PoreloDaemon.withoutAge(strings(row(context, TASK)));
        proposedAs(TASK, context, idea(TASK, "Kept", "It is kept."));
        final Path record = context.paths().tasks().taskRecord(TASK).resolve("ideas.json");
        assertThat(record).exists();
        assertThat(Files.getPosixFilePermissions(record))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(record.startsWith(context.paths().messaging().mailbox(TASK))).isFalse();
        assertThat(PoreloDaemon.withoutAge(strings(row(context, TASK))))
                .as("a new idea is a change a watcher is sent")
                .isNotEqualTo(before);
    }

    @Test
    void aTaskSocketWithoutLogsOrTailIsSentNoIdeas() throws Exception {
        // One task's text must not reach another agent's prompt through a listing.
        final PoreloContext context = context();
        project("limits:\n  ideas: 2\n", "ProposeIdea, List");
        proposedAs(TASK, context, idea(TASK, "Private", "Only its reader sees it."));
        try (McpTaskSockets sockets = new McpTaskSockets(context)) {
            final Path socket = sockets.open(OTHER, "uc").orElseThrow();
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<?, ?> listed =
                        ((List<?>)
                                        client.call(PoreloDaemon.INTERFACE + ".List", Map.of())
                                                .get("tasks"))
                                .stream()
                                        .map(Map.class::cast)
                                        .filter(each -> TASK.equals(each.get("name")))
                                        .findFirst()
                                        .orElseThrow();
                assertThat(listed.get("ideas")).as("the field stays, empty").isEqualTo(List.of());
                assertThat(((Number) listed.get("ideasHeldBack")).longValue())
                        .as("nor is a count of them told")
                        .isZero();
            }
        }
        assertThat((List<?>) row(context, TASK).get("ideas"))
                .as("the person's own listing has it")
                .hasSize(1);
    }

    @Test
    void thePersonsSocketRefusesItAsNoTasksOwn() throws Exception {
        final PoreloContext context = context();
        project("limits:\n  ideas: 2\n", "ProposeIdea");
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("person.sock"))) {
            final Thread thread = Thread.ofPlatform().daemon().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".ProposeIdea",
                                                idea(TASK, "Impersonated", "A name alone.")))
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                ex ->
                                        assertThat(ex.getErrorName())
                                                .isEqualTo(PoreloDaemon.INTERFACE + ".NotItsOwn"));
            } finally {
                server.close();
                thread.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    /** Proposes as the task, running {@code between} after its check and before its write. */
    private static Throwable proposedWith(
            final PoreloContext context, final Map<String, Object> call, final Runnable between) {
        final List<Throwable> thrown = new ArrayList<>();
        McpTaskSockets.as(
                TASK,
                () -> {
                    try {
                        TaskMethods.proposeIdea(context, call, between);
                    } catch (IOException | RuntimeException ex) {
                        thrown.add(ex);
                    }
                });
        return thrown.isEmpty() ? null : thrown.getFirst();
    }

    /** Makes the runtime say the task's container is gone, as after its removal. */
    private void gone() {
        runner.failing("inspect", 125, "Error: no such container");
    }

    private static void refusedAsNotItsOwn(final Throwable thrown) {
        assertThat(thrown)
                .as("refused as no longer the socket's own task")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".NotItsOwn"));
    }

    @Test
    void aRemovalBetweenTheCheckAndTheWriteLeavesNoRecordBehind() throws Exception {
        // A record made again after the removal deleted it blocks the name's next start.
        final PoreloContext context = context();
        project("limits:\n  ideas: 2\n", "ProposeIdea");
        final Throwable thrown =
                proposedWith(
                        context,
                        idea(TASK, "Late", "It arrives after the removal."),
                        () -> {
                            gone();
                            assertThat(
                                            new org.fuin.porelo.app.TaskControl(context)
                                                    .remove(TASK, false, true)
                                                    .removed())
                                    .as("the removal went through")
                                    .isTrue();
                            running(Map.of(OTHER, "b".repeat(64)));
                        });
        assertThat(context.paths().tasks().taskRecord(TASK))
                .as("no record of the removed task made again")
                .doesNotExist();
        refusedAsNotItsOwn(thrown);
    }

    @Test
    void aReplacementBetweenTheCheckAndTheWriteIsNotGivenTheIdea() throws Exception {
        // The idea of a removed task would show on the row of the next task of its name.
        final PoreloContext context = context();
        project("limits:\n  ideas: 2\n", "ProposeIdea");
        final Throwable thrown =
                proposedWith(
                        context,
                        idea(TASK, "Stale", "It belongs to the removed task."),
                        () -> {
                            try {
                                gone();
                                assertThat(
                                                new org.fuin.porelo.app.TaskControl(context)
                                                        .remove(TASK, false, true)
                                                        .removed())
                                        .as("the removal went through")
                                        .isTrue();
                                recorded(context, TASK, "c".repeat(64));
                                running(Map.of(TASK, "c".repeat(64), OTHER, "b".repeat(64)));
                            } catch (IOException ex) {
                                throw new UncheckedIOException(ex);
                            }
                        });
        assertThat(new TaskIdeas(context.paths()).read(TASK))
                .as("the replacement holds no idea of its predecessor's")
                .isEmpty();
        refusedAsNotItsOwn(thrown);
    }

    @Test
    void aListingOfTasksAtTheirBoundStaysOneMessageAClientReads() throws Exception {
        // Each task's ideas are bounded alone; a listing of several at their bound passed the 4 MiB
        // a varlink message may hold, and the person's whole listing could no longer be read.
        final PoreloContext context = context();
        project("limits:\n  ideas: 100\n", "ProposeIdea");
        final Map<String, String> tasks = new LinkedHashMap<>();
        tasks.put(TASK, "a".repeat(64));
        tasks.put(OTHER, "b".repeat(64));
        tasks.put("porelo-uc-third", "c".repeat(64));
        tasks.put("porelo-uc-fourth", "d".repeat(64));
        // A format character is written escaped, twelve bytes for each: the longest an idea
        // the host keeps can be sent.
        final String wide = new String(Character.toChars(0xE0041));
        for (final Map.Entry<String, String> each : tasks.entrySet()) {
            recorded(context, each.getKey(), each.getValue());
            final List<Map<String, Object>> ideas = new ArrayList<>();
            for (int number = 1; number <= 100; number++) {
                ideas.add(
                        new TaskIdeas.Idea(
                                        "idea-" + number,
                                        wide.repeat(TaskIdeas.MOST_TITLE),
                                        wide.repeat(TaskIdeas.MOST_WHAT_MUST_BE_TRUE),
                                        "2026-11-13T00:00:00Z")
                                .asMap());
            }
            Files.writeString(
                    context.paths().tasks().taskRecord(each.getKey()).resolve("ideas.json"),
                    org.fuin.porelo.wire.Json.write(Map.of("ideas", ideas)));
        }
        running(tasks);
        assertThat(new TaskIdeas(context.paths()).read(TASK))
                .as("each record is one the host keeps")
                .hasSize(100);
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("person.sock"))) {
            final Thread thread = Thread.ofPlatform().daemon().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Map<String, Object> reply =
                        client.call(PoreloDaemon.INTERFACE + ".List", Map.of());
                assertThat(
                                org.fuin.porelo.wire.Json.write(reply)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                        .length)
                        .as("the reply's encoded size")
                        .isLessThan(4 * 1024 * 1024);
                long heldBack = 0;
                for (final Object row : (List<?>) reply.get("tasks")) {
                    final List<?> ideas = (List<?>) ((Map<?, ?>) row).get("ideas");
                    assertThat(((Map<?, ?>) row).get("ideasHeldBack"))
                            .as("what was left out, so the window knows more wait")
                            .isInstanceOf(Number.class);
                    final long left = ((Number) ((Map<?, ?>) row).get("ideasHeldBack")).longValue();
                    assertThat(ideas.size() + left)
                            .as("listed and held back are the task's every idea")
                            .isEqualTo(100L);
                    heldBack += left;
                    assertThat(ideas).as("every task's oldest idea is carried").isNotEmpty();
                    for (int index = 0; index < ideas.size(); index++) {
                        assertThat(strings(ideas.get(index)))
                                .as("the oldest first, none skipped")
                                .containsEntry("id", "idea-" + (index + 1));
                    }
                }
                assertThat(heldBack).as("this listing is past the limit").isPositive();
            } finally {
                server.close();
                thread.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    @Test
    void theTaskEndpointMayCarryProposeIdeaAsAWritingMethod() {
        assertThat(McpTaskSockets.refusals(List.of("ProposeIdea"))).isEmpty();
        assertThat(McpMethods.READING).doesNotContain("ProposeIdea");
    }
}
