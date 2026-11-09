package org.fuin.porelo.planning.api;

import java.util.List;
import java.util.Map;

/** A issue summary returned by a planning adapter. */
public record IssueSummary(
        String id,
        String repository,
        String title,
        String state,
        String size,
        List<String> blockedBy,
        String link) {
    /** Copies the collections so a published snapshot cannot change. */
    public IssueSummary {
        blockedBy = List.copyOf(blockedBy);
    }

    /** Returns this record as the published wire object. */
    public Map<String, Object> asMap() {
        return Map.ofEntries(
                Map.entry("id", id),
                Map.entry("repository", repository),
                Map.entry("title", title),
                Map.entry("state", state),
                Map.entry("size", size),
                Map.entry("blockedBy", blockedBy),
                Map.entry("link", link));
    }

    /** Reads the published wire object, refusing missing or mistyped fields. */
    public static IssueSummary of(Map<?, ?> map) {
        return new IssueSummary(
                Wire.required(map, "id"),
                Wire.required(map, "repository"),
                Wire.text(map, "title"),
                Wire.required(map, "state"),
                Wire.text(map, "size"),
                Wire.strings(map, "blockedBy"),
                Wire.text(map, "link"));
    }
}
