package org.fuin.porelo.wire.planning;

import java.util.Map;

/**
 * The PlanIssueReadingRounds record in Tasks1 planning; field semantics are defined in its
 * interface part.
 */
public record PlanIssueReadingRounds(String issue, int rounds) {
    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("issue", issue);
        fields.put("rounds", rounds);
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanIssueReadingRounds of(Map<?, ?> fields) {
        return new PlanIssueReadingRounds(
                Fields.text(fields, "issue"), Fields.integer(fields, "rounds"));
    }
}
