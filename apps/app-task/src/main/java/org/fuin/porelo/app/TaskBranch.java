package org.fuin.porelo.app;

import java.util.Map;
import org.fuin.porelo.core.project.BranchScheme;
import org.fuin.porelo.core.project.Parameters;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.jspecify.annotations.Nullable;

/**
 * A task's branch at the forge, made and judged once: by the start and by the daemon's {@code
 * CanStart} alike, so the question and the start never answer differently.
 */
public final class TaskBranch {

    private TaskBranch() {}

    /**
     * Returns the branch a new task's start settles, as its launch settles it: the branch its
     * builds are read from and its pull request is opened from.
     *
     * @param projectFile The start's project file.
     * @param repository The repository by its key or full name, or {@code null} when none is named.
     * @param task The task's name.
     * @param given The values the start was given, before the project's own are added.
     * @param branch The branch named outright, or {@code null} for the scheme's.
     * @return The branch, or {@code null} when the start refuses it or what it names does not read;
     *     the launch then says why.
     */
    public static @Nullable String of(
            final java.nio.file.Path projectFile,
            final @Nullable String repository,
            final String task,
            final Map<String, Parameters.Given> given,
            final @Nullable String branch) {
        if (repository == null) {
            return null;
        }
        try {
            final Project project = org.fuin.porelo.core.project.ProjectReader.read(projectFile);
            return settle(
                            project,
                            GateSupport.repository(project, repository).name(),
                            task,
                            project.parameters().resolve(given),
                            branch)
                    .name();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * What an existing task recorded of where it works.
     *
     * @param repository The repository it was labelled with, or {@code null} when it has none.
     * @param branch The branch its work carries beyond the gate, or {@code null} when it has none.
     * @param parts The parts its start recorded, or {@code null} when it recorded none that read.
     */
    public record Recorded(
            @Nullable String repository,
            @Nullable String branch,
            org.fuin.porelo.gate.review.@Nullable TaskParts parts) {}

    /** A task's record or its runtime listing could not be read, so no place can be checked. */
    public static final class Unreadable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param cause A short cause for a person; never a secret.
         */
        public Unreadable(final String cause) {
            super(cause, null, false, false);
        }
    }

    /**
     * Returns what an existing task recorded of its repository and branch, so a start of it is
     * checked against the task it resumes and not against the ones its arguments would make anew.
     *
     * @param context The machine.
     * @param projectFile The start's project file.
     * @param task The task's name within the project.
     * @return What it recorded, or {@code null} only when there is no such task: its project does
     *     not read, no task of that name is recorded, or the runtime lists no container for it; the
     *     arguments and the launch then decide.
     * @throws Unreadable When the runtime cannot be listed, or the task's identity is ambiguous or
     *     cannot be read: the start is refused, never carried on unchecked.
     */
    public static @Nullable Recorded recorded(
            final PoreloContext context, final java.nio.file.Path projectFile, final String task) {
        final Project project;
        try {
            project = org.fuin.porelo.core.project.ProjectReader.read(projectFile);
        } catch (RuntimeException ex) {
            // Refused by the launch, in its own words.
            return null;
        }
        final TaskIdentities.Result found;
        final java.util.List<org.fuin.porelo.runtime.ContainerSummary> rows;
        try {
            found = new TaskIdentities(context).lookup(task, project.name());
            rows = context.podman().poreloTasks();
        } catch (RuntimeException ex) {
            throw new Unreadable(
                    "the task's container could not be looked up: "
                            + ex.getClass().getSimpleName());
        }
        if (found instanceof TaskIdentities.Refused refused) {
            return switch (refused.reason()) {
                case MISSING, MISSING_RECORD, PROJECT_MISMATCH -> null;
                default -> throw new Unreadable(refused.said());
            };
        }
        final String container = ((TaskIdentities.Found) found).identity().container();
        final org.fuin.porelo.runtime.ContainerSummary row =
                rows.stream()
                        .filter(each -> each.name().equals(container))
                        .findFirst()
                        .orElse(null);
        if (row == null) {
            return null;
        }
        final org.fuin.porelo.wire.TaskProfile profile;
        try {
            final org.fuin.porelo.wire.TaskProfile state =
                    org.fuin.porelo.wire.TaskProfile.readFrom(
                            context.paths().tasks().containerState(container));
            profile =
                    state != null
                            ? state
                            : org.fuin.porelo.wire.TaskProfile.readFrom(
                                    context.paths().tasks().taskRecord(container));
        } catch (RuntimeException ex) {
            throw new Unreadable(
                    "the task's profile could not be read: " + ex.getClass().getSimpleName());
        }
        final String repository = row.repository();
        return new Recorded(
                repository == null || repository.isBlank() ? null : repository,
                profile == null ? null : profile.forgeBranchOrToday(),
                parts(context, container));
    }

    /** The parts a task's start recorded, in its state or its saved record; none that read. */
    private static org.fuin.porelo.gate.review.@Nullable TaskParts parts(
            final PoreloContext context, final String container) {
        try {
            final org.fuin.porelo.gate.review.TaskParts inState =
                    RunPlace.partsIn(context.paths().tasks().containerState(container));
            return inState != null
                    ? inState
                    : RunPlace.partsIn(context.paths().tasks().taskRecord(container));
        } catch (IllegalStateException ex) {
            // Parts that do not read keep nothing; bringing the task back refuses it.
            return null;
        }
    }

    /**
     * What came of making a task's branch.
     *
     * @param name The branch, or {@code null} when it is refused.
     * @param refused Why it is refused, or {@code null}.
     */
    record Settled(@Nullable String name, @Nullable String refused) {}

    /**
     * Makes a task's branch from {@code --branch} or the repository's scheme, and checks it is one
     * the forge accepts and git can name.
     *
     * @param project The project.
     * @param repository The repository's name.
     * @param task The task's name.
     * @param values The task's values, settled.
     * @param given The branch named outright, or {@code null} for the scheme's.
     * @return The branch, or why it is refused.
     */
    static Settled settle(
            final Project project,
            final String repository,
            final String task,
            final Map<String, Parameters.Given> values,
            final @Nullable String given) {
        final BranchScheme scheme = project.branchScheme(repository);
        final String name;
        try {
            name =
                    given != null
                            ? given
                            : project.parameters()
                                    .fill(
                                            scheme.template(),
                                            values,
                                            task,
                                            project.name(),
                                            repository);
        } catch (ProjectException ex) {
            return new Settled(null, "the task's branch: " + ex.getMessage());
        }
        if (!scheme.accepts(name)) {
            return new Settled(
                    null,
                    "the task's branch '"
                            + name
                            + "' is not one the project's branch.pattern "
                            + scheme.pattern()
                            + " accepts"
                            + (given == null
                                    ? "; its branch.template makes names the pattern refuses"
                                    : "; name one it accepts with --branch"));
        }
        if (!BranchScheme.refFormat(name)) {
            return new Settled(
                    null, "the task's branch '" + name + "' is not a name git takes for a branch");
        }
        return new Settled(name, null);
    }

    /**
     * Returns the task of the same project and repository that already carries a branch: two would
     * pass their work on onto one, and one of them would move the other's.
     *
     * @param context The machine.
     * @param project The project's name.
     * @param repository The repository's name.
     * @param branch The branch.
     * @param container This task's container, which a resume finds holding its own branch.
     * @return The other task's container, or {@code null}.
     */
    static @Nullable String heldBy(
            final PoreloContext context,
            final String project,
            final String repository,
            final String branch,
            final String container) {
        for (final org.fuin.porelo.runtime.ContainerSummary task : context.podman().poreloTasks()) {
            if (task.name().equals(container)
                    || !project.equals(task.project())
                    || !sameRepository(project, repository, task.repository())) {
                continue;
            }
            org.fuin.porelo.wire.TaskProfile profile =
                    org.fuin.porelo.wire.TaskProfile.readFrom(
                            context.paths().tasks().containerState(task.name()));
            if (profile == null) {
                profile =
                        org.fuin.porelo.wire.TaskProfile.readFrom(
                                context.paths().tasks().taskRecord(task.name()));
            }
            if (profile != null && branch.equals(profile.forgeBranchOrToday())) {
                return task.name();
            }
        }
        return null;
    }

    /** A task labelled before repositories were worked on the project's own one. */
    private static boolean sameRepository(
            final String project, final String repository, final @Nullable String labelled) {
        return repository.equals(labelled == null || labelled.isBlank() ? project : labelled);
    }
}
