package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * An agent in a Porelo task, named by the task the host took the item from.
 *
 * @param id The task's container name, from the mailbox the item came through, as every daemon
 *     method takes a task; a task name's grammar with the underscore that joins project and task,
 *     so it carries no line break or control character into what is shown.
 */
public record Task(String id) implements Producer {

    /** The grammar of a task's id: a task name, or a container name joining project and task. */
    public static final String NAME = "[a-z0-9]([a-z0-9_-]*[a-z0-9])?";

    /** Refuses an id outside {@link #NAME}. */
    public Task {
        Objects.requireNonNull(id, "id");
        if (!id.matches(NAME)) {
            throw new IllegalArgumentException("not a task name");
        }
    }

    @Override
    public String words() {
        return "task " + id;
    }
}
