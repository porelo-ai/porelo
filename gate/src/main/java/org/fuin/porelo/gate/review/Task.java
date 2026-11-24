package org.fuin.porelo.gate.review;

import java.util.Objects;
import org.fuin.porelo.core.project.Project;

/**
 * An agent in a Porelo task, named by the task the host took the item from.
 *
 * @param id The task's name, from the mailbox the item came through; a task name's grammar, so it
 *     carries no line break or control character into what is shown.
 */
public record Task(String id) implements Producer {

    /** Refuses a blank task name. */
    public Task {
        Objects.requireNonNull(id, "id");
        if (!id.matches(Project.TASK_NAME_PATTERN)) {
            throw new IllegalArgumentException("not a task name");
        }
    }

    @Override
    public String words() {
        return "task " + id;
    }
}
