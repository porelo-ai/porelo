package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.agent.api.AgentException;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a repository may reach is answered for the agent a start on it would take. */
class EgressControlAgentTest {

    private PoreloContext context(Path dir) {
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
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void aRepositorysAgentThatIsNotInstalledIsSaidRatherThanLeftOut(@TempDir Path dir)
            throws Exception {

        // Answered without it, the listing showed what another agent, or none, would reach, while
        // the start takes the repository's agent or refuses: an interface planned on the wrong set.
        final Path project = dir.resolve("project.yml");
        Files.writeString(
                project,
                "project:\n  name: \"uc\"\n  security_class: \"guarded\"\n"
                        + "image:\n  base_image: \"ubuntu:24.04\"\n"
                        + "repositories:\n  backend:\n    agent: \"worker\"\n");
        final EgressControl control = new EgressControl(context(dir));

        assertThatThrownBy(() -> control.reachable(project, "backend", null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("'worker'")
                .hasMessageContaining("'backend'");
        assertThatThrownBy(() -> control.refusals(project, "backend", null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("'worker'");
    }
}
