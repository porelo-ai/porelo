package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * What TAB is answered with.
 *
 * <p>Driven through the real command tree rather than a fixture: the point of asking the program is
 * that the answers cannot drift from what the program accepts, and a test that built its own tree
 * would be testing the fixture.
 */
class CompleteCommandTest {

    private final CompleteCommand complete = new CompleteCommand();

    private final CommandLine root = PoreloCli.commandLine(PoreloContext.real());

    private List<String> completing(String... words) {
        return complete.candidatesFor(root, List.of(words));
    }

    @Test
    void theTopLevelOffersItsSubcommands() {
        assertThat(completing("porelo", "")).contains("task", "vault", "gate", "project");
    }

    @Test
    void aPartialWordNarrowsIt() {
        assertThat(completing("porelo", "va")).containsExactly("vault");
    }

    @Test
    void anAliasIsNotOfferedBesideTheNameItIsAnAliasFor() {

        // picocli keys aliases into the same subcommand map, so taking the keys would offer
        // 'task' and 'tasks' as if they were two commands to choose between.
        assertThat(completing("porelo", "")).contains("task").doesNotContain("tasks");
        assertThat(completing("porelo", "")).contains("project").doesNotContain("projects");
    }

    @Test
    void aHiddenCommandIsNeverOffered() {

        // __complete is how this works and is not something to type. A completion that offered it
        // would advertise the machinery instead of the product.
        assertThat(completing("porelo", "")).doesNotContain("__complete");
        assertThat(completing("porelo", "__")).isEmpty();
    }

    @Test
    void aSubcommandOffersItsOwnVerbs() {
        assertThat(completing("porelo", "task", "")).contains("start", "stop", "remove", "attach");
        // 'delete' is gone: a project exists because the machine follows its repository, so
        // removing what Porelo built while going on following it would only build it again.
        assertThat(completing("porelo", "project", "")).contains("list", "unfollow");
    }

    @Test
    void aDashOffersTheLongOptionsOfTheCommandItIsIn() {

        // Of that command, not of the root: 'porelo task remove --' must not offer the top-level
        // options, or every list is the same list.
        assertThat(completing("porelo", "task", "remove", "--")).contains("--force", "--rescue");
    }

    @Test
    void shortOptionsAreLeftOut() {

        // Offering '-p' beside '--project-file' doubles the list to say the same thing twice.
        assertThat(completing("porelo", "task", "start", "-")).allMatch(c -> c.startsWith("--"));
    }

    @Test
    void anOptionAlreadyTypedDoesNotMoveUsOutOfTheCommand() {

        // The words between the program and the partial word say which command we are in, and a
        // word that is not a subcommand is an option or an argument rather than a step down.
        assertThat(completing("porelo", "task", "remove", "--force", "--")).contains("--rescue");
    }

    @Test
    void aShellThatHasNoScriptCompletesToNothingRatherThanToEverything() {
        assertThat(completing("porelo", "completion", "")).containsExactly("bash", "zsh");
        assertThat(completing("porelo", "completion", "f")).isEmpty();
    }

    @Test
    void nothingTypedAtAllIsAnswerableRatherThanAFailure() {
        assertThat(complete.candidatesFor(root, List.of())).isEmpty();
        assertThat(complete.candidatesFor(null, List.of("porelo", ""))).isEmpty();
    }
}
