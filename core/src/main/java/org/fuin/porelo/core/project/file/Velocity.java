package org.fuin.porelo.core.project.file;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the person's planner starts a project's velocity from ({@code velocity}): the planner's,
 * kept unread.
 *
 * @param seed The starting median in minutes, by repository and then by size ({@code XS} to {@code
 *     XL}), each in the order written ({@code velocity.seed}).
 */
public record Velocity(Map<String, Map<String, Integer>> seed) {

    /**
     * Constructor.
     *
     * @param seed The starting minutes by repository and size.
     */
    public Velocity {
        final Map<String, Map<String, Integer>> copy = new LinkedHashMap<>();
        seed.forEach(
                (repository, sizes) ->
                        copy.put(
                                repository,
                                Collections.unmodifiableMap(new LinkedHashMap<>(sizes))));
        seed = Collections.unmodifiableMap(copy);
    }
}
