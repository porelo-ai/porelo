package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The agent each repository of a project is worked with, and the name it goes by. */
class ProjectRepositoryAgentTest {

    private static final String HEAD =
            """
            project:
              name: "p"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    private static Project read(final String repositories) {
        return ProjectReader.read(
                new StringReader(HEAD + "repositories:\n" + repositories), "test");
    }

    @Test
    void readsEachRepositorysAgentAndNicknameAndTheNameItGoesBy() {
        final Project project =
                read(
                        """
                  backend:
                    agent: "worker"
                    nickname: "Ledger"
                  site:
                    agent: "worker"
                  docs:
                    nickname: "Scribe"
                """);

        assertThat(project.repository("backend").agent()).isEqualTo("worker");
        assertThat(project.repository("backend").nickname()).isEqualTo("Ledger");
        assertThat(project.repository("backend").goesBy())
                .as("a nickname is the name its agent goes by")
                .isEqualTo("Agent Ledger");
        assertThat(project.repository("site").goesBy())
                .as("without a nickname the agent goes by the repository's name")
                .isEqualTo("Agent site");
        assertThat(project.repository("docs").agent())
                .as("a nickname names whoever works the repository, with or without an agent")
                .isNull();
        assertThat(project.ownRepository().goesBy())
                .as("the project's own repository goes by the project's name")
                .isEqualTo("Agent p");
    }

    @Test
    void bothKeysAreSettingsAndNoUnknownKey() {

        // An unknown key is reported to the person as one that does nothing, so a file naming an
        // agent would be told its agent is ignored.
        assertThat(
                        ProjectReader.unknownKeys(
                                new StringReader(
                                        HEAD
                                                + "repositories:\n  core:\n    agent: \"worker\"\n"
                                                + "    nickname: \"Ledger\"\n"),
                                "test"))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Two Words",
                "ledger@host",
                "-Ledger",
                "ledger:x",
                "9lives",
                "",
                "Abcdefghijklmnopqrstuvwxyzabcdefg"
            })
    void refusesANicknameThatIsNoSingleWordStartingWithALetter(final String nickname) {

        // It becomes a room's display name and a commit's author: a space, '@' or ':' reads as
        // something else there, and a leading '-' as an option to the transport.
        assertThatThrownBy(() -> read("  core:\n    nickname: \"" + nickname + "\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'repositories.core.nickname'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Worker", "my agent", "-worker", "worker@x", ""})
    void refusesAnAgentThatIsNoAgentsName(final String agent) {
        assertThatThrownBy(() -> read("  core:\n    agent: \"" + agent + "\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'repositories.core.agent'");
    }

    @Test
    void refusesTwoRepositoriesGoingByOneNameWhateverTheCase() {
        assertThatThrownBy(
                        () ->
                                read(
                                        """
                  core:
                    nickname: "Ledger"
                  frontend:
                    nickname: "lEDGER"
                """))
                .isInstanceOf(ProjectException.class)
                .as("the name and both repositories named")
                .hasMessageContaining("'Agent lEDGER'")
                .hasMessageContaining("core")
                .hasMessageContaining("frontend");
    }

    @Test
    void refusesANicknameThatIsAnotherRepositorysName() {

        // That repository goes by its own name when it has no nickname, so two would answer to it.
        assertThatThrownBy(
                        () ->
                                read(
                                        """
                  core:
                    nickname: "Frontend"
                  frontend:
                    agent: "worker"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'Agent frontend'")
                .hasMessageContaining("core");
    }

    @Test
    void refusesANicknameThatIsTheProjectsName() {

        // The project's own repository has no entry here and goes by the project's name.
        assertThatThrownBy(() -> read("  core:\n    nickname: \"P\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'Agent P'")
                .hasMessageContaining("'p'");
    }

    @Test
    void theProjectsOwnRepositoryGoesByTheProjectsNickname() {
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                HEAD.replace(
                                        "  name: \"p\"\n",
                                        "  name: \"p\"\n  nickname: \"Coordinator\"\n")),
                        "test");

        assertThat(project.ownRepository().goesBy()).isEqualTo("Agent Coordinator");
    }

    @Test
    void refusesAProjectNicknameThatIsNoSingleWord() {
        assertThatThrownBy(
                        () ->
                                ProjectReader.read(
                                        new StringReader(
                                                HEAD.replace(
                                                        "  name: \"p\"\n",
                                                        "  name: \"p\"\n  nickname: \"Co ordinator\"\n")),
                                        "test"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'project.nickname'");
    }

    @Test
    void refusesARepositoryGoingByTheProjectsNickname() {
        assertThatThrownBy(
                        () ->
                                ProjectReader.read(
                                        new StringReader(
                                                HEAD.replace(
                                                                "  name: \"p\"\n",
                                                                "  name: \"p\"\n  nickname: \"Core\"\n")
                                                        + "repositories:\n  core:\n    agent: \"worker\"\n"),
                                        "test"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'Agent core'");
    }
}
