package org.fuin.porelo.ecosystem.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Something a plugin read and can place in no part. Never a credential's value.
 *
 * @param file Where it is, relative to the directory or the home.
 * @param line Its line, 0 when the file has no line for it.
 * @param note What it is.
 */
public record Unplaced(String file, int line, String note) {

    /**
     * Constructor.
     *
     * @throws IllegalArgumentException Without a file, or with a negative line.
     */
    public Unplaced {
        if (file.isBlank()) {
            throw new IllegalArgumentException("an unplaced value needs a file");
        }
        if (line < 0) {
            throw new IllegalArgumentException(
                    "an unplaced value in " + file + " has line " + line);
        }
    }

    /**
     * Returns it as it goes on the wire.
     *
     * @return Its fields.
     */
    Map<String, Object> asMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("file", file);
        map.put("line", Integer.valueOf(line));
        map.put("note", note);
        return map;
    }

    /**
     * Reads one off the wire.
     *
     * @param map Its fields.
     * @return It.
     * @throws IllegalArgumentException When they do not make one.
     */
    static Unplaced of(final Map<?, ?> map) {
        return new Unplaced(
                Wire.required(map, "file"), Wire.integer(map, "line"), Wire.text(map, "note"));
    }
}
