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

    /** No action is authorized by this lookup. */
    public record Refused(Reason reason, String name) implements Result {}

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

    private final PoreloContext context;

    public TaskIdentities(PoreloContext context) {
        this.context = context;
    }

    /** Applies new-name rules while allowing an existing legacy task to keep its name. */
    public java.util.Optional<String> newTaskRefusal(String project, String task) {
        if (ContainerName.reserved(project, task)) {
            return lookup(task, project) instanceof Found
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
        if (!container.matches("porelo-[a-z0-9_-]+")) {
            return new Refused(Reason.INVALID_RECORD, container);
        }
        return read(
                new ContainerSummary(
                        container,
                        "removed",
                        "",
                        null,
                        null,
                        null,
                        null,
                        null,
                        context.recordedId(container)));
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
        for (ContainerSummary summary : candidates) {
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
                    && given.equals(logical(summary.project(), summary))) {
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
        final String name = summary.name();
        if (!ContainerName.isTask(name) || !name.matches("porelo-[a-z0-9_-]+")) {
            return new Refused(Reason.INVALID_RECORD, name);
        }
        final Path runtime = context.paths().tasks().containerState(name);
        final Path durable = context.paths().tasks().taskRecord(name);
        String id = null;
        String project = summary.project();
        try {
            for (Path directory : List.of(durable, runtime)) {
                final Path idFile = directory.resolve(Sidecar.RECORDED_ID);
                final String recorded = Sidecar.recordedId(directory);
                if (Files.exists(idFile, LinkOption.NOFOLLOW_LINKS) && recorded == null) {
                    return new Refused(Reason.INVALID_RECORD, name);
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
                        return new Refused(Reason.INVALID_RECORD, name);
                    }
                    final Sidecar sidecar = Sidecar.readFrom(file);
                    if (!Path.of(sidecar.stateDirectory())
                            .normalize()
                            .equals(runtime.normalize())) {
                        return new Refused(Reason.INVALID_RECORD, name);
                    }
                    if (project != null && !project.equals(sidecar.project())) {
                        return new Refused(Reason.PROJECT_MISMATCH, name);
                    }
                    project = sidecar.project();
                }
            }
        } catch (IOException | IllegalArgumentException | org.fuin.porelo.wire.JsonException ex) {
            return new Refused(Reason.INVALID_RECORD, name);
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
        final String task = logical(project, summary);
        if (task.isEmpty() || !task.matches(Project.TASK_NAME_PATTERN)) {
            return new Refused(Reason.INVALID_RECORD, name);
        }
        return new Found(
                new TaskIdentity(
                        summary.task() == null
                                ? TaskIdentity.Format.LEGACY
                                : TaskIdentity.Format.ENCODED,
                        project,
                        task,
                        name,
                        id));
    }

    private static String logical(String project, ContainerSummary summary) {
        if (summary.task() == null) {
            return ContainerName.taskIn(project, summary.name());
        }
        return ContainerName.isEncoded(project, summary.task(), summary.name())
                ? summary.task()
                : "";
    }
}
