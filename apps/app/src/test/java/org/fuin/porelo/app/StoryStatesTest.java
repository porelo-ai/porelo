package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.fuin.porelo.planning.api.IssueSummary;
import org.fuin.porelo.planning.api.PlanPhase;
import org.fuin.porelo.planning.api.PlanState;
import org.fuin.porelo.planning.api.Workflow;
import org.junit.jupiter.api.Test;

/** A wrong aggregate can offer the wrong action for a story's issues. */
class StoryStatesTest {
    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of(
                            new PlanState("Queue", PlanState.Kind.WAITING),
                            new PlanState("Build", PlanState.Kind.WORKING),
                            new PlanState("Review", PlanState.Kind.WAITING),
                            new PlanState("Finished", PlanState.Kind.CLOSED),
                            new PlanState("Rejected", PlanState.Kind.CLOSED)),
                    List.of(
                            new PlanPhase(
                                    "All",
                                    List.of("Queue", "Build", "Review", "Finished", "Rejected"))));

    private static IssueSummary issue(String state) {
        return new IssueSummary(
                "item", "repo", "Title", state, "S", List.of(), "https://example.org/item");
    }

    @Test
    void choosesTheFirstOpenStateRegardlessOfIssueOrderAndClosedItems() {
        assertThat(
                        StoryStates.of(
                                WORKFLOW,
                                "Queue",
                                List.of(issue("Rejected"), issue("Review"), issue("Build"))))
                .as("an earlier working issue determines the story state")
                .isEqualTo("Build");
    }

    @Test
    void allClosedUsesWorkflowOrderAndAllRejectedRemainsRejected() {
        assertThat(StoryStates.of(WORKFLOW, "Queue", List.of(issue("Rejected"), issue("Finished"))))
                .as("closed states use workflow order too")
                .isEqualTo("Finished");
        assertThat(StoryStates.of(WORKFLOW, "Queue", List.of(issue("Rejected"), issue("Rejected"))))
                .as("a rejected story is never invented as finished")
                .isEqualTo("Rejected");
    }

    @Test
    void aClosedStateEarlierInBoardOrderNeverHidesAnOpenIssue() {
        final Workflow closedFirst =
                new Workflow(
                        List.of(
                                new PlanState("Rejected", PlanState.Kind.CLOSED),
                                new PlanState("Queue", PlanState.Kind.WAITING),
                                new PlanState("Review", PlanState.Kind.WAITING),
                                new PlanState("Finished", PlanState.Kind.CLOSED)),
                        List.of(
                                new PlanPhase(
                                        "All",
                                        List.of("Rejected", "Queue", "Review", "Finished"))));

        assertThat(
                        StoryStates.of(
                                closedFirst, "Queue", List.of(issue("Rejected"), issue("Review"))))
                .as("an open issue decides even when a closed state comes first on the board")
                .isEqualTo("Review");
        assertThat(
                        StoryStates.of(
                                closedFirst,
                                "Queue",
                                List.of(issue("Finished"), issue("Rejected"))))
                .as("with all closed, board order decides among the closed states")
                .isEqualTo("Rejected");
    }

    @Test
    void noIssuesPreservesTheStoredStoryStateAndAnUnknownIssueStateIsRefused() {
        assertThat(StoryStates.of(WORKFLOW, "Queue", List.of()))
                .as("issue-less story keeps its own state")
                .isEqualTo("Queue");
        assertThatThrownBy(() -> StoryStates.of(WORKFLOW, "Queue", List.of(issue("Unknown"))))
                .as("an unknown state cannot become an apparently valid aggregate")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
