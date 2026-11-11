package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The repositories a task that reads its whole project is given: every repository's main as a
 * directory of its own, written by the host and mounted read only at {@link #MOUNT}.
 *
 * <p>A reading copy, never a clone: it holds no {@code .git}, so nothing the host does with it runs
 * git in a directory a task wrote, and nothing in it can be pushed. Its directory being there is
 * what makes a task one that reads its project, from its creation to its removal.
 */
public final class TaskRepositories {

    /** Where the directory appears inside the container: where any task finds its workspace. */
    public static final String MOUNT = "/workspace";

    private final Path directory;

    /**
     * Constructor. Creates nothing.
     *
     * @param paths The machine's roots.
     * @param container The task.
     */
    public TaskRepositories(final PoreloPaths paths, final String container) {
        this.directory = paths.tasks().repositories(container);
    }

    /**
     * Returns the host's directory, the one that is mounted.
     *
     * @return Directory.
     */
    public Path directory() {
        return directory;
    }

    /**
     * Returns whether the task has the directory: it was made to read its whole project.
     *
     * @return {@code true} when it is a directory.
     */
    public boolean exists() {
        return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Returns the repositories the task was given, by name, in order.
     *
     * @return The names; empty when the task has none.
     */
    public List<String> names() {
        if (!exists()) {
            return List.of();
        }
        try (Stream<Path> each = Files.list(directory)) {
            return each.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    /**
     * Deletes the directory. Called when the task is removed, and when a start that made it fails.
     *
     * @throws IOException Deleting failed.
     */
    public void delete() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // Not followed: a link in a repository's tree points wherever its author wanted.
        try (Stream<Path> tree = Files.walk(directory)) {
            for (final Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
