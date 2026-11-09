package org.fuin.porelo.planning.api;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A story summary returned by a planning adapter. */
public record StorySummary(
        String id,
        String title,
        String state,
        String link,
        @Nullable List<IssueSummary> issues,
        @Nullable String priority) {
    /**
     * Copies issue summaries at the same revision; null means an older reply without a snapshot.
     */
    public StorySummary {
        if (issues != null) issues = List.copyOf(issues);
    }

    /** A summary from an adapter that does not yet supply a board snapshot. */
    public StorySummary(String id, String title, String state, String link) {
        this(id, title, state, link, null, null);
    }

    /** A summary without a stored priority. */
    public StorySummary(
            String id,
            String title,
            String state,
            String link,
            @Nullable List<IssueSummary> issues) {
        this(id, title, state, link, issues, null);
    }

    /** Returns this record as the published wire object. */
    public Map<String, Object> asMap() {
        final Map<String, Object> result =
                new java.util.LinkedHashMap<>(
                        Map.ofEntries(
                                Map.entry("id", id),
                                Map.entry("title", title),
                                Map.entry("state", state),
                                Map.entry("link", link)));
        if (issues != null) result.put("issues", issues.stream().map(IssueSummary::asMap).toList());
        if (priority != null) result.put("priority", priority);
        return result;
    }

    /** Reads the published wire object, refusing missing or mistyped fields. */
    public static StorySummary of(Map<?, ?> map) {
        return new StorySummary(
                Wire.required(map, "id"),
                Wire.text(map, "title"),
                Wire.text(map, "state"),
                Wire.text(map, "link"),
                map.get("issues") == null ? null : Wire.list(map, "issues", IssueSummary::of),
                map.get("priority") == null ? null : Wire.text(map, "priority"));
    }
}
