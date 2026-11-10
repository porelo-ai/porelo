package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.fuin.porelo.core.project.ProjectException;
import org.jspecify.annotations.Nullable;

/** Finds the actual owner of retained task files, including after normal removal. */
public final class TaskHistory {
    private TaskHistory() {}

    /** Resolves a pending push, preferring an exact logical owner over a rescue suffix. */
    public static @Nullable TaskIdentity forPush(PoreloPaths paths, String project, String push) {
        final TaskIdentity own = find(paths, project, push);
        if (own != null || !push.endsWith(org.fuin.porelo.gate.GitGate.RESCUED)) return own;
        return find(
                paths,
                project,
                push.substring(0, push.length() - org.fuin.porelo.gate.GitGate.RESCUED.length()));
    }

    /** Resolves a push and checks any live container against the retained id. */
    public static @Nullable TaskIdentity forPush(
            PoreloContext context, String project, String push) {
        final TaskIdentity identity = forPush(context.paths(), project, push);
        return identity == null ? null : find(context, identity.project(), identity.task());
    }

    /** Resolves project-scoped history and checks a present container against its original id. */
    public static @Nullable TaskIdentity find(PoreloContext context, String project, String task) {
        final TaskIdentity identity = find(context.paths(), project, task);
        if (identity == null) return null;
        for (var row : context.podman().taskCandidates()) {
            if (row.name().equals(identity.container())) {
                final var current = new TaskIdentities(context).read(row);
                if (!(current instanceof TaskIdentities.Found found)
                        || !sameOwner(identity, found.identity())) {
                    throw new ProjectException(
                            "the task's retained identity contradicts its container; list tasks before acting");
                }
            }
        }
        return identity;
    }

    /** Resolves retained history from its records without reconstructing a container name. */
    public static @Nullable TaskIdentity find(PoreloPaths paths, String project, String task) {
        final Set<String> names = new LinkedHashSet<>();
        try {
            collect(paths.xdg().runtime(), false, names);
            collect(paths.tasks().taskRecord("porelo-x").getParent(), false, names);
            collect(paths.tasks().identity("porelo-x").getParent(), true, names);
            TaskIdentity result = null;
            for (String name : names) {
                final var recorded = TaskIdentities.retained(paths, name);
                final var kept = TaskReservations.historical(paths, name);
                final TaskIdentity identity;
                if (recorded instanceof TaskIdentities.Found found) {
                    identity = found.identity();
                    if (kept.isPresent() && !sameOwner(identity, kept.get())) {
                        throw new ProjectException(
                                "the task's ownership records contradict each other; list tasks before acting");
                    }
                } else if (recorded instanceof TaskIdentities.Refused refused
                        && refused.reason() == TaskIdentities.Reason.MISSING_RECORD) {
                    if (kept.isPresent()
                            && (Files.exists(
                                            paths.tasks().containerState(name),
                                            LinkOption.NOFOLLOW_LINKS)
                                    || Files.exists(
                                            paths.tasks().taskRecord(name),
                                            LinkOption.NOFOLLOW_LINKS))) {
                        throw new ProjectException(
                                "historical ownership cannot verify the surviving task files; list tasks before acting");
                    }
                    identity = kept.orElse(null);
                } else {
                    throw new ProjectException(
                            "a retained task identity cannot be verified; list tasks before acting");
                }
                if (identity != null
                        && identity.project().equals(project)
                        && identity.task().equals(task)) {
                    if (result != null)
                        throw new ProjectException(
                                "several retained tasks have that logical name; use their actual names");
                    result = identity;
                }
            }
            return result;
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read retained task ownership", ex);
        }
    }

    private static boolean sameOwner(TaskIdentity first, TaskIdentity second) {
        return first.project().equals(second.project())
                && first.task().equals(second.task())
                && first.container().equals(second.container())
                && first.recordedId().equals(second.recordedId());
    }

    private static void collect(@Nullable Path directory, boolean files, Set<String> names)
            throws IOException {
        if (directory == null || Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid task history directory: " + directory);
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (files) {
                    if (!name.endsWith(".json")) continue;
                    name = name.substring(0, name.length() - ".json".length());
                }
                if (name.matches("porelo-[a-z0-9_-]+")
                        && org.fuin.porelo.runtime.ContainerName.isTask(name)) names.add(name);
            }
        }
    }
}
