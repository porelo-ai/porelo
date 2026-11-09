package org.fuin.porelo.wire.planning;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The PlanWork record in Tasks1 planning; field semantics are defined in its interface part. */
public record PlanWork(
        List<PlanWorkTask> tasks,
        @Nullable String coordinator,
        String since,
        boolean atWork,
        List<PlanRepositoryWork> repositories,
        String comment,
        int readingRounds,
        List<PlanIssueReadingRounds> issueReadingRounds,
        List<PlanWorkIssue> issues) {
    /** Copies lists so published records cannot change. */
    public PlanWork {
        tasks = List.copyOf(tasks);
        repositories = List.copyOf(repositories);
        issueReadingRounds = List.copyOf(issueReadingRounds);
        issues = List.copyOf(issues);
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("tasks", tasks.stream().map(PlanWorkTask::asMap).toList());
        if (coordinator != null) {
            fields.put("coordinator", coordinator);
        }
        fields.put("since", since);
        fields.put("atWork", atWork);
        fields.put("repositories", repositories.stream().map(PlanRepositoryWork::asMap).toList());
        fields.put("comment", comment);
        fields.put("readingRounds", readingRounds);
        fields.put(
                "issueReadingRounds",
                issueReadingRounds.stream().map(PlanIssueReadingRounds::asMap).toList());
        fields.put("issues", issues.stream().map(PlanWorkIssue::asMap).toList());
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanWork of(Map<?, ?> fields) {
        return new PlanWork(
                Fields.list(fields, "tasks", PlanWorkTask::of),
                fields.get("coordinator") == null ? null : Fields.text(fields, "coordinator"),
                Fields.text(fields, "since"),
                Fields.bool(fields, "atWork"),
                Fields.list(fields, "repositories", PlanRepositoryWork::of),
                Fields.text(fields, "comment"),
                Fields.integer(fields, "readingRounds"),
                Fields.list(fields, "issueReadingRounds", PlanIssueReadingRounds::of),
                Fields.list(fields, "issues", PlanWorkIssue::of));
    }
}
