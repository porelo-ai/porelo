package org.fuin.porelo.core.project.file;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a task's container is built from, as the project file and a repository's file both say it.
 *
 * @param from The image to build on ({@code from}).
 * @param env Variables set in the image, in the order written ({@code env}); empty for none.
 * @param run Containerfile lines run as root: {@code RUN}, {@code ARG} and {@code ENV} only ({@code
 *     run}).
 * @param runFile The same lines in a file of the repository ({@code run_file}); never with {@code
 *     run}.
 */
public record Image(
        @Nullable String from,
        Map<String, String> env,
        @Nullable String run,
        @Nullable String runFile) {

    /**
     * Constructor.
     *
     * @param from The image to build on.
     * @param env Variables, in order.
     * @param run Containerfile lines.
     * @param runFile A file of them.
     */
    public Image {
        env = ProjectFile.ordered(env);
    }
}
