package org.fuin.porelo.ecosystem.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a plugin recognized in a directory.
 *
 * @param findings The values it recognized.
 * @param unplaced What it read and can place in no part.
 */
public record Detection(List<Finding> findings, List<Unplaced> unplaced) {

    /** Constructor. */
    public Detection {
        findings = List.copyOf(findings);
        unplaced = List.copyOf(unplaced);
    }

    /**
     * Returns it as it goes on the wire.
     *
     * @return Its fields.
     */
    public Map<String, Object> asMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("findings", findings.stream().map(Finding::asMap).toList());
        map.put("unplaced", unplaced.stream().map(Unplaced::asMap).toList());
        return map;
    }

    /**
     * Reads one off the wire.
     *
     * @param map Its fields.
     * @return It.
     * @throws IllegalArgumentException When they do not make one.
     */
    public static Detection of(final Map<?, ?> map) {
        return new Detection(
                Wire.list(map, "findings", Finding::of), Wire.list(map, "unplaced", Unplaced::of));
    }
}
