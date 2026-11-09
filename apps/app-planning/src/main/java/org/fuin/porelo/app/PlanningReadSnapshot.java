package org.fuin.porelo.app;

import java.util.Map;
import org.fuin.porelo.wire.planning.PlanWork;
import org.fuin.porelo.wire.planning.PlanWorkIssue;
import org.jspecify.annotations.Nullable;

/** One immutable local work snapshot shared by every issue view in a board request. */
public record PlanningReadSnapshot(Map<String, PlanWork> stories) {
    /** Copies the story registry; work records copy their own collections. */
    public PlanningReadSnapshot {
        stories = Map.copyOf(stories);
    }

    /** Returns an empty local registry until the work lifecycle supplies its saved records. */
    public static PlanningReadSnapshot empty() {
        return new PlanningReadSnapshot(Map.of());
    }

    /** Finds the saved result for an issue without inferring anything from its adapter state. */
    public @Nullable PlanWorkIssue issue(String id) {
        return stories.values().stream()
                .flatMap(work -> work.issues().stream())
                .filter(issue -> issue.issue().equals(id))
                .findFirst()
                .orElse(null);
    }
}
