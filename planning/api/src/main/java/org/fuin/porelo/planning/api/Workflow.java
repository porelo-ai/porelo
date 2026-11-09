package org.fuin.porelo.planning.api;

import java.util.List;
import java.util.Map;

/** The single ordered workflow shared by stories and issues. */
public record Workflow(List<PlanState> states, List<PlanPhase> phases) {
    /** Copies the collections so a published snapshot cannot change. */
    public Workflow {
        states = List.copyOf(states);
        phases = List.copyOf(phases);
    }

    /** Returns this record as the published wire object. */
    public Map<String, Object> asMap() {
        return Map.ofEntries(
                Map.entry("states", states.stream().map(PlanState::asMap).toList()),
                Map.entry("phases", phases.stream().map(PlanPhase::asMap).toList()));
    }

    /** Reads the published wire object, refusing missing or mistyped fields. */
    public static Workflow of(Map<?, ?> map) {
        return new Workflow(
                Wire.list(map, "states", PlanState::of), Wire.list(map, "phases", PlanPhase::of));
    }
}
