package org.fuin.porelo.ecosystem.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A settings file for a task.
 *
 * @param path Relative to the task user's home.
 * @param content What it holds.
 * @param ownerOnly Whether only that user may read it.
 */
public record File(String path, String content, boolean ownerOnly) {

    /**
     * Constructor.
     *
     * @throws IllegalArgumentException When the path is no plain one inside the home: this is where
     *     a plugin's answer becomes what Porelo places.
     */
    public File {
        for (final String segment : path.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        "'" + path + "' is not a plain path relative to the home");
            }
        }
        if (path.chars()
                .anyMatch(each -> Character.isISOControl(each) || "'\"\\".indexOf(each) >= 0)) {
            throw new IllegalArgumentException(
                    "'" + path + "' holds a quote, a backslash or a control character");
        }
    }

    /**
     * Returns it as it goes on the wire.
     *
     * @return Its fields.
     */
    Map<String, Object> asMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", path);
        map.put("content", content);
        map.put("ownerOnly", Boolean.valueOf(ownerOnly));
        return map;
    }

    /**
     * Reads the files of an answer off the wire.
     *
     * @param map The answer.
     * @return The files.
     * @throws IllegalArgumentException When they do not make files.
     */
    public static List<File> of(final Map<?, ?> map) {
        return Wire.list(
                map,
                "files",
                file ->
                        new File(
                                Wire.required(file, "path"),
                                Wire.text(file, "content"),
                                Wire.bool(file, "ownerOnly")));
    }
}
