package org.fuin.porelo.wire.planning;

import java.util.List;
import java.util.Map;

/** The PlanPhase record in Tasks1 planning; field semantics are defined in its interface part. */
public record PlanPhase(String name, List<String> states) {
    /** Copies lists so published records cannot change. */
    public PlanPhase {
        states = List.copyOf(states);
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("name", name);
        fields.put("states", states);
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanPhase of(Map<?, ?> fields) {
        return new PlanPhase(Fields.text(fields, "name"), Fields.strings(fields, "states"));
    }
}
