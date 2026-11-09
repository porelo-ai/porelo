package org.fuin.porelo.wire.planning;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The PlanFinding record in Tasks1 planning; field semantics are defined in its interface part. */
public record PlanFinding(
        int number, Severity severity, String text, @Nullable String file, @Nullable Integer line) {
    /** Closed contract choices. */
    public enum Severity {
        /** Blocking value. */
        BLOCKING,
        /** Note value. */
        NOTE
    }

    /** Returns the declared wire fields, omitting absent optional fields. */
    public Map<String, Object> asMap() {
        final var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("number", number);
        fields.put("severity", severity.name());
        fields.put("text", text);
        if (file != null) {
            fields.put("file", file);
        }
        if (line != null) {
            fields.put("line", line);
        }
        return fields;
    }

    /** Reads every required field strictly, preserving absence of optional fields. */
    public static PlanFinding of(Map<?, ?> fields) {
        return new PlanFinding(
                Fields.integer(fields, "number"),
                Severity.valueOf(Fields.text(fields, "severity")),
                Fields.text(fields, "text"),
                fields.get("file") == null ? null : Fields.text(fields, "file"),
                fields.get("line") == null ? null : Fields.integer(fields, "line"));
    }
}
