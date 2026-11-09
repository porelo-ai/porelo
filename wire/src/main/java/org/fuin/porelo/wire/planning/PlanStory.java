package org.fuin.porelo.wire.planning;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The PlanStory record in Tasks1 planning; field semantics are defined in its interface part. */
public record PlanStory(
        String id,
        String title,
        String state,
        String link,
        String description,
        List<PlanIssue> issues,
        @Nullable PlanWork work,
        @Nullable String priority) {
    /** Copies lists so published records cannot change. */
    public PlanStory {
        issues = List.copyOf(issues);
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("id", id);
        fields.put("title", title);
        fields.put("state", state);
        fields.put("link", link);
        fields.put("description", description);
        fields.put("issues", issues.stream().map(PlanIssue::asMap).toList());
        if (work != null) {
            fields.put("work", work.asMap());
        }
        if (priority != null) {
            fields.put("priority", priority);
        }
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanStory of(Map<?, ?> fields) {
        return new PlanStory(
                Fields.text(fields, "id"),
                Fields.text(fields, "title"),
                Fields.text(fields, "state"),
                Fields.text(fields, "link"),
                Fields.text(fields, "description"),
                Fields.list(fields, "issues", PlanIssue::of),
                fields.get("work") == null ? null : PlanWork.of(Fields.object(fields, "work")),
                fields.get("priority") == null ? null : Fields.text(fields, "priority"));
    }
}
