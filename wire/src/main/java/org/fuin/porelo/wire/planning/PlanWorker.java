package org.fuin.porelo.wire.planning;

import java.util.Map;

/** The PlanWorker record in Tasks1 planning; field semantics are defined in its interface part. */
public record PlanWorker(String name, String agent, String model, Role role, String brief) {
    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("name", name);
        fields.put("agent", agent);
        fields.put("model", model);
        fields.put("role", role.name());
        fields.put("brief", brief);
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanWorker of(Map<?, ?> fields) {
        return new PlanWorker(
                Fields.text(fields, "name"),
                Fields.text(fields, "agent"),
                Fields.text(fields, "model"),
                Role.valueOf(Fields.text(fields, "role")),
                Fields.text(fields, "brief"));
    }
}
