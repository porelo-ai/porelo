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
        return choose(context, project, repository).name();
    }

    /**
     * A name chosen for an unnamed task, and what it says when the name first wanted was held. One
     * value is chosen once and carried to everything that names or tells of the task.
     */
    public static final class Chosen {
        private final String name;
        private final String first;
        private final @org.jspecify.annotations.Nullable String holder;

        private Chosen(
                final String name,
                final String first,
                final @org.jspecify.annotations.Nullable String holder) {
            this.name = name;
            this.first = first;
            this.holder = holder;
        }

        /**
         * Returns a name nothing held.
         *
         * @param name The name.
         * @return The choice, with no note.
         */
        public static Chosen free(final String name) {
            return new Chosen(name, name, null);
        }

        /**
         * Returns the name the task takes.
         *
         * @return The name.
         */
        public String name() {
            return name;
        }

        /**
         * Returns the line that says which name was held, by what, and the name taken instead.
         *
         * @return The line, or {@code null} when the first name was free.
         */
        public @org.jspecify.annotations.Nullable String note() {
            return holder == null ? null : TaskNames.note(first, holder, name);
        }

        /**
         * Moves the choice on to another name because something held the one it has now.
         *
         * <p>The name first wanted and what held it stay, so the note tells of the name a person
         * expected; only a choice that was free until now takes the holder it is given.
         *
         * @param next The name taken now.
         * @param heldBy What held the name left: {@code the task <name>}, {@code a container of
         *     that name} or {@code a start reserving it}.
         * @return The choice with the new name.
         */
        public Chosen movedOn(final String next, final String heldBy) {
            return new Chosen(next, first, holder == null ? heldBy : holder);
        }
    }

    /**
     * Returns the line for a name that was held.
     *
     * @param first The name that was wanted.
     * @param holder What holds it: {@code the task <name>}, {@code a container of that name} or
     *     {@code a start reserving it}.
     * @param chosen The name taken instead.
     * @return The line.
     */
    public static String note(final String first, final String holder, final String chosen) {
        return "name " + first + " is held by " + holder + "; this task is " + chosen;
    }

    /**
     * Returns the name for an unnamed task and, when its first choice was held, the line that says
     * so: a person who believes the work goes to one name finds it under another.
     *
     * @param context The machine, whose tasks a new name must not clash with.
     * @param project The project's name.
     * @param repository The repository's name.
     * @return The name, free on this machine, and the note when it is not the first choice.
     */
    public static Chosen choose(
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
        final java.util.Set<String> taken = new java.util.HashSet<>();
        final java.util.Set<String> logical = new java.util.HashSet<>();
        final java.util.Set<String> containers = new java.util.HashSet<>();
        final java.util.Set<String> identified = new java.util.HashSet<>();
        final TaskIdentities identities = new TaskIdentities(context);
        for (final org.fuin.porelo.runtime.ContainerSummary summary :
                context.podman().taskCandidates()) {
            taken.add(summary.name());
            containers.add(summary.name());
            if (identities.read(summary) instanceof TaskIdentities.Found found
                    && found.identity().project().equals(project)) {
                logical.add(found.identity().task());
                identified.add(found.identity().task());
            }
        }
        try {
            final TaskReservations reservations = new TaskReservations(context);
            taken.addAll(reservations.protectedNames());
            logical.addAll(reservations.tasks(project));
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Cannot check reserved task names", ex);
        }
        final String first = fitted(project, base, "");
        String chosen = first;
        for (int at = 2;
                taken.contains(ContainerName.contract(project, chosen)) || logical.contains(chosen);
                at++) {
            chosen = fitted(project, base, "-" + at);
        }
        if (chosen.equals(first)) {
            return Chosen.free(chosen);
        }
        final String holder =
                identified.contains(first)
                        ? "the task " + first
                        : containers.contains(ContainerName.contract(project, first))
                                ? "a container of that name"
                                : "a start reserving it";
        return new Chosen(chosen, first, holder);
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
