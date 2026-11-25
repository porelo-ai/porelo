package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * The channel a profile document came through, which alone decides the producer its items carry.
 */
public sealed interface ProfileChannel {

    /**
     * A forge adapter's answer for one forge instance: pages of forge accounts' items.
     *
     * @param forge The instance's host; the items' {@link ForgeAccount}s are of this forge.
     */
    record Adapter(String forge) implements ProfileChannel {
        /** Refuses a blank forge. */
        public Adapter {
            Objects.requireNonNull(forge, "forge");
            if (forge.isBlank()) {
                throw new IllegalArgumentException("an adapter's channel names its forge");
            }
        }
    }

    /**
     * A task's mailbox: a submission, stamped with the task.
     *
     * @param task The task whose mailbox it came through.
     */
    record FromTask(Task task) implements ProfileChannel {
        /** Refuses a missing task. */
        public FromTask {
            Objects.requireNonNull(task, "task");
        }
    }
}
