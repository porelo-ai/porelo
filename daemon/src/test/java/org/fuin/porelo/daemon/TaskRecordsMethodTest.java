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
                        new TaskWork("ISSUE-1", "Implementing"),
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
                        "method Records(project: string, since: string) -> (records: []TaskRecord)")
                .contains("work: ?(issue: string, phase: string)");
    }
}
