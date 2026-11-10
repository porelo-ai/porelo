package org.fuin.porelo.app;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The exact task names used both to hand out a message and to wake its recipient. */
final class TaskRecipients {

    private TaskRecipients() {}

    static boolean names(
            final String container,
            final @Nullable String task,
            final List<String> displays,
            final String to) {
        return !to.isEmpty() && (to.equals(container) || to.equals(task) || displays.contains(to));
    }

    static @Nullable TaskIdentity identity(
            PoreloContext context, String container, @Nullable String project) {
        return new TaskIdentities(context).lookup(container, project)
                        instanceof TaskIdentities.Found found
                ? found.identity()
                : null;
    }

    static @Nullable String task(PoreloContext context, String container, String project) {
        final TaskIdentity identity = identity(context, container, project);
        return identity == null ? null : identity.task();
    }

    /** A recorded display wins; an earlier enrollment borrows only an unclaimed derived name. */
    static List<String> displays(
            final @Nullable String task, final String container, final Map<String, String> room) {
        final String recorded = room.get(container);
        if (recorded != null) {
            return List.of(recorded);
        }
        final String derived = TransportLifecycle.agentName(task);
        return derived == null || room.containsValue(derived) ? List.of() : List.of(derived);
    }
}
