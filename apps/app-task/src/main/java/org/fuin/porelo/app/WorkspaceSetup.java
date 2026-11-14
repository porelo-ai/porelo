package org.fuin.porelo.app;

import java.io.PrintWriter;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.jspecify.annotations.Nullable;

/**
 * The repository the agent works in, and where it came from.
 *
 * <p>Split out of {@code TaskRunCommand} with the other wirings. A workspace is a clone the
 * container makes from Porelo's own mirror, never a directory from the host: what the agent writes
 * stays inside the container until it is pushed through the gate, which is the property the whole
 * review step rests on.
 */
final class WorkspaceSetup {

    private final PoreloContext context;

    private final String task;

    private final @Nullable String upstream;

    /** Which of the project's repositories this task works on, or {@code null} for its own. */
    private final @Nullable String repository;

    /**
     * Constructor with what the run decided.
     *
     * @param context Where podman and the paths come from.
     * @param task Task name, which becomes the ref the agent pushes to.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     */
    WorkspaceSetup(PoreloContext context, String task, @Nullable String upstream) {
        this(context, task, upstream, null);
    }

    /**
     * Constructor naming the repository.
     *
     * @param context Where podman and the paths come from.
     * @param task Task name, which becomes the ref the agent pushes to.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     * @param repository Which repository of the project, or {@code null} for its own.
     */
    /**
     * Why the start must not go on, when the gate holds what an earlier task of this name left; or
     * {@code null}.
     */
    private @Nullable String refusal;

    /** The earlier work of this task's name that waits at the gate, when that is the refusal. */
    private org.fuin.porelo.gate.GitGate.@Nullable Waiting earlierWork;

    /**
     * The next name under which no earlier work waits and no container is, when earlier work is the
     * refusal.
     */
    private @Nullable String freeName;

    /**
     * Returns the next free name for the task, when earlier work of its name is the refusal.
     *
     * @return The name, or {@code null}.
     */
    @Nullable String freeName() {
        return freeName;
    }

    /**
     * Returns why the last {@link #openWorkspace} refused the start, or {@code null}.
     *
     * @return The reason, for the start to say before it creates nothing.
     */
    org.fuin.porelo.gate.GitGate.@Nullable Waiting earlierWork() {
        return earlierWork;
    }

    @Nullable String refusal() {
        return refusal;
    }

    /** The online task's branch at the forge, checked against the upstream when it is opened. */
    private @Nullable String forgeBranch;

    /** The branch the upstream holds already, when that is the refusal. */
    private @Nullable String takenBranch;

    /**
     * Sets the task's branch at the forge, which an online start checks is free before it opens.
     *
     * @param branch The branch, or {@code null} for none.
     */
    void forgeBranch(final @Nullable String branch) {
        this.forgeBranch = branch;
    }

    /**
     * Returns the branch the upstream holds already, when that is why the last {@link
     * #openWorkspace} refused.
     *
     * @return The branch, or {@code null}.
     */
    @Nullable String takenBranch() {
        return takenBranch;
    }

    WorkspaceSetup(
            PoreloContext context,
            String task,
            @Nullable String upstream,
            @Nullable String repository) {
        this.context = context;
        this.task = task;
        this.upstream = upstream;
        this.repository = repository;
    }

    /**
     * Opens the git gate for this task, unless the caller asked for none.
     *
     * @param wanted Whether this run has a gate at all: a dry run and {@code --no-gate} both mean
     *     no workspace, and neither is this class's decision to read.
     * @return The workspace, or {@code null} when running without a gate.
     */
    @Nullable TaskWorkspace openWorkspace(
            Project project, boolean wanted, PrintWriter out, PrintWriter err) {
        refusal = null;
        takenBranch = null;
        if (!wanted) {
            return null;
        }
        try {
            final Repository chosen = GateSupport.repository(project, repository);
            if (project.securityClass() == org.fuin.porelo.core.project.SecurityClass.OFFLINE
                    && upstream != null) {
                // The host would clone the mirror from it over the network; an offline project
                // never connects.
                err.println(
                        "porelo: '"
                                + project.name()
                                + "' is offline, so it takes no --upstream: nothing connects"
                                + " out for it, not even the host. Bring the repository in as a file with 'porelo gate"
                                + " restore', or start from a checkout of it on this machine.");
                err.flush();
                return null;
            }
            if (project.securityClass() == org.fuin.porelo.core.project.SecurityClass.ONLINE
                    && chosen.upstream() == null
                    && upstream == null) {
                // Online passes every push on at once, which needs somewhere to pass it to. Its
                // gate is the same as a guarded task's: the agent's remote is the gate, and only
                // the host reaches the upstream.
                err.println(
                        "porelo: an online project needs an upstream for '"
                                + chosen.name()
                                + "': its gate passes every push on to it");
                err.flush();
                return null;
            }
            final org.fuin.porelo.gate.GitGate gate =
                    GateSupport.gate(
                            context, project, chosen, upstream, seed(project, chosen, out));
            gate.initialize();
            if (project.securityClass() == org.fuin.porelo.core.project.SecurityClass.ONLINE) {
                // What an earlier task of this name left went on to the upstream long ago; here it
                // would only make the new task's plain push "fetch first", and an agent that
                // followed git's hint pull it into its own.
                gate.forget(task);
                if (forgeBranch != null) {
                    // Passed on with force from here on: onto a branch that exists, it would
                    // replace what a person or another task put there. A resume does not come here;
                    // the branch is its own.
                    final String taken = gate.taken(forgeBranch);
                    if (taken != null) {
                        takenBranch = forgeBranch;
                        refusal = taken;
                        return null;
                    }
                }
            } else {
                final String earlier = gate.waiting(task);
                if (earlier != null) {
                    earlierWork = gate.waitingWork(task);
                    final java.util.List<String> containers = context.podman().poreloContainers();
                    for (int n = 2; n < 100 && freeName == null; n++) {
                        final String candidate = task + "-" + n;
                        // Taken under either name: one made before, or one a new task gets.
                        if (gate.waiting(candidate) == null
                                && !containers.contains(
                                        org.fuin.porelo.runtime.ContainerName.of(
                                                project, candidate))
                                && !containers.contains(newName(project, candidate))) {
                            freeName = candidate;
                        }
                    }
                    // Its work waits for a person, and a new task of the same name would push onto
                    // it.
                    refusal =
                            "work of an earlier task called "
                                    + task
                                    + " waits at the gate ("
                                    + earlier
                                    + "): approve or reject it first, with 'porelo gate approve' or 'porelo gate reject', or"
                                    + " start under another name";
                    return null;
                }
            }
            // A new task of an earlier one's name has landed nothing, in every class: its row and
            // a reader naming it would take that work for its own. Only where it landed: in a
            // guarded project the earlier task's own ref may hold work nobody reviewed yet.
            gate.forgetLanded(task);
            final TaskWorkspace opened =
                    TaskWorkspace.gated(chosen, gate, TaskWorkspace.containerVisibleHost());
            if (!opened.refreshed().isEmpty()) {
                err.println("porelo: " + opened.refreshed());
                err.flush();
            }
            return opened;
        } catch (RuntimeException ex) {
            // A task with no workspace is still a useful task - a shell in a hardened box - so this
            // reports and continues rather than refusing to start.
            err.println("porelo: no git gate for this task: " + ex.getMessage());
            final String said = String.valueOf(ex.getMessage());
            if (HostKeys.refusedTheHost(said)) {
                hostKeyHint(said, GateSupport.repository(project, repository), err);
            }
            err.flush();
            return null;
        }
    }

    /**
     * Says how to go on when ssh refused a repository's host: never met, or met with another key.
     *
     * <p>Answered as the question it is, as a follow answers it: git's own words name no way on,
     * and a person new to Porelo met them on the first task in 'default' (2026-10-01).
     *
     * @param said What the gate's failure said.
     * @param repository The repository whose upstream was asked.
     * @param err Where the way on is said, when ssh refused the host.
     */
    private static void hostKeyHint(
            final String said, final Repository repository, PrintWriter err) {
        if (!HostKeys.refusedTheHost(said)) {
            return;
        }
        final String host = HostKeys.nameOf(String.valueOf(repository.upstream()));
        err.println(
                "porelo: "
                        + (HostKeys.changed(said)
                                ? host
                                        + " answered with a different key from the one this machine remembers - nothing was"
                                        + " fetched; find out why before trusting it again"
                                : "this machine has never met "
                                        + host
                                        + ", so it stopped rather than deciding for you. See"
                                        + " what it offers with 'porelo credentials trust-host "
                                        + host
                                        + "', confirm one"
                                        + " against what its owner publishes, and start again."));
    }

    /**
     * Writes every repository of the project, its own among them, into a directory each under
     * {@code into}, as its main stands once its gate brought it up to the upstream; {@code into} is
     * made anew.
     *
     * <p>Each through its gate as a task's workspace is, so a repository is taken wherever this
     * machine takes it and with the credentials it has for it, and an offline project's only from
     * its mirror. A repository that cannot be read is named and left out: the others are still
     * worth reading, and the start refuses only when none could be.
     *
     * @param project The project.
     * @param into The directory to write them in; what it held is removed first.
     * @param out Where each repository read is said.
     * @param err Where each one that could not be is said, with why.
     * @return The repositories written, by name, in the project's order.
     */
    java.util.List<String> readAll(
            Project project,
            java.nio.file.Path into,
            SecretCheck secrets,
            PrintWriter out,
            PrintWriter err) {
        // Whatever an earlier start of this name left is no reading of today's mains; the name is
        // free by now, so nothing of it is in use.
        removePartial(into, err);
        try {
            org.fuin.porelo.wire.Directories.create(into);
            // The task's /workspace: its agent is another uid than the person, who made it under
            // the daemon's umask. Read-only all the same, by its mount.
            java.nio.file.Files.setPosixFilePermissions(
                    into, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (java.io.IOException ex) {
            err.println("porelo: could not make " + into + ": " + ex.getMessage());
            err.flush();
            return java.util.List.of();
        }
        final java.util.List<String> read = new java.util.ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (final Repository each : project.allRepositories()) {
            if (!seen.add(each.name())) {
                continue;
            }
            // A name is a directory here: one that is not a plain name would be a path elsewhere.
            if (!each.name().matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                err.println(
                        "porelo: repository '"
                                + each.name()
                                + "' is not read: its name is no directory name");
                continue;
            }
            try {
                final org.fuin.porelo.gate.GitGate gate =
                        GateSupport.gate(context, project, each, null, seed(project, each, out));
                gate.initialize();
                final String behind = gate.refresh();
                if (!behind.isEmpty()) {
                    err.println("porelo: " + each.name() + ": " + behind);
                }
                final String commit = gate.readMain(into.resolve(each.name()));
                out.println(
                        "read      "
                                + each.name()
                                + " at "
                                + commit.substring(0, 12)
                                + " into "
                                + TaskRepositories.MOUNT
                                + "/"
                                + each.name());
                read.add(each.name());
                if (!secrets.check(each.name(), gate.mirror(), commit, null)) {
                    // The project refuses: the rest is not read, the start ends.
                    break;
                }
            } catch (RuntimeException ex) {
                err.println(
                        "porelo: repository '"
                                + each.name()
                                + "' is not read, and the task starts without it: "
                                + ex.getMessage());
                // Each repository's host for itself: the others may be at hosts this machine met.
                hostKeyHint(String.valueOf(ex.getMessage()), each, err);
                removePartial(into.resolve(each.name()), err);
            }
        }
        out.flush();
        err.flush();
        return java.util.List.copyOf(read);
    }

    /** Takes away what a failed read left half written, so the task never sees half a tree. */
    private static void removePartial(java.nio.file.Path partial, PrintWriter err) {
        if (!java.nio.file.Files.exists(partial, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> tree = java.nio.file.Files.walk(partial)) {
            for (final java.nio.file.Path path :
                    tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        } catch (java.io.IOException ex) {
            err.println("porelo: could not remove " + partial + ": " + ex.getMessage());
        }
    }

    /**
     * Returns the repository an empty mirror should be seeded from.
     *
     * <p>Only reached when neither {@code --upstream} nor the project names one. Standing in a
     * checkout is taken as meaning that checkout, so the common case needs no flag. It is printed
     * rather than assumed silently, and only committed history is copied - a bare clone has no
     * working tree.
     *
     * <p><strong>Only for the project's own repository.</strong> The checkout somebody is standing
     * in is the project's own - that is where {@code project.yml} is - so seeding a repository the
     * project merely names from it would fill that repository's mirror with somebody else's
     * history. A named repository with no upstream simply starts empty, which is what it is.
     *
     * @param project The project.
     * @param chosen The repository this task works on.
     * @param out Where to report.
     * @return Path of the work tree, or {@code null} when there is none.
     */
    @org.jspecify.annotations.Nullable
    private String seed(Project project, Repository chosen, PrintWriter out) {
        if (!chosen.name().equals(project.name())) {
            return null;
        }
        if (upstream != null
                || chosen.hasUpstream()
                || java.nio.file.Files.isDirectory(
                        GateSupport.mirror(context, project, chosen).resolve("objects"))) {
            // A mirror that exists is never re-seeded, so saying it would be seeded is a lie.
            return null;
        }
        // The organization's project's own repository is seeded from the clone its start judged.
        // That clone IS its history, and nothing should fetch it twice from the forge.
        final java.nio.file.Path clone =
                context.paths().projects().organizationClone(project.name());
        if (java.nio.file.Files.isDirectory(clone.resolve(".git"))) {
            out.println("seed      " + clone + " (the judged clone)");
            return clone.toString();
        }
        // A project of this machine alone: the checkout beside its file, wherever a person stands.
        final java.nio.file.Path file = ProjectSource.resolve(context, project.name()).file();
        final java.nio.file.Path local =
                file == null
                        ? null
                        : ownCheckout(
                                project.name(),
                                file.toAbsolutePath().normalize().resolveSibling("."));
        if (local == null) {
            return null;
        }
        out.println("seed      " + local + " (committed history only)");
        return local.toString();
    }

    /**
     * Returns the checkout a command runs in when it is the project's own repository, the one its
     * project file is in.
     *
     * <p>Any checkout it ran in was taken: through the daemon that is its home, and a home kept in
     * git gave the agent's mirror its whole history.
     *
     * @param project The project's name.
     * @param here Where the command runs.
     * @return The checkout's top level, or {@code null} when it is not the project's own.
     */
    static java.nio.file.@org.jspecify.annotations.Nullable Path ownCheckout(
            String project, java.nio.file.Path here) {
        final java.nio.file.Path local =
                org.fuin.porelo.gate.LocalRepository.topLevel(
                        new org.fuin.porelo.core.process.ProcessCommandRunner(), here);
        if (local == null) {
            return null;
        }
        try {
            return org.fuin.porelo.core.project.ProjectReader.read(local.resolve("project.yml"))
                            .name()
                            .equals(project)
                    ? local
                    : null;
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    /**
     * Clones the mirror into the container's workspace.
     *
     * @param runner Runs it inside the container.
     * @param workspace What to clone and where from.
     * @param container Container name.
     * @param environment What the clone needs - the gate's URL and its token.
     * @param out Where progress is reported.
     * @param err Where a failure is reported.
     */
    boolean prepareWorkspace(
            TaskRunner runner,
            TaskWorkspace workspace,
            String container,
            java.util.Map<String, String> environment,
            PrintWriter out,
            PrintWriter err) {

        final java.nio.file.Path log =
                context.paths().tasks().containerState(container).resolve("workspace.log");
        final int code =
                runner.execute(
                        container,
                        environment,
                        workspace.cloneCommand(),
                        log,
                        java.time.Duration.ofMinutes(5));
        String problem = null;
        String said = "";
        try {
            said =
                    java.nio.file.Files.isRegularFile(log)
                            ? java.nio.file.Files.readString(log)
                            : "";
            problem = TaskWorkspace.fetchProblem(said);
        } catch (java.io.IOException ex) {
            // Unread, nothing is said about the fetch; the outcome below still is.
        }
        if (problem != null) {
            err.println("porelo: the workspace starts without its remote: " + problem);
            err.flush();
        }
        if (code == 0) {
            out.println("workspace " + TaskWorkspace.MOUNT + " ready");
        } else {
            err.println("porelo: could not prepare the workspace, see " + log);
            err.flush();
        }
        out.flush();
        return code == 0;
    }

    /** The name a new task of that name gets, or "" when it is too long to get one. */
    private String newName(org.fuin.porelo.core.project.Project project, String task) {
        try {
            return context.tasks().containerName(project, task);
        } catch (IllegalArgumentException tooLong) {
            return "";
        }
    }
}
