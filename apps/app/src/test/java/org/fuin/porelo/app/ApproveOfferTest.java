package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/**
 * Tests that approving onto a branch that already holds earlier work offers the next free one, and
 * never forces.
 */
class ApproveOfferTest {

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
}
