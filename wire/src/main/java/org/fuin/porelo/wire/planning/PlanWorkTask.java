package org.fuin.porelo.wire.planning;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The PlanWorkTask record in Tasks1 planning; field semantics are defined in its interface part.
 */
public record PlanWorkTask(
        String task,
        String containerId,
        String repository,
        List<String> issues,
        String state,
        String done,
        Mode mode,
        String agent,
        String model,
        Role role,
        String since,
        boolean atWork,
        @Nullable WorkEnded ended) {
    /** Copies lists so published records cannot change. */
    public PlanWorkTask {
        issues = List.copyOf(issues);
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("task", task);
        fields.put("containerId", containerId);
        fields.put("repository", repository);
        fields.put("issues", issues);
        fields.put("state", state);
        fields.put("done", done);
        fields.put("mode", mode.name());
        fields.put("agent", agent);
        fields.put("model", model);
        fields.put("role", role.name());
        fields.put("since", since);
        fields.put("atWork", atWork);
        if (ended != null) {
            fields.put("ended", ended.asMap());
        }
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanWorkTask of(Map<?, ?> fields) {
        return new PlanWorkTask(
                Fields.text(fields, "task"),
                Fields.text(fields, "containerId"),
                Fields.text(fields, "repository"),
                Fields.strings(fields, "issues"),
                Fields.text(fields, "state"),
                Fields.text(fields, "done"),
                Mode.valueOf(Fields.text(fields, "mode")),
                Fields.text(fields, "agent"),
                Fields.text(fields, "model"),
                Role.valueOf(Fields.text(fields, "role")),
                Fields.text(fields, "since"),
                Fields.bool(fields, "atWork"),
                fields.get("ended") == null ? null : WorkEnded.of(Fields.object(fields, "ended")));
    }
}
