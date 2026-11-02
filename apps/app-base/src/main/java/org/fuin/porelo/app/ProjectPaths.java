package org.fuin.porelo.app;

import java.nio.file.Path;

/**
 * Where Porelo keeps the files of projects: what a start placed, its clones, its signers and its
 * records.
 *
 * <p>One class per area, beside {@link PoreloPaths}, which keeps only the roots and what belongs to
 * the machine as a whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record ProjectPaths(PoreloPaths paths) {

    /**
     * Returns where the organization's projects are placed: a record and a clone each.
     *
     * <p>Per account, not per machine: two people on one machine read the organization with
     * different credentials, and what a start judged is theirs.
     *
     * @return The directory.
     */
    public Path organizationProjects() {
        return paths.xdg().state().resolve("organization-projects");
    }

    /**
     * Returns where the last revision of each project's file judged is remembered, so a file does
     * not go back unnoticed, whichever command read it.
     *
     * @return The directory.
     */
    public Path projectFileMemory() {
        return paths.xdg().state().resolve("organization").resolve("project-files");
    }

    /**
     * Returns where a start places an organization's project's repository, at the revision it
     * judged. Under the state directory because nothing edits it and a start rebuilds it; an agent
     * gets its own checkout through the gate.
     *
     * @param project The project's name.
     * @return The clone's directory, which need not exist.
     */
    public Path organizationClone(String project) {
        return organizationProjects().resolve(project + ".git");
    }

    /**
     * Returns the lock that serializes changes to a project's clone.
     *
     * @param project The project's name.
     * @return The lock file, which need not exist.
     */
    public Path organizationCloneLock(String project) {
        return organizationProjects().resolve(project + ".git.lock");
    }

    /**
     * Returns the account's SSH key revocation list for new configuration commits.
     *
     * @return The file, which need not exist.
     */
    public Path revocations() {
        return paths.xdg().config().resolve("revoked.krl");
    }

    public Path projectRegistry() {
        return paths.xdg().data().resolve("projects");
    }

    public Path upstreamRecords() {
        return paths.xdg().data().resolve("upstream");
    }

    /**
     * Returns where a project's file goes when nobody says where.
     *
     * <p><strong>A place rather than a prompt.</strong> An interface reaching a machine over a
     * forwarded socket cannot look at its filesystem, so asking a person for an absolute path there
     * asks them to guess - and an interface that guessed for them would be writing this layout down
     * a second time, where it could not be kept in step.
     *
     * <p>A directory per project, so that what belongs to it - an image snippet, anything a project
     * grows later - can sit beside it rather than somewhere else with a name that has to match.
     *
     * @param name The project's name.
     * @return The file, which need not exist.
     */
    public Path defaultProjectFile(String name) {
        return paths.xdg().config().resolve("projects").resolve(name).resolve("project.yml");
    }
}
