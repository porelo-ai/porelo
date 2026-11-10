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
 * a removed task's start recorded; a name alone never proves ownership.
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
        return null;
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
        final TaskIdentities identities = new TaskIdentities(context);
        final java.util.Set<String> live = new java.util.HashSet<>();
        for (final org.fuin.porelo.runtime.ContainerSummary task :
                context.podman().taskCandidates()) {
            live.add(task.name());
            if (identities.read(task) instanceof TaskIdentities.Found found) {
                labels.put(task.name(), found.identity().project());
            }
        }
        // A removed task's start recorded its project, which its name alone may not tell.
        final java.nio.file.Path records = context.paths().tasks().taskRecord("");
        if (java.nio.file.Files.isDirectory(records)) {
            try (java.util.stream.Stream<java.nio.file.Path> entries =
                    java.nio.file.Files.list(records)) {
                for (final java.nio.file.Path record : entries.toList()) {
                    final String container = record.getFileName().toString();
                    if (!live.contains(container)
                            && identities.retained(container)
                                    instanceof TaskIdentities.Found found) {
                        labels.put(container, found.identity().project());
                    }
                }
            } catch (java.io.IOException | java.io.UncheckedIOException ex) {
                // Unlisted, the records say nothing; the runtime's labels still do.
            }
        }
        return labels;
    }
}
