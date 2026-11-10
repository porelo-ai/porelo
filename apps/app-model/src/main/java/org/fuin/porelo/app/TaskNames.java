package org.fuin.porelo.app;

import org.fuin.porelo.runtime.ContainerName;

/**
 * Names a task nobody named: after its repository.
 *
 * <p>An unnamed start used to be called {@code shell} whatever it was, so an agent's session read
 * as the wrong mode (2026-10-01). The repository is what a person recognises a task by. A second
 * unnamed task on the same repository gets the next free name - {@code my-first-2} - and never
 * takes over the first silently.
 */
public final class TaskNames {

    private TaskNames() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the name for an unnamed task.
     *
     * @param context The machine, whose tasks a new name must not clash with.
     * @param project The project's name.
     * @param repository The repository's name.
     * @return The name, free on this machine.
     */
    public static String fromRepository(
            final PoreloContext context, final String project, final String repository) {
        // A leading '.' or '_' (GitHub's '.github') is neither a container name nor a peer name.
        // Nor is a leading 'porelo-', which begins a container's name, a task's.
        String base =
                repository
                        .replaceFirst("^[._]+", "")
                        .replaceFirst("^(porelo-)+", "")
                        .replace('.', '-');
        // A repository named like the room's people or a person names no task.
        if (base.isEmpty() || ContainerName.reserved(base)) {
            base = "work";
        }
        if (ContainerName.reserved(project, base)) {
            base += "-work";
        }
        final java.util.Set<String> taken = new java.util.HashSet<>();
        final java.util.Set<String> logical = new java.util.HashSet<>();
        final TaskIdentities identities = new TaskIdentities(context);
        for (final org.fuin.porelo.runtime.ContainerSummary summary :
                context.podman().taskCandidates()) {
            taken.add(summary.name());
            if (identities.read(summary) instanceof TaskIdentities.Found found
                    && found.identity().project().equals(project)) {
                logical.add(found.identity().task());
            }
        }
        final String prefix = ContainerName.PREFIX + project + "-";
        String chosen = fitted(project, base, "");
        for (int at = 2; taken.contains(prefix + chosen) || logical.contains(chosen); at++) {
            chosen = fitted(project, base, "-" + at);
        }
        return chosen;
    }

    /**
     * Returns the base cut so that it and its number fit beside the project, or {@code work} with
     * the number where the cut is a name the start would refuse.
     */
    private static String fitted(final String project, final String base, final String number) {
        final String cut =
                ContainerName.shortened(base, ContainerName.taskRoom(project) - number.length());
        final String named = cut + number;
        return cut.isEmpty() || ContainerName.refusal(project, named).isPresent()
                ? "work" + number
                : named;
    }
}
