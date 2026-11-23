package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.TaskUsage;
import org.fuin.porelo.app.TaskWork;
import org.fuin.porelo.app.TaskWorkList;
import org.fuin.porelo.app.TaskWorkRecords;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests that the daemon answers a project's task records as the command line does. */
class TaskRecordsMethodTest {

    @Test
    void recordsAnswersARemovedTasksRecordAsTheCommandLineReadsIt(@TempDir final Path dir)
            throws Exception {
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
        final var context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        args -> 0);
        final String task = "porelo-uc-work";
        new TaskWorkRecords(context)
                .started(
                        task,
                        "uc",
                        "work",
                        new TaskWorkList(
                                java.util.List.of(new TaskWork("ISSUE-1", "Implementing"))),
                        "worker-one",
                        null,
                        "agent");
        new TaskUsage(context).archive(task);

        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final var answer =
                        client.call(
                                PoreloDaemon.INTERFACE + ".Records",
                                Map.of("project", "uc", "since", "2026-01-01T00:00:00Z"));
                assertThat(answer.get("records"))
                        .as(
                                "the same records the command line reads, the removed task's among them")
                        .isEqualTo(
                                Json.parseExact(
                                        Json.write(
                                                new TaskWorkRecords(context)
                                                        .since(
                                                                "uc",
                                                                Instant.parse(
                                                                        "2026-01-01T00:00:00Z")))));
                assertThat((java.util.List<?>) answer.get("records")).hasSize(1);
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".Records",
                                                Map.of("project", "uc", "since", "yesterday")))
                        .as("a since that is no time")
                        .isInstanceOf(VarlinkException.class);
            }
        }
        assertThat(PoreloDaemon.description())
                .contains(
                        "method Records(project: string, since: string, from: ?RecordsFrom)"
                                + " -> (records: []TaskRecord, next: ?RecordsFrom)")
                .contains("work: ?[](issue: string, phase: string)");
    }

    @Test
    void aLaterPageOfRecordsReadsNoRecordFileAndAReadingGoneIsReadAgain() throws Exception {
        // Each page of a project's records read and parsed every record file of it again.
        final java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(
                        Instant.parse("2026-11-20T10:00:00Z"));
        final java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<Map<String, Object>> stored =
                java.util.stream.IntStream.range(0, 5)
                        .<Map<String, Object>>mapToObj(
                                i ->
                                        Map.of(
                                                "task",
                                                "porelo-uc-work-" + i,
                                                "startedAt",
                                                "2026-11-20T09:0" + i + ":00Z",
                                                "prompt",
                                                "x".repeat(1_500_000)))
                        .toList();
        final RecordReadings readings =
                new RecordReadings(
                        now::get,
                        (project, since) -> {
                            reads.incrementAndGet();
                            return stored;
                        },
                        task -> true);
        final Instant since = Instant.parse("2026-11-01T00:00:00Z");
        final int limit = org.fuin.porelo.wire.varlink.VarlinkConnection.MAX_MESSAGE;

        final java.util.List<Object> answered = new java.util.ArrayList<>();
        Map<String, Object> page = readings.page("uc", since, null, limit);
        answered.addAll((java.util.List<?>) page.get("records"));
        assertThat(reads).as("the first page reads the records").hasValue(1);
        final Object first = page.get("next");
        java.util.List<?> second = null;
        int pages = 1;
        while (page.get("next") != null) {
            page =
                    readings.page(
                            "uc", since, RecordPages.from(Map.of("from", page.get("next"))), limit);
            answered.addAll((java.util.List<?>) page.get("records"));
            second = second == null ? (java.util.List<?>) page.get("records") : second;
            assertThat(++pages).as("a page that does not move on").isLessThan(20);
        }
        assertThat(pages).as("more than one page").isGreaterThan(1);
        assertThat(reads).as("a later page reads no record file").hasValue(1);
        assertThat(answered).as("every record once, in order").isEqualTo(stored);

        now.set(now.get().plus(java.time.Duration.ofMinutes(10)).plusSeconds(1));
        final Map<String, Object> again =
                readings.page("uc", since, RecordPages.from(Map.of("from", first)), limit);
        assertThat(reads).as("a reading no longer kept is read again").hasValue(2);
        assertThat(again.get("records")).as("the same page as before").isEqualTo(second);
    }
}
