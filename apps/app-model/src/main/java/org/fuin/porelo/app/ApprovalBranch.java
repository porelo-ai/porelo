package org.fuin.porelo.app;

import org.fuin.porelo.core.project.BranchScheme;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.Repository;
import org.jspecify.annotations.Nullable;

/**
 * Where a guarded task's approved work goes: one rule for {@code porelo gate approve} and the
 * daemon's {@code Approve} and {@code PendingBundle}.
 *
 * <p>In a project with a {@code branch} block, the task's own branch, as its start recorded it,
 * unless a person names another, and a named one only when the forge's pattern accepts it: so work
 * reaches only branches the forge's rules cover. A project without one approves as before.
 */
public final class ApprovalBranch {

    private ApprovalBranch() {}

    /** A named branch the project's pattern does not accept. */
    public static final class Refused extends ProjectException {

        private static final long serialVersionUID = 1L;

        private final String branch;

        private final String pattern;

        /**
         * Constructor.
         *
         * @param branch The branch named.
         * @param pattern The pattern it does not match.
         */
        public Refused(final String branch, final String pattern) {
            super(
                    "'"
                            + branch
                            + "' is not a branch the project's branch.pattern "
                            + pattern
                            + " accepts, so its forge's rules would not cover it; name one it accepts,"
                            + " or leave the branch out for the task's own");
            this.branch = branch;
            this.pattern = pattern;
        }

        /**
         * Returns the branch named.
         *
         * @return The branch.
         */
        public String branch() {
            return branch;
        }

        /**
         * Returns the pattern it does not match.
         *
         * @return The pattern.
         */
        public String pattern() {
            return pattern;
        }
    }

    /**
     * Says whether a project names its tasks' branches on a repository itself.
     *
     * @param project The project.
     * @param repository The repository.
     * @return {@code true} when the file has a {@code branch} block for it, even one that says what
     *     the default says.
     */
    public static boolean declared(final Project project, final Repository repository) {
        return project.declaresBranch(repository.name());
    }

    /**
     * Returns the branch a task's work is approved onto.
     *
     * @param context The machine, where the task's record is.
     * @param project The project.
     * @param repository The repository the task worked on.
     * @param task The task's name, which is the pending push's.
     * @param named The branch a person named, or {@code null} or empty for none.
     * @return The branch; {@code null} when the project names no scheme and nobody named one, where
     *     the caller's own default applies as before.
     * @throws Refused When the project has a scheme and the named branch does not match it.
     * @throws ProjectException When the project has a scheme, nobody named a branch and the task's
     *     is not recorded.
     */
    public static @Nullable String of(
            final PoreloContext context,
            final Project project,
            final Repository repository,
            final String task,
            final @Nullable String named) {
        final BranchScheme scheme = project.branchScheme(repository.name());
        final boolean declared = declared(project, repository);
        if (named != null && !named.isEmpty()) {
            if (declared && !scheme.accepts(named)) {
                throw new Refused(named, scheme.pattern());
            }
            return named;
        }
        if (!declared) {
            return null;
        }
        final String container = org.fuin.porelo.runtime.ContainerName.of(project, task);
        org.fuin.porelo.wire.TaskProfile profile =
                org.fuin.porelo.wire.TaskProfile.readFrom(
                        context.paths().tasks().containerState(container));
        if (profile == null) {
            profile =
                    org.fuin.porelo.wire.TaskProfile.readFrom(
                            context.paths().tasks().taskRecord(container));
        }
        final String recorded = profile == null ? null : profile.forgeBranchOrToday();
        if (recorded == null || !scheme.accepts(recorded)) {
            throw new ProjectException(
                    "no branch of "
                            + task
                            + " is recorded that the project's branch.pattern "
                            + scheme.pattern()
                            + " accepts; name the branch to approve onto");
        }
        return recorded;
    }

    /**
     * Says whether a branch may be offered in place of one that holds earlier work.
     *
     * @param project The project.
     * @param repository The repository.
     * @param branch The branch to offer.
     * @return {@code true} when the project names no scheme or its pattern accepts the branch.
     */
    public static boolean offerable(
            final Project project, final Repository repository, final String branch) {
        return !declared(project, repository)
                || project.branchScheme(repository.name()).accepts(branch);
    }
}
