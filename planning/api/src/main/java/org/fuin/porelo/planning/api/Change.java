package org.fuin.porelo.planning.api;

import java.util.List;
import java.util.Map;

/** A revision and resources to reread; empty resource lists invalidate the whole board. */
public record Change(String revision, List<String> stories, List<String> issues) {
    /** Copies the collections so a published snapshot cannot change. */
    public Change {
        stories = List.copyOf(stories);
        issues = List.copyOf(issues);
    }

    /** Returns this record as the published wire object. */
    public Map<String, Object> asMap() {
        return Map.ofEntries(
                Map.entry("revision", revision),
                Map.entry("stories", stories),
                Map.entry("issues", issues));
    }

    /** Reads the published wire object, refusing missing or mistyped fields. */
    public static Change of(Map<?, ?> map) {
        return new Change(
                Wire.required(map, "revision"),
                Wire.strings(map, "stories"),
                Wire.strings(map, "issues"));
    }
}
