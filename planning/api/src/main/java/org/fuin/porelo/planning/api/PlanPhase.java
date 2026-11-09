package org.fuin.porelo.planning.api;

import java.util.List;
import java.util.Map;

/** A named phase containing state names in workflow order. */
public record PlanPhase(String name, List<String> states) {
    /** Copies the collections so a published snapshot cannot change. */
    public PlanPhase {
        states = List.copyOf(states);
    }

    /** Returns this record as the published wire object. */
    public Map<String, Object> asMap() {
        return Map.ofEntries(Map.entry("name", name), Map.entry("states", states));
    }

    /** Reads the published wire object, refusing missing or mistyped fields. */
    public static PlanPhase of(Map<?, ?> map) {
        return new PlanPhase(Wire.required(map, "name"), Wire.strings(map, "states"));
    }
}
