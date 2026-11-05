package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.project.BranchScheme;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.gate.LocalRepository;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** The branch a task starts from, separate from the branch its work is forwarded to. */
public record TaskBase(String branch, String source) {

    /** Durable, host-owned start information. */
    public static final String FILE = "base.json";

    /** Validates a branch before it reaches git or a workspace's environment. */
    public TaskBase {
        if (!BranchScheme.refFormat(branch)) {
            throw new ProjectException(
                    "the starting branch must be a branch name; name one with --from");
        }
    }

    /** Returns an explicit starting branch, or none when the caller names none. */
    public static @Nullable TaskBase named(@Nullable String branch) {
        return branch == null ? null : new TaskBase(branch, "FROM");
    }

    /** Takes the caller's branch only when this checkout is the selected repository. */
    public static @Nullable TaskBase requested(
            PoreloContext context,
            Project project,
            Repository repository,
            Path here,
            @Nullable String from) {
        if (from != null) {
            return named(from);
        }
        final Path top = LocalRepository.topLevel(context.runner(), here);
        if (top == null) {
            return null;
        }
        final String origin =
                context.runner()
                        .run(Command.of("git", "-C", top.toString(), "remote", "get-url", "origin"))
                        .standardOutput()
                        .strip();
        boolean matches =
                RepositoryAddress.sameUpstream(repository.upstream(), top.toString())
                        || RepositoryAddress.sameUpstream(repository.upstream(), origin);
        if (!matches
                && repository.name().equals(project.name())
                && Files.isRegularFile(top.resolve("project.yml"))) {
            matches = ProjectReader.read(top.resolve("project.yml")).name().equals(project.name());
        }
        if (!matches) {
            return null;
        }
        final var symbolic =
                context.runner()
                        .run(
                                Command.of(
                                        "git",
                                        "-C",
                                        top.toString(),
                                        "symbolic-ref",
                                        "--quiet",
                                        "--short",
                                        "HEAD"));
        if (!symbolic.successful()) {
            throw new ProjectException(
                    "your checkout is detached; name a starting branch with --from");
        }
        final String name = symbolic.standardOutput().strip();
        // A git that names no branch says nothing to choose by; the project and mirror decide.
        return name.isEmpty() ? null : new TaskBase(name, "CHECKOUT");
    }

    /**
     * Settles and verifies the branch after the mirror has refreshed, before a container exists.
     */
    public static @Nullable TaskBase select(
            PoreloContext context, Project project, Path mirror, @Nullable TaskBase requested) {
        TaskBase chosen = requested;
        if (chosen == null && project.baseBranch() != null) {
            chosen = new TaskBase(project.baseBranch(), "PROJECT");
        }
        if (chosen == null) {
            final var head =
                    context.runner()
                            .run(
                                    Command.of(
                                            "git",
                                            "-C",
                                            mirror.toString(),
                                            "symbolic-ref",
                                            "--quiet",
                                            "HEAD"));
            final String ref = head.standardOutput().strip();
            if (!head.successful() || !ref.startsWith("refs/heads/")) {
                throw new ProjectException(
                        "the mirror has no default branch; name one with --from");
            }
            chosen = new TaskBase(ref.substring("refs/heads/".length()), "DEFAULT");
        }
        final var exists =
                context.runner()
                        .run(
                                Command.of(
                                        "git",
                                        "-C",
                                        mirror.toString(),
                                        "rev-parse",
                                        "--verify",
                                        "refs/heads/" + chosen.branch() + "^{commit}"));
        if (!exists.successful()) {
            final var heads =
                    context.runner()
                            .run(
                                    Command.of(
                                            "git",
                                            "-C",
                                            mirror.toString(),
                                            "for-each-ref",
                                            "--format=%(refname)",
                                            "refs/heads/"));
            if (!heads.successful()
                    || !heads.standardOutput().isBlank()
                    || !"DEFAULT".equals(chosen.source())) {
                throw new ProjectException(
                        "starting branch '"
                                + chosen.branch()
                                + "' is not in the mirror; publish it to the repository's source and fetch again, or name another with --from");
            }
        }
        return !exists.successful() ? null : chosen;
    }

    /** Writes the choice where task state is saved for restart and later review. */
    public void writeTo(Path state) {
        try {
            Files.writeString(
                    state.resolve(FILE), Json.write(Map.of("branch", branch, "source", source)));
        } catch (IOException ex) {
            throw new UncheckedIOException("could not record the starting branch", ex);
        }
    }

    /** Keeps the choice with pending work, which outlives a removed task's state. */
    public void writeForGate(Path mirror, String task) {
        final Path record = gateRecord(mirror, task);
        try {
            org.fuin.porelo.wire.Directories.create(record);
            writeTo(record);
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "could not record the pending work's starting branch", ex);
        }
    }

    /** Reads the choice retained with a mirror's pending work. */
    public static @Nullable TaskBase waiting(Path mirror, String task) {
        return readFrom(gateRecord(mirror, task));
    }

    private static Path gateRecord(Path mirror, String task) {
        if (!task.matches(Project.TASK_NAME_PATTERN)) {
            throw new ProjectException("the pending work must have a task name");
        }
        return GatePaths.taskStart(mirror, task);
    }

    /** Reads a recorded choice; an invalid record refuses instead of choosing another branch. */
    public static @Nullable TaskBase readFrom(Path state) {
        final Path file = state.resolve(FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            if (Json.parse(Files.readString(file)) instanceof Map<?, ?> doc
                    && doc.get("branch") instanceof String branch
                    && doc.get("source") instanceof String source) {
                return new TaskBase(branch, source);
            }
            throw new ProjectException("the task's starting branch record cannot be read");
        } catch (IOException ex) {
            throw new UncheckedIOException("could not read the starting branch", ex);
        }
    }

    /** Reads live state first, then its durable copy after a restart. */
    public static @Nullable TaskBase recorded(PoreloContext context, String container) {
        final TaskBase live = readFrom(context.paths().tasks().containerState(container));
        return live != null ? live : readFrom(context.paths().tasks().taskRecord(container));
    }

    /** Returns the review base; tasks predating the record retain the mirror's default. */
    public static String reviewAgainst(PoreloContext context, Project project, String task) {
        final TaskBase base =
                recorded(context, org.fuin.porelo.runtime.ContainerName.of(project, task));
        return base == null ? "HEAD" : "refs/heads/" + base.branch();
    }

    /** Refuses a missing recorded base instead of showing only the push's last commit. */
    public static String reviewAgainst(
            PoreloContext context,
            Project project,
            String task,
            org.fuin.porelo.gate.GitGate gate) {
        final TaskBase pending = waiting(gate.mirror(), task);
        final String ref =
                pending == null
                        ? reviewAgainst(context, project, task)
                        : "refs/heads/" + pending.branch();
        if (!"HEAD".equals(ref) && !gate.resolves(ref)) {
            throw new ProjectException(
                    "the recorded starting branch "
                            + ref
                            + " is absent; fetch it before reviewing, or name --against explicitly");
        }
        return ref;
    }

    /** Returns the source in the start's sentence. */
    public String phrase() {
        return switch (source) {
            case "CHECKOUT" -> "your checkout";
            case "FROM" -> "named with --from";
            case "PROJECT" -> "base_branch in project.yml";
            default -> "the repository's default branch";
        };
    }
}
