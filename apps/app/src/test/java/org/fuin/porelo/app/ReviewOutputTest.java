package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for what a review prints: the agent's words, never its terminal controls. */
class ReviewOutputTest {

    @Test
    void anAlternateBranchCannotRewriteTheApprovalQuestion() {
        final StringWriter err = new StringWriter();
        final List<String> questions = new ArrayList<>();
        GateApproveCommand.instead(
                "taken\u001b[8m\r",
                "a".repeat(40),
                "next\u001b[2K\u009b",
                new Offer(
                        question -> {
                            questions.add(question);
                            return "n";
                        },
                        false,
                        false,
                        new PrintWriter(err)));

        assertThat(questions)
                .singleElement()
                .asString()
                .contains("taken\\x1b[8m\\x0d", "next\\x1b[2K\\x9b")
                .doesNotContain("\u001b", "\r", "\u009b");
        assertThat(err.toString()).doesNotContain("\u001b", "\r", "\u009b");
    }

    @Test
    void aSubjectOrALineOfThePatchCannotRewriteWhatThePersonReads() {

        // Printed raw, a subject or a patch line carrying ESC[1A ESC[2K erased the lines before it
        // - the warning
        // to read first among them - and the person approved what they had not seen.
        final String said =
                GateReviewCommand.shown("line one\n\u001b[1A\u001b[2Kall is well\n\tindented\n");

        assertThat(said)
                .doesNotContain("\u001b")
                .contains("\\x1b[1A")
                .contains("line one\n")
                .contains("\n\tindented");
    }
}
