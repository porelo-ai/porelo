package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.agent.api.AgentDirectory;
import org.fuin.porelo.agent.api.InstalledAgents;
import org.fuin.porelo.core.project.Egress;
import org.fuin.porelo.core.project.Limits;
import org.fuin.porelo.core.project.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which agent a start on a repository works with: {@code --agent}, the repository's, the machine's.
 */
class AgentChoiceTest {

    private static Repository workedWith(final String agent) {
        return new Repository(
                "backend",
                null,
                "",
                Egress.none(),
                Limits.Declared.none(),
                List.of(),
                null,
                agent,
                null);
    }

    @Test
    void theRepositorysAgentIsTakenWhenNoneIsNamed() {
        assertThat(AgentChoice.wanted(null, workedWith("helper")))
                .as("the project file's agent for the repository, said as its source")
                .isEqualTo(new AgentChoice.Wanted("helper", AgentChoice.Source.REPOSITORY));
    }

    @Test
    void agentWinsOverTheRepositorysAgent() {
        assertThat(AgentChoice.wanted("worker", workedWith("helper")))
                .isEqualTo(new AgentChoice.Wanted("worker", AgentChoice.Source.OPTION));
    }

    @Test
    void withoutEitherTheMachineDecides() {
        assertThat(AgentChoice.wanted(null, new Repository("site", null)))
                .isEqualTo(new AgentChoice.Wanted(null, AgentChoice.Source.MACHINE));
        assertThat(AgentChoice.wanted(null, null))
                .isEqualTo(new AgentChoice.Wanted(null, AgentChoice.Source.MACHINE));
    }

    @Test
    void aRepositorysAgentThatIsNotInstalledRefusesTheStartAndSaysHowToGetIt(
            @TempDir final Path dir) throws Exception {

        // Taking whatever is installed instead would start another agent than the project file
        // names, and its commits would go by the wrong name.
        final Path agents = Files.createDirectories(dir.resolve("agents"));
        try (InstalledAgents installed =
                new InstalledAgents(new AgentDirectory(List.of(agents)), dir.resolve("run"))) {

            final AgentChoice.Choice choice =
                    AgentChoice.considerAgent(installed, null, workedWith("helper"));

            assertThat(choice.refusal()).isEqualTo(AgentChoice.AgentRefusal.UNKNOWN_AGENT);
            assertThat(choice.source()).isEqualTo(AgentChoice.Source.REPOSITORY);
            assertThat(choice.detail())
                    .as("the agent, the repository, what to install and the way around it")
                    .contains("'helper'")
                    .contains("'backend'")
                    .contains("install " + AgentDirectory.PREFIX + "helper")
                    .contains("--agent");
        }
    }
}
