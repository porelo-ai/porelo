package org.fuin.porelo.wire.planning;

import java.util.List;
import java.util.Map;

/**
 * The PlanWorkIssue record in Tasks1 planning; field semantics are defined in its interface part.
 */
public record PlanWorkIssue(
        String issue,
        int rounds,
        int roundsMost,
        boolean atWork,
        boolean needsYou,
        boolean readingStopped,
        List<PlanFinding> findings,
        Reason reason) {
    /** Copies lists so published records cannot change. */
    public PlanWorkIssue {
        findings = List.copyOf(findings);
    }

    /** Closed contract choices. */
    public enum Reason {
        /** None value. */
        NONE,
        /** Round Limit value. */
        ROUND_LIMIT,
        /** Blocked value. */
        BLOCKED,
        /** Failed value. */
        FAILED,
        /** Stopped value. */
        STOPPED
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("issue", issue);
        fields.put("rounds", rounds);
        fields.put("roundsMost", roundsMost);
        fields.put("atWork", atWork);
        fields.put("needsYou", needsYou);
        fields.put("readingStopped", readingStopped);
        fields.put("findings", findings.stream().map(PlanFinding::asMap).toList());
        fields.put("reason", reason.name());
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanWorkIssue of(Map<?, ?> fields) {
        return new PlanWorkIssue(
                Fields.text(fields, "issue"),
                Fields.integer(fields, "rounds"),
                Fields.integer(fields, "roundsMost"),
                Fields.bool(fields, "atWork"),
                Fields.bool(fields, "needsYou"),
                Fields.bool(fields, "readingStopped"),
                Fields.list(fields, "findings", PlanFinding::of),
                Reason.valueOf(Fields.text(fields, "reason")));
    }
}
