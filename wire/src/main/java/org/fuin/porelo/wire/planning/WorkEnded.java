package org.fuin.porelo.wire.planning;

import java.util.Map;

/** The WorkEnded record in Tasks1 planning; field semantics are defined in its interface part. */
public record WorkEnded(Outcome outcome, String why, String answer, String at) {
    /** Closed contract choices. */
    public enum Outcome {
        /** Done value. */
        DONE,
        /** Blocked value. */
        BLOCKED,
        /** Failed value. */
        FAILED
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("outcome", outcome.name());
        fields.put("why", why);
        fields.put("answer", answer);
        fields.put("at", at);
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static WorkEnded of(Map<?, ?> fields) {
        return new WorkEnded(
                Outcome.valueOf(Fields.text(fields, "outcome")),
                Fields.text(fields, "why"),
                Fields.text(fields, "answer"),
                Fields.text(fields, "at"));
    }
}
