package org.fuin.porelo.planning.api;

import java.util.Map;

/** A named state in board order, classified independently of its spelling. */
public record PlanState(String name, Kind kind) {
    /** State categories understood by Porelo, with a fallback for future categories. */
    public enum Kind {
        /** No work is running. */
        WAITING,
        /** Work is running. */
        WORKING,
        /** Work has ended. */
        CLOSED,
        /** A category added by a newer adapter; never starts work automatically. */
        OTHER;

        static Kind of(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ex) {
                return OTHER;
            }
        }
    }

    /** Returns this record as the published wire object. */
    public Map<String, Object> asMap() {
        return Map.ofEntries(Map.entry("name", name), Map.entry("kind", kind.name()));
    }

    /** Reads the published wire object, refusing missing or mistyped fields. */
    public static PlanState of(Map<?, ?> map) {
        return new PlanState(Wire.required(map, "name"), Kind.of(Wire.text(map, "kind")));
    }
}
