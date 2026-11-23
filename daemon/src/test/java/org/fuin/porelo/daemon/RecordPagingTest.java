package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkConnection;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests that a project's task records reach a client in pages that each fit one message. */
class RecordPagingTest {

    private static final String PROJECT = "uc";
    private static final Instant FIRST = Instant.parse("2026-11-20T10:00:00Z");

    @TempDir Path dir;

    @Test
    void recordsPastOneMessageComeInPagesGivingEveryCallOnceInOrder() throws Exception {
        // 40 records of 500 calls each are about 6 MB: far more than one message holds.
        final PoreloContext context = context();
        final List<String> written = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            written.addAll(record(context, "porelo-uc-w" + i, FIRST.plusSeconds(i), 500, "u1"));
        }

        try (VarlinkServer server = serve(context);
                VarlinkClient client = new VarlinkClient(server.socketPath())) {
            final Read read = readAll(client);

            assertThat(read.calls).as("every call once, in order").isEqualTo(written);
            assertThat(read.calls).as("twenty thousand calls").hasSize(20_000);
            assertThat(read.recordPages).as("more than one page of records").isGreaterThan(1);
            assertThat(read.largest)
                    .as("every page fits one message")
                    .isLessThanOrEqualTo(VarlinkConnection.MAX_MESSAGE);
        }
    }

    @Test
    void aRecordOfMoreThanOneMessageOfCallsIsPaged() throws Exception {
        // One task of forty thousand calls is a record three messages hardly hold.
        final PoreloContext context = context();
        final List<String> written = new ArrayList<>();
        written.addAll(record(context, "porelo-uc-small", FIRST, 3, "u1"));
        written.addAll(record(context, "porelo-uc-large", FIRST.plusSeconds(1), 40_000, "u1"));
        written.addAll(record(context, "porelo-uc-after", FIRST.plusSeconds(2), 3, "u1"));

        try (VarlinkServer server = serve(context);
                VarlinkClient client = new VarlinkClient(server.socketPath())) {
            final Read read = readAll(client);

            assertThat(read.calls).as("every call once, in order").isEqualTo(written);
            assertThat(read.callPages)
                    .as("the large record's further calls came from RecordCalls, page by page")
                    .isGreaterThan(1);
            assertThat(read.records)
                    .as("each record once")
                    .containsExactly("porelo-uc-small", "porelo-uc-large", "porelo-uc-after");
            assertThat(read.largest)
                    .as("every page fits one message")
                    .isLessThanOrEqualTo(VarlinkConnection.MAX_MESSAGE);
        }
    }

    @Test
    void aSmallProjectIsAnsweredWholeWithoutAPosition() throws Exception {
        // A client that reads the first reply alone gets every record and call.
        final PoreloContext context = context();
        record(context, "porelo-uc-one", FIRST, 3, "u1");

        try (VarlinkServer server = serve(context);
                VarlinkClient client = new VarlinkClient(server.socketPath())) {
            final Map<String, Object> answer = records(client, null);

            assertThat(answer).as("no next page").doesNotContainKey("next");
            final Map<String, Object> only =
                    (Map<String, Object>) ((List<?>) answer.get("records")).get(0);
            assertThat(only).as("its calls whole").doesNotContainKey("toolCallsNext");
            assertThat((List<?>) only.get("toolCalls")).hasSize(3);
        }
    }

    @Test
    void aPositionAtOrPastTheRecordsCallsIsRefusedAsInvalid() throws Exception {
        final PoreloContext context = context();
        record(context, "porelo-uc-large", FIRST, 20_000, "u1");

        try (VarlinkServer server = serve(context);
                VarlinkClient client = new VarlinkClient(server.socketPath())) {
            for (final long from : List.of(20_000L, 30_000L, -1L)) {
                assertThatThrownBy(() -> calls(client, "porelo-uc-large", "u1", from))
                        .as("from %d is no position the daemon gave", from)
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                error -> {
                                    assertThat(error.getErrorName())
                                            .isEqualTo("org.varlink.service.InvalidParameter");
                                    assertThat(error.getParameters())
                                            .containsEntry("parameter", "from");
                                });
            }
        }
    }

    @Test
    void aRecordWrittenSinceItsPositionWasGivenAnswersRecordChanged() throws Exception {
        // A record that grew would give calls skipped or repeated: the client reads again.
        final PoreloContext context = context();
        record(context, "porelo-uc-large", FIRST, 20_000, "u1");

        try (VarlinkServer server = serve(context);
                VarlinkClient client = new VarlinkClient(server.socketPath())) {
            final Map<?, ?> first =
                    (Map<?, ?>) ((List<?>) records(client, null).get("records")).get(0);
            final long next = ((Number) first.get("toolCallsNext")).longValue();
            record(context, "porelo-uc-large", FIRST, 20_100, "u2");

            assertThatThrownBy(() -> calls(client, "porelo-uc-large", "u1", next))
                    .as("the record was written since")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            error -> {
                                assertThat(error.getErrorName())
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".RecordChanged");
                                assertThat(error.getParameters())
                                        .containsEntry("task", "porelo-uc-large");
                            });
            Files.delete(file(context, "porelo-uc-large"));
            assertThatThrownBy(() -> calls(client, "porelo-uc-large", "u2", next))
                    .as("the record is gone")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            error ->
                                    assertThat(error.getErrorName())
                                            .isEqualTo(PoreloDaemon.INTERFACE + ".RecordChanged"));
        }
    }

    @Test
    void aNextRecordNoLongerStoredAnswersRecordChanged() throws Exception {
        // Going on past a record removed by hand would look like the whole list.
        final PoreloContext context = context();
        for (int i = 0; i < 40; i++) {
            record(context, "porelo-uc-w" + i, FIRST.plusSeconds(i), 500, "u1");
        }

        try (VarlinkServer server = serve(context);
                VarlinkClient client = new VarlinkClient(server.socketPath())) {
            final Map<?, ?> next = (Map<?, ?>) records(client, null).get("next");
            Files.delete(file(context, String.valueOf(next.get("task"))));

            assertThatThrownBy(() -> records(client, next))
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            error -> {
                                assertThat(error.getErrorName())
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".RecordChanged");
                                assertThat(error.getParameters())
                                        .containsEntry("task", next.get("task"));
                            });
            final Map<String, Object> made = new HashMap<>();
            made.put("asOf", "no time");
            made.put("startedAt", FIRST.toString());
            made.put("task", "porelo-uc-w0");
            assertThatThrownBy(() -> records(client, made))
                    .as("a position the daemon did not give")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            error ->
                                    assertThat(error.getParameters())
                                            .containsEntry("parameter", "from"));
        }
    }

    @Test
    void aRecordStartedAfterTheFirstPageIsLeftToTheNextReading() throws Exception {
        // The first page pins when the reading began: pages never grow while a client reads them.
        final PoreloContext context = context();
        final List<String> written = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            written.addAll(record(context, "porelo-uc-w" + i, FIRST.plusSeconds(i), 500, "u1"));
        }

        try (VarlinkServer server = serve(context);
                VarlinkClient client = new VarlinkClient(server.socketPath())) {
            final Map<String, Object> first = records(client, null);
            record(context, "porelo-uc-later", Instant.now().plusSeconds(3600), 3, "u1");
            final List<String> read = new ArrayList<>(ids(first));
            Map<?, ?> next = (Map<?, ?>) first.get("next");
            while (next != null) {
                final Map<String, Object> page = records(client, next);
                read.addAll(ids(page));
                next = (Map<?, ?>) page.get("next");
            }

            assertThat(read).as("only the records there when reading began").isEqualTo(written);
        }
    }

    @Test
    void theContractDeclaresThePages() {
        assertThat(PoreloDaemon.description())
                .contains(
                        "method Records(project: string, since: string, from: ?RecordsFrom)"
                                + " -> (records: []TaskRecord, next: ?RecordsFrom)")
                .contains(") -> (toolCalls: []ToolCall, next: ?int)")
                .contains("error RecordChanged(task: string)")
                .contains("toolCallsNext: ?int");
    }

    private record Read(
            List<String> calls,
            List<String> records,
            int recordPages,
            int callPages,
            int largest) {}

    private static Read readAll(final VarlinkClient client) {
        final List<String> calls = new ArrayList<>();
        final List<String> tasks = new ArrayList<>();
        int recordPages = 0;
        int callPages = 0;
        int largest = 0;
        Map<?, ?> from = null;
        do {
            final Map<String, Object> page = records(client, from);
            recordPages++;
            largest = Math.max(largest, ReviewCut.bytes(page));
            for (final Object each : (List<?>) page.get("records")) {
                final Map<?, ?> record = (Map<?, ?>) each;
                tasks.add(String.valueOf(record.get("task")));
                ((List<?>) record.get("toolCalls"))
                        .forEach(call -> calls.add(String.valueOf(((Map<?, ?>) call).get("id"))));
                Object next = record.get("toolCallsNext");
                while (next != null) {
                    final Map<String, Object> more =
                            calls(
                                    client,
                                    String.valueOf(record.get("task")),
                                    String.valueOf(record.get("startedAt")),
                                    String.valueOf(record.get("updatedAt")),
                                    ((Number) next).longValue());
                    callPages++;
                    largest = Math.max(largest, ReviewCut.bytes(more));
                    ((List<?>) more.get("toolCalls"))
                            .forEach(
                                    call ->
                                            calls.add(
                                                    String.valueOf(((Map<?, ?>) call).get("id"))));
                    next = more.get("next");
                }
            }
            from = (Map<?, ?>) page.get("next");
        } while (from != null);
        return new Read(calls, tasks, recordPages, callPages, largest);
    }

    private static List<String> ids(final Map<String, Object> page) {
        final List<String> ids = new ArrayList<>();
        for (final Object each : (List<?>) page.get("records")) {
            ((List<?>) ((Map<?, ?>) each).get("toolCalls"))
                    .forEach(call -> ids.add(String.valueOf(((Map<?, ?>) call).get("id"))));
        }
        return ids;
    }

    private static Map<String, Object> records(final VarlinkClient client, final Map<?, ?> from) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("project", PROJECT);
        parameters.put("since", "2026-01-01T00:00:00Z");
        if (from != null) {
            parameters.put("from", from);
        }
        return client.call(PoreloDaemon.INTERFACE + ".Records", parameters);
    }

    private static Map<String, Object> calls(
            final VarlinkClient client,
            final String task,
            final String updatedAt,
            final long from) {
        return calls(client, task, FIRST.toString(), updatedAt, from);
    }

    private static Map<String, Object> calls(
            final VarlinkClient client,
            final String task,
            final String startedAt,
            final String updatedAt,
            final long from) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("project", PROJECT);
        parameters.put("task", task);
        parameters.put("startedAt", startedAt);
        parameters.put("updatedAt", updatedAt);
        parameters.put("from", from);
        return client.call(PoreloDaemon.INTERFACE + ".RecordCalls", parameters);
    }

    /** Writes a record as porelod keeps it, returning its calls' ids in their order. */
    private static List<String> record(
            final PoreloContext context,
            final String task,
            final Instant started,
            final int calls,
            final String updatedAt)
            throws IOException {
        final List<Object> entries = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            final String id = task + "-call-" + i;
            ids.add(id);
            final Map<String, Object> call = new LinkedHashMap<>();
            call.put("task", task);
            call.put("id", id);
            call.put("parent", null);
            call.put("run", 1L);
            call.put("tool", "Bash");
            call.put("kind", "shell");
            call.put("start", "2026-11-20T10:00:00Z");
            call.put("startFrom", "stream");
            call.put("end", "2026-11-20T10:00:01Z");
            call.put("endFrom", "stream");
            call.put(
                    "command",
                    "git status --short && ./mvnw -q verify -pl apps/app 2>&1 | tail -40");
            call.put("background", false);
            call.put("issue", null);
            call.put("tokens", null);
            entries.add(call);
        }
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("task", task);
        record.put("project", PROJECT);
        record.put("repository", null);
        record.put("work", null);
        record.put("agent", "worker-one");
        record.put("model", null);
        record.put("modelFrom", "agent");
        record.put("effort", null);
        record.put("startedAt", started.toString());
        record.put("endedAt", started.plusSeconds(60).toString());
        record.put("end", "done");
        record.put("totals", Map.of());
        record.put("toolCallsAsked", "ASKED");
        record.put("toolCalls", entries);
        record.put("runs", 1L);
        record.put("updatedAt", updatedAt);
        final Path file = file(context, task);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(record), StandardCharsets.UTF_8);
        return ids;
    }

    private static Path file(final PoreloContext context, final String task) {
        return context.paths().tasks().workRecord(task);
    }

    private VarlinkServer serve(final PoreloContext context) {
        final VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"));
        Thread.ofVirtual().start(server);
        return server;
    }

    private PoreloContext context() {
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
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }
}
