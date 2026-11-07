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
              from: "ubuntu:24.04"
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
                .as("without a nickname the agent goes by the repository's name, capitalized")
                .isEqualTo("Agent Site");
        assertThat(project.repository("docs").agent())
                .as("a nickname names whoever works the repository, with or without an agent")
                .isNull();
        assertThat(project.ownRepository().goesBy())
                .as("the project's own repository goes by the project's name, capitalized")
                .isEqualTo("Agent P");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ledger@host",
                "-Ledger",
                "ledger:x",
                "Sea,Lion",
                "Sea\\tLion",
                "Sea  Lion",
                " Sea",
                "Sea ",
                "Sea -Lion",
                "S\u00e9a",
                "Ledger\u0441",
                "9lives",
                "",
                "Abcdefghijklmnopqrstuvwxyzabcdefg"
            })
    void refusesANicknameOutsideTheRule(final String nickname) {

        // It becomes a room's display name and a commit's author: ':' or ',' ends a list of
        // recipients, a look-alike letter passes for another agent, and a leading '-' reads as an
        // option.
        assertThatThrownBy(() -> read("  core:\n    nickname: \"" + nickname + "\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'repositories.core.nickname'")
                .hasMessageContaining("a colon, a comma");
    }

    @Test
    void takesANicknameOfSeveralWordsAsWritten() {
        final Project project = read("  core:\n    nickname: \"Sea Lion 2\"\n");

        assertThat(project.repository("core").goesBy())
                .as("a nickname of several words, separated by single spaces")
                .isEqualTo("Agent Sea Lion 2");
    }

    @Test
    void takesADefaultNameAsItIsWithItsFirstLetterCapitalized() {
        final Project project =
                read("  web.app:\n    agent: \"worker\"\n  3d:\n    agent: \"worker\"\n");

        assertThat(project.repository("web.app").goesBy())
                .as("a dot a nickname may not hold is kept in a default name")
                .isEqualTo("Agent Web.app");
        assertThat(project.repository("3d").goesBy())
                .as("a digit first stays as it is")
                .isEqualTo("Agent 3d");
    }

    @Test
    void refusesANameThatAnothersStartsWithBeforeASpace() {

        // A reader matching a whole phrase from the left would take "Agent Sea" for "Agent Sea
        // Lion".
        assertThatThrownBy(
                        () ->
                                read(
                                        "  core:\n    nickname: \"Sea\"\n  frontend:\n    nickname: \"sea Lion\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'Agent sea Lion'")
                .hasMessageContaining("'Agent Sea'")
                .hasMessageContaining("core")
                .hasMessageContaining("frontend");
    }

    @Test
    void refusesANameThatAnothersStartsWithWhenTheLongerComesFirst() {
        assertThatThrownBy(
                        () ->
                                read(
                                        "  core:\n    nickname: \"Sea Lion\"\n  frontend:\n    nickname: \"Sea\"\n"))
                .isInstanceOf(ProjectException.class)
                .as("the order in the file does not decide which of the two is refused")
                .hasMessageContaining("'Agent Sea'")
                .hasMessageContaining("'Agent Sea Lion'");
    }

    @Test
    void takesANicknameOfExactly32CharactersAndRefusesOneLonger() {
        final String longest = "Sea Lion Abcdefghijklmnopqrstuvw";

        assertThat(read("  core:\n    nickname: \"" + longest + "\"\n").repository("core").goesBy())
                .as("32 characters, spaces counted")
                .isEqualTo("Agent " + longest);
        assertThatThrownBy(() -> read("  core:\n    nickname: \"" + longest + "x\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'repositories.core.nickname'");
    }

    @Test
    void takesANameThatAnothersStartsWithWithoutASpace() {
        final Project project =
                read("  core:\n    nickname: \"Seal\"\n  frontend:\n    nickname: \"Sea\"\n");

        assertThat(project.repository("core").goesBy())
                .as("'Agent Seal' is no phrase 'Agent Sea' begins")
                .isEqualTo("Agent Seal");
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
                .hasMessageContaining("'Agent Frontend'")
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
    void refusesAProjectNicknameOutsideTheRule() {
        assertThatThrownBy(
                        () ->
                                ProjectReader.read(
                                        new StringReader(
                                                HEAD.replace(
                                                        "  name: \"p\"\n",
                                                        "  name: \"p\"\n  nickname: \"Co  ordinator\"\n")),
                                        "test"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'project.nickname'")
                .hasMessageContaining("a colon, a comma");
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
                .hasMessageContaining("'Agent Core'");
    }
}
