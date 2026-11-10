package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The repositories of {@code default} that a project of the organization names, and taking one out.
 *
 * <p>A repository stays in {@code default} until the person takes it out. Once a project of the
 * organization names its upstream, that project takes the next start from a checkout, and the
 * repository is offered for removal from {@code default} - at a start in {@code default} and to an
 * interface. Nothing here removes anything the person did not ask to have removed.
 */
public final class DefaultNamed {

    /**
     * A repository of {@code default} that a project of the organization names.
     *
     * @param repository Its name in {@code default}, as {@code porelo project default remove} takes
     *     it.
     * @param upstream Where its history comes from and its approved work goes in {@code default}.
     * @param project The organization's project that names it.
     * @param projectRepository The repository's key in that project.
     */
    public record Named(
            String repository, String upstream, String project, String projectRepository) {

        /**
         * Returns the command that takes it out of {@code default}.
         *
         * @return The command, as a person types it.
         */
        public String removal() {
            return "porelo project default remove " + repository;
        }

        /**
         * Returns what a start in {@code default} on it says.
         *
         * @return One line.
         */
        public String said() {
            return "'"
                    + project
                    + "' names this repository as '"
                    + projectRepository
                    + "', and a start from its checkout goes there; '"
                    + removal()
                    + "' takes it out of '"
                    + DefaultProject.NAME
                    + "'";
        }

        /**
         * Returns this as plain values, for a wire.
         *
         * @return The row.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", repository);
            map.put("upstream", upstream);
            map.put("project", project);
            map.put("projectRepository", projectRepository);
            map.put("removal", removal());
            return map;
        }
    }

    /** What taking a repository out of {@code default} came to. */
    public sealed interface Removal {

        /**
         * Taken out.
         *
         * @param entry What was in {@code default}.
         * @param named The organization's project that named it, or {@code null} when none did.
         * @param keys This machine's deploy keys forgotten with it.
         */
        record Removed(
                DefaultProject.Entry entry, @Nullable Named named, List<LeftDeployKeys.Key> keys)
                implements Removal {}

        /**
         * Refused: tasks of {@code default} still work on it, and it stays.
         *
         * @param repository Its name.
         * @param tasks The tasks, by container name.
         */
        record InUse(String repository, List<String> tasks) implements Removal {}

        /**
         * Refused: {@code default} has no repository of that name.
         *
         * @param repository The name asked for.
         */
        record NotThere(String repository) implements Removal {}
    }

    private DefaultNamed() {}

    /**
     * Returns the repositories of {@code default} that a project of the organization names, in the
     * order they were added.
     *
     * @param context This machine.
     * @return Each with the project and the repository key that name it; empty when none.
     */
    public static List<Named> all(final PoreloContext context) {
        final List<Named> named = new ArrayList<>();
        for (final DefaultProject.Entry entry : new DefaultProject(context).usableEntries()) {
            final Named one = naming(context, entry);
            if (one != null) {
                named.add(one);
            }
        }
        return List.copyOf(named);
    }

    /**
     * Returns the project of the organization that names a repository of {@code default}, or {@code
     * null}.
     *
     * @param context This machine.
     * @param repository Its name in {@code default}.
     * @return What names it, or {@code null} when nothing does or it is not in {@code default}.
     */
    public static @Nullable Named of(final PoreloContext context, final String repository) {
        for (final DefaultProject.Entry entry : new DefaultProject(context).usableEntries()) {
            if (entry.name().equals(repository)) {
                return naming(context, entry);
            }
        }
        return null;
    }

    /**
     * Takes a repository out of {@code default} through its own removal, unless a task of {@code
     * default} still works on it: that task would lose the repository it was started on.
     *
     * @param context This machine.
     * @param tasks The tasks this machine has.
     * @param repository Its name in {@code default}.
     * @return What was removed, or why nothing was.
     */
    public static Removal remove(
            final PoreloContext context,
            final List<TaskInventory.Task> tasks,
            final String repository) {
        final DefaultProject project = new DefaultProject(context);
        final DefaultProject.@Nullable Entry entry =
                project.entries().stream()
                        .filter(each -> each.name().equals(repository))
                        .findFirst()
                        .orElse(null);
        if (entry == null) {
            return new Removal.NotThere(repository);
        }
        final List<String> working =
                tasks.stream()
                        .filter(task -> DefaultProject.is(task.project()))
                        .filter(task -> repository.equals(task.repository()))
                        .map(TaskInventory.Task::name)
                        .toList();
        if (!working.isEmpty()) {
            return new Removal.InUse(repository, working);
        }
        final @Nullable Named named = naming(context, entry);
        final List<LeftDeployKeys.Key> keys = project.remove(repository);
        return keys == null
                ? new Removal.NotThere(repository)
                : new Removal.Removed(entry, named, keys);
    }

    /** The checkout's origin first, which is what a project names; then the upstream itself. */
    private static @Nullable Named naming(
            final PoreloContext context, final DefaultProject.Entry entry) {
        for (final String address : List.of(entry.remote(), entry.upstream())) {
            if (address.isBlank()) {
                continue;
            }
            final WorkOrigin.Choice placed = WorkOrigin.organizationNaming(context, address);
            if (placed != null) {
                return new Named(
                        entry.name(), entry.upstream(), placed.project(), placed.repository());
            }
        }
        return null;
    }
}
