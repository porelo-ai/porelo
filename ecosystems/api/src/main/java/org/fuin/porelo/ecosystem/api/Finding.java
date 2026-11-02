package org.fuin.porelo.ecosystem.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One value a plugin recognized.
 *
 * @param part What it belongs to.
 * @param key Its name.
 * @param value Its value, "" when {@code unknown}. A credential is named by its id only.
 * @param file Where it is set: relative to the directory for {@link Origin#CHECKOUT}, otherwise to
 *     the home; "" only for {@link Origin#MACHINE}, when the value comes from no file.
 * @param line Its line, 0 when the file has no line for it, and always 0 without a file.
 * @param origin Whose file it is.
 * @param unknown Whether it is set where it cannot be read without running something.
 * @param note What a person should know of it, or "".
 */
public record Finding(
        Part part,
        String key,
        String value,
        String file,
        int line,
        Origin origin,
        boolean unknown,
        String note) {

    /** What a finding belongs to. */
    public enum Part {

        /** What a task's image needs. */
        TOOLS,

        /** What a project's file may declare. */
        RULES
    }

    /** Whose file a finding comes from. */
    public enum Origin {

        /** The person's own files, under the home. */
        YOURS,

        /** The directory that was asked about. */
        CHECKOUT,

        /**
         * Neither the person nor the checkout wrote it: a parent in a local repository, or the
         * tool's built-in default.
         */
        MACHINE
    }

    /**
     * Constructor.
     *
     * @throws IllegalArgumentException Without a key, with a negative line, without a file where
     *     one is needed, with a file that may not stand so, or with a value that is unknown.
     */
    public Finding {
        if (key.isBlank()) {
            throw new IllegalArgumentException("a finding needs a key");
        }
        if (file.isEmpty() ? origin != Origin.MACHINE : file.isBlank()) {
            throw new IllegalArgumentException("finding '" + key + "' needs a file");
        }
        if (file.startsWith("/") && origin != Origin.MACHINE) {
            throw new IllegalArgumentException("finding '" + key + "' has an absolute file");
        }
        if (java.util.Arrays.asList(file.split("/")).contains("..")) {
            throw new IllegalArgumentException("finding '" + key + "' has a file with ..");
        }
        if (unknown && !value.isEmpty()) {
            throw new IllegalArgumentException("finding '" + key + "' is unknown and has a value");
        }
        if (line < 0 || (file.isEmpty() && line != 0)) {
            throw new IllegalArgumentException("finding '" + key + "' has line " + line);
        }
    }

    /**
     * Returns the finding as it goes on the wire.
     *
     * @return Its fields.
     */
    Map<String, Object> asMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("part", part.name());
        map.put("key", key);
        map.put("value", value);
        map.put("file", file);
        map.put("line", Integer.valueOf(line));
        map.put("origin", origin.name());
        map.put("unknown", Boolean.valueOf(unknown));
        map.put("note", note);
        return map;
    }

    /**
     * Reads a finding off the wire.
     *
     * @param map Its fields.
     * @return The finding.
     * @throws IllegalArgumentException When they do not make one.
     */
    static Finding of(final Map<?, ?> map) {
        return new Finding(
                Part.valueOf(Wire.required(map, "part")),
                Wire.required(map, "key"),
                Wire.text(map, "value"),
                Wire.text(map, "file"),
                Wire.integer(map, "line"),
                Origin.valueOf(Wire.required(map, "origin")),
                Wire.bool(map, "unknown"),
                Wire.text(map, "note"));
    }
}
