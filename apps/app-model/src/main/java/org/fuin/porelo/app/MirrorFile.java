package org.fuin.porelo.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.SnippetFiles;

/**
 * Reads a file from a repository's mirror, at its default branch: reviewed work only.
 *
 * <p><strong>Never a branch a task pushed.</strong> A task's work lands on {@code porelo/<task>}
 * and reaches the default branch only once a person approved it, so a file read here cannot be one
 * the task wrote for itself. A mirror whose HEAD names such a branch is read as having none.
 */
final class MirrorFile {

    /** Where a task's own work lands, and which is never read here. */
    private static final String TASK_BRANCHES = "refs/heads/porelo/";

    private MirrorFile() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the default branch's ref, when the mirror has one that is not a task's.
     *
     * @param runner Runs git.
     * @param mirror The bare mirror.
     * @return The ref, such as {@code refs/heads/main}, or empty.
     */
    static Optional<String> defaultBranch(CommandRunner runner, Path mirror) {
        if (!Files.isDirectory(mirror)) {
            return Optional.empty();
        }
        final CommandResult head =
                runner.run(
                        Command.of(
                                "git", "-C", mirror.toString(), "symbolic-ref", "--quiet", "HEAD"));
        final String ref = head.standardOutput().strip();
        if (!head.successful() || !ref.startsWith("refs/heads/") || ref.startsWith(TASK_BRANCHES)) {
            return Optional.empty();
        }
        final CommandResult exists =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                mirror.toString(),
                                "rev-parse",
                                "--verify",
                                "--quiet",
                                ref + "^{commit}"));
        return exists.successful() ? Optional.of(ref) : Optional.empty();
    }

    /**
     * Reads a file at the mirror's default branch.
     *
     * @param runner Runs git.
     * @param mirror The bare mirror.
     * @param path The file's path in the repository.
     * @return Its content, or empty when there is no default branch or no such file.
     * @throws ProjectException If the path is a link, or git cannot read what it lists.
     */
    static Optional<String> read(CommandRunner runner, Path mirror, String path) {
        final Optional<String> branch = defaultBranch(runner, mirror);
        if (branch.isEmpty()) {
            return Optional.empty();
        }
        return read(runner, mirror, branch.get(), path);
    }

    /**
     * Reads a snippet file the same way, from the same branch as the file naming it.
     *
     * @param runner Runs git.
     * @param mirror The bare mirror.
     * @param branch The branch the naming file was read from.
     * @return The reader.
     */
    static SnippetFiles snippets(CommandRunner runner, Path mirror, String branch) {
        return (named, origin) -> {
            if (named.startsWith("/") || java.util.Arrays.asList(named.split("/")).contains("..")) {
                throw new ProjectException(
                        origin
                                + ": 'image.snippet_file' must name a file of the repository, not "
                                + named);
            }
            return read(runner, mirror, branch, named)
                    .orElseThrow(
                            () -> new ProjectException(origin + ": no image snippet at " + named));
        };
    }

    private static Optional<String> read(
            CommandRunner runner, Path mirror, String branch, String path) {
        final CommandResult listed =
                runner.run(
                        Command.of("git", "-C", mirror.toString(), "ls-tree", branch, "--", path));
        if (!listed.successful()) {
            throw new ProjectException(
                    "Cannot list "
                            + path
                            + " in "
                            + mirror
                            + ": "
                            + listed.standardError().strip());
        }
        final String entry = listed.standardOutput().strip();
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (!entry.startsWith("100644 blob ") && !entry.startsWith("100755 blob ")) {
            // A link would be read as its target's name, and a tree is no file; neither is what
            // the repository's authors reviewed as a file.
            throw new ProjectException(
                    mirror + ": " + path + " at " + branch + " is not a plain file");
        }
        final CommandResult shown =
                runner.run(Command.of("git", "-C", mirror.toString(), "show", branch + ":" + path));
        if (!shown.successful()) {
            throw new ProjectException(
                    "Cannot read " + path + " in " + mirror + ": " + shown.standardError().strip());
        }
        return Optional.of(shown.standardOutput());
    }
}
