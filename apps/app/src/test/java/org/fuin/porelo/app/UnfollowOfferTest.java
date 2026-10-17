package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests that unfollowing a project offers to stop its running tasks, and to destroy unreviewed work
 * only on an explicit yes, instead of naming what to do and ending.
 */
class UnfollowOfferTest {

    private final StringWriter said = new StringWriter();

    private Offer answering(final String answer) {
        return new Offer(question -> answer, false, false, new PrintWriter(said, true));
    }

    @Test
    void runningTasksAreOfferedToBeStoppedWithYesAsTheDefault() {
        assertThat(
                        ProjectUnfollowCommand.nextTry(
                                ProjectDeletion.Outcome.TASKS_RUNNING,
                                List.of("porelo-uc-a"),
                                answering("")))
                .isEqualTo("stop");
    }

    @Test
    void unreviewedWorkIsDestroyedOnlyOnAnExplicitYesAndSaysWhatGoes() {
        assertThat(
                        ProjectUnfollowCommand.nextTry(
                                ProjectDeletion.Outcome.HOLDS_WORK,
                                List.of("refs/heads/porelo/uc-a"),
                                answering("")))
                .isNull();
        assertThat(
                        ProjectUnfollowCommand.nextTry(
                                ProjectDeletion.Outcome.HOLDS_WORK,
                                List.of("refs/heads/porelo/uc-a"),
                                answering("y")))
                .isEqualTo("force");
        assertThat(said.toString()).contains("refs/heads/porelo/uc-a");
    }

    @Test
    void nobodyToAskIsTheRefusalAsBefore() {
        assertThat(
                        ProjectUnfollowCommand.nextTry(
                                ProjectDeletion.Outcome.TASKS_RUNNING,
                                List.of("porelo-uc-a"),
                                Offer.NOBODY))
                .isNull();
        assertThat(
                        ProjectUnfollowCommand.nextTry(
                                ProjectDeletion.Outcome.HOLDS_WORK, List.of("x"), Offer.NOBODY))
                .isNull();
    }
}
