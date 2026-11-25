package org.fuin.porelo.app;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.agent.api.InstalledAgent;
import org.fuin.porelo.gate.GitGate;
import org.jspecify.annotations.Nullable;

/**
 * What a task's start gives it, by the role its run recorded: a reviewer reads untrusted work, so
 * it gets no credential the project names, no package repository and no host the project names; it
 * keeps its agent's and its provider's hosts, and its workspace has nowhere to push.
 */
final class ReviewerStart {

    private ReviewerStart() {}

    /**
     * Whether a start's recorded place is a reviewer's.
     *
     * @param place The recorded role and place, or {@code null} for a task without a gate.
     * @return True for a reviewer.
     */
    static boolean reviewing(final @Nullable RunPlace place) {
        return place != null && RunPlace.REVIEWER.equals(place.role());
    }

    /**
     * Returns the project's other credentials a task is given.
     *
     * @param place The recorded role and place, or {@code null}.
     * @param declared Every one the project names, resolved.
     * @param <V> What each resolves to.
     * @return None for a reviewer, else all of them.
     */
    static <V> Map<String, V> credentials(
            final @Nullable RunPlace place, final Map<String, V> declared) {
        return reviewing(place) ? Map.of() : declared;
    }

    /**
     * Returns the package repositories a task's broker serves it.
     *
     * @param place The recorded role and place, or {@code null}.
     * @param declared Every one the project declares.
     * @param <T> How each is declared.
     * @return None for a reviewer, else all of them.
     */
    static <T> List<T> repositories(final @Nullable RunPlace place, final List<T> declared) {
        return reviewing(place) ? List.of() : declared;
    }

    /**
     * Returns what a task may reach: a reviewer its agent's and its provider's hosts alone.
     *
     * @param place The recorded role and place, or {@code null}.
     * @param selected The chosen agent, or {@code null}.
     * @param serving Its provider, or {@code null}.
     * @param projectOrigins What the project declared.
     * @return The hosts and their origins.
     */
    static EgressReport.Reachable reachable(
            final @Nullable RunPlace place,
            final @Nullable InstalledAgent selected,
            final @Nullable SelectedProvider serving,
            final Map<String, String> projectOrigins) {
        return EgressReport.compose(
                selected, serving, reviewing(place) ? Map.of() : projectOrigins);
    }

    /**
     * Returns the workspace's variables naming its refs: a reviewer's pinned ref and no ref to push
     * to, an author's own ref.
     *
     * @param place The recorded role and place, or {@code null}.
     * @param task The task's name.
     * @return The variables.
     */
    static Map<String, String> workspaceRefs(final @Nullable RunPlace place, final String task) {
        return reviewing(place)
                ? Map.of("PORELO_PINNED_REF", GitGate.PINNED + task)
                : Map.of("PORELO_TASK_REF", GitGate.INCOMING + task);
    }
}
