package org.fuin.porelo.ecosystem.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A repository a task's tool reaches.
 *
 * @param name The name the project declares it under.
 * @param url Its URL inside the task.
 */
public record Repository(String name, String url) {

    /**
     * Constructor.
     *
     * @throws IllegalArgumentException Without a name or URL.
     */
    public Repository {
        if (name.isBlank()) {
            throw new IllegalArgumentException("a repository needs a name");
        }
        if (url.isBlank()) {
            throw new IllegalArgumentException("repository '" + name + "' needs a URL");
        }
    }

    /**
     * Returns it as it goes on the wire.
     *
     * @return Its fields.
     */
    Map<String, Object> asMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("url", url);
        return map;
    }

    /**
     * Reads one off the wire.
     *
     * @param map Its fields.
     * @return It.
     * @throws IllegalArgumentException When they do not make one.
     */
    static Repository of(final Map<?, ?> map) {
        return new Repository(Wire.required(map, "name"), Wire.required(map, "url"));
    }
}
