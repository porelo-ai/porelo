package org.fuin.porelo.wire.planning;

import java.util.List;
import java.util.Map;

/**
 * The PlanRepositoryWork record in Tasks1 planning; field semantics are defined in its interface
 * part.
 */
public record PlanRepositoryWork(
        String repository, List<String> issues, PlanWorker main, List<PlanAssignment> subAgents) {
    /** Copies lists so published records cannot change. */
    public PlanRepositoryWork {
        issues = List.copyOf(issues);
        subAgents = List.copyOf(subAgents);
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("repository", repository);
        fields.put("issues", issues);
        fields.put("main", main.asMap());
        fields.put("subAgents", subAgents.stream().map(PlanAssignment::asMap).toList());
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanRepositoryWork of(Map<?, ?> fields) {
        return new PlanRepositoryWork(
                Fields.text(fields, "repository"),
                Fields.strings(fields, "issues"),
                PlanWorker.of(Fields.object(fields, "main")),
                Fields.list(fields, "subAgents", PlanAssignment::of));
    }
}
