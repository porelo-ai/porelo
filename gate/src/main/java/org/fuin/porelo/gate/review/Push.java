package org.fuin.porelo.gate.review;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The waiting push a review belongs to, bound to the task that pushed it.
 *
 * @param project The project.
 * @param repository The repository in the project.
 * @param name The push's name at the gate.
 * @param head The reviewed commit.
 * @param base The commit it is compared with, or null when the review names none.
 * @param task The container id Porelo recorded for the task that pushed it ({@code recordedId},
 *     never the reusable container name), so a task started later under the same name inherits no
 *     thread. It names a directory, so only lowercase hex is taken.
 */
public record Push(
        String project,
        String repository,
        String name,
        String head,
        @Nullable String base,
        String task) {

    private static final String COMMIT = "[0-9a-f]{40}|[0-9a-f]{64}";
    private static final String CONTAINER = "[0-9a-f]{12,64}";

    /**
     * Refuses a missing or blank part, a commit not written in full and a container id that is
     * none.
     */
    public Push {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(task, "task");
        if (project.isBlank()) {
            throw new IllegalArgumentException("project is blank");
        }
        if (repository.isBlank()) {
            throw new IllegalArgumentException("repository is blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name is blank");
        }
        if (!head.matches(COMMIT)) {
            throw new IllegalArgumentException("head is not a full commit id");
        }
        if (base != null && !base.matches(COMMIT)) {
            throw new IllegalArgumentException("base is not a full commit id");
        }
        if (!task.matches(CONTAINER)) {
            throw new IllegalArgumentException("task is not a recorded container id");
        }
    }
}
