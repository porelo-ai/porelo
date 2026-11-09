package org.fuin.porelo.wire.planning;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The PlanIssue record in Tasks1 planning; field semantics are defined in its interface part. */
public record PlanIssue(
        String id,
        String repository,
        String title,
        String state,
        String size,
        List<String> blockedBy,
        String link,
        int rounds,
        int roundsMost,
        boolean atWork,
        boolean needsYou,
        boolean readingStopped,
        List<PlanFinding> findings,
        @Nullable String whatMustBeTrue,
        @Nullable List<PlanWorker> subAgents) {
    /** Copies lists so published records cannot change. */
    public PlanIssue {
        blockedBy = List.copyOf(blockedBy);
        findings = List.copyOf(findings);
        if (subAgents != null) {
            subAgents = List.copyOf(subAgents);
        }
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("id", id);
        fields.put("repository", repository);
        fields.put("title", title);
        fields.put("state", state);
        fields.put("size", size);
        fields.put("blockedBy", blockedBy);
        fields.put("link", link);
        fields.put("rounds", rounds);
        fields.put("roundsMost", roundsMost);
        fields.put("atWork", atWork);
        fields.put("needsYou", needsYou);
        fields.put("readingStopped", readingStopped);
        fields.put("findings", findings.stream().map(PlanFinding::asMap).toList());
        if (whatMustBeTrue != null) {
            fields.put("whatMustBeTrue", whatMustBeTrue);
        }
        if (subAgents != null) {
            fields.put("subAgents", subAgents.stream().map(PlanWorker::asMap).toList());
        }
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanIssue of(Map<?, ?> fields) {
        return new PlanIssue(
                Fields.text(fields, "id"),
                Fields.text(fields, "repository"),
                Fields.text(fields, "title"),
                Fields.text(fields, "state"),
                Fields.text(fields, "size"),
                Fields.strings(fields, "blockedBy"),
                Fields.text(fields, "link"),
                Fields.integer(fields, "rounds"),
                Fields.integer(fields, "roundsMost"),
                Fields.bool(fields, "atWork"),
                Fields.bool(fields, "needsYou"),
                Fields.bool(fields, "readingStopped"),
                Fields.list(fields, "findings", PlanFinding::of),
                fields.get("whatMustBeTrue") == null ? null : Fields.text(fields, "whatMustBeTrue"),
                fields.get("subAgents") == null
                        ? null
                        : Fields.list(fields, "subAgents", PlanWorker::of));
    }
}
