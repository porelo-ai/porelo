package org.fuin.porelo.planning.api;

import java.util.List;
import java.util.Map;

/** An issue with its acceptance text, proposed sub-agents and project repository key. */
public record Issue(
        String id,
        String repository,
        String title,
        String state,
        String size,
        List<String> blockedBy,
        String link,
        String whatMustBeTrue,
        List<SubAgent> subAgents) {
    /** Copies the collections so a published snapshot cannot change. */
    public Issue {
        blockedBy = List.copyOf(blockedBy);
        subAgents = List.copyOf(subAgents);
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
                Map.entry("link", link),
                Map.entry("whatMustBeTrue", whatMustBeTrue),
                Map.entry("subAgents", subAgents.stream().map(SubAgent::asMap).toList()));
    }

    /** Reads the wire object; older adapters without subAgents propose none. */
    public static Issue of(Map<?, ?> map) {
        return new Issue(
                Wire.required(map, "id"),
                Wire.required(map, "repository"),
                Wire.text(map, "title"),
                Wire.required(map, "state"),
                Wire.text(map, "size"),
                Wire.strings(map, "blockedBy"),
                Wire.text(map, "link"),
                Wire.text(map, "whatMustBeTrue"),
                map.get("subAgents") != null
                        ? Wire.list(map, "subAgents", SubAgent::of)
                        : List.of());
    }

    /** Returns the fields used by listings. */
    public IssueSummary summary() {
        return new IssueSummary(id, repository, title, state, size, blockedBy, link);
    }
}
