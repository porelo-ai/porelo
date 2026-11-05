package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.runtime.ImageSettings;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a task's image was built with, asked over the socket as an interface asks it. */
class ImageSettingsMethodTest {

    private static final String TASK = "porelo-uc-shell";

    private static final String METHOD = PoreloDaemon.INTERFACE + ".ImageSettings";

    private static final ImageSettings BUILT =
            new ImageSettings(
                    "ubuntu:24.04",
                    "project.yml",
                    List.of("maven"),
                    List.of("RUN apt-get install -y maven"),
                    "project.yml",
                    List.of("git"),
                    "an-agent",
                    "1.2.3",
                    "guarded",
                    "project.yml");

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context(final Path dir) throws Exception {
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
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        Files.createDirectories(context.paths().tasks().containerState(TASK));
        return context;
    }

    private Map<String, Object> ask(final Path dir, final String task) throws Exception {
        try (VarlinkServer server =
                PoreloDaemon.serving(context(dir), dir.resolve("porelod.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                return client.call(METHOD, Map.of("task", task));
            }
        }
    }

    private void aTaskOfTheImage(final String id) {
        runner.answering("ps", TASK + "\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n");
        runner.answering("{{.ImageName}}", "porelo/uc:latest\t" + id + "\n");
    }

    @Test
    void answersWhatTheImageRecordedWithWhereEachCameFrom(@TempDir final Path dir)
            throws Exception {
        aTaskOfTheImage("imageid1");
        runner.answering("Created", "2026-11-05T08:22:05Z\n");
        runner.answering("org.fuin.porelo.settings", BUILT.json() + "\n");

        final Map<String, Object> answer = ask(dir, TASK);

        assertThat(answer.get("known")).isEqualTo(true);
        assertThat(answer.get("baseImage"))
                .isEqualTo(Map.of("value", "ubuntu:24.04", "source", "project.yml"));
        assertThat(answer.get("packages"))
                .isEqualTo(List.of(Map.of("value", "maven", "source", "project.yml")));
        assertThat(answer.get("tools"))
                .isEqualTo(List.of(Map.of("value", "git", "source", "porelo")));
        assertThat(answer.get("lines"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "value",
                                        "RUN apt-get install -y maven",
                                        "source",
                                        "project.yml")));
        assertThat(answer.get("agent"))
                .isEqualTo(Map.of("value", "an-agent", "source", "agent package"));
        assertThat(answer.get("agentVersion")).isEqualTo("1.2.3");
        assertThat(answer.get("securityClass"))
                .isEqualTo(Map.of("value", "guarded", "source", "project.yml"));
        assertThat(answer.get("recorded")).isEqualTo("2026-11-05T08:22:05Z");
    }

    @Test
    void answersForTheImageTheContainerWasMadeFromNotWhatTheTagNamesNow(@TempDir final Path dir)
            throws Exception {
        // The project's image was built anew since this task started: the tag names another, and
        // what the settings say now is not what this task has.
        aTaskOfTheImage("imageid1");
        runner.answering("Created", "2026-11-05T08:22:05Z\n");
        runner.answering("org.fuin.porelo.settings\"}} imageid1", BUILT.json() + "\n");
        runner.answering("org.fuin.porelo.settings\"}} porelo/uc", "{\"version\":1}\n");

        assertThat(ask(dir, TASK).get("known"))
                .as("what podman was asked: %s", runner.lines())
                .isEqualTo(true);
    }

    @Test
    void anImageBuiltBeforeThisSaysItDoesNotKnow(@TempDir final Path dir) throws Exception {
        aTaskOfTheImage("imageid1");
        runner.answering("Created", "2026-11-05T08:22:05Z\n");
        runner.answering("org.fuin.porelo.settings", "\n");

        final Map<String, Object> answer = ask(dir, TASK);

        assertThat(answer.get("known")).isEqualTo(false);
        assertThat(answer.get("baseImage")).isEqualTo(Map.of("value", "", "source", ""));
        assertThat(answer.get("packages")).isEqualTo(List.of());
        assertThat(answer.get("securityClass")).isEqualTo(Map.of("value", "", "source", ""));
        assertThat(answer.get("recorded")).as("a guess of nothing").isEqualTo("");
    }

    @Test
    void aContainerWhoseImageIsGoneSaysItDoesNotKnow(@TempDir final Path dir) throws Exception {
        aTaskOfTheImage("imageid1");
        runner.failing("image inspect", 125, "no such image");

        assertThat(ask(dir, TASK).get("known")).isEqualTo(false);
    }

    @Test
    void aNameThatIsNoTaskHereIsRefused(@TempDir final Path dir) throws Exception {
        // Somebody else's container is not asked anything, and an absent one is no "unknown".
        runner.answering("ps", TASK + "\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n");

        assertThatThrownBy(() -> ask(dir, "somebody-elses-database"))
                .hasMessageContaining("NoSuchTask");
        assertThatThrownBy(() -> ask(dir, "porelo-uc-gone")).hasMessageContaining("NoSuchTask");
    }
}
