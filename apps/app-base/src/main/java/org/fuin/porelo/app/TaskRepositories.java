package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The repositories a task that only reads is given: every repository's main, for a task that reads
 * its whole project, or its one repository's, for a task made read only on it; each a directory of
 * its own, written by the host and mounted read only at {@link #MOUNT}.
 *
 * <p>A reading copy, never a clone: it holds no {@code .git}, so nothing the host does with it runs
 * git in a directory a task wrote, and nothing in it can be pushed. Its directory being there is
 * what makes a task one that reads, from its creation to its removal; which of the two directories
 * it has is which kind it was made.
 */
public final class TaskRepositories {

    /** Where the directory appears inside the container: where any task finds its workspace. */
    public static final String MOUNT = "/workspace";

    private final Path all;

    private final Path one;

    /**
     * Constructor. Creates nothing.
     *
     * @param paths The machine's roots.
     * @param container The task.
     */
    public TaskRepositories(final PoreloPaths paths, final String container) {
        this.all = paths.tasks().repositories(container);
        this.one = paths.tasks().readOnly(container);
    }

    /**
     * Returns the host's directory, the one that is mounted: the one-repository directory when the
     * task has it, else the whole project's.
     *
     * @return Directory.
     */
    public Path directory() {
        return isDirectory(one) ? one : all;
    }

    /**
     * Returns the directory a new task of the kind asked for is given.
     *
     * @param single Whether the task is made read only on its one repository.
     * @return Directory, not made yet.
     */
    public Path directory(final boolean single) {
        return single ? one : all;
    }

    /**
     * Returns whether the task has the directory: it was made to read, its whole project or its one
     * repository.
     *
     * @return {@code true} when either is a directory.
     */
    public boolean exists() {
        return isDirectory(all) || isDirectory(one);
    }

    /**
     * Returns whether the task was made read only on its one repository.
     *
     * @return {@code true} when it has that directory.
     */
    public boolean single() {
        return isDirectory(one);
    }

    private static boolean isDirectory(final Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
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
        try (Stream<Path> each = Files.list(directory())) {
            return each.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    /**
     * Deletes the directories. Called when the task is removed, and when a start that made one
     * fails.
     *
     * @throws IOException Deleting failed.
     */
    public void delete() throws IOException {
        delete(all);
        delete(one);
    }

    private static void delete(final Path directory) throws IOException {
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
