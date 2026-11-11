package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.runtime.ContainerName;
import org.fuin.porelo.runtime.ContainerSummary;
import org.fuin.porelo.runtime.Podman.ContainerState;
import org.fuin.porelo.wire.Directories;
import org.jspecify.annotations.Nullable;

/**
 * Stopping and resuming a task, decided once for every caller.
 *
 * <p>The CLI renders what these return and the daemon serializes it, the way both already share
 * {@link TaskInventory}. The CLI and the interface have to reach identical behavior through the
 * same calls, and these two operations are where a second implementation would hurt most: they
 * refuse things. A refusal that exists in one caller and not the other is a task removed with work
 * in it.
 *
 * <p>Nothing here prints. What happened is returned, including the parts that read as failures, so
 * that a caller with no terminal can act on them.
 */
public final class TaskControl {

    /** What became of the request. */
    public enum Outcome {

        /** The name is not one Porelo created, so this refused to touch it. */
        NOT_A_TASK,

        /** There is no such task; stopping something already gone is not an error. */
        NOTHING_TO_STOP,

        /** Stopped, and removed when that was asked for. */
        STOPPED,

        /** Removal refused: the workspace holds work that reached nothing else. */
        HOLDS_WORK,

        /** Removal refused: it is stopped and nothing recorded what it holds. */
        NOTHING_KNOWS,

        /** Rescue refused: the task is stopped, so nothing can push from it. */
        RESCUE_NEEDS_IT_RUNNING,

        /** Rescue attempted and the work did not arrive; nothing was removed. */
        RESCUE_FAILED,

        /** Removed: the container is gone and its helpers were already down. */
        REMOVED,

        /**
         * Removal refused: the task is running.
         *
         * <p>Stopping ends an agent's session and removing destroys its work. Somebody who asked
         * for the second did not ask for the first, and until these were two verbs they could not
         * say so - {@code stop --purge} did both whatever the task was doing.
         */
        STILL_RUNNING,

        /** Started again, with the workspace it had. */
        RESUMED,

        /** Already up, so there was nothing to resume. */
        ALREADY_RUNNING,

        /** No container of that name to resume. */
        NO_CONTAINER,

        /** Resumed, but the task recorded no helpers for this version to start. */
        NO_HELPERS_RECORDED,

        /** The container came up but not every helper did. */
        HELPERS_INCOMPLETE,

        /**
         * The task was started before this machine restarted, so it cannot be started again.
         *
         * <p>Its state directory is under {@code $XDG_RUNTIME_DIR}, which the system clears when
         * the user's last session ends - and the container bind-mounts the broker socket from
         * inside it. podman refuses to start a container whose mount source is gone, with a message
         * about {@code crun} and a path, which says nothing about what happened or what to do. The
         * workspace is still in the container and can be copied out.
         */
        PREDATES_RESTART,

        /**
         * A reboot emptied its runtime directory, its state was saved, and bringing it back needs
         * its two tokens from the vault, which is locked. Unlocking it and starting again brings it
         * back.
         */
        NEEDS_VAULT,

        /**
         * A reboot emptied its runtime directory, and its tokens were never kept - the vault was
         * locked or absent when it started - so the container's credentials cannot be matched
         * again.
         */
        TOKENS_NOT_KEPT
    }

    /**
     * What stopping a task did.
     *
     * @param outcome What became of the request.
     * @param work What the workspace holds that never reached the gate, or {@code null}.
     * @param rescuedRef Ref the work was pushed to, or {@code null} when none was.
     * @param removed Whether the container was removed as well as stopped.
     * @param helpers How many helpers were running when this started.
     * @param surviving Containers and helpers that survived, named as container or pid and command.
     * @param state The task's state directory, or {@code null} once it was removed.
     * @param detail Why a rescue could not happen, or {@code null}. Three cases with three
     *     different fixes: a task with no gate ref, one that pushes straight to its upstream, and a
     *     push that did not arrive.
     * @param discarded How many paths the container had that its image did not - what the agent
     *     installed inside it - counted only when it was removed, because that is when they stopped
     *     existing. Zero otherwise.
     */
    public record Stopped(
            Outcome outcome,
            @Nullable String work,
            @Nullable String rescuedRef,
            boolean removed,
            int helpers,
            List<String> surviving,
            @Nullable Path state,
            @Nullable String detail,
            long discarded) {

        /**
         * @return Whether anything is left running that should not be.
         */
        public boolean clean() {
            return surviving.isEmpty();
        }
    }

    /**
     * What resuming a task did.
     *
     * @param outcome What became of the request.
     * @param started How many helpers are up.
     * @param recorded How many the task recorded.
     * @param imageDrift The image name when it has been rebuilt since, otherwise {@code null}.
     * @param state The task's state directory, or {@code null} when it has none.
     * @param problems What could not be started, empty when everything did.
     */
    public record Resumed(
            Outcome outcome,
            int started,
            int recorded,
            @Nullable String imageDrift,
            @Nullable Path state,
            List<String> problems,
            boolean restored,
            String refreshed) {

        /**
         * Constructor for a resume that brought nothing in from the task's source.
         *
         * @param outcome What became of the request.
         * @param started How many helpers were started.
         * @param recorded How many were recorded.
         * @param imageDrift The image rebuilt since the task started, or {@code null}.
         * @param state The task's state directory, or {@code null}.
         * @param problems What went wrong.
         * @param restored Whether what a restart took was restored.
         */
        public Resumed(
                Outcome outcome,
                int started,
                int recorded,
                @Nullable String imageDrift,
                @Nullable Path state,
                List<String> problems,
                boolean restored) {
            this(outcome, started, recorded, imageDrift, state, problems, restored, "");
        }

        /**
         * Constructor for a resume that found its runtime directory where it left it.
         *
         * @param outcome What became of the request.
         * @param started How many helpers were started.
         * @param recorded How many were recorded.
         * @param imageDrift The image rebuilt since the task started, or {@code null}.
         * @param state The task's state directory, or {@code null}.
         * @param problems What went wrong.
         */
        public Resumed(
                Outcome outcome,
                int started,
                int recorded,
                @Nullable String imageDrift,
                @Nullable Path state,
                List<String> problems) {
            this(outcome, started, recorded, imageDrift, state, problems, false);
        }
    }

    private final PoreloContext context;

    /** The host a removal tells; another one only in a test. */
    java.util.function.Function<PoreloContext, HostPerson> hosts = HostPerson::new;

    /**
     * Constructor with the context to act through.
     *
     * @param context Where podman and the paths come from.
     */
    public TaskControl(PoreloContext context) {
        this.context = context;
    }

    /**
     * Stops a task and the helpers beside it, keeping everything it holds.
     *
     * @param container Container name.
     * @return What happened.
     */
    public Stopped stop(String container) {
        return stop(container, false, false, false);
    }

    /**
     * Removes a task: its container, its state, and the workspace inside it.
     *
     * <p>Refuses a running task rather than stopping it as a side effect. Stopping ends an agent's
     * session and removing destroys its work; somebody who typed one of those did not ask for the
     * other, and the two used to be the same command with a flag.
     *
     * @param container Container name.
     * @param rescue Push what the workspace holds to a ref of its own first.
     * @param force Stop it if it runs, and discard work that never reached the gate.
     * @return What happened.
     */
    public Stopped remove(String container, boolean rescue, boolean force) {
        if (!ContainerName.isTask(container)) {
            return new Stopped(Outcome.NOT_A_TASK, null, null, false, 0, List.of(), null, null, 0);
        }
        final TaskReservations reservations = new TaskReservations(context);
        try {
            return reservations.mutate(
                    container,
                    () -> {
                        final TaskReservations.Owner pending = reservations.unmade(container);
                        if (pending == null) return removeExisting(container, rescue, force);
                        context.tasks().reapOrphans(container);
                        new TaskConversations(context)
                                .retire(context, container, pending.project());
                        deleteTree(context.paths().tasks().containerState(container));
                        new TaskState(context).forget(container);
                        new TaskSession(context).forget(container);
                        new TaskSecrets(context).forget(container);
                        new Mailbox(context.paths().messaging().mailbox(container)).delete();
                        new TaskGuide(context.paths(), container).delete();
                        new TaskRepositories(context.paths(), container).delete();
                        reservations.retire(container);
                        return new Stopped(
                                Outcome.STOPPED, null, null, true, 0, List.of(), null, null, 0);
                    });
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Cannot remove an unfinished task", ex);
        }
    }

    private Stopped removeExisting(String container, boolean rescue, boolean force) {
        if (context.recognizesTasks() && context.recordedId(container) == null) {
            return new Stopped(Outcome.NOT_A_TASK, null, null, false, 0, List.of(), null, null, 0);
        }
        // Not when rescuing: pushing what the workspace holds needs the container up, so refusing a
        // running one here would make --rescue impossible to use.
        if (ContainerName.isTask(container) && !force && !rescue) {
            final String recorded = context.recordedId(container);
            final boolean running =
                    context.podman().stateOf(recorded == null ? container : recorded)
                            == ContainerState.UNCONFIRMED;
            if (running) {
                // What it holds is asked FIRST, and the order is the whole point. Rescuing needs
                // the container up, so a caller told "it is running" would stop it, be told "it
                // holds work", and have to start it again to save that work. Asking now puts the
                // question while the answer can still be acted on.
                final String work = heldBy(container).phrase();
                if (work != null) {
                    return new Stopped(
                            Outcome.HOLDS_WORK,
                            work,
                            null,
                            false,
                            0,
                            List.of(),
                            context.paths().tasks().containerState(container),
                            null,
                            0);
                }
                return new Stopped(
                        Outcome.STILL_RUNNING, null, null, false, 0, List.of(), null, null, 0);
            }
        }
        return stop(container, true, rescue, force);
    }

    /**
     * Stops a task, and removes it when asked.
     *
     * @param container Container name.
     * @param purge Remove the container as well as stopping it.
     * @param rescue Push what the workspace holds to a ref of its own first.
     * @param force Remove it even though work would be lost.
     * @return What happened.
     */
    private Stopped stop(String container, boolean purge, boolean rescue, boolean force) {

        if (!ContainerName.isTask(container)) {
            return new Stopped(Outcome.NOT_A_TASK, null, null, false, 0, List.of(), null, null, 0);
        }
        try {
            return stopRecorded(container, purge, rescue, force, null);
        } catch (org.fuin.porelo.runtime.NotATaskException ex) {
            // Named like a task, no record of it: one never made here, or removed already. Not a
            // failure - the same answer as for a name of no task at all, and nothing done to a
            // container of that name.
            return new Stopped(Outcome.NOT_A_TASK, null, null, false, 0, List.of(), null, null, 0);
        }
    }

    Stopped stop(String container, ContainerState before) {
        return stopRecorded(container, false, false, false, before);
    }

    /**
     * Has an online task's gate forget its ref once the task is removed: it was passed on as it was
     * pushed, and a new task of the same name would otherwise have its plain push refused with
     * "fetch first". Never fatal: the next start of that name forgets it too.
     *
     * @param task The removed task, as podman listed it.
     */
    private void forgetOnline(final ContainerSummary task, final TaskIdentity identity) {
        if (task.project() == null) {
            return;
        }
        try {
            final org.fuin.porelo.core.project.Project project =
                    GateSupport.byName(context, task.project());
            if (project.securityClass() != org.fuin.porelo.core.project.SecurityClass.ONLINE) {
                return;
            }
            final org.fuin.porelo.core.project.Repository repository =
                    GateSupport.repository(
                            project,
                            task.repository() == null || task.repository().isBlank()
                                    ? null
                                    : task.repository());
            GateSupport.gate(context, project, repository, null, null).forget(identity.task());
        } catch (RuntimeException ex) {
            // Left for the next start of that name, which forgets it before the task clones.
        }
    }

    private static void forgetNote(Path state) {
        try {
            Files.deleteIfExists(state.resolve(UnhandedWork.FILE));
        } catch (java.io.IOException ex) {
            // The task stops regardless; a note that stays says what was asked last.
        }
    }

    private Stopped stopRecorded(
            String container,
            boolean purge,
            boolean rescue,
            boolean force,
            @Nullable ContainerState before) {
        try {
            final var reservations = new TaskReservations(context);
            final TaskReservations.Operation<Stopped> stop =
                    () -> stopClaimed(container, purge, rescue, force, before);
            return before == null
                    ? reservations.mutate(container, stop)
                    : reservations.mutateRecorded(container, stop);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Cannot reserve task removal", ex);
        }
    }

    private Stopped stopClaimed(
            String container,
            boolean purge,
            boolean rescue,
            boolean force,
            @Nullable ContainerState before)
            throws IOException {

        // A bulk stop has already matched these records to its runtime snapshot by id.
        final var identities = new TaskIdentities(context);
        final TaskIdentities.Result identified =
                before == null
                        ? identities.lookup(container, null)
                        : identities.retained(container);
        if (identified instanceof TaskIdentities.Refused refused
                && refused.reason() != TaskIdentities.Reason.MISSING) {
            throw new TaskReservations.ContainerNameTaken(container, "", "", null, null);
        }
        final TaskIdentity identity =
                identified instanceof TaskIdentities.Found found ? found.identity() : null;
        if (identity != null && !TaskReservations.matches(context.paths(), identity)) {
            throw new TaskReservations.ContainerNameTaken(container, "", "", null, null);
        }
        final Path state = context.paths().tasks().containerState(container);
        final String recorded = context.recordedId(container);
        if (context.recognizesTasks() && recorded == null) {
            return new Stopped(Outcome.NOT_A_TASK, null, null, false, 0, List.of(), null, null, 0);
        }
        final String id = recorded == null ? container : recorded;
        final java.util.Optional<ContainerSummary> summary =
                purge
                        ? context.podman().poreloTasks().stream()
                                .filter(task -> task.name().equals(container))
                                .findFirst()
                        : java.util.Optional.empty();
        final ContainerState initial = before == null ? context.podman().stateOf(id) : before;
        final boolean known =
                recorded != null || initial != ContainerState.ABSENT || Files.isDirectory(state);
        final boolean running = initial == ContainerState.UNCONFIRMED;

        // After a reboot the runtime directory is gone, and the note saved from it is what knows
        // what the task held: without it every such task answered NOTHING_KNOWS, and a guard that
        // must always be forced is none.
        final UnhandedWork.Held held =
                running
                        ? heldBy(container)
                        : UnhandedWork.read(
                                Files.isDirectory(state)
                                        ? state
                                        : context.paths().tasks().taskRecord(container));
        final String work = held.phrase();

        // "nobody looked" rather than "held nothing": only the first is a reason to refuse.
        if (purge && initial != ContainerState.ABSENT && !running && !held.readable() && !force) {
            return new Stopped(
                    Outcome.NOTHING_KNOWS, null, null, false, 0, List.of(), state, null, 0);
        }
        if (work != null && purge && !rescue && !force) {
            return new Stopped(Outcome.HOLDS_WORK, work, null, false, 0, List.of(), state, null, 0);
        }
        if (purge && rescue && running && !held.readable() && !force) {
            // Nothing said it holds work because nobody could ask: removing it now would be
            // removing it unrescued.
            return new Stopped(
                    Outcome.RESCUE_FAILED,
                    null,
                    null,
                    false,
                    0,
                    List.of(),
                    state,
                    "what the workspace holds could not be asked, so nothing was removed",
                    0);
        }
        String rescued = null;
        if (work != null && rescue) {
            if (!running) {
                return new Stopped(
                        Outcome.RESCUE_NEEDS_IT_RUNNING,
                        work,
                        null,
                        false,
                        0,
                        List.of(),
                        state,
                        null,
                        0);
            }
            final Rescue attempt = rescue(container);
            if (attempt.ref() == null) {
                return new Stopped(
                        Outcome.RESCUE_FAILED,
                        work,
                        null,
                        false,
                        0,
                        List.of(),
                        state,
                        attempt.problem(),
                        0);
            }
            rescued = attempt.ref();
        }

        // The session an attached agent was keeping, read while there is still a container to ask -
        // so starting the task again continues the conversation. A removal has no next start to
        // continue.
        if (running && !purge) {
            new TaskSession(context).rememberAttached(container);
        }

        // Counted first: stopping the container fires the poststop hook, which reaps the helpers
        // and deletes their pid files, leaving nothing to count afterwards.
        final List<ProcessHandle> helpers = TaskLifecycle.running(state);
        // What they started, taken before the stop as the stop takes it: a child whose parent is
        // gone is no longer its descendant, and a forced removal kills those that outlive SIGTERM.
        final java.util.Set<ProcessHandle> started = new java.util.LinkedHashSet<>();
        if (force) helpers.stream().flatMap(ProcessHandle::descendants).forEach(started::add);

        // Counted before the container goes, because afterwards there is nothing left to ask.
        final long discarded = purge ? context.podman().addedPaths(container) : 0;

        if (purge) {
            context.podman().remove(id);
        } else {
            context.podman().stop(id);
        }

        // Taken again: a helper may have started another process while the container went.
        if (force) helpers.stream().flatMap(ProcessHandle::descendants).forEach(started::add);
        TaskLifecycle.stopHelpers(state);
        // Its porelo-mcp went with the helpers; the daemon socket opened for it and the socket it
        // bound go here, which no hook knows of.
        McpWiring.stop(context, container);

        if (!known) {
            return new Stopped(
                    Outcome.NOTHING_TO_STOP, null, null, false, 0, List.of(), null, null, 0);
        }

        // Waited for first: termination is asynchronous, so checking straight away reports live
        // processes that are already on their way out.
        final List<ProcessHandle> signalled = new ArrayList<>(started);
        signalled.addAll(helpers);
        awaitEnd(signalled);
        if (force) {
            // What ignored SIGTERM gets SIGKILL, which it cannot ignore.
            final List<ProcessHandle> stubborn =
                    signalled.stream().filter(TaskLifecycle::living).toList();
            stubborn.forEach(ProcessHandle::destroyForcibly);
            awaitEnd(stubborn);
        }
        final List<String> surviving = new ArrayList<>();
        signalled.stream()
                .filter(TaskLifecycle::living)
                .map(alive -> alive.pid() + " " + alive.info().command().orElse("?"))
                .forEach(surviving::add);
        final ContainerState observed = context.podman().stateOf(id);
        final boolean gone = observed == ContainerState.ABSENT;
        if (!gone && (purge || observed != ContainerState.TERMINAL)) {
            surviving.add(
                    "container " + container + (purge ? " still exists" : " stop unconfirmed"));
        }
        if (!purge) {
            if (!gone && observed != ContainerState.TERMINAL) {
                forgetNote(state);
                forgetNote(context.paths().tasks().taskRecord(container));
            } else if (running) {
                if (held.readable()) UnhandedWork.note(state, held);
                else forgetNote(state);
                new TaskState(context).save(container);
            }
        }
        // Out of its rooms where its next start brings it back, or a room counts it as running. Not
        // in a bulk stop: a panic asks no transport anything.
        if (!purge
                && before == null
                && identity != null
                && (gone || observed == ContainerState.TERMINAL)) {
            new TaskConversations(context)
                    .leave(
                            context,
                            container,
                            identity.project(),
                            new java.io.PrintWriter(System.err, true));
        }
        final boolean removed = purge && gone;
        if (removed) {
            closeJournal(container);
            new TaskUsage(context).archive(container);
            if (identity != null) summary.ifPresent(task -> forgetOnline(task, identity));
            // Read before the records that say it go: the project a task belongs to is not in its
            // name, where both halves may hold hyphens.
            final String project = identity == null ? null : identity.project();
            final TaskSecrets secrets = new TaskSecrets(context);
            secrets.forget(container);
            deleteTree(state);
            new TaskSession(context).forget(container);
            new TaskState(context).forget(container);
            // A vault that is locked keeps them until the next task started prunes them: a removal
            // is not refused for want of a passphrase.
            // The host is told whether or not its start reached it: the task's key in the vault
            // cannot say so while the vault is locked, and a record left behind refuses the next
            // start of the same name. A task the host never recorded is answered as such.
            final HostPerson host = hosts.apply(context);
            if (host.present() && identity != null) {
                try {
                    host.end(identity.task());
                } catch (final HostPerson.Refused ex) {
                    // The removal goes on; a host that cannot be told now drops the token when the
                    // certificate expires, and its words say what the next start of the name meets.
                    final String words = String.valueOf(ex.getMessage());
                    if (ex.code() != 65 || !words.contains(" is not recorded for ")) {
                        System.err.println(
                                "porelo: the host still records task "
                                        + identity.task()
                                        + ": "
                                        + words);
                    }
                }
            }
            // Out of its project's conversations: its account there ends with it.
            new TaskConversations(context).retire(context, container, project);
            // The mailbox outlives the container on purpose, so this is the one place it ends.
            try {
                new Mailbox(context.paths().messaging().mailbox(container)).delete();
                new TaskGuide(context.paths(), container).delete();
                new TaskRepositories(context.paths(), container).delete();
            } catch (final java.io.IOException e) {
                throw new java.io.UncheckedIOException(
                        "Removed the task but could not delete its mailbox: " + container, e);
            }
            try {
                new TaskReservations(context).retire(container);
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException("Cannot retire task identity", ex);
            }
        }
        return new Stopped(
                Outcome.STOPPED,
                work,
                rescued,
                removed,
                helpers.size(),
                List.copyOf(surviving),
                removed || !Files.isDirectory(state) ? null : state,
                surviving.isEmpty() ? null : "still running: " + String.join(", ", surviving),
                removed ? discarded : 0);
    }

    /**
     * Describes what the agent has done and not handed back, or {@code null} when there is none.
     *
     * <p>Only answerable while the container runs, which is why it is asked before anything is
     * stopped.
     *
     * <p>Public because the end of an attached run asks it too, and for the same reason: a
     * workspace lives in the container's own writable layer, so removing the container destroys it.
     * Asking here and not there was the difference between {@code task stop --purge}, which
     * refuses, and walking out of a shell, which did not.
     *
     * @param container Container name.
     * @return What is held, or {@code null}.
     */
    public UnhandedWork.Held heldBy(String container) {
        return UnhandedWork.ask(context.podman(), container);
    }

    /**
     * Pushes what the agent never handed back, under a ref of its own.
     *
     * <p>Rescued work is not work an agent offered up, so it lands beside the reviewed ref rather
     * than in it. Only a gated task can be rescued: one that pushes straight to a real upstream has
     * no place to put unreviewed work.
     *
     * @param container Container name.
     * @return The ref it landed on, or {@code null} when it did not.
     */
    private Rescue rescue(String container) {

        final CommandResult reference =
                context.podman()
                        .ask(
                                container,
                                Map.of(),
                                List.of("sh", "-c", "printf '%s' \"$PORELO_TASK_REF\""));
        if (!reference.successful()) {
            return new Rescue(
                    null, "the task has no gate ref, so there is nowhere to rescue it to");
        }
        final String taskRef = reference.trimmedOutput();
        if (!taskRef.startsWith("refs/porelo/incoming/")) {
            return new Rescue(
                    null,
                    "this task pushes straight to its upstream, so unreviewed work"
                            + " cannot be rescued without publishing it");
        }
        final String rescueRef = taskRef + org.fuin.porelo.gate.GitGate.RESCUED;
        final CommandResult pushed =
                context.podman()
                        .ask(
                                container,
                                Map.of("PORELO_TASK_REF", rescueRef),
                                TaskWorkspace.pushCommand());
        // The output is checked as well as the exit code: this answer decides whether a container
        // holding the only copy of something is removed, so "it did not fail" is not enough.
        if (!pushed.successful() || pushed.trimmedOutput().contains("nothing to push")) {
            return new Rescue(
                    null,
                    "could not push the work: "
                            + (pushed.trimmedOutput().isEmpty()
                                    ? pushed.standardError().strip()
                                    : pushed.trimmedOutput()));
        }
        return new Rescue(rescueRef, null);
    }

    /** An attempted rescue: the ref it landed on, or why it did not. */
    private record Rescue(@Nullable String ref, @Nullable String problem) {}

    /** Waits up to 2 s until none of the processes is living. A zombie counts as ended. */
    private static void awaitEnd(List<ProcessHandle> processes) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (processes.stream().anyMatch(TaskLifecycle::living) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void deleteTree(Path directory) {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (java.io.IOException ex) {
                                    // Best effort: what is left is a directory the runtime clears
                                    // at logout.
                                }
                            });
        } catch (java.io.IOException ex) {
            // Same.
        }
    }

    /**
     * Starts a stopped task again, with the workspace it already has.
     *
     * <p>The container is started rather than rebuilt, so the agent's work, its branch and its
     * uncommitted changes are all still there. What has to be started again is everything that
     * lives on the host - the credential proxy, the git gate, the clearance watcher - none of which
     * can be reconstructed from the container, which is why each was written down when the task
     * first ran.
     *
     * @param container Container name.
     * @return What happened.
     */
    /** What labelling a task did. */
    public enum Labelled {

        /** The caption was written down. */
        LABELLED,

        /** The caption was removed, because it was blank or absent. */
        CLEARED,

        /** No such task, or its state directory is gone. */
        NOT_A_TASK,

        /** It exists but recorded nothing about itself - started by an older Porelo. */
        NOT_RECORDED,

        /** The profile could not be written. */
        FAILED
    }

    /**
     * Gives a task a changeable caption, beside the name that identifies it.
     *
     * <p><strong>Nothing about the identity moves.</strong> The container keeps its name, the gate
     * keeps its ref, the workspace and the logs stay where they are. A rename would have to move
     * all four - including a ref that may have unreviewed pushes behind it - which is why what was
     * asked for is a caption and not a rename.
     *
     * <p>Written into the task's own profile, so it survives a resume and outlives the container,
     * and so it is exactly as durable as the mode and the prompt beside it.
     *
     * @param container Container name.
     * @param caption The caption, or {@code null} / blank to remove one.
     * @return What happened.
     */
    public Labelled label(String container, @Nullable String caption) {

        if (!ContainerName.isTask(container)) {
            return Labelled.NOT_A_TASK;
        }
        final Path state = context.paths().tasks().containerState(container);
        if (!Files.isDirectory(state)) {
            return Labelled.NOT_A_TASK;
        }
        final org.fuin.porelo.wire.TaskProfile profile =
                org.fuin.porelo.wire.TaskProfile.readFrom(state);
        if (profile == null) {
            // Nothing to change without inventing the rest of the profile, and a profile invented
            // here would claim a mode and a start time nobody recorded.
            return Labelled.NOT_RECORDED;
        }
        try {
            profile.withLabel(caption).writeTo(state);
            new TaskState(context).save(container);
        } catch (java.io.IOException ex) {
            return Labelled.FAILED;
        }
        return caption == null || caption.isBlank() ? Labelled.CLEARED : Labelled.LABELLED;
    }

    /**
     * Whether a task a restart took down lost a token its saved helpers need, so it cannot come
     * back whole.
     *
     * @param saved The helpers its start saved.
     * @param tokens What the vault kept for it.
     * @return {@code true} when its gate or its vault proxy would start without its token.
     */
    static boolean tokensLost(TaskHelpers saved, TaskSecrets.Tokens tokens) {
        final boolean gated = saved.helpers().stream().anyMatch(h -> "gate".equals(h.name()));
        final boolean proxied = saved.helpers().stream().anyMatch(h -> "vault".equals(h.name()));
        return gated && tokens.gate() == null || proxied && tokens.provider() == null;
    }

    public Resumed resume(String container) {

        if (!ContainerName.isTask(container)) {
            return new Resumed(Outcome.NOT_A_TASK, 0, 0, null, null, List.of());
        }
        // One resume of a task at a time, across the daemon and 'task attach': both passed the "is
        // it running" check, both started the helpers, and the second set overwrote the first's pid
        // files and was left running.
        final Path lock =
                context.paths()
                        .tasks()
                        .containerState(container)
                        .resolveSibling(container + ".resume.lock");
        try (var reservation = new TaskReservations(context).resume(container).orElse(null)) {
            return FileLocks.holding(lock, () -> resumeHeld(container));
        } catch (final java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private Resumed resumeHeld(String container) {
        final TaskIdentities.Result identity = new TaskIdentities(context).lookup(container, null);
        if (identity instanceof TaskIdentities.Refused refused
                && refused.reason() != TaskIdentities.Reason.MISSING) {
            throw new TaskReservations.ContainerNameTaken(container, "", "", null, null);
        }
        if (identity instanceof TaskIdentities.Found found
                && !new TaskIdentities(context).matches(found.identity())) {
            throw new TaskReservations.ContainerNameTaken(container, "", "", null, null);
        }
        final Path state = context.paths().tasks().containerState(container);
        TaskHelpers recorded = TaskHelpers.readFrom(state);
        Path logs = Files.isDirectory(state) ? state : null;

        if (context.podman().idOf(container).isEmpty()) {
            return new Resumed(Outcome.NO_CONTAINER, 0, 0, null, null, List.of());
        }

        // A container that is already up has nothing to resume, and starting its helpers a second
        // time is not harmless: each one writes the same pid file, so the earlier process is no
        // longer named by anything and nothing reaps it. Measured - two resumes of a running task
        // left two 'shield watch' processes re-parented to init, which 'task stop' then reported as
        // "4 of 4 stopped" while they went on running. Symmetrical with stopping a task that has
        // already gone: doing it twice is not an error, it is simply already done.
        if (context.podman().poreloTasks().stream()
                .anyMatch(task -> task.name().equals(container) && task.running())) {
            return new Resumed(
                    Outcome.ALREADY_RUNNING, 0, recorded.helpers().size(), null, logs, List.of());
        }

        boolean restored = false;
        if (!Files.isDirectory(state)) {
            // The whole directory is gone, which happens when the machine restarts. A task whose
            // state was saved is brought back from it; one started before that was kept still
            // cannot be - answered before podman is asked, so the operator gets a sentence about
            // what happened rather than crun's about a path.
            final TaskState saved = new TaskState(context);
            if (!saved.saved(container)) {
                return new Resumed(Outcome.PREDATES_RESTART, 0, 0, null, null, List.of());
            }
            final TaskHelpers savedHelpers =
                    TaskHelpers.readFrom(context.paths().tasks().taskRecord(container));
            final boolean gated =
                    savedHelpers.helpers().stream()
                            .anyMatch(helper -> "gate".equals(helper.name()));
            final boolean proxied =
                    savedHelpers.helpers().stream()
                            .anyMatch(helper -> "vault".equals(helper.name()));
            TaskSecrets.Tokens tokens = new TaskSecrets.Tokens(null, null);
            if (gated || proxied) {
                final java.util.Optional<TaskSecrets.Tokens> kept =
                        new TaskSecrets(context).read(container);
                if (kept.isEmpty()) {
                    return new Resumed(
                            Outcome.NEEDS_VAULT,
                            0,
                            savedHelpers.helpers().size(),
                            null,
                            null,
                            List.of());
                }
                tokens = kept.get();
                if (tokensLost(savedHelpers, tokens)) {
                    return new Resumed(
                            Outcome.TOKENS_NOT_KEPT,
                            0,
                            savedHelpers.helpers().size(),
                            null,
                            null,
                            List.of());
                }
            }
            try {
                saved.restore(container);
                // A mount whose source is gone fails the container's start: the screen's directory
                // was in the runtime directory the restart emptied.
                ScreenFile.prepare(state);
                if (tokens.provider() != null) {
                    final Path file = state.resolve(TaskSecrets.PROVIDER_FILE);
                    Files.writeString(
                            file, tokens.provider(), java.nio.charset.StandardCharsets.UTF_8);
                    Files.setPosixFilePermissions(
                            file,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                }
                // The other credentials' tokens too: the container still holds them in its
                // environment.
                if (!tokens.routes().isEmpty()) {
                    final Path routes =
                            Directories.create(state.resolve(TaskSecrets.ROUTES_DIRECTORY));
                    Files.setPosixFilePermissions(
                            routes,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                    for (final java.util.Map.Entry<String, String> route :
                            tokens.routes().entrySet()) {
                        final Path file = routes.resolve(route.getKey() + ".token");
                        Files.writeString(
                                file, route.getValue(), java.nio.charset.StandardCharsets.UTF_8);
                        Files.setPosixFilePermissions(
                                file,
                                java.nio.file.attribute.PosixFilePermissions.fromString(
                                        "rw-------"));
                    }
                }
            } catch (IOException ex) {
                return new Resumed(
                        Outcome.HELPERS_INCOMPLETE,
                        0,
                        savedHelpers.helpers().size(),
                        null,
                        null,
                        List.of("could not put back what the restart took: " + ex.getMessage()));
            }
            recorded = withGateToken(TaskHelpers.readFrom(state), tokens.gate());
            logs = state;
            restored = true;
        }

        new TaskDone(context.paths()).clear(container);
        final List<String> problems = new java.util.ArrayList<>();
        try {
            recorded = deferredBuilds(recorded, container);
        } catch (IOException ex) {
            problems.add("could not prepare the deferred builds reader: " + ex.getMessage());
        }
        if (recorded.helpers().isEmpty()) {
            context.podman().start(container);
            context.tasks().trustCertificates(container);
            return new Resumed(
                    Outcome.NO_HELPERS_RECORDED, 0, 0, null, logs, List.copyOf(problems), restored);
        }

        final String drift = imageDrift(container);

        // The proxy's socket is mounted into the container, and a mount is bound to the file that
        // existed when the container started. Starting the container first therefore binds it to
        // the socket of the previous run, which the resumed proxy then replaces: measured, the
        // container held a deleted inode and every request through it went nowhere, while the same
        // request from the host was answered.
        int started = start(recorded, TaskHelpers.BEFORE, 0, state, problems);
        awaitHelpers(recorded, state);

        context.podman().start(container);
        context.tasks().trustCertificates(container);
        context.tasks().startScreenWriter(container);

        final long pid = context.podman().pidOf(container).orElse(0L);
        if (pid <= 0) {
            problems.add("the container did not come up; see " + state);
            return new Resumed(
                    Outcome.HELPERS_INCOMPLETE,
                    started,
                    recorded.helpers().size(),
                    drift,
                    logs,
                    List.copyOf(problems));
        }

        started += start(recorded, TaskHelpers.AFTER, pid, state, problems);
        // What the source gained while the task was down reaches its gate now, and its agent hears
        // of it; before, only a new task's start brought it in.
        String brought = "";
        if (recorded.helpers().stream().anyMatch(helper -> "gate".equals(helper.name()))) {
            final TaskRefresh.Result refreshed = new TaskRefresh(context).refresh(container);
            if (refreshed.outcome() == TaskRefresh.Outcome.FAILED) {
                problems.add(
                        "its repository could not be brought up to its source: "
                                + refreshed.detail());
            } else if (refreshed.outcome() == TaskRefresh.Outcome.MOVED) {
                brought = TaskRefresh.said(refreshed);
            }
        }
        return new Resumed(
                started == recorded.helpers().size() ? Outcome.RESUMED : Outcome.HELPERS_INCOMPLETE,
                started,
                recorded.helpers().size(),
                drift,
                logs,
                List.copyOf(problems),
                restored,
                brought);
    }

    /** A stored token enables the reader a previous start could only defer. */
    private TaskHelpers deferredBuilds(final TaskHelpers recorded, final String container)
            throws IOException {
        if (recorded.helpers().stream()
                .anyMatch(helper -> TaskBuilds.HELPER.equals(helper.name()))) {
            return recorded;
        }
        final TaskBuilds builds = new TaskBuilds(context.paths().tasks());
        final MissingCredential missing = builds.view(container).credential();
        final List<String> command = builds.deferred(container);
        if (missing == null
                || !"builds".equals(missing.purpose())
                || command.isEmpty()
                || context.readableCredentials()
                        .filter(entries -> entries.containsKey(missing.credential()))
                        .isEmpty()) {
            return recorded;
        }
        final List<TaskHelpers.Helper> helpers = new ArrayList<>(recorded.helpers());
        helpers.add(
                new TaskHelpers.Helper(
                        TaskBuilds.HELPER, command, java.util.Map.of(), TaskHelpers.AFTER));
        final TaskHelpers updated = new TaskHelpers(List.copyOf(helpers));
        updated.writeTo(context.paths().tasks().containerState(container));
        new TaskState(context).save(container);
        return updated;
    }

    /**
     * Gives the gate its token back, which the saved record leaves out and the vault kept.
     *
     * @param recorded The record as restored.
     * @param token The gate token, or {@code null} for a task without a gate.
     * @return The record to start from.
     */
    static TaskHelpers withGateToken(TaskHelpers recorded, @Nullable String token) {
        if (token == null) {
            return recorded;
        }
        final List<TaskHelpers.Helper> helpers = new ArrayList<>();
        for (final TaskHelpers.Helper helper : recorded.helpers()) {
            if ("gate".equals(helper.name())) {
                final java.util.Map<String, String> environment =
                        new java.util.LinkedHashMap<>(helper.environment());
                environment.put(TaskState.GATE_TOKEN, token);
                helpers.add(
                        new TaskHelpers.Helper(
                                helper.name(),
                                helper.command(),
                                java.util.Map.copyOf(environment),
                                helper.phase()));
            } else {
                helpers.add(helper);
            }
        }
        return new TaskHelpers(List.copyOf(helpers));
    }

    /**
     * Replaces the container pid a helper was started with, which belongs to the previous run.
     *
     * <p>The watcher takes it as {@code --pid}; the relay enters the container's network namespace
     * with {@code nsenter --target}. A relay resumed with the old pid entered a namespace that no
     * longer existed.
     *
     * @param command The recorded command.
     * @param option The option the pid follows.
     * @param pid The pid the container has now.
     * @return The command to start.
     */
    static List<String> withPid(List<String> command, String option, long pid) {
        final List<String> updated = new ArrayList<>(command);
        final int flag = updated.indexOf(option);
        if (flag >= 0 && flag + 1 < updated.size()) {
            updated.set(flag + 1, String.valueOf(pid));
        }
        return updated;
    }

    /**
     * Adds the flag that makes the proxy keep the token the container already holds.
     *
     * @param command The recorded command.
     * @return The command to start.
     */
    private static List<String> reusingToken(List<String> command) {
        if (command.contains("--reuse-token")) {
            return command;
        }
        final List<String> updated = new ArrayList<>(command);
        updated.add("--reuse-token");
        return updated;
    }

    /**
     * Starts the helpers of one phase.
     *
     * @param recorded What the task wrote down.
     * @param phase Which phase to start.
     * @param pid Container pid, for helpers that need it; ignored when zero.
     * @param state The task's state directory.
     * @param problems Collects what could not be started, for a caller with no terminal.
     * @return How many started.
     */
    private int start(
            TaskHelpers recorded, String phase, long pid, Path state, List<String> problems) {

        int started = 0;
        for (final TaskHelpers.Helper helper : recorded.helpers()) {
            if (!phase.equals(helper.phase())) {
                continue;
            }
            // One that is still alive is not started again: every helper of a given name writes the
            // same pid file, so a second one would leave the first named by nothing and reapable by
            // nothing. Counted as started, because it is running - which is what the caller is
            // about to report.
            if (TaskLifecycle.alive(state.resolve(helper.name() + ".pid"))) {
                started++;
                continue;
            }
            if (McpWiring.HELPER.equals(helper.name())) {
                // Not from its recorded command: its daemon socket is opened again, which the
                // project's file may now refuse, and its key goes on its standard input.
                final String container = state.getFileName().toString();
                final String problem =
                        McpWiring.resume(
                                context,
                                container,
                                projectOf(container),
                                () -> new TaskSecrets(context).mcpKey(container));
                if (problem == null) {
                    started++;
                } else {
                    problems.add(problem);
                }
                continue;
            }
            List<String> command = helper.command();
            if ("watcher".equals(helper.name()) && pid > 0) {
                command = withPid(command, "--pid", pid);
            }
            if ("relay".equals(helper.name()) && pid > 0) {
                command = withPid(command, "--target", pid);
            }
            if ("vault".equals(helper.name())) {
                command = reusingToken(command);
            }
            try {
                // A scope of its own, as at the first start: a task resumed through the daemon must
                // not hand its helpers to the daemon's control group.
                final ProcessBuilder builder =
                        new ProcessBuilder(
                                        org.fuin.porelo.core.process.Scope.around(
                                                "porelo "
                                                        + state.getFileName()
                                                        + " "
                                                        + helper.name(),
                                                command))
                                .redirectErrorStream(true)
                                .redirectOutput(
                                        ProcessBuilder.Redirect.appendTo(
                                                state.resolve(helper.name() + ".log").toFile()));
                builder.environment().putAll(helper.environment());
                builder.start();
                started++;
            } catch (IOException ex) {
                problems.add("could not start the " + helper.name() + ": " + ex.getMessage());
            }
        }
        return started;
    }

    /**
     * Waits for the helpers that must be up before the container to write their pid files.
     *
     * <p>Starting the container while the proxy is still binding its socket is the same failure as
     * starting it too early: the mount catches whatever is at the path at that moment.
     *
     * @param recorded What the task wrote down.
     * @param state The task's state directory.
     */
    private void awaitHelpers(TaskHelpers recorded, Path state) {
        final List<Path> expected =
                recorded.helpers().stream()
                        .filter(helper -> TaskHelpers.BEFORE.equals(helper.phase()))
                        .map(helper -> state.resolve(helper.name() + ".pid"))
                        .toList();
        for (int attempt = 0; attempt < 50; attempt++) {
            if (expected.stream().allMatch(Files::isRegularFile)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Returns whether the project's image has been rebuilt since this task was created.
     *
     * <p>The task deliberately keeps the image it has - everything installed in the container since
     * it started would be lost otherwise, which is the opposite of what resuming is for. The
     * operator is told rather than upgraded, because only they know whether the difference matters.
     *
     * @param container Container name.
     * @return The image name when it has drifted, otherwise {@code null}.
     */
    public @Nullable String imageDrift(String container) {
        return context.podman()
                .imageOf(container)
                .flatMap(
                        image ->
                                context.podman()
                                        .imageId(image[0])
                                        .filter(now -> !now.equals(image[1]))
                                        .map(now -> image[0]))
                .orElse(null);
    }

    /**
     * Opens again the MCP daemon socket of every task whose porelo-mcp still runs, as a restarted
     * {@code porelod} must: the one before closed them all, and each porelo-mcp connects to the
     * same path at its next call. One whose project allows it none now, or whose socket cannot be
     * opened, has its porelo-mcp stopped and its endpoint recorded as absent. One task's failure
     * leaves the others to be opened.
     *
     * @param opener Opens a socket, with the daemon's checks.
     * @return One line per task acted on, its name first.
     */
    public List<String> reopenMcpSockets(McpDaemonSockets.Opener opener) {
        final List<Path> states;
        try (java.util.stream.Stream<Path> listed = Files.list(context.paths().xdg().runtime())) {
            states =
                    listed.filter(
                                    state ->
                                            Files.isRegularFile(
                                                    state.resolve(McpWiring.RECORD),
                                                    java.nio.file.LinkOption.NOFOLLOW_LINKS))
                            .sorted()
                            .toList();
        } catch (IOException ex) {
            // No runtime directory: no task runs.
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        for (final Path state : states) {
            final String container = state.getFileName().toString();
            try {
                final String line =
                        McpWiring.reopen(context, container, projectOf(container), opener);
                if (line != null) {
                    lines.add(container + ": " + line);
                }
            } catch (RuntimeException ex) {
                lines.add(
                        container
                                + ": its MCP daemon socket was not opened again: "
                                + ex.getMessage());
            }
        }
        return List.copyOf(lines);
    }

    /**
     * Returns the project a task was started for, as its own records say, or {@code null} when none
     * is left.
     *
     * @param container The task.
     * @return The project's name, or {@code null}.
     */
    @org.jspecify.annotations.Nullable
    String projectOf(String container) {
        for (final java.nio.file.Path record :
                List.of(
                        context.paths().tasks().containerState(container),
                        context.paths().tasks().taskRecord(container))) {
            final java.nio.file.Path file = record.resolve("sidecar.json");
            try {
                if (java.nio.file.Files.isRegularFile(file)) {
                    return org.fuin.porelo.wire.Sidecar.readFrom(file).project();
                }
            } catch (java.io.IOException | RuntimeException ex) {
                // An unreadable record names no project; the next one may.
            }
        }
        return null;
    }

    /**
     * Keeps a removed task's clearance journal, under a name no later task will read.
     *
     * <p>A container name no longer carries a timestamp, so the next task of the same project and
     * name gets the same one - and a watcher reads its journal back so that a resumed task is not
     * asked twice. Left in place, the journal handed every decision of a removed task to a new one:
     * measured, when a grant from an earlier run left an address open that the new task never asked
     * for. Renamed rather than deleted, because it is an audit record.
     *
     * @param container The removed task's container.
     */
    private void closeJournal(String container) {
        final Path journal = context.paths().tasks().clearanceJournal(container);
        if (!Files.isRegularFile(journal)) {
            return;
        }
        try {
            Files.move(
                    journal,
                    journal.resolveSibling(
                            container + ".removed-" + System.currentTimeMillis() + ".jsonl"));
        } catch (java.io.IOException ex) {
            // Kept where it is: the next task of this name then starts with its decisions, which is
            // what happened before this existed - worse, but nothing is lost.
        }
    }
}
