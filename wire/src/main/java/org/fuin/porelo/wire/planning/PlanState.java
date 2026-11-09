package org.fuin.porelo.wire.planning;

import java.util.Map;

/** The PlanState record in Tasks1 planning; field semantics are defined in its interface part. */
public record PlanState(String name, Kind kind) {
    /** Closed contract choices. */
    public enum Kind {
        /** Waiting value. */
        WAITING,
        /** Working value. */
        WORKING,
        /** Closed value. */
        CLOSED,
        /** Unknown adapter value; display only, never authorizes work. */
        OTHER
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("name", name);
        fields.put("kind", kind.name());
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanState of(Map<?, ?> fields) {
        return new PlanState(
                Fields.text(fields, "name"), Kind.valueOf(Fields.text(fields, "kind")));
    }
}
