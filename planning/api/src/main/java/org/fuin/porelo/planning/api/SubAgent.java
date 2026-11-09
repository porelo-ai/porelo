package org.fuin.porelo.planning.api;

import java.util.Map;

/** A proposed sub-agent with its complete Markdown brief, never a host path. */
public record SubAgent(String name, Kind kind, String agent, String model, String brief) {
    /** The understood set of proposed sub-agent roles. */
    public enum Kind {
        /** Builds part of the issue. */
        BUILDER,
        /** Tests the issue's behavior. */
        TESTER,
        /** Reads the completed work. */
        READER,
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
        return Map.of(
                "name", name,
                "kind", kind.name(),
                "agent", agent,
                "model", model,
                "brief", brief);
    }

    /**
     * Reads the published wire object, refusing missing or mistyped fields; newer kinds read as
     * OTHER.
     */
    public static SubAgent of(Map<?, ?> map) {
        return new SubAgent(
                Wire.required(map, "name"),
                Kind.of(Wire.text(map, "kind")),
                Wire.required(map, "agent"),
                Wire.required(map, "model"),
                Wire.text(map, "brief"));
    }
}
