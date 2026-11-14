package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for what {@link TerminalSteps} checks before it touches a machine. */
class TerminalStepsTest {

    @Test
    void aScenarioThatNamesAnImageOfItsOwnIsTheOnlyImageInTheProjectFile() {
        final String more =
                """
                egress:
                  mode: deny
                image: # The scenario chooses the base.
                  from: "ubuntu:24.04@sha256:%s"
                """
                        .formatted("a".repeat(64));

        assertThat(TerminalSteps.projectImage(more))
                .as("the scenario's image must not be refused as a duplicate image key")
                .isEqualTo(more);
    }

    @Test
    void aScenarioWithAnIndentedSnippetKeepsTheDefaultBaseImage() {
        final String more =
                """
                  run: |
                    image: this is snippet content
                """;

        assertThat(TerminalSteps.projectImage(more))
                .as("an indented snippet must remain inside the default image block")
                .isEqualTo("image:\n  from: \"ubuntu:24.04\"\n" + more);
    }

    @Test
    void refusesAProjectNameThatWouldDeleteTheHomeItLivesIn() {
        assertThatThrownBy(() -> TerminalSteps.projectName(".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TerminalSteps.projectName("."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TerminalSteps.projectName("a/../.."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAProjectNameWithADotInIt() {
        assertThat(TerminalSteps.projectName("live.1")).isEqualTo("live.1");
    }

    @Test
    void remembersAProjectForTheWholeRunNotOneScenarioUnderTheAccountThatMadeIt() {
        // Whatever accounts the run was given - a leg gives the kit's own tests its accounts too.
        final boolean named =
                System.getProperty("porelo.acceptance.user") != null
                        || System.getProperty(Accounts.USERS) != null;
        if (!named) {
            System.setProperty("porelo.acceptance.user", "operator");
        }
        try {
            final String account = Accounts.forThisThread();
            World.takeProjects();
            new World().made("restarted");
            new World().made("restarted");

            assertThat(World.takeProjects())
                    .as("a second scenario sharing the project names it once")
                    .containsExactly(java.util.Map.entry(account, java.util.List.of("restarted")));
            assertThat(World.takeProjects()).isEmpty();
        } finally {
            if (!named) {
                System.clearProperty("porelo.acceptance.user");
            }
        }
    }

    @Test
    void refusesADirectoryNoAcceptanceRunMadeAndSaysWhy() {
        assertThat(TerminalSteps.fixtureCheck("live"))
                .isEqualTo(
                        "if [ -e ~/'live' ] && [ ! -e ~/'live'/.git/porelo-acceptance-fixture ]; then echo 'refused: live"
                                + " is in the home and no acceptance run made it - a project of a person is not a fixture';"
                                + " exit 1; fi");
    }

    @Test
    void anEmptyAlternativeIsRefusedRatherThanMatchingAnything() {
        // "a,,b" held an empty text, which every output contains: the step passed whatever was
        // said.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TerminalSteps.alternatives("a,,b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TerminalSteps.alternatives("a, b")).containsExactly("a", "b");
    }

    @Test
    void aScreenGivenBackPassesAndOneLeftTakenFailsSayingWhat() {
        final String drawn = "ready$ porelo task list --watch\033[?1049h\033[?25l\033[1;1Hrows";
        final String reset = "\033[0m\033[?7h\033[?1049l\033[?25h";

        assertThat(TerminalSteps.leftAsFound(drawn + reset + "^C\r\nready$ "))
                .as("given back")
                .isEmpty();
        assertThat(TerminalSteps.leftAsFound(drawn + "^C\r\nready$ "))
                .as("the reset left out")
                .contains("the alternate screen was never left");
        assertThat(TerminalSteps.leftAsFound(drawn + "\033[?1049l^C\r\nready$ "))
                .contains("the cursor was left hidden");
        assertThat(TerminalSteps.leftAsFound("ready$ "))
                .as("nothing that took the screen proves nothing")
                .isPresent();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "porelo task start attended --project waits --repository waits --agent stub|porelo-waits_attended",
                "porelo task start shell --project accept|porelo-accept_shell",
                "porelo task start keys --agent stub --project keys --clearance deny|porelo-keys_keys",
                "porelo task start fenced --agent stub --project armored; echo start-exit-$?|porelo-armored_fenced",
                "cd ~/imaged && porelo task start shaped --project imaged|porelo-imaged_shaped",
                "porelo task start shell -p cpu --agent stub|porelo-cpu_shell",
                "porelo task start shell --project=cpu|porelo-cpu_shell",
                "porelo task start attended --project porelo --agent stub|porelo_attended"
            })
    void aTypedStartNamesTheContainerAFailedScenarioRemoves(String command, String container) {
        assertThat(TerminalSteps.startedContainer(command)).as(command).contains(container);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "porelo task start attended",
                "porelo task stop porelo-waits_attended",
                "porelo task list",
                "echo porelo task start x --project y",
                "porelo task start --repository session --project session --agent stub",
                "porelo task start attended; porelo task list --project waits"
            })
    void aCommandThatStartsNoTaskOfANamedProjectNamesNone(String command) {
        assertThat(TerminalSteps.startedContainer(command)).as(command).isEmpty();
    }
}
