package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A start at another task's waiting push keeps Porelo's rules for a task reading another task's
 * work as a start at a pull request does: a clearance that lets a blocked connection through is
 * refused before anything is made.
 */
class AnotherTasksPushStartRulesTest {

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context;

    private Path projectFile;

    @BeforeEach
    void machine() throws IOException {
        context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                new XdgPaths(
                                        dir.resolve("config"),
                                        dir.resolve("data"),
                                        dir.resolve("state"),
                                        dir.resolve("run"),
                                        dir),
                                dir.resolve("bin")),
                        args -> 0);
        projectFile = context.paths().projects().defaultProjectFile("demo");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(
                projectFile,
                """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                """);
    }

    @Test
    void aStartAtAnotherTasksWaitingPushWithClearanceAllowIsRefusedBeforeAnythingIsMade() {
        final VarlinkException refused =
                catchThrowableOfType(
                        VarlinkException.class,
                        () ->
                                RunPlaces.taken(
                                        context,
                                        Map.of(
                                                "rights",
                                                java.util.List.of("reply", "openThreads"),
                                                "place",
                                                Map.of("gatePush", Map.of("name", "foo-1")),
                                                "clearance",
                                                "allow"),
                                        projectFile,
                                        "reader",
                                        null));

        assertThat(refused).as("the start").isNotNull();
        assertThat(refused.getErrorName())
                .as("the refusal")
                .isEqualTo(PoreloDaemon.INTERFACE + ".StartRuleBroken");
        assertThat(refused.getParameters())
                .as("the rule and the part that breaks it")
                .isEqualTo(Map.of("rule", "foreignWorkLeadsOut", "detail", "clearance allow"));
        assertThat(Files.exists(dir.resolve("data").resolve("mirrors")))
                .as("whether a mirror was made")
                .isFalse();
        assertThat(Files.exists(context.paths().tasks().containerState("demo_reader")))
                .as("whether the task's state was made")
                .isFalse();
    }
}
