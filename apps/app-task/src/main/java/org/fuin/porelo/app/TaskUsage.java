package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Comparator;
import org.fuin.porelo.core.UsageSums;
import org.fuin.porelo.runtime.ContainerName;

/** Finds current or retained task usage for both the command line and daemon. */
public record TaskUsage(PoreloContext context) {
    /** Accepts task names that cannot traverse outside the usage directory. */
    public static boolean validTask(String task) {
        return ContainerName.isTask(task) && task.matches("[A-Za-z0-9_.-]{1,64}");
    }

    /** Reads this task's record, or its most recently retained record after removal. */
    public UsageSums.Summary read(String task) {
        if (!validTask(task)) return UsageSums.absent();
        final Path current = context.paths().tasks().usageRecord(task);
        if (Files.exists(current)
                || Files.exists(context.paths().tasks().containerState(task))
                || Files.exists(context.paths().tasks().taskRecord(task)))
            return UsageSums.read(current, ZoneId.systemDefault());
        final Path directory = current.getParent();
        if (directory == null || !Files.isDirectory(directory))
            return UsageSums.read(current, ZoneId.systemDefault());
        try (var entries = Files.list(directory)) {
            final Path latest =
                    entries.filter(
                                    file ->
                                            file.getFileName()
                                                    .toString()
                                                    .startsWith(task + ".removed-"))
                            .filter(file -> file.getFileName().toString().endsWith(".jsonl"))
                            // By name: files restored onto a machine all carry the restore's time.
                            .max(Comparator.comparing(file -> file.getFileName().toString()))
                            .orElse(current);
            return UsageSums.read(latest, ZoneId.systemDefault());
        } catch (IOException ex) {
            return UsageSums.unreadable();
        }
    }

    /**
     * Retains usage under a fresh name so a later task cannot inherit its counts. Never fails: the
     * task is gone by then, and what follows in a removal has still to run.
     */
    public void archive(String task) {
        if (!validTask(task)) return;
        final Path file = context.paths().tasks().usageRecord(task);
        try {
            long at = System.currentTimeMillis();
            Path retained = file.resolveSibling(task + ".removed-" + at + ".jsonl");
            // Two removals of a name within a millisecond must not lose the later one.
            while (Files.exists(retained)) {
                at++;
                retained = file.resolveSibling(task + ".removed-" + at + ".jsonl");
            }
            final Path directory =
                    java.util.Objects.requireNonNull(
                            file.getParent(), "usage records have a parent directory");
            org.fuin.porelo.wire.Directories.create(directory);
            Files.setPosixFilePermissions(
                    directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            try {
                Files.move(file, retained);
            } catch (java.nio.file.NoSuchFileException ex) {
                // An empty latest incarnation must hide the preceding task's retained usage.
                Files.createFile(
                        retained,
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString(
                                        "rw-------")));
            }
        } catch (IOException | RuntimeException ex) {
            // Kept where it is: the next task of this name then adds to it, which is worse, but
            // nothing is lost.
        }
    }
}
