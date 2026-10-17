package org.fuin.porelo.app;

import java.util.List;
import org.fuin.porelo.runtime.ContainerSummary;

/**
 * The task names a command could have been given.
 *
 * <p>Read through {@code poreloTasks()}, which filters on {@link
 * org.fuin.porelo.runtime.ContainerName#isTask} - so the throwaway containers {@code vault login}
 * creates are not offered. Building this on the {@code porelo-} prefix instead would put them back,
 * which is the defect that put them in {@code task list}.
 */
final class TaskCandidates {

    private TaskCandidates() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the tasks that are up.
     *
     * @param context Where to ask.
     * @return Names.
     */
    static List<String> running(PoreloContext context) {
        return context.podman().poreloTasks().stream()
                .filter(ContainerSummary::running)
                .map(ContainerSummary::name)
                .sorted()
                .toList();
    }

    /**
     * Returns the tasks that exist and are not up.
     *
     * @param context Where to ask.
     * @return Names.
     */
    static List<String> stopped(PoreloContext context) {
        return context.podman().poreloTasks().stream()
                .filter(task -> !task.running())
                .map(ContainerSummary::name)
                .sorted()
                .toList();
    }

    /**
     * Returns every task, running or not.
     *
     * @param context Where to ask.
     * @return Names.
     */
    static List<String> all(PoreloContext context) {
        return context.podman().poreloTasks().stream()
                .map(ContainerSummary::name)
                .sorted()
                .toList();
    }
}
