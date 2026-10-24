package org.fuin.porelo.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.core.project.SnippetFiles;
import org.jspecify.annotations.Nullable;

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

    /** The states that determine whether reviewed files can be read from a mirror. */
    sealed interface DefaultBranch permits Found, NoMirror, Empty, Missing, Detached, TaskBranch {}

    /**
     * A default branch holding reviewed commits.
     *
     * @param ref Its full ref.
     */
    record Found(String ref) implements DefaultBranch {}

    /** The mirror has not been made yet. */
    record NoMirror() implements DefaultBranch {}

    /**
     * HEAD names an unborn branch and the mirror has no reviewed branches.
     *
     * @param ref The ref HEAD names.
     */
    record Empty(String ref) implements DefaultBranch {}

    /**
     * HEAD names a branch without a commit while other reviewed branches exist.
     *
     * @param ref The ref HEAD names.
     * @param branches One to five other branches, sorted, without task branches.
     */
    record Missing(String ref, List<String> branches) implements DefaultBranch {
        /**
         * Keeps the branch list immutable.
         *
         * @param ref The ref HEAD names.
         * @param branches The other branches.
         */
        public Missing {
            branches = List.copyOf(branches);
        }
    }

    /** HEAD does not name a branch symbolically. */
    record Detached() implements DefaultBranch {}

    /**
     * HEAD names work a task pushed, which cannot choose its own image.
     *
     * @param ref The task branch's full ref.
     */
    record TaskBranch(String ref) implements DefaultBranch {}

    /**
     * Returns whether the mirror has a default branch holding reviewed commits.
     *
     * @param runner Runs git.
     * @param mirror The bare mirror.
     * @return The branch, or why none can be read.
     */
    static DefaultBranch defaultBranch(CommandRunner runner, Path mirror) {
        if (!Files.isDirectory(mirror)) {
            return new NoMirror();
        }
        final CommandResult head =
                runner.run(
                        Command.of(
                                "git", "-C", mirror.toString(), "symbolic-ref", "--quiet", "HEAD"));
        final String ref = head.standardOutput().strip();
        if (!head.successful() || !ref.startsWith("refs/heads/")) {
            return new Detached();
        }
        if (ref.startsWith(TASK_BRANCHES)) {
            return new TaskBranch(ref);
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
        if (exists.successful()) {
            return new Found(ref);
        }
        final CommandResult listed =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                mirror.toString(),
                                "for-each-ref",
                                "--format=%(refname)",
                                "--sort=refname",
                                "refs/heads/"));
        if (!listed.successful()) {
            throw new ProjectException(
                    "Cannot list branches in " + mirror + ": " + listed.standardError().strip());
        }
        final List<String> branches =
                listed.standardOutput()
                        .lines()
                        .filter(branch -> !branch.startsWith(TASK_BRANCHES))
                        .sorted()
                        .limit(5)
                        .toList();
        return branches.isEmpty() ? new Empty(ref) : new Missing(ref, branches);
    }

    /**
     * Explains why a mirror cannot choose a repository's image, with its upstream fix.
     *
     * @param branch The mirror's default branch state.
     * @param repository The repository it mirrors.
     * @return The refusal, or {@code null} when the mirror is absent, empty or readable.
     */
    static @Nullable String refusal(DefaultBranch branch, Repository repository) {
        final String problem =
                switch (branch) {
                    case Found _, NoMirror _, Empty _ -> null;
                    case Missing missing ->
                            "names "
                                    + missing.ref()
                                    + " as its default branch, which does not exist "
                                    + branchSummary(missing.branches());
                    case Detached _ -> "has a detached HEAD instead of a default branch";
                    case TaskBranch task ->
                            "names "
                                    + task.ref()
                                    + " as its default branch, which is a task branch";
                };
        if (problem == null) {
            return null;
        }
        final String upstream = repository.upstream();
        return "the mirror of "
                + repository.name()
                + " "
                + problem
                + ", so its porelo.yml cannot be read; set the default branch of "
                + (upstream == null
                        ? "its upstream"
                        : RepositoryAddress.withoutCredential(upstream))
                + " to one that exists - the next start takes it";
    }

    private static String branchSummary(List<String> branches) {
        final List<String> names =
                branches.stream().map(ref -> ref.substring("refs/heads/".length())).toList();
        return "(its branches: " + String.join(", ", names) + ")";
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
        return switch (defaultBranch(runner, mirror)) {
            case Found found -> read(runner, mirror, found.ref(), path);
            case NoMirror _, Empty _, Missing _, Detached _, TaskBranch _ -> Optional.empty();
        };
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
