package org.fuin.porelo.app;

import java.util.List;
import org.fuin.porelo.planning.api.IssueSummary;
import org.fuin.porelo.planning.api.PlanState;
import org.fuin.porelo.planning.api.Workflow;

/** Computes story state without assigning meaning to any adapter's state names. */
public final class StoryStates {
    private StoryStates() {}

    /**
     * Returns the first open issue state in workflow order, or first closed state if all closed.
     */
    public static String of(Workflow workflow, String own, List<IssueSummary> issues) {
        if (issues.isEmpty()) {
            return own;
        }
        for (IssueSummary issue : issues) {
            if (workflow.states().stream().noneMatch(state -> state.name().equals(issue.state()))) {
                throw new IllegalArgumentException("An issue names a state outside the workflow");
            }
        }
        final List<PlanState> present =
                workflow.states().stream()
                        .filter(
                                state ->
                                        issues.stream()
                                                .anyMatch(
                                                        issue ->
                                                                issue.state().equals(state.name())))
                        .toList();
        return present.stream()
                .filter(state -> state.kind() != PlanState.Kind.CLOSED)
                .findFirst()
                .orElse(present.getFirst())
                .name();
    }
}
