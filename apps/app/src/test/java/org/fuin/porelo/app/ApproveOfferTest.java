package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.fuin.porelo.gate.GitGate;
import org.junit.jupiter.api.Test;

/**
 * Tests that approving onto a branch that already holds earlier work offers the next free one, and
 * never forces.
 */
class ApproveOfferTest {

    private static final GitGate.Waiting WAITING =
            new GitGate.Waiting("a".repeat(40), "reviewed work");

    private final StringWriter said = new StringWriter();

    private Offer answering(final String answer) {
        return new Offer(question -> answer, false, false, new PrintWriter(said, true));
    }

    @Test
    void theNextFreeBranchIsOfferedAndTakenOnAYes() {
        assertThat(
                        GateApproveCommand.instead(
                                "porelo/app", "abc1234", "porelo/app-2", answering("")))
                .isEqualTo("porelo/app-2");
        assertThat(
                        GateApproveCommand.instead(
                                "porelo/app", "abc1234", "porelo/app-2", answering("n")))
                .isNull();
    }

    @Test
    void nobodyToAskIsTheRefusalAsBefore() {
        assertThat(
                        GateApproveCommand.instead(
                                "porelo/app", "abc1234", "porelo/app-2", Offer.NOBODY))
                .isNull();
    }

    @Test
    void theBoundCommandKeepsTheDestinationAndSigningChoice() {
        final String command =
                new GateApprovalHint(
                                "project",
                                "repo",
                                "writer",
                                WAITING.commit(),
                                "/work/person's checkout",
                                "review/selected",
                                true)
                        .command();

        assertThat(command)
                .as("following the remedy cannot change target or lose signing")
                .contains(
                        "--project project",
                        "--repository repo",
                        "--commit " + WAITING.commit(),
                        "--upstream '/work/person'\"'\"'s checkout'",
                        "--branch review/selected",
                        "--signed",
                        "-- writer");
    }
}
