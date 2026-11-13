package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.fuin.porelo.runtime.ContainerSummary;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.Sidecar;
import org.jspecify.annotations.Nullable;

/**
 * Claims actual names before task state is made. The account name lock precedes the vault lock;
 * vault callbacks read committed claims without taking the name lock. A lease belongs to its
 * calling thread and never holds the account lock while an image or helper starts.
 */
public final class TaskReservations {
    private final PoreloContext context;

    enum Publication {
        BEFORE,
        AFTER
    }

    @FunctionalInterface
    interface Fault {
        void at(Publication point) throws IOException;
    }

    private final Fault fault;

    private static final ThreadLocal<Map<Path, String>> OWNED =
            ThreadLocal.withInitial(HashMap::new);

    public TaskReservations(PoreloContext context) {
        this(context, point -> {});
    }

    TaskReservations(PoreloContext context, Fault fault) {
        this.context = context;
        this.fault = fault;
    }

    /** A name already held by a task, or by state whose owner cannot be proved. */
    public static final class ContainerNameTaken extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String container;
        public final String project;
        public final String task;
        public final @Nullable String ownerProject;
        public final @Nullable String ownerTask;

        ContainerNameTaken(
                String container,
                String project,
                String task,
                @Nullable String ownerProject,
                @Nullable String ownerTask) {
            super(
                    "container "
                            + container
                            + (ownerProject == null
                                    ? " is occupied or unrecognized"
                                    : " belongs to project " + ownerProject + ", task " + ownerTask)
                            + "; choose another task name");
            this.container = container;
            this.project = project;
            this.task = task;
            this.ownerProject = ownerProject;
            this.ownerTask = ownerTask;
        }
    }

    private enum Stage {
        RESERVED,
        CREATED,
        RETIRED
    }

    private record Claim(
            String project,
            String task,
            String container,
            String attempt,
            Stage stage,
            String id,
            long pid,
            String started) {
        boolean owner(String p, String t) {
            return project.equals(p) && task.equals(t);
        }

        Claim held(long holder, String start) {
            return new Claim(project, task, container, attempt, stage, id, holder, start);
        }

        boolean live() {
            if (pid == 0) return false;
            return ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .map(
                            process ->
                                    process.info()
                                            .startInstant()
                                            .map(time -> time.toString().equals(started))
                                            .orElse(true))
                    .orElse(false);
        }
    }

    /** Work performed with the account name lock held. */
    @FunctionalInterface
    public interface Operation<T> {
        T run() throws IOException;
    }

    /** The caller owns this attempt until it finishes or fails. */
    public final class Lease implements AutoCloseable {
        private final String container;
        private final String attempt;
        private boolean closed;

        private Lease(Claim claim) {
            container = claim.container();
            attempt = claim.attempt();
            OWNED.get().put(claimKey(container), attempt);
        }

        public String attempt() {
            return attempt;
        }

        /** Called only after both copies of podman's id have been recorded. */
        public void created(String id) throws IOException {
            locked(
                    () -> {
                        final Claim old = required(container);
                        if (!old.attempt().equals(attempt) || !id.equals(recorded(old))) {
                            throw taken(old.project(), old.task(), container, old);
                        }
                        write(
                                new Claim(
                                        old.project(),
                                        old.task(),
                                        container,
                                        attempt,
                                        Stage.CREATED,
                                        id,
                                        old.pid(),
                                        old.started()),
                                true);
                        return Boolean.TRUE;
                    });
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            try {
                locked(
                        () -> {
                            final Claim old = required(container);
                            if (!old.attempt().equals(attempt))
                                throw new IOException("Task attempt changed: " + container);
                            write(old.held(0, ""), true);
                            return Boolean.TRUE;
                        });
            } finally {
                closed = true;
                OWNED.get().remove(claimKey(container), attempt);
            }
        }
    }

    /** Claims a fresh name, or continues exactly the supplied attempt. */
    public Lease reserve(String project, String task, String container, String attempt)
            throws IOException {
        return locked(() -> reserveLocked(project, task, container, attempt));
    }

    /** Refuses a held name before credential preflight without claiming a free name. */
    public void checkClaim(String project, String task, String container) throws IOException {
        locked(
                () -> {
                    final Claim claim = read(container, project, task);
                    if (claim != null && (!claim.owner(project, task) || claim.live()))
                        throw taken(project, task, container, claim);
                    return Boolean.TRUE;
                });
    }

    /** Recovers a dead or finished holder's original attempt; a new attempt never replaces it. */
    public Lease start(String project, String task, String container) throws IOException {
        return locked(
                () -> {
                    final Claim previous = read(container, project, task);
                    return reserveLocked(
                            project,
                            task,
                            container,
                            previous != null
                                            && previous.owner(project, task)
                                            && previous.stage() != Stage.RETIRED
                                    ? previous.attempt()
                                    : UUID.randomUUID().toString());
                });
    }

    private Lease reserveLocked(String project, String task, String container, String attempt)
            throws IOException {
        if (!project.matches(org.fuin.porelo.core.project.Project.NAME_PATTERN)
                || !task.matches(org.fuin.porelo.core.project.Project.TASK_NAME_PATTERN)
                || attempt.isBlank())
            throw new IllegalArgumentException("Invalid reservation owner or attempt");
        validate(container);
        final Claim old = read(container, project, task);
        if (old != null) {
            if (!old.owner(project, task) || old.live()) throw taken(project, task, container, old);
            if (old.stage() == Stage.RETIRED) {
                rejectLinks(old);
                if (context.podman().poreloContainers().contains(container)
                        || context.recordedId(container) != null
                        || Files.exists(
                                context.paths().tasks().containerState(container),
                                LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(
                                context.paths().tasks().taskRecord(container),
                                LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(
                                context.paths().messaging().mailbox(container),
                                LinkOption.NOFOLLOW_LINKS)) {
                    throw taken(project, task, container, old);
                }
                final Claim fresh =
                        new Claim(
                                project,
                                task,
                                container,
                                attempt,
                                Stage.RESERVED,
                                "",
                                ProcessHandle.current().pid(),
                                processStart());
                if (context.vault().exists()) {
                    final var opener = context.opener();
                    if (opener.isEmpty())
                        throw new VaultException(
                                "the vault is locked; run 'porelo vault unlock' and start again");
                    context.vault()
                            .update(
                                    opener.get(),
                                    entries -> {
                                        try {
                                            write(fresh, true);
                                        } catch (IOException ex) {
                                            throw new UncheckedIOException(ex);
                                        }
                                        return entries;
                                    });
                } else write(fresh, true);
                return new Lease(fresh);
            }
            if (!old.attempt().equals(attempt)) throw taken(project, task, container, old);
            reconcile(old);
            final String recorded = recorded(old);
            final Claim next =
                    new Claim(
                            old.project(),
                            old.task(),
                            container,
                            old.attempt(),
                            recorded == null ? old.stage() : Stage.CREATED,
                            recorded == null ? old.id() : recorded,
                            ProcessHandle.current().pid(),
                            processStart());
            write(next, true);
            return new Lease(next);
        }
        for (String other : protectedNames()) {
            final Claim owner = known(other);
            if (owner != null && !other.equals(container) && owner.owner(project, task))
                throw taken(project, task, container, owner);
        }
        final List<ContainerSummary> candidates = context.podman().taskCandidates();
        TaskIdentity verified = null;
        for (ContainerSummary candidate : candidates) {
            final TaskIdentities.Result result = new TaskIdentities(context).read(candidate);
            if (candidate.name().equals(container)) {
                if (!(result instanceof TaskIdentities.Found found))
                    throw taken(project, task, container, null);
                verified = found.identity();
                if (!verified.project().equals(project) || !verified.task().equals(task)) {
                    throw new ContainerNameTaken(
                            container, project, task, verified.project(), verified.task());
                }
            } else if (result instanceof TaskIdentities.Found found
                    && found.identity().project().equals(project)
                    && found.identity().task().equals(task)) {
                throw new ContainerNameTaken(container, project, task, project, task);
            }
        }
        if (context.podman().poreloContainers().contains(container) && verified == null) {
            throw taken(project, task, container, null);
        }
        final boolean occupiedState;
        try {
            occupiedState = verified == null && retainedState(container);
        } catch (IOException | org.fuin.porelo.wire.JsonException ex) {
            throw taken(project, task, container, null);
        }
        if (occupiedState) {
            final TaskIdentities.Result retained = new TaskIdentities(context).retained(container);
            if (retained instanceof TaskIdentities.Found found
                    && found.identity().project().equals(project)
                    && found.identity().task().equals(task)) {
                // The task's own retained state: a stopped or kept task coming back.
                verified = found.identity();
            } else if (retained instanceof TaskIdentities.Found found) {
                throw new ContainerNameTaken(
                        container,
                        project,
                        task,
                        found.identity().project(),
                        found.identity().task());
            } else {
                throw taken(project, task, container, null);
            }
        }
        final Claim fresh =
                new Claim(
                        project,
                        task,
                        container,
                        attempt,
                        verified == null ? Stage.RESERVED : Stage.CREATED,
                        verified == null ? "" : verified.recordedId(),
                        ProcessHandle.current().pid(),
                        processStart());
        // The claim is published under the vault lock too, so its pruning census cannot miss it.
        if (verified == null && context.vault().exists()) {
            final var opener = context.opener();
            if (opener.isEmpty())
                throw new VaultException(
                        "the vault is locked; run 'porelo vault unlock' and start again");
            final boolean known = verified != null;
            context.vault()
                    .update(
                            opener.get(),
                            entries -> {
                                if (!known
                                        && entries.keySet().stream()
                                                .anyMatch(name -> scope(name, container))) {
                                    throw taken(project, task, container, null);
                                }
                                try {
                                    publish(fresh);
                                } catch (IOException ex) {
                                    throw new UncheckedIOException(ex);
                                }
                                return entries;
                            });
        } else {
            publish(fresh);
        }
        return new Lease(fresh);
    }

    /** Verifies every id independently; an unrecorded container is never adopted. */
    private @Nullable String recorded(Claim claim) throws IOException {
        final String name = claim.container();
        String recorded = null;
        for (Path directory :
                List.of(
                        context.paths().tasks().containerState(name),
                        context.paths().tasks().taskRecord(name))) {
            final Path file = directory.resolve(Sidecar.RECORDED_ID);
            try {
                final var attributes =
                        Files.readAttributes(
                                file,
                                java.nio.file.attribute.BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile())
                    throw taken(claim.project(), claim.task(), name, claim);
            } catch (NoSuchFileException absent) {
                continue;
            }
            final String id = Sidecar.recordedId(directory);
            if (id == null
                    || recorded != null && !id.equals(recorded)
                    || !claim.id().isEmpty() && !claim.id().equals(id))
                throw taken(claim.project(), claim.task(), name, claim);
            recorded = id;
        }
        return recorded;
    }

    private void reconcile(Claim claim) throws IOException {
        rejectLinks(claim);
        final String name = claim.container();
        final String recorded = recorded(claim);
        final boolean occupied = context.podman().poreloContainers().contains(name);
        if (occupied || !claim.id().isEmpty() || recorded != null) {
            final var row =
                    context.podman().taskCandidates().stream()
                            .filter(item -> item.name().equals(name))
                            .findFirst();
            if (row.isEmpty()
                    || recorded == null
                    || !recorded.equals(row.get().id())
                    || !(new TaskIdentities(context).read(row.get())
                            instanceof TaskIdentities.Found found)
                    || !found.identity().project().equals(claim.project())
                    || !found.identity().task().equals(claim.task())) {
                throw taken(claim.project(), claim.task(), name, claim);
            }
        }
    }

    /**
     * Reads committed owners without locking; safe inside a vault update. Corruption protects the
     * name.
     */
    public Set<String> protectedNames() throws IOException {
        final Set<String> names = new LinkedHashSet<>();
        final Path directory =
                java.util.Objects.requireNonNull(
                        claim("porelo-x").getParent(), "a claim has its identity directory");
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return names;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unreadable task identities: " + directory);
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                final String filename = file.getFileName().toString();
                if (!filename.endsWith(".json")) continue;
                final String name = filename.substring(0, filename.length() - 5);
                try {
                    final Claim owner = required(name);
                    if (owner.stage() != Stage.RETIRED) names.add(name);
                } catch (IOException | RuntimeException ex) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** Retained logical owner, including retired tasks whose transport accounts still exist. */
    public @Nullable Owner owner(String container) {
        final Claim claim = known(container);
        return claim == null ? null : new Owner(claim.project(), claim.task());
    }

    /** Logical names claimed by this project, including starts with no container yet. */
    public Set<String> tasks(String project) throws IOException {
        final Set<String> tasks = new LinkedHashSet<>();
        for (String container : protectedNames()) {
            final Claim owner = known(container);
            if (owner != null && owner.project().equals(project)) tasks.add(owner.task());
        }
        return tasks;
    }

    /** Projects with recoverable attempts cannot be cleared by orphan detection. */
    public Set<String> projects() throws IOException {
        final Set<String> projects = new LinkedHashSet<>();
        for (String container : protectedNames()) {
            projects.add(required(container).project());
        }
        return projects;
    }

    /** The committed logical owner of an attempt that has not made a container. */
    public record Owner(String project, String task) {}

    /** Exact actual names in committed pending claims can be selected for removal without an id. */
    public boolean pendingRemoval(String container) throws IOException {
        return locked(() -> unmade(container) != null);
    }

    /** An explicit removal may finish a start or removal whose container is confirmed absent. */
    public @Nullable Owner unmade(String container) throws IOException {
        final Claim owner = read(container, "", "");
        if (owner == null || owner.stage() == Stage.RETIRED) return null;
        rejectLinks(owner);
        if (recorded(owner) != null) return null;
        if (!owner.id().isEmpty()
                && context.podman().stateOf(owner.id())
                        != org.fuin.porelo.runtime.Podman.ContainerState.ABSENT) {
            throw taken(owner.project(), owner.task(), container, owner);
        }
        if (context.podman().poreloContainers().contains(container)) {
            throw taken(owner.project(), owner.task(), container, owner);
        }
        return new Owner(owner.project(), owner.task());
    }

    /** Unknown historical ownership is retained before any of its last files are removed. */
    public void retainBeforeRemoval(String container) throws IOException {
        locked(
                () -> {
                    if (read(container, "", "") == null) retire(container);
                    return Boolean.TRUE;
                });
    }

    /** Starts whose host state or secrets cannot be cleared, even before a container exists. */
    public Set<String> unfinishedNames() throws IOException {
        final Set<String> containers = new LinkedHashSet<>(context.podman().poreloContainers());
        final Set<String> unfinished = new LinkedHashSet<>();
        for (String container : protectedNames()) {
            final Claim owner = required(container);
            if (owner.live() || owner.stage() == Stage.RESERVED || !containers.contains(container))
                unfinished.add(container);
        }
        return unfinished;
    }

    /** Clearing a project cannot cancel a live or recoverable start. Called under the name lock. */
    public @Nullable String projectBusy(String project) throws IOException {
        final Set<String> containers = new LinkedHashSet<>(context.podman().poreloContainers());
        for (String container : protectedNames()) {
            final Claim owner = required(container);
            if (owner.project().equals(project)
                    && (owner.live()
                            || owner.stage() == Stage.RESERVED
                            || !containers.contains(container))) {
                return "task "
                        + owner.task()
                        + " has an unfinished start; retry it or remove that task first";
            }
        }
        return null;
    }

    /**
     * Mutations recheck ownership before deletion, taking the name lock before any vault update.
     */
    public <T> T mutate(String container, Operation<T> action) throws IOException {
        return mutate(container, action, false);
    }

    /** A bulk operation already matched the recorded id against its one runtime snapshot. */
    public <T> T mutateRecorded(String container, Operation<T> action) throws IOException {
        return mutate(container, action, true);
    }

    private <T> T mutate(String container, Operation<T> action, boolean recordedOnly)
            throws IOException {
        return locked(
                () -> {
                    Claim old = read(container, "", "");
                    if (old != null
                            && old.live()
                            && !old.attempt().equals(OWNED.get().get(claimKey(container)))) {
                        throw taken(old.project(), old.task(), container, old);
                    }
                    if (old != null) {
                        final String id = recorded(old);
                        if (old.stage() == Stage.RESERVED && id != null) {
                            reconcile(old);
                            old =
                                    new Claim(
                                            old.project(),
                                            old.task(),
                                            container,
                                            old.attempt(),
                                            Stage.CREATED,
                                            id,
                                            old.pid(),
                                            old.started());
                            write(old, true);
                        }
                    }
                    if (old == null) {
                        final var identities = new TaskIdentities(context);
                        final var identity =
                                recordedOnly
                                        ? identities.retained(container)
                                        : identities.lookup(container, null);
                        if (identity instanceof TaskIdentities.Refused refused
                                && refused.reason() != TaskIdentities.Reason.MISSING
                                && refused.reason() != TaskIdentities.Reason.MISSING_RECORD) {
                            throw taken("", "", container, null);
                        }
                        if (identity instanceof TaskIdentities.Found found) {
                            final TaskIdentity owner = found.identity();
                            old =
                                    new Claim(
                                            owner.project(),
                                            owner.task(),
                                            container,
                                            UUID.randomUUID().toString(),
                                            Stage.CREATED,
                                            owner.recordedId(),
                                            0,
                                            "");
                            write(old, false);
                        }
                    }
                    return action.run();
                });
    }

    /** Retains historical ownership after all task state and secrets have been removed. */
    public void retire(String container) throws IOException {
        locked(
                () -> {
                    final Claim old = read(container, "", "");
                    if (old != null) {
                        if (old.live()
                                && !old.attempt().equals(OWNED.get().get(claimKey(container))))
                            throw taken(old.project(), old.task(), container, old);
                        write(
                                new Claim(
                                        old.project(),
                                        old.task(),
                                        container,
                                        old.attempt(),
                                        Stage.RETIRED,
                                        old.id(),
                                        0,
                                        ""),
                                true);
                    } else {
                        write(
                                new Claim(
                                        "",
                                        "",
                                        container,
                                        UUID.randomUUID().toString(),
                                        Stage.RETIRED,
                                        "",
                                        0,
                                        ""),
                                false);
                    }
                    return Boolean.TRUE;
                });
    }

    public <T> T locked(Operation<T> action) throws IOException {
        return FileLocks.holding(lockPath(), action::run);
    }

    private Path claimKey(String container) {
        return claim(container).toAbsolutePath().normalize();
    }

    private Path lockPath() {
        return context.paths().tasks().reservationsLock().toAbsolutePath().normalize();
    }

    Path claim(String container) {
        validate(container);
        return context.paths().tasks().identity(container);
    }

    private static void validate(String container) {
        if (!container.matches(org.fuin.porelo.runtime.ContainerName.NAME_PATTERN))
            throw new IllegalArgumentException("Invalid actual task name");
    }

    private @Nullable Claim read(String container, String project, String task) {
        try {
            return required(container);
        } catch (NoSuchFileException ex) {
            return null;
        } catch (IOException | RuntimeException ex) {
            throw taken(project, task, container, null);
        }
    }

    /** An unreadable claim still occupies its actual name, without inventing a logical owner. */
    private @Nullable Claim known(String container) {
        try {
            return required(container);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private Claim required(String container) throws IOException {
        return required(context.paths(), container);
    }

    private static Claim required(PoreloPaths paths, String container) throws IOException {
        validate(container);
        final Path file = paths.tasks().identity(container);
        final var attributes =
                Files.readAttributes(
                        file,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > 16384)
            throw new IOException("Invalid task claim: " + container);
        if (!(Json.parseExact(Files.readString(file)) instanceof Map<?, ?> map)
                || !Long.valueOf(1).equals(map.get("version")))
            throw new IOException("Unknown task claim: " + container);
        final Claim result =
                new Claim(
                        text(map, "project"),
                        text(map, "task"),
                        text(map, "container"),
                        text(map, "attempt"),
                        Stage.valueOf(text(map, "stage")),
                        text(map, "id"),
                        Long.parseLong(text(map, "pid")),
                        text(map, "started"));
        final boolean unknown =
                result.stage() == Stage.RETIRED
                        && result.project().isEmpty()
                        && result.task().isEmpty()
                        && result.pid() == 0
                        && result.id().isEmpty();
        if (!result.container().equals(container)
                || !unknown
                        && (!result.project()
                                        .matches(org.fuin.porelo.core.project.Project.NAME_PATTERN)
                                || !result.task()
                                        .matches(
                                                org.fuin.porelo.core.project.Project
                                                        .TASK_NAME_PATTERN))
                || result.attempt().isBlank()
                || result.pid() < 0
                || result.stage() == Stage.CREATED && result.id().isEmpty()
                || !result.id().isEmpty() && !result.id().matches("[0-9a-f]{12,64}"))
            throw new IOException("Contradictory task claim: " + container);
        if (result.pid() > 0) Instant.parse(result.started());
        return result;
    }

    /** Reads the original owner kept after removal, without taking a lock or adopting a task. */
    public static java.util.Optional<TaskIdentity> historical(PoreloPaths paths, String container)
            throws IOException {
        final Claim owner;
        try {
            owner = required(paths, container);
        } catch (NoSuchFileException absent) {
            return java.util.Optional.empty();
        }
        if (owner.project().isEmpty() || owner.id().isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(
                new TaskIdentity(
                        container.equals(
                                        org.fuin.porelo.runtime.ContainerName.contract(
                                                owner.project(), owner.task()))
                                ? TaskIdentity.Format.LEGACY
                                : TaskIdentity.Format.ENCODED,
                        owner.project(),
                        owner.task(),
                        container,
                        owner.id()));
    }

    /**
     * Whether the name's task was removed: its claim is retired. Read without the name lock, so a
     * pass over every mailbox never waits for a removal holding it; a missing or unreadable claim
     * is no removal.
     */
    public boolean removed(String container) {
        final Claim owner = known(container);
        return owner != null && owner.stage() == Stage.RETIRED;
    }

    /**
     * Only explicitly retired scopes may be pruned; unidentified vault leftovers keep their name.
     */
    public boolean retired(String container) {
        final Claim owner = known(container);
        if (owner == null || owner.stage() != Stage.RETIRED || owner.project().isEmpty())
            return false;
        if (Files.notExists(
                        context.paths().tasks().containerState(container),
                        LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(
                        context.paths().tasks().taskRecord(container), LinkOption.NOFOLLOW_LINKS))
            return true;
        return new TaskIdentities(context).retained(container) instanceof TaskIdentities.Found found
                && owner.owner(found.identity().project(), found.identity().task())
                && owner.id().equals(found.identity().recordedId());
    }

    /**
     * Checks committed metadata against the recorded owner; a legacy task may have no claim yet.
     */
    public static boolean matches(PoreloPaths paths, TaskIdentity identity) throws IOException {
        final Claim owner;
        try {
            owner = required(paths, identity.container());
        } catch (NoSuchFileException absent) {
            return true;
        }
        return owner.stage() != Stage.RETIRED
                && owner.owner(identity.project(), identity.task())
                && (owner.id().isEmpty() || owner.id().equals(identity.recordedId()));
    }

    private static String text(Map<?, ?> map, String key) throws IOException {
        if (map.get(key) instanceof String value) return value;
        throw new IOException("Missing task claim field: " + key);
    }

    private void publish(Claim fresh) throws IOException {
        fault.at(Publication.BEFORE);
        try {
            write(fresh, false);
            fault.at(Publication.AFTER);
        } catch (IOException ex) {
            try {
                final Claim published = required(fresh.container());
                if (published.attempt().equals(fresh.attempt())) write(published.held(0, ""), true);
            } catch (NoSuchFileException absent) {
                // An interrupted temporary write has not claimed the name.
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
    }

    /** Resumes reuse their original attempt, including from task attach and machine restart. */
    public java.util.Optional<Lease> resume(String container) throws IOException {
        return locked(
                () -> {
                    final Claim old = read(container, "", "");
                    if (old != null && old.attempt().equals(OWNED.get().get(claimKey(container))))
                        return java.util.Optional.empty();
                    final TaskIdentities.Result result =
                            new TaskIdentities(context).lookup(container, null);
                    if (!(result instanceof TaskIdentities.Found found)) {
                        if (old != null) {
                            // A start that failed and kept its task left the claim; resuming
                            // that task takes over its original attempt once its holder is gone.
                            if (old.live() || old.project().isEmpty() || old.task().isEmpty())
                                throw taken(old.project(), old.task(), container, old);
                            return java.util.Optional.of(
                                    start(old.project(), old.task(), container));
                        }
                        if (result instanceof TaskIdentities.Refused refused
                                && refused.reason() != TaskIdentities.Reason.MISSING) {
                            throw taken("", "", container, null);
                        }
                        return java.util.Optional.empty();
                    }
                    final TaskIdentity identity = found.identity();
                    return java.util.Optional.of(
                            start(identity.project(), identity.task(), container));
                });
    }

    private void write(Claim value, boolean replace) throws IOException {
        final Path file = claim(value.container());
        final Path directory =
                java.util.Objects.requireNonNull(
                        file.getParent(), "a claim has its identity directory");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid identity directory: " + directory);
        Directories.create(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        final Path temp =
                Files.createTempFile(
                        directory,
                        ".claim-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("version", 1);
            map.put("project", value.project());
            map.put("task", value.task());
            map.put("container", value.container());
            map.put("attempt", value.attempt());
            map.put("stage", value.stage().name());
            map.put("id", value.id());
            map.put("pid", Long.toString(value.pid()));
            map.put("started", value.started());
            Files.writeString(temp, Json.write(map) + "\n");
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (replace) {
                required(value.container());
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createLink(file, temp);
            }
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private List<Path> statePaths(String container) {
        final PoreloPaths paths = context.paths();
        return List.of(
                paths.tasks().containerState(container),
                paths.tasks().taskRecord(container),
                paths.messaging().mailbox(container),
                paths.tasks().sessionRecord(container),
                paths.tasks().guide(container),
                paths.tasks().repositories(container),
                paths.tasks().usageRecord(container),
                paths.tasks().clearanceJournal(container),
                paths.xdg().state().resolve("hand-ins").resolve(container + ".jsonl"),
                paths.xdg().state().resolve("builds").resolve(container + ".jsonl"),
                paths.xdg().runtime().resolve("hand-in").resolve(container),
                paths.xdg().runtime().resolve("mcp").resolve(container + ".sock"),
                paths.xdg().runtime().resolve("mcp").resolve(container + ".daemon.sock"));
    }

    private void rejectLinks(Claim claim) throws IOException {
        for (Path path : statePaths(claim.container())) {
            try {
                if (Files.readAttributes(
                                path,
                                java.nio.file.attribute.BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS)
                        .isSymbolicLink()) {
                    throw taken(claim.project(), claim.task(), claim.container(), claim);
                }
            } catch (NoSuchFileException absent) {
                // Only this namespace is absent; every other one still needs checking.
            }
        }
    }

    private boolean retainedState(String container) throws IOException {
        for (Path file : statePaths(container)) {
            try {
                Files.readAttributes(
                        file,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                return true;
            } catch (NoSuchFileException absent) {
                // Only absence proves this namespace free.
            }
        }
        return transportState(container);
    }

    private boolean transportState(String container) throws IOException {
        final Path root = context.paths().xdg().state().resolve("transport");
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return true;
        try (var schemes = Files.list(root)) {
            for (Path scheme : schemes.toList()) {
                if (!Files.isDirectory(scheme, LinkOption.NOFOLLOW_LINKS)) return true;
                for (String kind : List.of("addresses", "displays")) {
                    final Path directory = scheme.resolve(kind);
                    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return true;
                    try (var files = Files.list(directory)) {
                        for (Path file : files.toList()) {
                            if (!file.getFileName().toString().endsWith(".json")) continue;
                            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return true;
                            final Object parsed = Json.parse(Files.readString(file));
                            if (!(parsed instanceof Map<?, ?> map) || map.containsKey(container))
                                return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean scope(String entry, String container) {
        if (entry.startsWith(VaultNames.TASK_PREFIX + container + "/")) return true;
        final String[] parts = entry.split("/");
        return parts.length == 4
                && entry.startsWith(VaultNames.TRANSPORT_PREFIX)
                && parts[2].equals("task")
                && parts[3].equals(container);
    }

    private static String processStart() {
        return ProcessHandle.current().info().startInstant().orElse(Instant.EPOCH).toString();
    }

    private static ContainerNameTaken taken(
            String project, String task, String container, @Nullable Claim owner) {
        return new ContainerNameTaken(
                container,
                project,
                task,
                owner == null || owner.project().isEmpty() ? null : owner.project(),
                owner == null || owner.task().isEmpty() ? null : owner.task());
    }
}
