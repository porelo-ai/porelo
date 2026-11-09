package org.fuin.porelo.app;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.runtime.ContainerName;

/** The exact task names used both to hand out a message and to wake its recipient. */
final class TaskRecipients {

    private TaskRecipients() {}

    static boolean names(
            final String container,
            final String project,
            final List<String> displays,
            final String to) {
        return !to.isEmpty()
                && (to.equals(container)
                        || to.equals(ContainerName.taskIn(project, container))
                        || displays.contains(to));
    }

    /** A recorded display wins; an earlier enrollment borrows only an unclaimed derived name. */
    static List<String> displays(
            final String project, final String container, final Map<String, String> room) {
        final String recorded = room.get(container);
        if (recorded != null) {
            return List.of(recorded);
        }
        final String derived = TransportLifecycle.agentName(project, container);
        return derived == null || room.containsValue(derived) ? List.of() : List.of(derived);
    }
}
