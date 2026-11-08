package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.TaskUsage;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prevents CLI and daemon usage diverging or changing task listings. */
class TaskUsageMethodTest {
    @Test
    void usageReturnsTheSharedSumsAndTheTaskContractKeepsItsShape(@TempDir Path dir)
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
        final Path file = context.paths().tasks().usageRecord(task);
        Files.createDirectories(file.getParent());
        // A synthetic aborted broker record prevents a null output becoming a free answer.
        Files.writeString(
                file,
                """
                {"time":"2026-01-01T22:30:00Z","agent":"test-worker","model_answered":"model-a","input":7,"cache_read":null,"cache_write":null,"output":null,"reasoning":null,"end":"aborted"}
                """);
        new TaskUsage(context).archive(task);
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final var answer =
                        client.call(PoreloDaemon.INTERFACE + ".Usage", Map.of("task", task));
                assertThat(answer.get("usage"))
                        .as("the daemon reads retained records through the shared service")
                        .isEqualTo(
                                Json.parseExact(
                                        Json.write(new TaskUsage(context).read(task).asMap())));
            }
        }
        assertThat(PoreloDaemon.description())
                .as("Usage is an additive task method")
                .contains("method Usage(task: string) -> (usage: Usage)");
        final var type =
                java.util.regex.Pattern.compile("(?ms)^type Task \\(\\n(.*?)^\\)")
                        .matcher(PoreloDaemon.description());
        assertThat(type.find()).as("the existing Task type is present").isTrue();
        assertThat(type.group(1))
                .as("list and watch never read usage records")
                .doesNotContain("usage:");
    }
}
