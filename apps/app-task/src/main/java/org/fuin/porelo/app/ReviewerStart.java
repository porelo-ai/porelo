package org.fuin.porelo.app;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.agent.api.InstalledAgent;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.TaskParts;
import org.jspecify.annotations.Nullable;

/**
 * What a task's start gives it, by the parts it recorded: a task without the project's services
 * gets no credential the project names, no package repository and no host the project names, and
 * keeps its agent's and its provider's hosts; one that reads another task's work, which nobody has
 * reviewed, has a workspace with nowhere to push.
 */
final class ReviewerStart {

    private ReviewerStart() {}

    /**
     * Whether a start's recorded workspace is another task's work, frozen and read only.
     *
     * @param parts The parts the task records.
     * @return True for a task that reads another task's work.
     */
    static boolean reviewing(final TaskParts parts) {
        return parts.readsAnothersWork();
    }

    /**
     * Whether a start is given the project's services: its credentials, package repositories, host
     * services and egress, as one switch.
     *
     * @param parts The parts the task records.
     * @return False for a task whose parts switch them off.
     */
    static boolean serviced(final TaskParts parts) {
        return parts.projectServices();
    }

    /**
     * Whether a start's recorded rights let the task push to its gate.
     *
     * @param parts The parts the task records.
     * @return False for a task whose start recorded no right to push.
     */
    static boolean pushing(final TaskParts parts) {
        return parts.holds(org.fuin.porelo.gate.review.Right.PUSH);
    }

    /**
     * Returns the project's other credentials a task is given.
     *
     * @param parts The parts the task records.
     * @param declared Every one the project names, resolved.
     * @param <V> What each resolves to.
     * @return None for a task without the project's services, else all of them.
     */
    static <V> Map<String, V> credentials(final TaskParts parts, final Map<String, V> declared) {
        return serviced(parts) ? declared : Map.of();
    }

    /**
     * Returns the package repositories a task's broker serves it.
     *
     * @param parts The parts the task records.
     * @param declared Every one the project declares.
     * @param <T> How each is declared.
     * @return None for a task without the project's services, else all of them.
     */
    static <T> List<T> repositories(final TaskParts parts, final List<T> declared) {
        return serviced(parts) ? declared : List.of();
    }

    /**
     * Returns what a task may reach: one without the project's services its agent's and its
     * provider's hosts alone.
     *
     * @param parts The parts the task records.
     * @param selected The chosen agent, or {@code null}.
     * @param serving Its provider, or {@code null}.
     * @param projectOrigins What the project declared.
     * @return The hosts and their origins.
     */
    static EgressReport.Reachable reachable(
            final TaskParts parts,
            final @Nullable InstalledAgent selected,
            final @Nullable SelectedProvider serving,
            final Map<String, String> projectOrigins) {
        return EgressReport.compose(selected, serving, serviced(parts) ? projectOrigins : Map.of());
    }

    /**
     * Returns the project a task's container is started with: without the project's services it has
     * no host services, so no name resolves to this host and no port to it opens but its gate's.
     *
     * @param parts The parts the task records.
     * @param project The project.
     * @return The project to start with.
     */
    static org.fuin.porelo.core.project.Project started(
            final TaskParts parts, final org.fuin.porelo.core.project.Project project) {
        if (serviced(parts)) {
            return project;
        }
        final org.fuin.porelo.core.project.Egress egress = project.egress();
        return project.withEgress(
                new org.fuin.porelo.core.project.Egress(
                        List.of(), List.of(), egress.refused(), List.of()));
    }

    /**
     * Returns the workspace's variables naming its refs: for another task's work the pinned ref and
     * no ref to push to, for own work the task's own ref.
     *
     * @param parts The parts the task records.
     * @param task The task's name.
     * @return The variables.
     */
    static Map<String, String> workspaceRefs(final TaskParts parts, final String task) {
        return reviewing(parts)
                ? Map.of("PORELO_PINNED_REF", GitGate.PINNED + task)
                : Map.of("PORELO_TASK_REF", GitGate.INCOMING + task);
    }
}
