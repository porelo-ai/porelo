package org.fuin.porelo.core.project;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What one source says a repository's image is built from: a base image, a snippet and variables,
 * each of which it may leave unset for a later source to give.
 *
 * @param baseImage The image to build on, or {@code null} when this source does not say.
 * @param snippet Containerfile lines run as root, or {@code null} when this source does not say.
 * @param env Variables set in the image, in the order written; empty when it sets none.
 * @param from Which file or entry this came from, as the start names it.
 */
public record ImageSource(
        @Nullable String baseImage,
        @Nullable String snippet,
        Map<String, String> env,
        String from) {

    /**
     * Constructor.
     *
     * @param baseImage The image to build on, or {@code null}.
     * @param snippet Containerfile lines, or {@code null}.
     * @param env Variables, in order.
     * @param from Where it came from.
     */
    public ImageSource {
        env = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(env));
    }

    /**
     * Returns what a project's own {@code image} section says, the last source in the order.
     *
     * @param project The project.
     * @return Its image as a source.
     */
    public static ImageSource of(Project project) {
        return new ImageSource(
                project.baseImage(),
                project.imageSnippet(),
                Map.of(),
                "the project's image (" + project.name() + ")");
    }
}
