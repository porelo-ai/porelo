package org.fuin.porelo.app;

import org.fuin.porelo.wire.TaskProfile;
import org.jspecify.annotations.Nullable;

/** The branch at its forge that a task's start recorded, read from the task's own record. */
final class RecordedBranch {

    private RecordedBranch() {}

    /**
     * Returns the branch a task's work carries at the forge.
     *
     * @param context The machine, where the task's record is.
     * @param project The project's name.
     * @param task The task's name.
     * @return The branch; for a task started before it was recorded, {@code porelo/<task>}; null
     *     when no record of the task is kept or it has no gate.
     */
    static @Nullable String of(
            final PoreloContext context, final String project, final String task) {
        final TaskIdentity identity = TaskHistory.forPush(context, project, task);
        if (identity == null) {
            return null;
        }
        final String container = identity.container();
        TaskProfile profile =
                TaskProfile.readFrom(context.paths().tasks().containerState(container));
        if (profile == null) {
            // Kept over a restart, when the runtime directory is gone.
            profile = TaskProfile.readFrom(context.paths().tasks().taskRecord(container));
        }
        return profile == null ? null : profile.forgeBranchOrToday();
    }
}
