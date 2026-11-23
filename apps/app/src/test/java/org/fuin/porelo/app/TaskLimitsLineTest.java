package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.runtime.ImageLayers;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks that a task and its hooks are handed the factors of the Porelo that starts it. */
class TaskLimitsLineTest {

    private static final String DOUBLED = "services=1.0,machines=2.0,polling=1.0,retries=1.0";

    private static final String PLAIN = "services=1.0,machines=1.0,polling=1.0,retries=1.0";

    @TempDir Path dir;

    private final FakeCommandRunner commands =
            new FakeCommandRunner().answering(" create ", "0123456789abcdef");

    private Path start(final LimitFactors factors) throws IOException {
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                """
                project:
                  name: local
                  security_class: guarded
                image:
                  from: ubuntu:24.04
                repositories:
                  backend:
                """),
                        "local.yml");
        final PoreloPaths paths =
                new PoreloPaths(
                        XdgPaths.of(
                                name ->
                                        switch (name) {
                                            case "XDG_CONFIG_HOME" ->
                                                    dir.resolve("config").toString();
                                            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                            case "XDG_STATE_HOME" ->
                                                    dir.resolve("state").toString();
                                            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                            default -> null;
                                        },
                                dir),
                        dir.resolve("bin"));
        new TaskRunner(commands, paths, name -> null, () -> factors)
                .start(
                        project,
                        project.repository("backend"),
                        "porelo-local-limits",
                        ImageLayers.none(),
                        Map.of(),
                        List.of(),
                        new TaskWiring("192.0.2.5", 9418, null),
                        new PrintWriter(new StringWriter(), true));
        return paths.tasks().containerState("porelo-local-limits");
    }

    @Test
    void aTaskIsGivenTheLineByNameAndByValueFromOneObject() throws IOException {
        start(LimitFactors.parse("machines=2"));

        final Command create = commands.only(" create ");
        final List<String> arguments = create.arguments();
        final int named = arguments.indexOf(LimitFactors.ENVIRONMENT);
        assertThat(named).as("the create names the variable").isPositive();
        assertThat(arguments.get(named - 1))
                .as("by name alone, its value never on the command line")
                .isEqualTo("--env");
        assertThat(String.join(" ", arguments)).doesNotContain(DOUBLED);
        assertThat(create.environment())
                .as("the value travels in podman's environment")
                .containsEntry(LimitFactors.ENVIRONMENT, DOUBLED);
    }

    @Test
    void aTaskAtTheDefaultsIsStillGivenTheLineWhole() throws IOException {
        start(LimitFactors.DEFAULT);

        assertThat(commands.only(" create ").environment())
                .containsEntry(LimitFactors.ENVIRONMENT, PLAIN);
    }

    @Test
    void theSidecarCarriesTheLineForTheHooks() throws IOException {
        final Path state = start(LimitFactors.parse("machines=2"));

        assertThat(Files.readString(state.resolve("sidecar.json"), StandardCharsets.UTF_8))
                .contains("\"limits\":\"" + DOUBLED + "\"");
    }
}
