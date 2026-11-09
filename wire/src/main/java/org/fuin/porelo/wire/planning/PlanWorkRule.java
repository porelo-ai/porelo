package org.fuin.porelo.wire.planning;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The PlanWorkRule record in Tasks1 planning; field semantics are defined in its interface part.
 */
public record PlanWorkRule(
        String state,
        List<String> from,
        String done,
        @Nullable String then,
        @Nullable String blocked,
        Per per,
        List<Mode> modes,
        String order) {
    /** Copies lists so published records cannot change. */
    public PlanWorkRule {
        from = List.copyOf(from);
        modes = List.copyOf(modes);
    }

    /** Closed contract choices. */
    public enum Per {
        /** Story value. */
        STORY,
        /** Repository value. */
        REPOSITORY
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("state", state);
        fields.put("from", from);
        fields.put("done", done);
        if (then != null) {
            fields.put("then", then);
        }
        if (blocked != null) {
            fields.put("blocked", blocked);
        }
        fields.put("per", per.name());
        fields.put("modes", modes.stream().map(Enum::name).toList());
        fields.put("order", order);
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanWorkRule of(Map<?, ?> fields) {
        return new PlanWorkRule(
                Fields.text(fields, "state"),
                Fields.strings(fields, "from"),
                Fields.text(fields, "done"),
                fields.get("then") == null ? null : Fields.text(fields, "then"),
                fields.get("blocked") == null ? null : Fields.text(fields, "blocked"),
                Per.valueOf(Fields.text(fields, "per")),
                Fields.strings(fields, "modes").stream().map(Mode::valueOf).toList(),
                Fields.text(fields, "order"));
    }
}
