package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildReaderException;
import org.fuin.porelo.build.api.BuildRefused;
import org.fuin.porelo.build.api.Target;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.gate.LocalRepository;
import org.jspecify.annotations.Nullable;

/**
 * The answer to one question about a project's build.
 *
 * @param project The project key.
 * @param repository The repository key.
 * @param branch The branch asked about, or null for a commit query.
 * @param commit The full object name.
 * @param verdict The forge's verdict.
 * @param detail More from the forge.
 * @param jobs The jobs and their requested log text.
 */
public record BuildStatus(
        String project,
        String repository,
        @Nullable String branch,
        String commit,
        String verdict,
        String detail,
        List<Build.Job> jobs) {

    /**
     * Keeps the answer's job list immutable.
     *
     * @param project The project key.
     * @param repository The repository key.
     * @param branch The branch, or null.
     * @param commit The object name.
     * @param verdict The verdict.
     * @param detail More from the forge.
     * @param jobs The jobs.
     */
    public BuildStatus {
        jobs = List.copyOf(jobs);
    }

    enum Reason {
        UNKNOWN_PROJECT(2),
        REPOSITORY_REQUIRED(64),
        INVALID_REVISION(64),
        NO_BUILDS(78),
        CREDENTIAL_UNAVAILABLE(77),
        READER_UNAVAILABLE(69),
        NO_BRANCH(69),
        UNREACHABLE(69),
        RATE_LIMITED(69),
        CREDENTIAL_REFUSED(69),
        NO_SUCH_REPOSITORY(69);

        final int code;

        Reason(int code) {
            this.code = code;
        }
    }

    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Reason reason;

        Refused(final Reason reason, final String sentence) {
            super(sentence);
            this.reason = reason;
        }

        int code() {
            return reason.code;
        }
    }

    record Request(
            @Nullable String project,
            @Nullable String repository,
            @Nullable String branch,
            @Nullable String commit,
            String logs) {}

    static BuildStatus read(
            final PoreloContext context,
            final Request request,
            final BuildStatusCommand.Factory factory,
            final PrintWriter err)
            throws IOException {
        if (request.commit() != null
                && !request.commit().matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new Refused(
                    Reason.INVALID_REVISION,
                    "--commit needs a full hexadecimal object name (40 or 64 characters); "
                            + "use git rev-parse HEAD, or ask with --branch=<name>.");
        }
        final WorkOrigin.Choice checkout = request.project() == null ? checkout(context) : null;
        final String name =
                request.project() == null
                        ? Objects.requireNonNull(
                                        checkout, "an inferred project has a checkout match")
                                .project()
                        : request.project();
        if (DefaultProject.is(name)) {
            throw noBuilds(name);
        }
        final Project project;
        try {
            project = ProjectReader.read(ProjectSource.require(context, name));
        } catch (ProjectException ex) {
            throw new Refused(
                    Reason.UNKNOWN_PROJECT,
                    String.valueOf(ex.getMessage())
                            + ". Use -p <project> from 'porelo project list'.");
        }
        final var builds = project.builds();
        if (builds == null) {
            throw noBuilds(project.name());
        }
        final Repository repository = repository(project, request.repository(), checkout);
        final Build.Logs logs = Build.Logs.of(request.logs());
        context.openIfShut(true, true, err);
        final Target target;
        try {
            target =
                    BuildTarget.of(
                            context,
                            Objects.requireNonNull(
                                    repository.upstream(), "an online repository has an upstream"),
                            builds.api(),
                            builds.credential());
        } catch (BuildWatch.NotNow ex) {
            throw new Refused(
                    Reason.CREDENTIAL_UNAVAILABLE,
                    ex.reason() == BuildWatch.NotNow.Reason.VAULT_SHUT
                            ? "the vault is shut; run 'porelo vault unlock', then retry this build query."
                            : context.vault().exists()
                                    ? String.valueOf(ex.getMessage())
                                    : "there is no vault; run 'porelo vault init', then store the credential with 'porelo vault put "
                                            + builds.credential()
                                            + "'.");
        }
        final Path root = context.paths().tasks().buildQueries();
        Files.createDirectories(
                root,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final Path directory =
                Files.createTempDirectory(
                        root,
                        "query-",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        final Path socket = directory.resolve("reader.sock");
        try (var reader = factory.open(context, builds.forge(), socket)) {
            final String commit =
                    request.branch() == null
                            ? Objects.requireNonNull(request.commit(), "one revision is required")
                            : reader.head(target, request.branch());
            if (commit.isEmpty()) {
                throw new Refused(
                        Reason.NO_BRANCH,
                        "no branch "
                                + request.branch()
                                + " in "
                                + target.upstream()
                                + "; choose an existing --branch=<name> or a full --commit=<sha>.");
            }
            final Build build = reader.build(target, commit, logs);
            return new BuildStatus(
                    project.name(),
                    repository.name(),
                    request.branch(),
                    redacted(commit, target),
                    build.verdict(),
                    redacted(build.detail(), target),
                    build.jobs().stream()
                            .map(
                                    job ->
                                            job.log() == null
                                                    ? new Build.Job(
                                                            redacted(job.name(), target),
                                                            redacted(job.result(), target))
                                                    : new Build.Job(
                                                            redacted(job.name(), target),
                                                            redacted(job.result(), target),
                                                            redacted(job.logText(), target)
                                                                    .getBytes(
                                                                            java.nio.charset
                                                                                    .StandardCharsets
                                                                                    .UTF_8)))
                            .toList());
        } catch (BuildRefused ex) {
            throw forgeRefusal(ex, builds.credential(), target);
        } catch (Refused ex) {
            throw new Refused(ex.reason, redacted(String.valueOf(ex.getMessage()), target));
        } catch (RuntimeException ex) {
            // An adapter can echo its token in any exception or cause; none may reach CliErrors.
            throw new BuildReaderException(
                    "the build reader gave no answer; retry the query, then check the reader installation");
        } finally {
            Files.deleteIfExists(socket);
            Files.delete(directory);
        }
    }

    private static String redacted(final String text, final Target target) {
        return text.replace(target.token(), "[redacted]");
    }

    private static Refused noBuilds(final String project) {
        return new Refused(
                Reason.NO_BUILDS,
                "project '"
                        + project
                        + "' has no builds: configuration; builds: needs security_class: online. "
                        + (DefaultProject.is(project)
                                ? "Follow a project with builds:; default's settings are fixed. "
                                : "Configure builds: in its project file. ")
                        + "See the builds: example in doc/project-file.md ('The project file').");
    }

    private static WorkOrigin.Choice checkout(final PoreloContext context) {
        final Path top = LocalRepository.topLevel(context.runner(), Path.of("."));
        if (top != null) {
            final var origin =
                    context.runner()
                            .run(
                                    Command.of(
                                            "git",
                                            "-C",
                                            top.toString(),
                                            "remote",
                                            "get-url",
                                            "origin"));
            if (origin.successful()) {
                final WorkOrigin.Choice found =
                        WorkOrigin.followedNaming(
                                context,
                                RepositoryAddress.withoutCredential(
                                        origin.standardOutput().strip()));
                if (found != null) {
                    return found;
                }
            }
        }
        throw new Refused(
                Reason.UNKNOWN_PROJECT,
                "this checkout's origin matches no followed project; "
                        + "name one with -p <project>, or follow it with 'porelo project follow <name> <url>'.");
    }

    private static Repository repository(
            final Project project,
            final @Nullable String named,
            final WorkOrigin.@Nullable Choice checkout) {
        final String key = named != null ? named : checkout == null ? null : checkout.repository();
        if (key == null && project.allRepositories().size() != 1) {
            throw new Refused(
                    Reason.REPOSITORY_REQUIRED,
                    "choose a repository with -r <name>; project '"
                            + project.name()
                            + "' has: "
                            + String.join(", ", project.repositoryNames()));
        }
        try {
            return GateSupport.repository(project, key);
        } catch (ProjectException ex) {
            throw new Refused(
                    Reason.REPOSITORY_REQUIRED,
                    String.valueOf(ex.getMessage())
                            + ". Choose -r <name> from: "
                            + String.join(", ", project.repositoryNames()));
        }
    }

    private static Refused forgeRefusal(
            final BuildRefused ex, final String credential, final Target target) {
        final Reason reason;
        final String next;
        switch (ex.reason()) {
            case UNREACHABLE -> {
                reason = Reason.UNREACHABLE;
                next = "check the forge API address and connection, then retry";
            }
            case RATE_LIMITED -> {
                reason = Reason.RATE_LIMITED;
                next =
                        ex.retryAfterSeconds() == 0
                                ? "retry after the forge's rate limit resets"
                                : "retry after " + ex.retryAfterSeconds() + " seconds";
            }
            case CREDENTIAL_REFUSED -> {
                reason = Reason.CREDENTIAL_REFUSED;
                next =
                        "replace the credential with 'porelo vault put "
                                + credential
                                + "', then retry";
            }
            case NO_SUCH_REPOSITORY -> {
                reason = Reason.NO_SUCH_REPOSITORY;
                next =
                        "check the repository upstream in the project file and the credential's access, then retry";
            }
            default -> throw new IllegalStateException("every forge refusal has a next action");
        }
        return new Refused(
                reason,
                redacted(ex.reason().wire() + ": " + ex.detail() + "; " + next + ".", target));
    }

    /**
     * Returns the result a script branches on.
     *
     * @return One for a failed or cancelled build, zero for every other answer.
     */
    public int exitCode() {
        return Build.FAILURE.equals(verdict) || Build.CANCELLED.equals(verdict) ? 1 : 0;
    }

    /**
     * Returns the answer's fields as JSON values.
     *
     * @return One object's fields, including each job's log as text.
     */
    public Map<String, Object> json() {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("project", project);
        answer.put("repository", repository);
        answer.put("branch", branch);
        answer.put("commit", commit);
        answer.put("verdict", verdict);
        answer.put("detail", detail);
        answer.put(
                "jobs",
                jobs.stream()
                        .map(
                                job ->
                                        Map.of(
                                                "name",
                                                job.name(),
                                                "result",
                                                job.result(),
                                                "log",
                                                job.logText()))
                        .toList());
        return answer;
    }

    /**
     * Returns the build for a terminal.
     *
     * @return Its commit, verdict, jobs and requested logs.
     */
    public String human() {
        final StringBuilder text =
                new StringBuilder("commit: ").append(commit).append("\nverdict: ").append(verdict);
        if (!detail.isEmpty()) {
            text.append("\ndetail: ").append(detail);
        }
        for (final Build.Job job : jobs) {
            text.append("\njob: ").append(job.name()).append(" - ").append(job.result());
            if (!job.logText().isEmpty()) {
                text.append("\nlog: ").append(job.name()).append('\n').append(job.logText());
            }
        }
        return terminalText(text.toString());
    }

    static String terminalText(final String text) {
        final StringBuilder safe = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            final char ch = text.charAt(i);
            if (Character.isISOControl(ch) && ch != '\n' && ch != '\t') {
                safe.append("\\x")
                        .append(Character.forDigit(ch >> 4, 16))
                        .append(Character.forDigit(ch & 15, 16));
            } else if (Character.getType(ch) == Character.FORMAT) {
                // Bidirectional overrides and invisible joiners would show a line in another order.
                safe.append(String.format("\\u%04x", (int) ch));
            } else {
                safe.append(ch);
            }
        }
        return safe.toString();
    }
}
