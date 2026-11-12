package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.runtime.ContainerName;
import org.fuin.porelo.runtime.ContainerSummary;
import org.fuin.porelo.wire.Sidecar;
import org.jspecify.annotations.Nullable;

/** Resolves task ownership from Porelo's records and the runtime's labels without writing state. */
public final class TaskIdentities {
    /** A lookup either proves one owner or refuses it with a named reason. */
    public sealed interface Result permits Found, Refused {}

    /** One verified owner. */
    public record Found(TaskIdentity identity) implements Result {}

    /**
     * No action is authorized by this lookup.
     *
     * @param reason Why, as code branches on it.
     * @param name The name it was asked by.
     * @param check What failed, in a person's words, or "" where the reason says it all.
     */
    public record Refused(Reason reason, String name, String check) implements Result {
        /**
         * Refuses where the reason says it all.
         *
         * @param reason Why.
         * @param name The name it was asked by.
         */
        public Refused(final Reason reason, final String name) {
            this(reason, name, "");
        }

        /**
         * Returns the reason in a person's words, with the check that failed.
         *
         * @return The words.
         */
        public String said() {
            return check.isEmpty() ? reason.said() : reason.said() + ": " + check;
        }
    }

    /** Why ownership could not be established. */
    public enum Reason {
        MISSING("no task has that name"),
        AMBIGUOUS("tasks of several projects have that name"),
        MISSING_RECORD("its container has no identity record"),
        ID_MISMATCH("its identity record names another container"),
        PROJECT_MISMATCH("its identity record names another project"),
        INVALID_RECORD("its identity record cannot be read");

        private final String said;

        Reason(final String said) {
            this.said = said;
        }

        /**
         * Returns the reason in a person's words.
         *
         * @return The words.
         */
        public String said() {
            return said;
        }
    }

    private static final String NO_TASK_NAME = "its name is no task container's name";

    private final PoreloContext context;

    public TaskIdentities(PoreloContext context) {
        this.context = context;
    }

    /** Rechecks a previously selected incarnation, including its committed ownership metadata. */
    public boolean matches(TaskIdentity expected) {
        final Result current = lookup(expected.container(), expected.project());
        if (!(current instanceof Found found) || !found.identity().equals(expected)) return false;
        try {
            return TaskReservations.matches(context.paths(), expected);
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /** Applies new-name rules while allowing an existing legacy task to keep its name. */
    public java.util.Optional<String> newTaskRefusal(String project, String task) {
        if (ContainerName.reserved(project, task)) {
            // Only a legacy task whose container still exists keeps the name; a retained record
            // of a removed one is history and does not reserve it.
            return lookup(task, project) instanceof Found found
                            && context.podman().taskCandidates().stream()
                                    .anyMatch(
                                            row -> row.name().equals(found.identity().container()))
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(
                            "'"
                                    + task
                                    + "' is reserved for login containers in project porelo; choose another task name");
        }
        return ContainerName.refusal(project, task);
    }

    /**
     * Reads retained ownership for history, without claiming that a live container still exists.
     */
    public Result retained(String container) {
        return retained(context.paths(), container);
    }

    /** Reads retained ownership without asking a runtime or opening the account. */
    public static Result retained(PoreloPaths paths, String container) {
        if (!container.matches("porelo-[a-z0-9_-]+")) {
            return new Refused(Reason.INVALID_RECORD, container, NO_TASK_NAME);
        }
        final String runtimeId = Sidecar.recordedId(paths.tasks().containerState(container));
        final String durableId = Sidecar.recordedId(paths.tasks().taskRecord(container));
        return read(
                paths,
                new ContainerSummary(
                        container,
                        "removed",
                        "",
                        null,
                        null,
                        null,
                        null,
                        null,
                        runtimeId == null ? durableId : runtimeId));
    }

    /** Resolves an exact actual name first, then a logical name in the optional project. */
    public Result lookup(String given, @Nullable String project) {
        return lookup(given, project, context.podman().taskCandidates());
    }

    /** Resolves against one caller-owned runtime snapshot. */
    public Result lookup(
            String given, @Nullable String project, List<ContainerSummary> candidates) {
        for (ContainerSummary summary : candidates) {
            if (summary.name().equals(given)) {
                return scoped(read(summary), project, given);
            }
        }
        // A renamed or removed container still has its recorded id; shutdown must reach that id.
        if (ContainerName.isTask(given)
                && given.matches("porelo-[a-z0-9_-]+")
                && (Files.exists(
                                context.paths().tasks().taskRecord(given),
                                LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(
                                context.paths().tasks().containerState(given),
                                LinkOption.NOFOLLOW_LINKS))) {
            return scoped(retained(given), project, given);
        }
        final List<TaskIdentity> matching = new ArrayList<>();
        Refused incomplete = null;
        final List<ContainerSummary> known = new ArrayList<>(candidates);
        final Path records = context.paths().tasks().taskRecord("");
        Path reading = null;
        if (Files.isDirectory(records, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(records)) {
                for (Path entry : entries.toList()) {
                    final String name = entry.getFileName().toString();
                    if (candidates.stream().noneMatch(each -> each.name().equals(name))
                            && name.matches("porelo-[a-z0-9_-]+")) {
                        final Path sidecarFile = entry.resolve("sidecar.json");
                        reading = sidecarFile;
                        final Sidecar claimed =
                                Files.isRegularFile(sidecarFile, LinkOption.NOFOLLOW_LINKS)
                                        ? Sidecar.readFrom(sidecarFile)
                                        : null;
                        known.add(
                                new ContainerSummary(
                                        name,
                                        "removed",
                                        "",
                                        claimed == null ? null : claimed.project(),
                                        null,
                                        null,
                                        null,
                                        claimed == null ? null : claimed.task(),
                                        context.recordedId(name)));
                    }
                }
            } catch (IOException
                    | java.io.UncheckedIOException
                    | IllegalArgumentException
                    | org.fuin.porelo.wire.JsonException ex) {
                return new Refused(
                        Reason.INVALID_RECORD,
                        given,
                        (reading == null ? "the task records under " + records : reading.toString())
                                + " cannot be read: "
                                + ex.getMessage());
            }
        }
        for (ContainerSummary summary : known) {
            final Result result = read(summary);
            if (result instanceof Found found) {
                final TaskIdentity identity = found.identity();
                if (identity.task().equals(given)
                        && (project == null || identity.project().equals(project))) {
                    matching.add(identity);
                }
            } else if (result instanceof Refused refused
                    && summary.project() != null
                    && (project == null || project.equals(summary.project()))
                    && given.equals(claimedTask(summary.project(), summary))) {
                incomplete = refused;
            }
        }
        // Incomplete ownership cannot be used to disambiguate two containers with one logical name.
        if (matching.size() > 1 || !matching.isEmpty() && incomplete != null) {
            return new Refused(Reason.AMBIGUOUS, given);
        }
        if (matching.size() == 1) {
            return new Found(matching.getFirst());
        }
        return incomplete == null ? new Refused(Reason.MISSING, given) : incomplete;
    }

    private static Result scoped(Result result, @Nullable String project, String given) {
        return result instanceof Found found
                        && project != null
                        && !project.equals(found.identity().project())
                ? new Refused(Reason.PROJECT_MISMATCH, given)
                : result;
    }

    /** Verifies a runtime row against both copies of the original recorded id and sidecar. */
    public Result read(ContainerSummary summary) {
        return read(context.paths(), summary);
    }

    private static Result read(PoreloPaths paths, ContainerSummary summary) {
        final String name = summary.name();
        if (!ContainerName.isTask(name) || !name.matches("porelo-[a-z0-9_-]+")) {
            return new Refused(Reason.INVALID_RECORD, name, NO_TASK_NAME);
        }
        final Path runtime = paths.tasks().containerState(name);
        final Path durable = paths.tasks().taskRecord(name);
        String id = null;
        String project = summary.project();
        Sidecar identityRecord = null;
        Path read = durable.resolve("sidecar.json");
        try {
            for (Path directory : List.of(durable, runtime)) {
                final Path idFile = directory.resolve(Sidecar.RECORDED_ID);
                final String recorded = Sidecar.recordedId(directory);
                if (Files.exists(idFile, LinkOption.NOFOLLOW_LINKS) && recorded == null) {
                    return new Refused(Reason.INVALID_RECORD, name, idFile + " holds no id");
                }
                if (recorded != null) {
                    if (id != null && !id.equals(recorded)) {
                        return new Refused(Reason.ID_MISMATCH, name);
                    }
                    id = recorded;
                }
                final Path file = directory.resolve("sidecar.json");
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        return new Refused(
                                Reason.INVALID_RECORD, name, file + " is no regular file");
                    }
                    read = file;
                    final Sidecar sidecar = Sidecar.readFrom(file);
                    if (!Path.of(sidecar.stateDirectory())
                            .normalize()
                            .equals(runtime.normalize())) {
                        return new Refused(
                                Reason.INVALID_RECORD,
                                name,
                                file
                                        + " names the state directory "
                                        + sidecar.stateDirectory()
                                        + ", not "
                                        + runtime);
                    }
                    if (project != null && !project.equals(sidecar.project())) {
                        return new Refused(Reason.PROJECT_MISMATCH, name);
                    }
                    project = sidecar.project();
                    if (identityRecord != null
                            && (!java.util.Objects.equals(identityRecord.task(), sidecar.task())
                                    || !java.util.Objects.equals(
                                            identityRecord.projectShort(), sidecar.projectShort())
                                    || !java.util.Objects.equals(
                                            identityRecord.taskShort(), sidecar.taskShort()))) {
                        return new Refused(
                                Reason.INVALID_RECORD,
                                name,
                                "its two sidecars name another task or short name, "
                                        + durable
                                        + " and "
                                        + runtime);
                    }
                    identityRecord = sidecar;
                }
            }
        } catch (IOException | IllegalArgumentException | org.fuin.porelo.wire.JsonException ex) {
            return new Refused(
                    Reason.INVALID_RECORD,
                    name,
                    read + " is no sidecar porelo can read: " + ex.getMessage());
        }
        if (id == null) {
            return new Refused(Reason.MISSING_RECORD, name);
        }
        if (!id.equals(summary.id())) {
            return new Refused(Reason.ID_MISMATCH, name);
        }
        if (project == null || !project.matches(Project.NAME_PATTERN)) {
            return new Refused(Reason.MISSING_RECORD, name);
        }
        final String projectShort = identityRecord == null ? null : identityRecord.projectShort();
        final String taskShort = identityRecord == null ? null : identityRecord.taskShort();
        if (org.fuin.porelo.core.project.ShortName.refusal(projectShort).isPresent()
                || org.fuin.porelo.core.project.ShortName.refusal(taskShort).isPresent()) {
            return new Refused(
                    Reason.INVALID_RECORD,
                    name,
                    "its sidecar's short name '"
                            + (org.fuin.porelo.core.project.ShortName.refusal(projectShort)
                                            .isPresent()
                                    ? projectShort
                                    : taskShort)
                            + "' is no short name porelo takes");
        }
        final String recordedTask = identityRecord == null ? null : identityRecord.task();
        if (recordedTask != null
                && summary.task() != null
                && !recordedTask.equals(summary.task())) {
            return new Refused(
                    Reason.INVALID_RECORD,
                    name,
                    "its sidecar names the task "
                            + recordedTask
                            + ", its container's label "
                            + summary.task());
        }
        final String explicitTask = recordedTask == null ? summary.task() : recordedTask;
        final String task =
                explicitTask == null ? ContainerName.taskIn(project, name) : explicitTask;
        if (task.isEmpty()
                || !task.matches(Project.TASK_NAME_PATTERN)
                || explicitTask == null && (projectShort != null || taskShort != null)) {
            return new Refused(
                    Reason.INVALID_RECORD,
                    name,
                    explicitTask == null && (projectShort != null || taskShort != null)
                            ? "its sidecar names short names but no task"
                            : task.isEmpty()
                                    ? "neither its sidecar nor its label names its task, and its"
                                            + " name is no legacy name in project "
                                            + project
                                    : "the task name '" + task + "' is no task name porelo takes");
        }
        final TaskIdentity.Format format;
        if (name.equals(ContainerName.PREFIX + project + "-" + task)) {
            format = TaskIdentity.Format.LEGACY;
        } else if (explicitTask != null
                && ContainerName.isEncoded(project, task, projectShort, taskShort, name)) {
            format = TaskIdentity.Format.ENCODED;
        } else {
            return new Refused(
                    Reason.INVALID_RECORD,
                    name,
                    "its name is neither the legacy nor the encoded name of task '"
                            + task
                            + "' in project "
                            + project);
        }
        return new Found(
                new TaskIdentity(format, project, task, name, id, projectShort, taskShort));
    }

    // An unverified label can only block an ambiguous lookup, never authorize an action.
    private static String claimedTask(String project, ContainerSummary summary) {
        return summary.task() == null
                ? ContainerName.taskIn(project, summary.name())
                : summary.task();
    }

    /** Refuses a request that would change an existing task's original spelling. */
    public static java.util.Optional<String> shortNameRefusal(
            Result identity, @Nullable String shortName) {
        if (shortName == null
                || identity instanceof Refused refused && refused.reason() == Reason.MISSING) {
            return java.util.Optional.empty();
        }
        if (identity instanceof Found found && shortName.equals(found.identity().taskShort())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                "--short is fixed when a task is created; start a new task to use another spelling");
    }
}
