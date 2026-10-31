package org.fuin.porelo.app;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Says whose task a container is.
 *
 * <p>Not by the name's beginning alone: project and task names both take hyphens, so {@code
 * porelo-web-app-x} is task {@code app-x} of {@code web} or task {@code x} of {@code web-app}.
 * Taken by the first project that fitted, a task of one was the other's in messaging - its peers,
 * its conversation, its tasks as vouched siblings. The container's project label decides, then what
 * a removed task's start recorded; a name alone only when it can mean one project.
 */
final class ProjectNames {

    private ProjectNames() {}

    /**
     * Returns the project of a container.
     *
     * @param container The container's name.
     * @param labels Each container's project label, as the runtime reports it.
     * @param projects The projects this machine has.
     * @return The project, or {@code null} when nothing says, or more than one could be meant.
     */
    static @Nullable String of(
            String container, Map<String, String> labels, List<String> projects) {
        final String labelled = labels.get(container);
        if (labelled != null && !labelled.isBlank()) {
            return labelled;
        }
        final List<String> fitting =
                projects.stream()
                        .filter(name -> container.startsWith("porelo-" + name + "-"))
                        .toList();
        return fitting.size() == 1 ? fitting.getFirst() : null;
    }

    /**
     * Returns each Porelo container's project label, as the runtime reports it now, and for a
     * container the runtime no longer has, the project its start recorded.
     *
     * @param context The machine.
     * @return Container to project.
     */
    static Map<String, String> labels(PoreloContext context) {
        final Map<String, String> labels = new java.util.HashMap<>();
        for (final org.fuin.porelo.runtime.ContainerSummary task : context.podman().poreloTasks()) {
            if (task.project() != null) {
                labels.put(task.name(), task.project());
            }
        }
        // A removed task's start recorded its project, which its name alone may not tell.
        final java.nio.file.Path records = context.paths().tasks().taskRecord("");
        if (java.nio.file.Files.isDirectory(records)) {
            try (java.util.stream.Stream<java.nio.file.Path> entries =
                    java.nio.file.Files.list(records)) {
                for (final java.nio.file.Path record : entries.toList()) {
                    final String project = recorded(record);
                    if (project != null) {
                        labels.putIfAbsent(record.getFileName().toString(), project);
                    }
                }
            } catch (java.io.IOException | java.io.UncheckedIOException ex) {
                // Unlisted, the records say nothing; the runtime's labels still do.
            }
        }
        return labels;
    }

    private static @Nullable String recorded(final java.nio.file.Path record) {
        final java.nio.file.Path file = record.resolve("sidecar.json");
        try {
            return java.nio.file.Files.isRegularFile(file)
                    ? org.fuin.porelo.wire.Sidecar.readFrom(file).project()
                    : null;
        } catch (java.io.IOException | RuntimeException ex) {
            // An unreadable record names no project.
            return null;
        }
    }
}
