package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests what a start takes as the work a task is for. */
class TaskWorkTest {

    @Test
    void theWorkIsSplitAtItsFirstColonAndKeptAsGiven() {
        assertThat(TaskWork.parse("ISSUE-1:Reading: again"))
                .isEqualTo(new TaskWork("ISSUE-1", "Reading: again"));
        assertThat(TaskWork.parse("ISSUE-1:Implementing").refusal()).isNull();
    }

    @Test
    void aListOfWorkIsReadInItsOrderAndAnOlderOnePairAsAListOfOne() {
        final Map<String, String> first = Map.of("issue", "ISSUE-1", "phase", "Reworking");
        final Map<String, String> second = Map.of("issue", "ISSUE-2", "phase", "Reviewing");

        assertThat(TaskWorkList.of(java.util.List.of(first, second)))
                .as("both pairs, in order")
                .isEqualTo(
                        new TaskWorkList(
                                java.util.List.of(
                                        new TaskWork("ISSUE-1", "Reworking"),
                                        new TaskWork("ISSUE-2", "Reviewing"))));
        assertThat(TaskWorkList.of(first))
                .as("one pair as an older client sends it")
                .isEqualTo(
                        new TaskWorkList(java.util.List.of(new TaskWork("ISSUE-1", "Reworking"))));
        assertThat(TaskWorkList.of(null)).as("none given").isNull();
        assertThat(TaskWorkList.parse(java.util.List.of("ISSUE-1:Reworking", "ISSUE-2:Reviewing")))
                .as("--work given twice")
                .isEqualTo(TaskWorkList.of(java.util.List.of(first, second)));
        assertThat(
                        java.util.Objects.requireNonNull(
                                        TaskWorkList.of(java.util.List.of(first, second)))
                                .first())
                .as("the first pair, the one the gate judges by")
                .isEqualTo(new TaskWork("ISSUE-1", "Reworking"));
    }

    @Test
    void aListThatIsNoListOfIssueAndPhaseIsRefusedNeverTakenAsNoWork() {
        for (final Object given :
                java.util.List.of(
                        "ISSUE-1:Reworking",
                        java.util.List.of("ISSUE-1:Reworking"),
                        Map.of("issue", 1L, "phase", "Reworking"),
                        Map.of("issue", "ISSUE-1"))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> TaskWorkList.of(given))
                    .as("%s", given)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anEmptyListAnIssueTwiceTooManyPairsOrABadPairIsRefused() {
        assertThat(new TaskWorkList(java.util.List.of()).refusal())
                .as("an empty list")
                .contains("no issue");
        assertThat(
                        TaskWorkList.parse(
                                        java.util.List.of("ISSUE-1:Reworking", "ISSUE-1:Reviewing"))
                                .refusal())
                .as("one issue twice, named")
                .contains("ISSUE-1")
                .contains("twice");
        final java.util.List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i <= TaskWorkList.MOST; i++) {
            many.add("ISSUE-" + i + ":Reworking");
        }
        assertThat(TaskWorkList.parse(many).refusal()).as("one pair too many").contains("at most");
        assertThat(TaskWorkList.parse(java.util.List.of("ISSUE-1:Reworking", "ISSUE-2")).refusal())
                .as("a pair without its phase")
                .contains("phase");
        assertThat(
                        TaskWorkList.parse(
                                        java.util.List.of("ISSUE-1:Reworking", "ISSUE-2:Reviewing"))
                                .refusal())
                .as("two issues, each once")
                .isNull();
    }

    @Test
    void aWorkWithoutAPhaseOrOfMoreThanOneLineOrTooLongIsRefused() {
        assertThat(TaskWork.parse("ISSUE-1").refusal()).as("no phase").contains("phase");
        assertThat(TaskWork.parse(":Implementing").refusal()).as("no issue").contains("issue");
        assertThat(new TaskWork("ISSUE-1", "Imple\nmenting").refusal())
                .as("two lines")
                .contains("one line");
        assertThat(new TaskWork("ISSUE-1", "x".repeat(65)).refusal())
                .as("65 characters")
                .contains("at most 64");
        assertThat(new TaskWork("ISSUE-1", "x".repeat(64)).refusal()).as("64 characters").isNull();
    }

    @Test
    void anIssueIsATrackersReferenceOfAtMost200Characters() {
        assertThat(new TaskWork("porelo-ai/porelo#123", "Implementing").refusal()) // not-a-citation
                .as("a tracker's reference")
                .isNull();
        assertThat(new TaskWork("o/" + "r".repeat(195) + "#12", "Implementing").refusal())
                .as("200 characters")
                .isNull();
        assertThat(new TaskWork("o/" + "r".repeat(196) + "#12", "Implementing").refusal())
                .as("201 characters")
                .contains("at most 200");
    }

    // The issue stands as it is in a commit's trailer, the guide's code span and the gate's option:
    // a space or a colon would end the trailer's value where the agent cannot see it, a backtick
    // the code span, and a leading dash would read as an option.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "owner/repo #1",
                "owner/repo\u00a0#1",
                "owner/repo#1:x",
                "owner/`repo`#1",
                "-owner/repo#1",
                "owner/repo#1\tx",
                "owner/rëpo#1"
            })
    void anIssueThatWouldChangeWhatItSaysIsRefused(final String issue) {
        assertThat(new TaskWork(issue.translateEscapes(), "Implementing").refusal())
                .as("refused: " + issue)
                .contains("printable ASCII");
    }
}
