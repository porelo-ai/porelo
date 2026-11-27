package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A task reports back over its own socket, within its project file's bound; a person reads it. */
class TaskReportMethodsTest {
    private static final String TASK = "porelo-uc-work";
    private static final String OTHER = "porelo-uc-other";
    private static final String REPORT = PoreloDaemon.INTERFACE + ".Report";
    private static final String REPORTS = PoreloDaemon.INTERFACE + ".Reports";

    /**
     * A format character, written escaped: twelve bytes for each, the longest a character of a
     * report can be sent.
     */
    private static final String WIDE = new String(Character.toChars(0xE0041));

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
        // As a start naming done and report records it: its own work, its endpoint carrying both.
        new org.fuin.porelo.app.RunPlace(
                        new org.fuin.porelo.gate.review.TaskParts(
                                org.fuin.porelo.gate.review.TaskParts.Workspace.OWN,
                                List.of(),
                                true,
                                org.fuin.porelo.gate.review.TaskParts.Account.AUTHOR,
                                java.util.Set.of(
                                        org.fuin.porelo.gate.review.Right.PUSH,
                                        org.fuin.porelo.gate.review.Right.REPLY,
                                        org.fuin.porelo.gate.review.Right.DONE,
                                        org.fuin.porelo.gate.review.Right.REPORT)),
                        null,
                        task.substring("porelo-uc-".length()))
                .writeTo(Files.createDirectories(context.paths().tasks().containerState(task)));
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

    private static Map<String, Object> report(
            final String task, final String kind, final String text) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("task", task);
        parameters.put("kind", kind);
        parameters.put("text", text);
        return parameters;
    }

    private static Map<String, Object> after(final String task, final long after) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("task", task);
        parameters.put("after", after);
        return parameters;
    }

    /** Runs {@code body} with a client on the person's own socket. */
    private void asThePerson(final PoreloContext context, final Body body) throws Exception {
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("person.sock"))) {
            final Thread thread = Thread.ofPlatform().daemon().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                body.run(client);
            } finally {
                server.close();
                thread.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    /** Runs {@code body} with a client on a task's own socket. */
    private static void asTheTask(final PoreloContext context, final String task, final Body body)
            throws Exception {
        try (McpTaskSockets sockets = new McpTaskSockets(context)) {
            final Path socket = sockets.open(task, "uc").orElseThrow();
            try (VarlinkClient client = new VarlinkClient(socket)) {
                body.run(client);
            }
        }
    }

    /** What runs with a client. */
    @FunctionalInterface
    private interface Body {
        void run(VarlinkClient client) throws Exception;
    }

    private static List<?> reportsOf(final Map<String, Object> answer) {
        return (List<?>) answer.get("reports");
    }

    /** A report as the daemon sent it, keyed by name. */
    private static Map<String, Object> keyed(final Object map) {
        final Map<String, Object> keyed = new LinkedHashMap<>();
        ((Map<?, ?>) map).forEach((key, value) -> keyed.put(String.valueOf(key), value));
        return keyed;
    }

    private static long number(final Object value) {
        return ((Number) value).longValue();
    }

    private static Map<?, ?> row(final List<?> tasks, final String task) {
        return tasks.stream()
                .map(Map.class::cast)
                .filter(each -> task.equals(each.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<?, ?> row(final PoreloContext context, final String task) {
        return row((List<?>) TaskMethods.list(new TaskInventory(context)).get("tasks"), task);
    }

    private static void refused(final Throwable thrown, final String error) {
        assertThat(thrown)
                .as("refused as " + error)
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .as("typed refusal")
                                        .isEqualTo(PoreloDaemon.INTERFACE + "." + error));
    }

    @Test
    void aTaskReportsTwiceAndIsRefusedTheThirdTimeByTheBoundsName() throws Exception {
        // Acceptance: a project with limits.reports: 2, over the task's own socket.
        final PoreloContext context = context();
        project("limits:\n  reports: 2\n", "Report, List, Tail");
        asTheTask(
                context,
                TASK,
                client -> {
                    assertThat(client.call(REPORT, report(TASK, "status", "Half done.")))
                            .as("the first report's number")
                            .containsEntry("seq", 1L);
                    assertThat(
                                    client.call(
                                            REPORT,
                                            report(TASK, "finding", "The mirror is slow.\nTwice.")))
                            .as("the second report's number")
                            .containsEntry("seq", 2L);
                    assertThatThrownBy(() -> client.call(REPORT, report(TASK, "status", "More.")))
                            .as("a third is past the project's bound")
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex -> {
                                        assertThat(ex.getErrorName())
                                                .as("typed refusal")
                                                .isEqualTo(PoreloDaemon.INTERFACE + ".NotAllowed");
                                        assertThat(
                                                        String.valueOf(
                                                                ex.getParameters().get("message")))
                                                .as("the refusal names the bound")
                                                .contains("limits.reports")
                                                .contains("2");
                                    });
                });
        asThePerson(
                context,
                client -> {
                    final Map<String, Object> answer = client.call(REPORTS, Map.of("task", TASK));
                    assertThat(reportsOf(answer)).as("both, oldest first").hasSize(2);
                    assertThat(keyed(reportsOf(answer).get(1)))
                            .as("the second as the task gave it")
                            .containsEntry("seq", 2L)
                            .containsEntry("kind", "finding")
                            .containsEntry("text", "The mirror is slow.\nTwice.")
                            .containsKey("at");
                    assertThat(number(answer.get("last"))).as("the last number").isEqualTo(2);
                    assertThat(answer.get("more")).as("nothing left out").isEqualTo(false);
                });
        assertThat(number(row(context, TASK).get("reports")))
                .as("the row carries the last number")
                .isEqualTo(2);
        assertThat(number(row(context, OTHER).get("reports")))
                .as("another task's row is at 0")
                .isZero();
    }

    @Test
    void aReportTheHostCannotKeepIsAnsweredFailedNotADroppedSocket() throws Exception {
        // A caller left without an answer cannot tell a kept report from a lost one.
        final PoreloContext context = context();
        project("limits:\n  reports: 2\n", "Report");
        Files.createDirectories(context.paths().tasks().workRecords().resolve(TASK + ".reports"));
        asTheTask(
                context,
                TASK,
                client ->
                        refused(
                                catchThrowable(
                                        () -> client.call(REPORT, report(TASK, "status", "Lost."))),
                                "Failed"));
    }

    @Test
    void aProjectThatSaysNothingAllowsNoReport() throws Exception {
        // A task reporting what nobody asked for is a channel the file did not open.
        final PoreloContext context = context();
        project("", "Report, List");
        asTheTask(
                context,
                TASK,
                client ->
                        refused(
                                catchThrowable(
                                        () -> client.call(REPORT, report(TASK, "status", "No."))),
                                "NotAllowed"));
        assertThat(number(row(context, TASK).get("reports"))).as("none kept").isZero();
    }

    @Test
    void aReportOverAnotherTasksSocketIsNotItsOwn() throws Exception {
        // A report under a sibling's name would put words in its mouth for its caller.
        final PoreloContext context = context();
        project("limits:\n  reports: 2\n", "Report, List, Tail");
        asTheTask(
                context,
                OTHER,
                client ->
                        refused(
                                catchThrowable(
                                        () ->
                                                client.call(
                                                        REPORT,
                                                        report(TASK, "status", "Not mine."))),
                                "NotItsOwn"));
        asThePerson(
                context,
                client -> {
                    refused(
                            catchThrowable(
                                    () -> client.call(REPORT, report(TASK, "status", "A name."))),
                            "NotItsOwn");
                    assertThat(reportsOf(client.call(REPORTS, Map.of("task", TASK))))
                            .as("nothing kept")
                            .isEmpty();
                });
    }

    @Test
    void aRemovedTasksReportsAreStillRead() throws Exception {
        // The caller may read only after the task is gone; what it reported must not go with it.
        final PoreloContext context = context();
        project("limits:\n  reports: 2\n", "Report");
        asTheTask(context, TASK, client -> client.call(REPORT, report(TASK, "done", "Shipped.")));
        runner.failing("inspect", 125, "Error: no such container");
        assertThat(new org.fuin.porelo.app.TaskControl(context).remove(TASK, false, true).removed())
                .as("the removal went through")
                .isTrue();
        asThePerson(
                context,
                client -> {
                    final Map<String, Object> answer = client.call(REPORTS, Map.of("task", TASK));
                    assertThat(reportsOf(answer))
                            .as("the removed task's report")
                            .singleElement()
                            .satisfies(
                                    kept ->
                                            assertThat(keyed(kept))
                                                    .containsEntry("text", "Shipped."));
                    assertThat(number(answer.get("last"))).as("its number").isEqualTo(1);
                });
    }

    @Test
    void afterAnswersOnlyTheLaterOnes() throws Exception {
        final PoreloContext context = context();
        project("limits:\n  reports: 3\n", "Report");
        asTheTask(
                context,
                TASK,
                client -> {
                    for (int number = 1; number <= 3; number++) {
                        client.call(REPORT, report(TASK, "status", "Report " + number + "."));
                    }
                });
        asThePerson(
                context,
                client -> {
                    assertThat(reportsOf(client.call(REPORTS, after(TASK, 1))))
                            .as("the ones after 1")
                            .extracting(each -> number(((Map<?, ?>) each).get("seq")))
                            .containsExactly(2L, 3L);
                    final Map<String, Object> none = client.call(REPORTS, after(TASK, 3));
                    assertThat(reportsOf(none)).as("none after the last").isEmpty();
                    assertThat(number(none.get("last"))).as("the last still told").isEqualTo(3);
                    assertThat(none.get("more")).as("nothing left out").isEqualTo(false);
                    assertThatThrownBy(() -> client.call(REPORTS, after(TASK, -1)))
                            .as("no number before the first")
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    ex ->
                                            assertThat(ex.getErrorName())
                                                    .as("the standard refusal")
                                                    .isEqualTo(
                                                            "org.varlink.service.InvalidParameter"));
                });
    }

    @Test
    void moreIsSaidWhenTheOneMebibyteCutLeavesSomeOut() throws Exception {
        final PoreloContext context = context();
        project("limits:\n  reports: 3\n", "Report");
        final String widest = WIDE.repeat(65_536);
        asTheTask(
                context,
                TASK,
                client -> {
                    for (int number = 1; number <= 3; number++) {
                        client.call(REPORT, report(TASK, "status", widest));
                    }
                });
        asThePerson(
                context,
                client -> {
                    final Map<String, Object> answer = client.call(REPORTS, after(TASK, 0));
                    assertThat(reportsOf(answer)).as("one widest fits, two do not").hasSize(1);
                    assertThat(answer.get("more")).as("the cut is said").isEqualTo(true);
                    assertThat(number(answer.get("last"))).as("the task's last").isEqualTo(3);
                });
    }

    @Test
    void theRowsNumberChangesInWatch() throws Exception {
        // The caller learns of a new report from the row alone, then reads it with Reports.
        final PoreloContext context = context();
        project("limits:\n  reports: 2\n", "Report");
        asThePerson(
                context,
                client -> {
                    final BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
                    final VarlinkClient watcher = new VarlinkClient(dir.resolve("person.sock"));
                    final Thread watching =
                            Thread.ofPlatform()
                                    .daemon()
                                    .start(
                                            () -> {
                                                try {
                                                    watcher.callMore(
                                                            PoreloDaemon.INTERFACE + ".Watch",
                                                            Map.of(),
                                                            sent::add);
                                                } catch (VarlinkException closed) {
                                                    // The test closed it.
                                                }
                                            });
                    try {
                        final Map<String, Object> first = sent.poll(10, TimeUnit.SECONDS);
                        assertThat(first).as("the first state is sent").isNotNull();
                        assertThat(number(row((List<?>) first.get("tasks"), TASK).get("reports")))
                                .as("nothing reported yet")
                                .isZero();
                        asTheTask(
                                context,
                                TASK,
                                task -> task.call(REPORT, report(TASK, "status", "Moved.")));
                        final Map<String, Object> next = sent.poll(10, TimeUnit.SECONDS);
                        assertThat(next).as("a report is a change a watcher is sent").isNotNull();
                        assertThat(number(row((List<?>) next.get("tasks"), TASK).get("reports")))
                                .as("the row's number moved")
                                .isEqualTo(1);
                        // Recorded for the hand-over, under the build's own target.
                        final Path handover =
                                Files.createDirectories(Path.of("..", "target", "handover"));
                        Files.writeString(
                                handover.resolve("watch-row-reports-moved.json"),
                                org.fuin.porelo.wire.Json.write(
                                        row((List<?>) next.get("tasks"), TASK)));
                    } finally {
                        watcher.close();
                        watching.join(java.time.Duration.ofSeconds(5));
                    }
                });
    }

    @Test
    void aSocketWithoutLogsOrTailIsToldNoOtherTasksNumber() throws Exception {
        // Even the number says another task wrote something.
        final PoreloContext context = context();
        project("limits:\n  reports: 2\n", "Report, List");
        asTheTask(context, TASK, client -> client.call(REPORT, report(TASK, "status", "Mine.")));
        asTheTask(
                context,
                OTHER,
                client ->
                        assertThat(
                                        number(
                                                row(
                                                                (List<?>)
                                                                        client.call(
                                                                                        PoreloDaemon
                                                                                                        .INTERFACE
                                                                                                + ".List",
                                                                                        Map.of())
                                                                                .get("tasks"),
                                                                TASK)
                                                        .get("reports")))
                                .as("sent as 0")
                                .isZero());
        assertThat(number(row(context, TASK).get("reports")))
                .as("the person's own listing has it")
                .isEqualTo(1);
    }

    @Test
    void reportIsATaskSocketsMethodAndReportsThePersonsAlone() {
        assertThat(McpTaskSockets.refusals(List.of("Report")))
                .as("a task's endpoint may carry Report")
                .isEmpty();
        assertThat(McpMethods.NEVER)
                .as("what tasks reported is read by the person, never by a task")
                .contains("Reports");
        assertThat(McpTaskSockets.refusals(List.of("Reports")))
                .as("so no endpoint carries it")
                .isNotEmpty();
    }

    private static Throwable catchThrowable(
            final org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return org.assertj.core.api.Assertions.catchThrowable(call);
    }
}
