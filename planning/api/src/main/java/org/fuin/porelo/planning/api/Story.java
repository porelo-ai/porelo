package org.fuin.porelo.planning.api;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A story returned by a planning adapter. */
public record Story(
        String id,
        String title,
        String state,
        String link,
        String description,
        @Nullable String priority) {
    /** A story without a stored priority. */
    public Story(String id, String title, String state, String link, String description) {
        this(id, title, state, link, description, null);
    }

    /** Returns this record as the published wire object. */
    public Map<String, Object> asMap() {
        final Map<String, Object> result =
                new java.util.LinkedHashMap<>(
                        Map.ofEntries(
                                Map.entry("id", id),
                                Map.entry("title", title),
                                Map.entry("state", state),
                                Map.entry("link", link),
                                Map.entry("description", description)));
        if (priority != null) result.put("priority", priority);
        return result;
    }

    /** Reads the published wire object, refusing missing or mistyped fields. */
    public static Story of(Map<?, ?> map) {
        return new Story(
                Wire.required(map, "id"),
                Wire.text(map, "title"),
                Wire.text(map, "state"),
                Wire.text(map, "link"),
                Wire.text(map, "description"),
                map.get("priority") == null ? null : Wire.text(map, "priority"));
    }

    /** Returns the fields used by listings. */
    public StorySummary summary() {
        return new StorySummary(id, title, state, link, null, priority);
    }
}
