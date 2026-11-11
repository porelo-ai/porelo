package org.fuin.porelo.app;

import java.nio.file.Path;

/**
 * Where Porelo keeps the files of a task's own files: its record, its state, its session, its
 * clearance journal and its image's build context.
 *
 * <p>One class per area, beside {@link PoreloPaths}, which keeps only the roots and what belongs to
 * the machine as a whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record TaskPaths(PoreloPaths paths) {
    /** The account-wide cross-process name lock, always taken before a vault update. */
    public Path reservationsLock() {
        return paths.xdg().state().resolve("task-names.lock");
    }

    /** Committed ownership, retained even when a task's ordinary state is removed. */
    public Path identity(String container) {
        return paths.xdg().state().resolve("task-identities").resolve(container + ".json");
    }

    /**
     * Returns where a start remembers advice already said for an old local project's checkout.
     *
     * @return The directory in this machine's state, outside every checkout.
     */
    public Path localProjectNotes() {
        return paths.xdg().state().resolve("notes").resolve("local-project");
    }

    /** Returns the durable usage record retained after this container is removed. */
    public Path usageRecord(String container) {
        return paths.xdg().state().resolve("usage").resolve(container + ".jsonl");
    }

    /**
     * Returns the parent of the private socket directories used by build queries.
     *
     * @return A directory under this account's runtime root.
     */
    public Path buildQueries() {
        return paths.xdg().runtime().resolve("build-queries");
    }

    /**
     * Returns the directory holding the runtime state of one container.
     *
     * @param container Container name.
     * @return State directory.
     */
    public Path containerState(String container) {
        // Under the runtime directory, so the kernel removes it when the session ends and no stale
        // sidecar can be picked up by a later run.
        return paths.xdg().runtime().resolve(container);
    }

    /**
     * Returns the directory holding the certificates of the task keys this machine held.
     *
     * <p>Under the state directory and kept after a task is removed: a signature by a task's plain
     * key is traced to its certificate long after the key is gone.
     *
     * @return The directory.
     */
    public Path certificates() {
        return paths.xdg().state().resolve("task-certificates");
    }

    /**
     * Returns the directory holding the tasks' usage records.
     *
     * <p>Under the state directory and kept after a task is removed: what a project's work cost
     * outlives the task that did it.
     *
     * @return The directory.
     */
    public Path usageRecords() {
        return paths.xdg().state().resolve("usage");
    }

    /**
     * Returns the directory holding the tasks' work records.
     *
     * <p>Under the state directory and kept after a task is removed, as the usage records are: what
     * a task worked on, from when to when and with which tool calls is what planning measures.
     *
     * @return The directory.
     */
    public Path workRecords() {
        return paths.xdg().state().resolve("work");
    }

    /**
     * Returns the work record of one task, renamed when the task is removed.
     *
     * @param container Container name.
     * @return The file.
     */
    public Path workRecord(String container) {
        return workRecords().resolve(container + ".json");
    }

    /**
     * Returns the file holding the session a task's agent was running.
     *
     * <p>Under the state directory, like the mailbox and for the same reason: it is the task's, and
     * has to survive a stop, a restart and a reboot, which the runtime directory does not. Deleted
     * when the task is removed.
     *
     * @param container Container name.
     * @return The file.
     */
    public Path sessionRecord(String container) {
        return paths.xdg().state().resolve("sessions").resolve(container);
    }

    /**
     * Returns the directory a task's durable state is saved in: what it is, how its helpers start,
     * and the egress it was given - the copies that bring it back after a reboot.
     *
     * <p>Under the state directory, which a reboot does not touch; the runtime directory, which it
     * does, is still where the task's files are used from. Deleted when the task is removed.
     *
     * @param container Container name.
     * @return The directory.
     */
    public Path taskRecord(String container) {
        return paths.xdg().state().resolve("tasks").resolve(container);
    }

    /** Returns the host-only completion record, outside every directory mounted into the task. */
    public Path doneRecord(String container) {
        return taskRecord(container).resolve("done");
    }

    /**
     * Returns the directory holding the file a task hands back: its name, the box its agent writes
     * it into, and what the host took from there.
     *
     * <p>Under the state directory and outside the task's record: what was taken is answered after
     * the task is removed, until the next start of its name. Only the box inside it is mounted.
     *
     * @param container Container name.
     * @return The directory.
     */
    public Path result(String container) {
        return paths.xdg().state().resolve("results").resolve(container);
    }

    /**
     * Returns the box a task's agent writes the file it hands back into, mounted into the task.
     *
     * @param container Container name.
     * @return The directory.
     */
    public Path resultBox(String container) {
        return result(container).resolve("box");
    }

    /**
     * Returns the directory holding the guide a task's agent is given, mounted into the task.
     *
     * <p>Under the state directory, like the mailbox: what a container mounts has to be there when
     * it starts again after a reboot. Deleted when the task is removed.
     *
     * @param container Container name.
     * @return The directory.
     */
    public Path guide(String container) {
        return paths.xdg().state().resolve("guides").resolve(container);
    }

    /**
     * Returns the directory holding the repositories a task that reads its whole project is given,
     * mounted read only into the task.
     *
     * <p>Under the state directory, like the guide: what a container mounts has to be there when it
     * starts again after a reboot, and a whole project's trees do not belong in the runtime
     * directory, which is memory. Deleted when the task is removed.
     *
     * @param container Container name.
     * @return The directory.
     */
    public Path repositories(String container) {
        return paths.xdg().state().resolve("repositories").resolve(container);
    }

    /**
     * Returns the file holding one task's clearance decisions.
     *
     * <p>Under the state directory, not beside the rest of the task's files. Everything else a task
     * writes is in the runtime directory, which the kernel clears at logout and {@code task stop
     * --remove} deletes - and a record of what an agent tried to reach that goes when the task goes
     * is not an audit record. Named by the container, and renamed when the task is removed, so a
     * decision made for one task is not silently in force for the next one of the same name.
     *
     * @param container Container name.
     * @return The journal file.
     */
    public Path clearanceJournal(String container) {
        return paths.xdg().state().resolve("clearance").resolve(container + ".jsonl");
    }

    /**
     * Returns the build context directory for a project's image.
     *
     * @param project Project name.
     * @return Build context directory.
     */
    public Path buildContext(String project) {
        return paths.xdg().data().resolve("build").resolve(project);
    }

    /**
     * Returns the account's own tmux settings, copied into every task it starts: key bindings, the
     * prefix, the mouse, colours. Beside the account's other Porelo settings; there or not, as the
     * person chooses.
     *
     * @return {@code ~/.config/porelo/tmux.conf} by default.
     */
    public Path accountTmux() {
        return paths.xdg().config().resolve("tmux.conf");
    }
}
