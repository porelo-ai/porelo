package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests what a start takes as the work a task is for. */
class TaskWorkTest {

    @Test
    void theWorkIsSplitAtItsFirstColonAndKeptAsGiven() {
        assertThat(TaskWork.parse("ISSUE-1:Reading: again"))
                .isEqualTo(new TaskWork("ISSUE-1", "Reading: again"));
        assertThat(TaskWork.parse("ISSUE-1:Implementing").refusal()).isNull();
        assertThat(TaskWork.of(Map.of("issue", "ISSUE-1", "phase", "Reworking")))
                .isEqualTo(new TaskWork("ISSUE-1", "Reworking"));
        assertThat(TaskWork.of(null)).as("none given").isNull();
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
}
