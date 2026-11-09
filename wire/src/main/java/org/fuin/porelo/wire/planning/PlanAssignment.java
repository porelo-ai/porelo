package org.fuin.porelo.wire.planning;

import java.util.Map;

/**
 * The PlanAssignment record in Tasks1 planning; field semantics are defined in its interface part.
 */
public record PlanAssignment(
        String id,
        String issue,
        String name,
        String agent,
        String model,
        Kind kind,
        String workingState,
        String brief,
        Choice choice) {
    /** Closed contract choices. */
    public enum Kind {
        /** Builder value. */
        BUILDER,
        /** Tester value. */
        TESTER,
        /** Reader value. */
        READER,
        /** Unknown adapter value; display only, never authorizes work. */
        OTHER
    }

    /** Closed contract choices. */
    public enum Choice {
        /** Keep value. */
        KEEP,
        /** Change value. */
        CHANGE,
        /** Drop value. */
        DROP
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("id", id);
        fields.put("issue", issue);
        fields.put("name", name);
        fields.put("agent", agent);
        fields.put("model", model);
        fields.put("kind", kind.name());
        fields.put("workingState", workingState);
        fields.put("brief", brief);
        fields.put("choice", choice.name());
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanAssignment of(Map<?, ?> fields) {
        return new PlanAssignment(
                Fields.text(fields, "id"),
                Fields.text(fields, "issue"),
                Fields.text(fields, "name"),
                Fields.text(fields, "agent"),
                Fields.text(fields, "model"),
                Kind.valueOf(Fields.text(fields, "kind")),
                Fields.text(fields, "workingState"),
                Fields.text(fields, "brief"),
                Choice.valueOf(Fields.text(fields, "choice")));
    }
}
