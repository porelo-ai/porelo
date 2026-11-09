package org.fuin.porelo.planning.api;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Exactly one story or issue move within an atomic batch. */
public record StateChange(@Nullable String story, @Nullable String issue, String to) {
    /** Refuses ambiguous, missing or blank identifiers and blank targets. */
    public StateChange {
        if ((story == null) == (issue == null)) {
            throw new IllegalArgumentException("exactly one of story or issue is required");
        }
        if ((story != null && story.isBlank())
                || (issue != null && issue.isBlank())
                || to.isBlank()) {
            throw new IllegalArgumentException("blank state change identifier or target");
        }
    }

    /** Returns this record as the published wire object, omitting the unused identifier. */
    public Map<String, Object> asMap() {
        final Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (story != null) result.put("story", story);
        if (issue != null) result.put("issue", issue);
        result.put("to", to);
        return result;
    }

    /** Reads the published wire object, refusing missing, ambiguous or mistyped fields. */
    public static StateChange of(Map<?, ?> map) {
        return new StateChange(
                map.get("story") == null ? null : Wire.required(map, "story"),
                map.get("issue") == null ? null : Wire.required(map, "issue"),
                Wire.required(map, "to"));
    }
}
