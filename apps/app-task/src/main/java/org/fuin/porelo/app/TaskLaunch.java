package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.function.IntUnaryOperator;
import org.fuin.porelo.agent.api.InstalledAgent;
import org.fuin.porelo.agent.api.InstalledAgents;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.wire.Directories;
import org.jspecify.annotations.Nullable;

/**
 * Starting a task: from reading the project file to a container that is up, wired and firewalled.
 *
 * <p>Lifted out of {@code TaskRunCommand} after the six wirings, so that starting a task is
 * something the domain does rather than something a CLI class does. The CLI and the interface have
 * to reach identical behavior through the same calls, and this was the last operation where they
 * did not: the daemon had to spawn the CLI and read a line of its output, because running the
 * command was the only way to start a task.
 *
 * <p><strong>The caller is handed the running task inside this class's own resource scope.</strong>
 * The installed agents are processes this opens and closes, and the paths that follow a start -
 * running the agent to completion, attaching a terminal - use them. Returning before closing them
 * would leak a process per task; closing them first would break the attach.
 *
 * <p>Public, unlike the wirings beside it, because the daemon calls it: that is the whole point of
 * lifting it out of the command.
 */
public final class TaskLaunch {

    private static final String MIRROR_IMAGE_AGE =
            "porelo: repository image read from the mirror as of its last fetch";

    /** The variable a task is told its repository's issue prefixes in, comma-separated. */
    public static final String ISSUE_PREFIXES = "PORELO_ISSUE_PREFIXES";

    /**
     * The variable a task is told in where a reading of an issue writes its findings, relative to
     * its repository's root.
     */
    public static final String READINGS_DIRECTORY = "PORELO_READINGS_DIRECTORY";

    /**
     * What to start, as the operator asked for it.
     *
     * @param task Task name, which becomes the ref an agent pushes to.
     * @param projectFile The project file to read.
     * @param agentName Value of {@code --agent}, or {@code null} for the only one installed.
     * @param providerName Value of {@code --provider}, or {@code null}.
     * @param credentialType Value of {@code --credential-type}, or {@code null}.
     * @param tokenHours How long the task's own token lasts.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     * @param noGate Whether to run without a workspace and gate.
     * @param dryRun Whether to stop after reporting what the project opens.
     * @param clearance What to do about a blocked connection: prompt, allow, deny or off.
     * @param keep Whether the container survives the end of the run.
     * @param repository Which of the project's repositories the task works on. Never guessed: a
     *     request that names none is refused, because a command whose meaning depends on how many
     *     repositories exist today is one nobody can read.
     * @param parameters The values a start hands in, each with where it came from, not yet checked
     *     against the project's declarations.
     * @param branch Value of {@code --branch}: the task's branch at the forge named outright, or
     *     {@code null} for the one the project's scheme makes.
     * @param base The caller's starting branch, or null for the project and mirror default.
     * @param subagents Value of {@code --subagents}: the most subagents the agent may run at the
     *     same time, or {@code null} for what the project file says, and none when it says nothing.
     * @param parent The task whose MCP endpoint asked for this start, by container name, or {@code
     *     null} for a person's start. The daemon takes it from the socket the start came over.
     * @param shortName Optional container spelling, fixed when the task is created.
     * @param named The task name as the caller gave it, or {@code null} when the start took it from
     *     the repository or numbered it; recorded so a client shows the name a person chose.
     * @param allRepositories Whether the task reads every repository of its project, read only and
     *     with no gate, instead of working on one; refused beside an option that names one.
     * @param result The name of the one file the task hands back at its end, or {@code null} for
     *     none; fixed when the task is made.
     */
    public record Request(
            String task,
            Path projectFile,
            @Nullable String agentName,
            @Nullable String providerName,
            @Nullable String credentialType,
            int tokenHours,
            @Nullable String upstream,
            boolean noGate,
            boolean dryRun,
            String clearance,
            boolean keep,
            org.fuin.porelo.wire.TaskMode mode,
            @Nullable String prompt,
            @Nullable String model,
            @Nullable Integer maxTurns,
            int minutes,
            @Nullable String repository,
            java.util.Map<String, String> credentials,
            java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> parameters,
            @Nullable String branch,
            @Nullable Integer subagents,
            @Nullable TaskBase base,
            @Nullable String parent,
            @Nullable String named,
            @Nullable String shortName,
            boolean allRepositories,
            @Nullable TaskWork work,
            @Nullable String result) {

        /** Constructor for a start whose work nobody named and that names no file to hand back. */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep,
                org.fuin.porelo.wire.TaskMode mode,
                @Nullable String prompt,
                @Nullable String model,
                @Nullable Integer maxTurns,
                int minutes,
                @Nullable String repository,
                java.util.Map<String, String> credentials,
                java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> parameters,
                @Nullable String branch,
                @Nullable Integer subagents,
                @Nullable TaskBase base,
                @Nullable String parent,
                @Nullable String named,
                @Nullable String shortName,
                boolean allRepositories) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    repository,
                    credentials,
                    parameters,
                    branch,
                    subagents,
                    base,
                    parent,
                    named,
                    shortName,
                    allRepositories,
                    null,
                    null);
        }

        /**
         * Says why the file this request names to hand back cannot be one, or {@code null}.
         *
         * @return The refusal, a usage error; {@code null} when it names none or a file's name.
         */
        public @Nullable String resultRefusal() {
            final String refused = TaskResult.refusal(result);
            return refused == null ? null : "--result: " + refused;
        }

        /** Constructor for a start on one repository, or on none. */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep,
                org.fuin.porelo.wire.TaskMode mode,
                @Nullable String prompt,
                @Nullable String model,
                @Nullable Integer maxTurns,
                int minutes,
                @Nullable String repository,
                java.util.Map<String, String> credentials,
                java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> parameters,
                @Nullable String branch,
                @Nullable Integer subagents,
                @Nullable TaskBase base,
                @Nullable String parent,
                @Nullable String named,
                @Nullable String shortName) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    repository,
                    credentials,
                    parameters,
                    branch,
                    subagents,
                    base,
                    parent,
                    named,
                    shortName,
                    false);
        }

        /**
         * Says why this request cannot read every repository as it asks, or {@code null}.
         *
         * <p>Each option refused names one repository or what its gate does with it, and a task
         * that reads them all has neither: taking one silently would start a task other than the
         * one asked for.
         *
         * @return The refusal, a usage error; {@code null} when the request reads one repository or
         *     reads them all as it may.
         */
        public @Nullable String allRepositoriesRefusal() {
            if (!allRepositories) {
                return null;
            }
            final java.util.List<String> named = new java.util.ArrayList<>();
            if (repository != null && !repository.isBlank()) {
                named.add("--repository");
            }
            if (branch != null) {
                named.add("--branch");
            }
            if (base != null && "FROM".equals(base.source())) {
                named.add("--from");
            }
            if (noGate) {
                named.add("--no-gate");
            }
            if (upstream != null) {
                named.add("--upstream");
            }
            return named.isEmpty() ? null : allRepositoriesBeside(named);
        }

        /** Constructor for a start without a task short spelling. */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep,
                org.fuin.porelo.wire.TaskMode mode,
                @Nullable String prompt,
                @Nullable String model,
                @Nullable Integer maxTurns,
                int minutes,
                @Nullable String repository,
                java.util.Map<String, String> credentials,
                java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> parameters,
                @Nullable String branch,
                @Nullable Integer subagents,
                @Nullable TaskBase base,
                @Nullable String parent,
                @Nullable String named) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    repository,
                    credentials,
                    parameters,
                    branch,
                    subagents,
                    base,
                    parent,
                    named,
                    null);
        }

        /**
         * Constructor for a start whose name nobody gave.
         *
         * @param task Task name.
         * @param projectFile Project file.
         * @param agentName Agent to use, or {@code null} for the only one installed.
         * @param providerName Provider to use, or {@code null} for the agent's own.
         * @param credentialType Credential kind, or {@code null} for the stored one.
         * @param tokenHours How long the phantom token is accepted.
         * @param upstream Upstream for the gate, or {@code null}.
         * @param noGate Whether to run without a workspace and gate.
         * @param dryRun Whether to report rather than start.
         * @param clearance What to do with a blocked connection.
         * @param keep Whether the container survives the run.
         * @param mode How somebody is involved.
         * @param prompt What an unattended task is asked, or {@code null}.
         * @param model Model to request, or {@code null}.
         * @param maxTurns Turn limit, or {@code null}.
         * @param minutes How long an unattended run may take.
         * @param repository The repository, or {@code null}.
         * @param credentials Credentials beyond the agent's own.
         * @param parameters The values a start hands in.
         * @param branch Value of {@code --branch}, or {@code null}.
         * @param subagents Value of {@code --subagents}, or {@code null}.
         * @param base The starting branch, or {@code null}.
         * @param parent The task that asked for this start, or {@code null}.
         */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep,
                org.fuin.porelo.wire.TaskMode mode,
                @Nullable String prompt,
                @Nullable String model,
                @Nullable Integer maxTurns,
                int minutes,
                @Nullable String repository,
                java.util.Map<String, String> credentials,
                java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> parameters,
                @Nullable String branch,
                @Nullable Integer subagents,
                @Nullable TaskBase base,
                @Nullable String parent) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    repository,
                    credentials,
                    parameters,
                    branch,
                    subagents,
                    base,
                    parent,
                    null);
        }

        /** Constructor for a start a person asked for. */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep,
                org.fuin.porelo.wire.TaskMode mode,
                @Nullable String prompt,
                @Nullable String model,
                @Nullable Integer maxTurns,
                int minutes,
                @Nullable String repository,
                java.util.Map<String, String> credentials,
                java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> parameters,
                @Nullable String branch,
                @Nullable Integer subagents,
                @Nullable TaskBase base) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    repository,
                    credentials,
                    parameters,
                    branch,
                    subagents,
                    base,
                    null);
        }

        /** Constructor leaving the starting branch to the project and mirror. */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep,
                org.fuin.porelo.wire.TaskMode mode,
                @Nullable String prompt,
                @Nullable String model,
                @Nullable Integer maxTurns,
                int minutes,
                @Nullable String repository,
                java.util.Map<String, String> credentials,
                java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> parameters,
                @Nullable String branch,
                @Nullable Integer subagents) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    repository,
                    credentials,
                    parameters,
                    branch,
                    subagents,
                    null);
        }

        /**
         * Constructor for a request that leaves its subagents to the project file.
         *
         * @param task Task name.
         * @param projectFile Project file.
         * @param agentName Agent to use, or {@code null}.
         * @param providerName Provider, or {@code null}.
         * @param credentialType Credential type override, or {@code null}.
         * @param tokenHours Phantom token lifetime.
         * @param upstream Upstream override, or {@code null}.
         * @param noGate Whether to skip the gate.
         * @param dryRun Whether to stop before starting.
         * @param clearance Clearance mode.
         * @param keep Whether to keep the container.
         * @param mode What the task is for.
         * @param prompt Prompt, or {@code null}.
         * @param model Model, or {@code null}.
         * @param maxTurns Turn limit, or {@code null}.
         * @param minutes Minutes an unattended run may take.
         * @param repository Which repository, or {@code null}.
         * @param credentials Further credentials, by vault entry.
         * @param parameters The values a start hands in.
         * @param branch The task's branch at the forge, or {@code null}.
         */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep,
                org.fuin.porelo.wire.TaskMode mode,
                @Nullable String prompt,
                @Nullable String model,
                @Nullable Integer maxTurns,
                int minutes,
                @Nullable String repository,
                java.util.Map<String, String> credentials,
                java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> parameters,
                @Nullable String branch) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    repository,
                    credentials,
                    parameters,
                    branch,
                    null);
        }

        /**
         * Constructor for a request that names no branch: the project's scheme makes it.
         *
         * @param task Task name.
         * @param projectFile Project file.
         * @param agentName Agent to use, or {@code null}.
         * @param providerName Provider, or {@code null}.
         * @param credentialType Credential type override, or {@code null}.
         * @param tokenHours Phantom token lifetime.
         * @param upstream Upstream override, or {@code null}.
         * @param noGate Whether to skip the gate.
         * @param dryRun Whether to stop before starting.
         * @param clearance Clearance mode.
         * @param keep Whether to keep the container.
         * @param mode What the task is for.
         * @param prompt Prompt, or {@code null}.
         * @param model Model, or {@code null}.
         * @param maxTurns Turn limit, or {@code null}.
         * @param minutes Minutes an unattended run may take.
         * @param repository Which repository, or {@code null}.
         * @param credentials Further credentials, by vault entry.
         * @param parameters The values a start hands in.
         */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep,
                org.fuin.porelo.wire.TaskMode mode,
                @Nullable String prompt,
                @Nullable String model,
                @Nullable Integer maxTurns,
                int minutes,
                @Nullable String repository,
                java.util.Map<String, String> credentials,
                java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> parameters) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    repository,
                    credentials,
                    parameters,
                    null);
        }

        /**
         * Constructor for a request that hands in no values, as every start did before a project
         * could declare them.
         *
         * @param task Task name.
         * @param projectFile Project file.
         * @param agentName Agent to use, or {@code null}.
         * @param providerName Provider, or {@code null}.
         * @param credentialType Credential type override, or {@code null}.
         * @param tokenHours Phantom token lifetime.
         * @param upstream Upstream override, or {@code null}.
         * @param noGate Whether to skip the gate.
         * @param dryRun Whether to stop before starting.
         * @param clearance Clearance mode.
         * @param keep Whether to keep the container.
         * @param mode What the task is for.
         * @param prompt Prompt, or {@code null}.
         * @param model Model, or {@code null}.
         * @param maxTurns Turn limit, or {@code null}.
         * @param minutes Minutes an unattended run may take.
         * @param repository Which repository, or {@code null}.
         * @param credentials Further credentials, by vault entry.
         */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep,
                org.fuin.porelo.wire.TaskMode mode,
                @Nullable String prompt,
                @Nullable String model,
                @Nullable Integer maxTurns,
                int minutes,
                @Nullable String repository,
                java.util.Map<String, String> credentials) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    repository,
                    credentials,
                    java.util.Map.of());
        }

        /**
         * Constructor for a request that names no credentials of its own beyond the project's.
         *
         * @param task Task name.
         * @param projectFile Project file.
         * @param agentName Agent to use, or {@code null}.
         * @param providerName Provider, or {@code null}.
         * @param credentialType Credential type override, or {@code null}.
         * @param tokenHours Phantom token lifetime.
         * @param upstream Upstream override, or {@code null}.
         * @param noGate Whether to skip the gate.
         * @param dryRun Whether to stop before starting.
         * @param clearance Clearance mode.
         * @param keep Whether to keep the container.
         * @param mode What the task is for.
         * @param prompt Prompt, or {@code null}.
         * @param model Model, or {@code null}.
         * @param maxTurns Turn limit, or {@code null}.
         * @param minutes Minutes an unattended run may take.
         * @param repository Which repository, or {@code null}.
         */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep,
                org.fuin.porelo.wire.TaskMode mode,
                @Nullable String prompt,
                @Nullable String model,
                @Nullable Integer maxTurns,
                int minutes,
                @Nullable String repository) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    repository,
                    java.util.Map.of());
        }

        /**
         * Constructor for a request that predates the repository being named.
         *
         * @param task Task name.
         * @param projectFile Project file.
         * @param agentName Agent to use, or {@code null}.
         * @param providerName Provider to use, or {@code null}.
         * @param credentialType Credential kind, or {@code null}.
         * @param tokenHours How long the phantom token is accepted.
         * @param upstream Upstream for the gate, or {@code null}.
         * @param noGate Whether to run without a workspace and gate.
         * @param dryRun Whether to report rather than start.
         * @param clearance What to do with a blocked connection.
         * @param keep Whether the container survives the run.
         * @param mode How somebody is involved.
         * @param prompt What to run unattended, or {@code null}.
         * @param model Model to ask for, or {@code null}.
         * @param maxTurns Turn limit, or {@code null}.
         * @param minutes How long the agent may run.
         */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep,
                org.fuin.porelo.wire.TaskMode mode,
                @Nullable String prompt,
                @Nullable String model,
                @Nullable Integer maxTurns,
                int minutes) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    null);
        }

        /**
         * Constructor for a request that does not say how somebody is involved.
         *
         * @param task Task name.
         * @param projectFile Project file.
         * @param agentName Agent to use, or {@code null} for the only one installed.
         * @param providerName Provider to use, or {@code null} for the agent's own.
         * @param credentialType Credential kind, or {@code null} for the stored one.
         * @param tokenHours How long the phantom token is accepted.
         * @param upstream Upstream for the gate, or {@code null}.
         * @param noGate Whether to run without a workspace and gate.
         * @param dryRun Whether to report rather than start.
         * @param clearance What to do with a blocked connection.
         * @param keep Whether the container survives the run.
         */
        public Request(
                String task,
                Path projectFile,
                @Nullable String agentName,
                @Nullable String providerName,
                @Nullable String credentialType,
                int tokenHours,
                @Nullable String upstream,
                boolean noGate,
                boolean dryRun,
                String clearance,
                boolean keep) {
            this(
                    task,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    org.fuin.porelo.wire.TaskMode.SHELL,
                    null,
                    null,
                    null,
                    DEFAULT_MINUTES);
        }

        /**
         * Returns this request for another task name.
         *
         * @param name The task name.
         * @return The request.
         */
        public Request withTask(String name) {
            return new Request(
                    name,
                    projectFile,
                    agentName,
                    providerName,
                    credentialType,
                    tokenHours,
                    upstream,
                    noGate,
                    dryRun,
                    clearance,
                    keep,
                    mode,
                    prompt,
                    model,
                    maxTurns,
                    minutes,
                    repository,
                    credentials,
                    parameters,
                    branch,
                    subagents,
                    base,
                    parent,
                    // The same task by its other name: a person who named it named it so.
                    named == null ? null : name,
                    shortName,
                    allRepositories,
                    work,
                    result);
        }
    }

    /**
     * A task that is up, and what a caller needs to do anything with it.
     *
     * @param runner Runs commands in it.
     * @param agents The installed agents, open only for the duration of the call.
     * @param selected The agent this task runs, or {@code null}.
     * @param container Container name.
     * @param environment What the container was given.
     * @param project The project it belongs to.
     * @param cleanUp Turns an exit code into the final one, removing the container unless the run
     *     asked to keep it. Handed over rather than duplicated: whether a container survives is one
     *     decision, and the attaching path needs it too.
     * @param model The model its agent runs on as the start settled it, or {@code null} for the
     *     agent's own default; an attended start hands it to the agent as the unattended run does.
     */
    public record Running(
            TaskRunner runner,
            InstalledAgents agents,
            @Nullable InstalledAgent selected,
            String container,
            java.util.Map<String, String> environment,
            Project project,
            IntUnaryOperator cleanUp,
            @Nullable String model) {

        /**
         * Creates a running task whose agent runs on its own default model.
         *
         * @param runner Runs commands in it.
         * @param agents The installed agents.
         * @param selected The agent, or {@code null}.
         * @param container Container name.
         * @param environment What the container was given.
         * @param project The project.
         * @param cleanUp Turns an exit code into the final one.
         */
        public Running(
                TaskRunner runner,
                InstalledAgents agents,
                @Nullable InstalledAgent selected,
                String container,
                java.util.Map<String, String> environment,
                Project project,
                IntUnaryOperator cleanUp) {
            this(runner, agents, selected, container, environment, project, cleanUp, null);
        }

        /** Without what it holds, as a line must never carry that. */
        @Override
        public String toString() {
            return "Running[" + container + "]";
        }
    }

    /** How long an unattended agent may run when nothing says otherwise. */
    public static final int DEFAULT_MINUTES = 30;

    /** What a task that reads every repository of its project is named when nobody names it. */
    public static final String ALL_REPOSITORIES_TASK = "all";

    /**
     * Returns the refusal of {@code --all-repositories} beside options that name one repository or
     * its gate, in the words every interface says it with.
     *
     * @param named The options given beside it.
     * @return The sentence.
     */
    public static String allRepositoriesBeside(final java.util.List<String> named) {
        return "--all-repositories reads every repository of the project, read only and with no"
                + " gate, so it takes no "
                + String.join(", ", named);
    }

    /**
     * What {@link #runAgent} answers when there is no agent to run.
     *
     * <p>Distinct from the agent's own failure on purpose: "nothing was installed to do this" and
     * "the agent tried and failed" need different things from whoever reads it, and a caller that
     * collapsed them would send somebody looking through an empty log for a run that never started.
     */
    public static final int NO_AGENT = 69;

    /** Exit code of a run whose time limit passed, or whose podman exec failed. */
    static final int TIMED_OUT = 124;

    /** What happens once a task is up. */
    @FunctionalInterface
    public interface AfterStart {

        /**
         * Takes over a running task.
         *
         * @param running The task, valid only for the duration of this call.
         * @return The exit code the run ends with.
         * @throws Exception If taking over fails; the launch reports it and cleans up.
         */
        int started(Running running) throws Exception;
    }

    private java.util.function.@Nullable Consumer<String> runLines;

    private @Nullable RunResult runResult;

    private long runLinesDropped;

    private final PoreloContext context;

    private final StartSteps steps;

    private final HostPerson host;

    private final OrganizationProjects projects;

    private final @Nullable Path starterAgent;

    /** What to start. Replaced once, when a container name given as the task is taken apart. */
    private Request request;

    /**
     * The repository the task works on, once the start has settled it; its agent is taken when
     * {@code --agent} names none.
     */
    private org.fuin.porelo.core.project.@Nullable Repository workedOn;

    /**
     * The most subagents the task's agent may run at the same time, once the start has settled it
     * from {@code --subagents} or the project file; none until then.
     */
    private int subagents;

    /** The model the task runs on, once the start has settled it with its provider. */
    private ModelChoice.Settled model = ModelChoice.Settled.AGENTS;

    /** The model this call's run hands its agent: the settled one, or a task's recorded one. */
    private @Nullable String runModel;

    /**
     * Settles the model with the provider and refuses one the start cannot take: a model that is no
     * word, a provider the files name that this machine does not know, a model the agent has no
     * flag for, and, for a start through a task's MCP endpoint, one the project file's list does
     * not allow for the agent, whichever file named it.
     *
     * @return Whether the start is refused, which is said.
     */
    private boolean refusesModel(
            final @Nullable InstalledAgent agent,
            final Project project,
            final java.nio.file.Path projectFile,
            final PrintWriter out,
            final PrintWriter err) {
        String refused = ModelChoice.refusal(request.model(), "the model asked for");
        if (refused == null) {
            refused = ModelChoice.unknownProvider(project, context.providers().keySet());
        }
        if (refused == null && agent == null && request.model() != null) {
            // Recorded on a task no agent runs in, it would be shown as a model nothing uses.
            refused =
                    "no agent runs in this task, so the model "
                            + request.model()
                            + " would change nothing; start it without --model";
        }
        SelectedProvider serving = null;
        if (refused == null && agent != null) {
            try {
                serving = credentials().provider(agent);
            } catch (org.fuin.porelo.agent.api.AgentException ex) {
                refused = CliErrors.reason(ex);
            }
        }
        if (refused == null) {
            model =
                    ModelChoice.settle(
                            request.model(), project, serving == null ? null : serving.name());
            runModel = model.model();
        }
        if (refused == null
                && model.model() != null
                && agent != null
                && agent.definition().headless().modelFlag() == null) {
            refused =
                    agent.name()
                            + " does not take a model, so "
                            + model.model()
                            + (org.fuin.porelo.core.project.ModelDefault.START.equals(model.from())
                                    ? " would change nothing; start it without --model"
                                    : " named by the "
                                            + model.from()
                                            + " file for "
                                            + (serving == null ? "its provider" : serving.name())
                                            + " would change nothing; start another agent, or"
                                            + " remove it from the file");
        }
        if (refused == null && request.parent() != null && agent != null) {
            // A file that allows no endpoint has no list to hold a start to; one that cannot be
            // read allows nothing.
            java.util.@Nullable Optional<org.fuin.porelo.core.project.McpEndpoint> endpoint;
            try {
                endpoint = ProjectReader.endpoint(projectFile);
            } catch (ProjectException ex) {
                endpoint = null;
            }
            if (endpoint == null
                    || endpoint.isPresent()
                            && !endpoint.get().allows(agent.name(), model.model())) {
                refused =
                        "the project file's mcp.agents does not allow "
                                + agent.name()
                                + (model.model() == null
                                        ? " on its own default model"
                                        : " with model " + model.model());
            }
        }
        if (refused != null) {
            err.println("porelo: " + refused);
            err.println("porelo: nothing was created");
            err.flush();
            return true;
        }
        if (model.model() != null) {
            out.println(
                    "model          "
                            + model.model()
                            + (model.label().isEmpty() ? "" : " (" + model.label() + ")")
                            + "  (from "
                            + model.from()
                            + ")");
            out.flush();
        }
        return false;
    }

    /** What the start says {@link #subagents} came from, as a refusal names it. */
    private String subagentsAsked = "--subagents 0";

    /**
     * Constructor.
     *
     * @param context Where podman, the paths and the vault come from.
     * @param request What to start.
     */
    public TaskLaunch(PoreloContext context, Request request) {
        this(context, request, new StartSteps(step -> {}));
    }

    /**
     * Creates a launch with progress.
     *
     * @param context The machine.
     * @param request What to start.
     * @param steps Where progress goes.
     */
    public TaskLaunch(PoreloContext context, Request request, StartSteps steps) {
        this(context, request, steps, new HostPerson(context), agentSocket());
    }

    /** Creates a launch with the host's helper and the starter's SSH agent. */
    TaskLaunch(
            PoreloContext context,
            Request request,
            StartSteps steps,
            HostPerson host,
            @Nullable Path starterAgent) {
        this(context, request, steps, host, starterAgent, OrganizationProjects.of(context));
    }

    /** Creates a launch with the host's project files and helpers. */
    TaskLaunch(
            PoreloContext context,
            Request request,
            StartSteps steps,
            HostPerson host,
            @Nullable Path starterAgent,
            OrganizationProjects projects) {
        this.context = context;
        this.request = request;
        this.steps = steps;
        this.host = host;
        this.projects = projects;
        this.starterAgent = starterAgent;
    }

    /** Who asks an installed ecosystem adapter for its tool's settings; a test answers it. */
    private EcosystemDetector adapters = EcosystemDetector.INSTALLED;

    /**
     * Asks the ecosystem adapters through another.
     *
     * @param asking Who asks an installed adapter.
     * @return This launch.
     */
    public TaskLaunch configuringWith(final EcosystemDetector asking) {
        this.adapters = asking;
        return this;
    }

    /** Says when a stopped task's recorded repository settings differ from the file now. */
    private void settingsMovedOn(
            final java.nio.file.Path projectFile,
            final String project,
            final org.fuin.porelo.core.project.Repository repository,
            final PrintWriter out) {
        final org.fuin.porelo.wire.TaskProfile earlier =
                keptProfile(startContainer(ProjectReader.read(projectFile)));
        if (earlier == null || earlier.settingsHash() == null || earlier.settingsHash().isEmpty()) {
            return;
        }
        final RepositoryOwnSettings.Found now =
                DefaultProject.is(project)
                        ? RepositoryOwnSettings.of(context, projectFile, repository)
                        : RepositoryOwnSettings.ofOrganization(
                                context, projectFile, project, repository);
        final String hash = now == null ? "" : now.hash();
        if (!hash.equals(earlier.settingsHash())) {
            out.println(
                    "settings       "
                            + ImageChoice.FILE
                            + " of "
                            + repository.name()
                            + " changed since this task was created; this task keeps the egress and the other"
                            + " settings it held before, and a new task takes the new ones");
            out.flush();
        }
    }

    /** Says each setting a repository's own file gives, with the file that gave it. */
    private static void said(final RepositoryOwnSettings.Found own, final PrintWriter out) {
        for (final var each : own.asked()) {
            out.println(
                    "setting        "
                            + RepositorySettingsText.line(each)
                            + (each.removed()
                                    ? ""
                                    : "  (from "
                                            + own.sources().getOrDefault(each.key(), "?")
                                            + ")"));
        }
        out.flush();
    }

    /** The hash of the repository overrides this start applied before the fetch. */
    private String ownSettingsHash = "";

    /**
     * Returns the project with a repository's own files in 'default' added, each setting they ask
     * for shown with its source; asked once when this machine has not allowed it.
     *
     * @return The project to start under, or {@code null} when what is asked was not allowed, which
     *     is said.
     * @throws ProjectException If a file cannot be read, says what only the project may, or makes
     *     no valid project.
     */
    private @org.jspecify.annotations.Nullable Project withOwnSettings(
            final Project project,
            final java.nio.file.Path projectFile,
            final org.fuin.porelo.core.project.Repository repository,
            final PrintWriter out,
            final PrintWriter err) {
        final RepositoryOwnSettings.Found own =
                repositorySettings(project, projectFile, repository);
        if (!DefaultProject.is(project.name())) {
            ownSettingsHash = own == null ? "" : own.hash();
            if (own == null) {
                return project;
            }
            said(own, out);
            return own.project();
        }
        ownSettingsHash = own == null ? "" : own.hash();
        if (own == null) {
            return project;
        }
        said(own, out);
        final java.util.List<org.fuin.porelo.core.project.file.RepositorySettings.Asked> toAllow =
                own.toAllow(context, repository.name());
        if (toAllow.isEmpty()) {
            return own.project();
        }
        if (request.dryRun()) {
            out.println(
                    "settings       "
                            + toAllow.size()
                            + " not allowed on this machine yet; a start asks first");
            return own.project();
        }
        final String refused = RepositorySettingsText.notAllowed(repository.name(), toAllow);
        if (offer == Offer.NOBODY
                || !offer.confirm(
                        "the porelo.yml of "
                                + repository.name()
                                + " asks for what this machine has not allowed: "
                                + String.join(
                                        "; ",
                                        toAllow.stream()
                                                .map(RepositorySettingsText::line)
                                                .toList()),
                        "Allow it for " + repository.name() + " on this machine?",
                        "",
                        true)) {
            steps.refusal(refused);
            err.println("porelo: " + refused);
            return null;
        }
        new RepositoryAllowance(context).allow(repository.name(), own.asked());
        out.println("settings       allowed for " + repository.name() + " on this machine");
        return own.project();
    }

    /**
     * Previews the image, asking once for the lines a repository's unsigned {@code porelo.yml} adds
     * that this machine has not allowed for the project, as its own settings in 'default' are
     * asked.
     *
     * @return The preview, or {@code null} when what is asked was not allowed, which is said.
     * @throws ProjectException If a source is refused, or the file changed again before the yes.
     */
    private ImageChoice.@org.jspecify.annotations.Nullable Preview allowedPreview(
            final Project asDeclared,
            final org.fuin.porelo.core.project.Repository repository,
            final PrintWriter out,
            final PrintWriter err) {
        final ImageChoice.NotAllowed asked;
        try {
            return ImageChoice.preview(context, asDeclared, repository);
        } catch (ImageChoice.NotAllowed ex) {
            asked = ex;
        }
        if (request.dryRun() && DefaultProject.is(asDeclared.name())) {
            // The checkout's settings were said with the rest, the image among them.
            return asked.preview();
        }
        for (final var each : asked.toAllow()) {
            out.println("setting        " + RepositorySettingsText.line(each));
        }
        if (request.dryRun()) {
            out.println(
                    "settings       "
                            + asked.toAllow().size()
                            + " image settings not allowed on this machine yet; a start asks first");
            out.flush();
            return asked.preview();
        }
        if (!allowedImage(asked, asDeclared, repository, err)) {
            return null;
        }
        out.println(
                "settings       image allowed for "
                        + repository.name()
                        + " of "
                        + asDeclared.name()
                        + " on this machine");
        out.flush();
        return ImageChoice.preview(context, asDeclared, repository);
    }

    /**
     * Asks once for the image lines a repository's unsigned file adds that this machine has not
     * allowed, before the fetch or after it, when the fetch brought the file first.
     *
     * @return Whether a person allowed them; a refusal is said.
     */
    private boolean allowedImage(
            final ImageChoice.NotAllowed asked,
            final Project asDeclared,
            final org.fuin.porelo.core.project.Repository repository,
            final PrintWriter err) {
        if (offer == Offer.NOBODY
                || !offer.confirm(
                        "the "
                                + asked.origin()
                                + " adds to the image what this machine has not allowed: "
                                + String.join(
                                        "; ",
                                        asked.toAllow().stream()
                                                .map(RepositorySettingsText::line)
                                                .toList()),
                        "Allow them for "
                                + repository.name()
                                + " of "
                                + asDeclared.name()
                                + " on this machine?",
                        "",
                        true)) {
            steps.refusal(String.valueOf(asked.getMessage()));
            err.println("porelo: " + asked.getMessage());
            return false;
        }
        asked.allow(context);
        return true;
    }

    private boolean repositorySettingsRead;
    private RepositoryOwnSettings.@Nullable Found repositorySettings;

    /** A start uses the same settings snapshot to check credentials and to admit its files. */
    private RepositoryOwnSettings.@Nullable Found repositorySettings(
            Project project, Path file, org.fuin.porelo.core.project.Repository repository) {
        if (!repositorySettingsRead) {
            repositorySettings =
                    DefaultProject.is(project.name())
                            ? RepositoryOwnSettings.of(context, file, repository)
                            : RepositoryOwnSettings.ofOrganization(
                                    context, file, project.name(), repository);
            repositorySettingsRead = true;
        }
        return repositorySettings;
    }

    private Project credentialProject(
            Project project, Path file, org.fuin.porelo.core.project.Repository repository) {
        final RepositoryOwnSettings.Found own = repositorySettings(project, file, repository);
        return own == null ? project : own.project();
    }

    private boolean newCredentialsReady(
            Project project,
            Path file,
            org.fuin.porelo.core.project.Repository repository,
            PrintWriter out,
            PrintWriter err) {
        credentialCheckCode = 69;
        final Project effective = credentialProject(project, file, repository);
        if (!newArgumentsReady(effective, repository, err)) {
            credentialCheckCode = 64;
            return false;
        }
        final boolean descriptionNeededForUsage =
                subagents != 0
                        || request.model() != null
                        || !effective.providers().isEmpty()
                        || request.parent() != null;
        // With no description-dependent usage to refuse, a missing other credential needs no
        // adapter.
        if (!descriptionNeededForUsage && !otherCredentialsReady(effective, err)) {
            return false;
        }
        try (InstalledAgents agents = context.agents()) {
            final InstalledAgent agent = select(agents);
            reportAgentChoice(out, err);
            if (agent != null && refusesSubagents(agent, err)) {
                credentialCheckCode = 64;
                return false;
            }
            if (refusesOpening(agent, err)) {
                credentialCheckCode = 64;
                return false;
            }
            try (PrintWriter quiet = new PrintWriter(java.io.Writer.nullWriter())) {
                if (refusesModel(agent, effective, file, quiet, err)) {
                    credentialCheckCode = 2;
                    return false;
                }
            }
            if (descriptionNeededForUsage && !otherCredentialsReady(effective, err)) {
                return false;
            }
            if (refusesCredential(agent, err)) {
                return false;
            }
            return !refusesStartCredentials(agent, effective, request.mode(), out, err);
        } catch (org.fuin.porelo.agent.api.AgentException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return false;
        }
    }

    private boolean otherCredentialsReady(Project project, PrintWriter err) {
        context.openIfShut(
                !project.credentials().isEmpty() || !request.credentials().isEmpty(), true, err);
        return !refusesOtherCredentials(project, request.mode(), err);
    }

    /** Invalid usage must not be hidden by a missing or incompatible credential. */
    private boolean newArgumentsReady(
            Project project, org.fuin.porelo.core.project.Repository repository, PrintWriter err) {
        try {
            values = project.parameters().resolve(request.parameters());
        } catch (ProjectException ex) {
            err.println("porelo: " + ex.getMessage());
            err.println("porelo: nothing was created");
            err.flush();
            return false;
        }
        try (PrintWriter quiet = new PrintWriter(java.io.Writer.nullWriter())) {
            if (!settleSubagents(project, quiet, err)) {
                return false;
            }
        }
        final boolean ungated = request.noGate() || request.allRepositories();
        final String refused =
                ungated
                        ? StartCheck.hostServicesWithoutAGate(project)
                        : settleForgeBranch(project, repository);
        if (refused == null) {
            return true;
        }
        err.println(
                "porelo: "
                        + (request.allRepositories()
                                ? "--all-repositories: "
                                : request.noGate() ? "--no-gate: " : "")
                        + refused
                        + (request.allRepositories()
                                ? "; a task that reads them all has none"
                                : request.noGate() ? "; start it with its gate" : ""));
        err.println("porelo: nothing was created");
        err.flush();
        return false;
    }

    private void adopt(Project project, PrintWriter out) {
        // Only a name that reads as a container name can be another task's: a plain task name is
        // taken as given, and a dry run asks podman nothing for it.
        if (!org.fuin.porelo.runtime.ContainerName.isPorelo(request.task())) {
            return;
        }
        final TaskIdentities.Result identity =
                new TaskIdentities(context).lookup(request.task(), project.name());
        final String task =
                identity instanceof TaskIdentities.Found found
                        ? found.identity().task()
                        : request.task();
        if (!task.equals(request.task())) {
            out.println(
                    "task      '"
                            + request.task()
                            + "' is the container of task '"
                            + task
                            + "'; starting that");
            out.flush();
            request = request.withTask(task);
        }
    }

    /**
     * A task that was already there, and what bringing it back came to.
     *
     * @param container Container name.
     * @param project Name of the project it belongs to.
     * @param code Exit code: 0 when it is up again, a refusal otherwise.
     * @param action What Start did or refused with, in the contract's words - {@code RESUME},
     *     {@code RUNNING}, {@code NEEDS_VAULT} or {@code PREDATES_RESTART} - or {@code null} for a
     *     refusal that has no word there.
     */
    public record Existing(String container, String project, int code, @Nullable String action) {

        /**
         * Constructor for a refusal the contract names no action for.
         *
         * @param container Container name.
         * @param project Project name.
         * @param code Exit code.
         */
        public Existing(String container, String project, int code) {
            this(container, project, code, null);
        }
    }

    /**
     * Brings back the task's container when it already has one.
     *
     * <p>Here rather than in the command, because the daemon starts tasks too: while this lived in
     * {@code TaskRunCommand}, Start over the socket treated a stopped task as a new one, asked
     * which agent to install and never reached the reason it could not come back.
     *
     * <p>A task name maps to exactly one container, so it either exists or it does not. Running is
     * refused rather than started twice - a second container for one task would share the project's
     * mirror and its gate with the first.
     *
     * @param out Where to report.
     * @param err Where to refuse.
     * @return What happened, or {@code null} when there is nothing to bring back and the task has
     *     to be created.
     */
    public @Nullable Existing startExisting(PrintWriter out, PrintWriter err) {
        return startExisting(out, err, false);
    }

    /**
     * Brings an existing task back, or says it is running.
     *
     * @param out Where to report.
     * @param err Where to refuse.
     * @param goingIn Whether the caller goes into a running task itself, as a person's start at a
     *     terminal does: then a running task is answered with code 0 and {@code RUNNING}, and
     *     nothing is said of it here.
     * @return What happened, or {@code null} when there is nothing to bring back and the task has
     *     to be created.
     */
    public @Nullable Existing startExisting(PrintWriter out, PrintWriter err, boolean goingIn) {
        try {
            return startExistingSteps(out, err, goingIn);
        } catch (TaskReservations.ContainerNameTaken ex) {
            nameTaken = ex;
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return new Existing(ex.container, ex.project, 64);
        } catch (final TaskReservations.MailboxNotDeleted ex) {
            err.println("porelo: " + TaskReservations.MailboxNotDeleted.DETAIL);
            err.flush();
            return new Existing("", "", 70);
        } catch (org.fuin.porelo.vault.VaultException ex) {
            credentialRefusal = new CredentialRefusal("", true, null);
            err.println("porelo: " + ex.getMessage());
            err.println("porelo: nothing was created");
            err.flush();
            return new Existing("", "", reservationVaultCode);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        } finally {
            releaseReservation();
        }
    }

    /**
     * Says why an existing task cannot be brought back as this start asks, when it was made the
     * other kind: one that reads its whole project, or one with a gate on one repository.
     *
     * <p>Its kind was fixed when it was made, by what its container mounts: a gated task brought
     * back for reading would still hold its gate, its token and its certificate, and a reading task
     * brought back for one repository still has none of them. Read from the directory of what it
     * reads, which is there from before its container until its removal.
     *
     * @param container The existing task.
     * @return The refusal, a usage error, or {@code null} when the start asks for its own kind.
     */
    private @Nullable String otherKind(final String container) {
        final boolean reads = new TaskRepositories(context.paths(), container).exists();
        if (request.allRepositories() && !reads) {
            return container
                    + " was made to work on one repository and stays so; start a new task with"
                    + " --all-repositories under another name to read every repository";
        }
        final boolean namesOne =
                request.repository() != null && !request.repository().isBlank() || request.noGate();
        if (reads && namesOne) {
            return container
                    + " was made to read every repository and stays so; start it again without "
                    + (request.noGate() ? "--no-gate" : "--repository")
                    + ", or start a new task under another name";
        }
        return null;
    }

    private @Nullable Existing startExistingSteps(PrintWriter out, PrintWriter err, boolean goingIn)
            throws java.io.IOException {

        if (request.dryRun()
                || request.allRepositoriesRefusal() != null
                || request.resultRefusal() != null) {
            // A dry run must touch nothing, and asking the runtime what exists is touching it. The
            // launch says what it would do; the listing's startAction says which case applies.
            // A start refused for what it asks is refused by the launch, before it touches
            // anything.
            return null;
        }
        final Project project;
        try {
            project = ProjectReader.read(request.projectFile());
        } catch (RuntimeException ex) {
            // Not this method's refusal to make: the launch reads the same file and says what is
            // wrong with it far better than a guess here would.
            return null;
        }

        adopt(project, out);
        final String container = startContainer(project);
        final java.util.Optional<org.fuin.porelo.runtime.ContainerSummary> summary =
                context.podman().poreloTasks().stream()
                        .filter(found -> found.name().equals(container))
                        .findFirst();
        if (summary.isEmpty()) {
            if (new TaskIdentities(context)
                    .newTaskRefusal(project.name(), request.task())
                    .isEmpty())
                new TaskReservations(context).checkClaim(project.name(), request.task(), container);
            return null;
        }
        if (request.shortName() != null) {
            final java.util.Optional<String> immutable =
                    TaskIdentities.shortNameRefusal(
                            new TaskIdentities(context).read(summary.get()), request.shortName());
            if (immutable.isPresent()) {
                err.println("porelo: " + container + ": " + immutable.orElseThrow());
                err.flush();
                return new Existing(container, project.name(), 64);
            }
        }
        reserveTask(project, container, err);
        // Refused by the launch: a stopped task needs 'ssh-keygen -Y' as a new one does. A running
        // one is only gone into, which needs no signature.
        if (!summary.get().running()
                && OpenSshFloor.ask(context.runner()) instanceof OpenSshFloor.TooOld) {
            return null;
        }
        final String theirs = ofAnotherProject(summary.get(), project.name());
        if (theirs != null) {
            err.println("porelo: " + theirs);
            err.flush();
            return new Existing(container, project.name(), 64);
        }
        startingBranch = TaskBase.recorded(context, container);
        // Its branch at the forge was settled when it was created and recorded; one handed to a
        // start that brings it back would change nothing, and say nothing.
        if (request.branch() != null
                || (request.base() != null && "FROM".equals(request.base().source()))) {
            err.println(
                    "porelo: "
                            + container
                            + " was started with its branch, which stays as it was; start a new task"
                            + " for another branch");
            err.flush();
            return new Existing(container, project.name(), 64);
        }
        // Fixed when it was created, as its agent was set up then, and handed to its agent again
        // from its record; a different number handed to a start that brings it back would change
        // nothing, and say nothing.
        final int kept = keptSubagents(container);
        if (request.subagents() != null && request.subagents() != kept) {
            err.println(
                    "porelo: "
                            + container
                            + " was started with --subagents "
                            + kept
                            + ", which stays as it was; start it again without --subagents, or"
                            + " remove it and start a new task");
            err.flush();
            return new Existing(container, project.name(), 64);
        }
        // A task's values were fixed when it was created, in its environment and its record; a
        // value handed to a start that brings it back would change neither, and said nothing.
        if (!request.parameters().isEmpty()) {
            err.println(
                    "porelo: "
                            + container
                            + " was started with its values, which stay as they were; start it"
                            + " again without --set, --set-file and --set-env, or remove it and start"
                            + " a new task");
            err.flush();
            return new Existing(container, project.name(), 64);
        }
        // Its box was mounted when it was made, under the name it was made with.
        if (request.result() != null) {
            final String keptResult = new TaskResult(context.paths()).name(container);
            if (!request.result().equals(keptResult)) {
                err.println(
                        "porelo: "
                                + container
                                + (keptResult == null
                                        ? " was started without --result"
                                        : " was started with --result " + keptResult)
                                + ", which stays as it was; start it again without --result, or"
                                + " remove it and start a new task");
                err.flush();
                return new Existing(container, project.name(), 64);
            }
        }
        if (request.model() != null) {
            final org.fuin.porelo.wire.TaskProfile profile = keptProfile(container);
            if (profile != null && !request.model().equals(profile.model())) {
                err.println(
                        "porelo: "
                                + container
                                + " was started with "
                                + (profile.model() == null
                                        ? "the agent's default model"
                                        : "model '" + profile.model() + "'")
                                + "; start it again without --model, or give a new task name to use"
                                + " another model");
                err.flush();
                return new Existing(container, project.name(), 64);
            }
        }
        // A task's repository was fixed when it was created and cannot change, so a caller that
        // names a different one believes something false about work it is about to resume - and
        // would then look for that work where it is not. Ignoring it silently is the one answer
        // that leaves them believing it; the recorded one wins either way, so this says so rather
        // than acting on either.
        final String named;
        try {
            named =
                    request.repository() == null || request.repository().isBlank()
                            ? request.repository()
                            : GateSupport.repository(project, request.repository()).name();
        } catch (ProjectException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return new Existing(container, project.name(), 64);
        }
        final String recorded = summary.get().repository();
        if (named != null
                && !named.isBlank()
                && recorded != null
                && !recorded.isBlank()
                && !named.equals(recorded)) {
            err.println(
                    "porelo: "
                            + container
                            + " works on '"
                            + recorded
                            + "', not '"
                            + named
                            + "'. A task's repository is fixed when the task is created.");
            err.flush();
            return new Existing(container, project.name(), 64);
        }
        final String otherKind = otherKind(container);
        if (otherKind != null) {
            err.println("porelo: " + otherKind);
            err.flush();
            return new Existing(container, project.name(), 64);
        }

        if (summary.get().running()) {
            // Its live helpers need current stop hooks even when no new task is being started.
            if (!hooksReady(out, err)) {
                return new Existing(container, project.name(), 69);
            }
            if (goingIn) {
                return new Existing(container, project.name(), 0, "RUNNING");
            }
            err.println("porelo: " + container + " is already running");
            err.println("       go into it with 'porelo task attach " + container + "'.");
            err.flush();
            return new Existing(container, project.name(), 65, "RUNNING");
        }

        final org.fuin.porelo.wire.TaskProfile profile = keptProfile(container);
        final Path runtime = context.paths().tasks().containerState(container);
        if (!java.nio.file.Files.isDirectory(runtime) && new TaskState(context).saved(container)) {
            final TaskHelpers saved =
                    TaskHelpers.readFrom(context.paths().tasks().taskRecord(container));
            final boolean needsTokens =
                    saved.helpers().stream()
                            .anyMatch(
                                    helper ->
                                            "gate".equals(helper.name())
                                                    || "vault".equals(helper.name()));
            context.openIfShut(needsTokens, true, err);
            final java.util.Optional<TaskSecrets.Tokens> keptTokens =
                    needsTokens
                            ? new TaskSecrets(context).read(container)
                            : java.util.Optional.of(new TaskSecrets.Tokens(null, null));
            if (keptTokens.isPresent() && TaskControl.tokensLost(saved, keptTokens.get())) {
                // Before its credentials: no key stored now brings back the tokens it was never
                // kept with, so a refusal naming a credential sends a person the wrong way.
                final int code =
                        TaskResumeCommand.render(
                                new TaskControl.Resumed(
                                        TaskControl.Outcome.TOKENS_NOT_KEPT,
                                        0,
                                        saved.helpers().size(),
                                        null,
                                        null,
                                        java.util.List.of()),
                                container,
                                out,
                                err,
                                null);
                return new Existing(container, project.name(), code, "PREDATES_RESTART");
            }
            if (keptTokens.isEmpty()) {
                credentialRefusal =
                        new CredentialRefusal(
                                profile == null || profile.agent() == null ? "" : profile.agent(),
                                true,
                                null);
                final int code =
                        TaskResumeCommand.render(
                                new TaskControl.Resumed(
                                        TaskControl.Outcome.NEEDS_VAULT,
                                        0,
                                        saved.helpers().size(),
                                        null,
                                        null,
                                        java.util.List.of()),
                                container,
                                out,
                                err,
                                null);
                return new Existing(container, project.name(), code, "NEEDS_VAULT");
            }
        }
        // A repository its project no longer names takes its own settings with it; the task
        // still comes back, on its project's.
        org.fuin.porelo.core.project.@Nullable Repository worked = null;
        if (recorded != null && !recorded.isBlank()) {
            try {
                worked = GateSupport.repository(project, recorded);
            } catch (ProjectException ex) {
                worked = null;
            }
        }
        try {
            final Project effective =
                    worked == null
                            ? project
                            : credentialProject(project, request.projectFile(), worked);
            if (profile != null) {
                final org.fuin.porelo.wire.TaskMode mode =
                        refusesWithoutCredential(request.mode()) ? request.mode() : profile.mode();
                if (refusesRecordedCredentials(profile, container, effective, mode, err)) {
                    return new Existing(container, project.name(), 69);
                }
            } else if (refusesOtherCredentials(effective, request.mode(), err)) {
                return new Existing(container, project.name(), 69);
            }
        } catch (org.fuin.porelo.agent.api.AgentException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return new Existing(container, project.name(), 69);
        } catch (ProjectException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return new Existing(container, project.name(), 64);
        }
        if (!hooksReady(out, err)) {
            return new Existing(container, project.name(), 69);
        }

        // Into the project's conversations again: a task that existed before its project named a
        // transport, or before a machine took part in it, was never enrolled, and starting it again
        // left it outside - its messages were queued to a transport that had no account for it.
        // Enrolling keeps what exists, so it is harmless each time; what it cannot do is said, and
        // the task comes back all the same, its workspace being in it.
        final Path state = context.paths().tasks().containerState(container);
        if (java.nio.file.Files.isDirectory(state)) {
            steps.recordIn(state);
        }
        // Its agent as its start recorded it, so the room shows it by the name it had.
        final org.fuin.porelo.wire.TaskProfile recordedProfile = keptProfile(container);
        if (recordedProfile != null
                && recordedProfile.settingsHash() != null
                && !recordedProfile.settingsHash().isEmpty()
                && recorded != null
                && !recorded.isBlank()) {
            try {
                settingsMovedOn(
                        request.projectFile(),
                        project.name(),
                        GateSupport.repository(project, recorded),
                        out);
            } catch (ProjectException ex) {
                err.println(
                        "porelo: cannot compare the task's recorded repository settings: "
                                + ex.getMessage());
                err.flush();
            }
        }
        final String outside =
                new TaskConversations(context)
                        .enroll(
                                project,
                                container,
                                recordedProfile == null ? null : recordedProfile.agent(),
                                steps);
        if (outside != null) {
            err.println("porelo: " + outside);
            err.println(
                    "       it starts again, outside the conversation; its messages wait until it is in");
            err.flush();
        }
        // A task made before its box opened to its agent, or before agents were told about it, gets
        // both now; and its guide as this version writes it, where its container mounts one.
        try {
            informed(project, container, false);
        } catch (final java.io.IOException ex) {
            err.println(
                    "porelo: its mailbox or its guide could not be made ready: " + ex.getMessage());
            err.println(
                    "       it starts again; its agent may not reach its mailbox or know about it");
            err.flush();
        }
        // Its workspace, its branch and its uncommitted changes are all in that container. What has
        // to be started again is everything that lives on the host, which is what resume does.
        steps.at(container);
        steps.started(StartStep.Step.HELPERS);
        try {
            final TaskControl.Resumed resumed = new TaskControl(context).resume(container);
            final int code =
                    TaskResumeCommand.render(resumed, container, out, reporting(err), null);
            steps.finish(code);
            return new Existing(
                    container,
                    project.name(),
                    code,
                    switch (resumed.outcome()) {
                        case NEEDS_VAULT -> "NEEDS_VAULT";
                        case PREDATES_RESTART, TOKENS_NOT_KEPT -> "PREDATES_RESTART";
                        case ALREADY_RUNNING -> "RUNNING";
                        case RESUMED, NO_HELPERS_RECORDED, HELPERS_INCOMPLETE, NOT_TAKEN_BACK ->
                                "RESUME";
                        default -> null;
                    });
        } catch (CommandException ex) {
            steps.dependencyFailed(ex.standardError());
            throw ex;
        } catch (RuntimeException ex) {
            steps.refusal(java.util.Objects.requireNonNullElse(ex.getMessage(), ""));
            steps.finish(70);
            throw ex;
        } finally {
            steps.clear();
        }
    }

    /**
     * Returns the number of subagents a task's start settled, as its record keeps it.
     *
     * @param container The task's container.
     * @return The number; 0 for a task whose record does not say, which is what it was told.
     */
    private int keptSubagents(String container) {
        final org.fuin.porelo.wire.TaskProfile profile = keptProfile(container);
        return profile == null ? 0 : profile.subagents();
    }

    /** Returns what a task's start recorded, from where it runs or else from its durable record. */
    private org.fuin.porelo.wire.@Nullable TaskProfile keptProfile(String container) {
        final org.fuin.porelo.wire.TaskProfile profile =
                org.fuin.porelo.wire.TaskProfile.readFrom(
                        context.paths().tasks().containerState(container));
        // After a reboot, before the resume puts the runtime directory's records back.
        return profile != null
                ? profile
                : org.fuin.porelo.wire.TaskProfile.readFrom(
                        context.paths().tasks().taskRecord(container));
    }

    static @Nullable String ofAnotherProject(
            org.fuin.porelo.runtime.ContainerSummary found, String project) {
        return TaskInventory.ofAnotherProject(found, project);
    }

    /**
     * Says what a task in {@code default} may not be given, and narrows what it would be given by
     * default.
     *
     * <p>{@code default}'s settings are Porelo's and cannot be changed (decided on 2026-10-01): no
     * egress beyond what the agent needs, so no credential for another destination, no other
     * upstream, and no blocked connection let through by a prompt. Whoever needs any of that makes
     * a project repository and follows it.
     *
     * @return Why the start is refused, or {@code null} when it may go ahead.
     */
    private @org.jspecify.annotations.Nullable String defaultRefuses() {
        final String way =
                "; that is what a project repository is for: write its project.yml and follow it";
        if (!request.credentials().isEmpty()) {
            return "a task in '"
                    + DefaultProject.NAME
                    + "' holds no credential beyond its agent's own"
                    + way;
        }
        if (request.upstream() != null) {
            return "a task in '"
                    + DefaultProject.NAME
                    + "' sends its approved work to its repository's origin and"
                    + " nowhere else"
                    + way;
        }
        if ("prompt".equals(request.clearance()) || "allow".equals(request.clearance())) {
            request =
                    new Request(
                            request.task(),
                            request.projectFile(),
                            request.agentName(),
                            request.providerName(),
                            request.credentialType(),
                            request.tokenHours(),
                            request.upstream(),
                            request.noGate(),
                            request.dryRun(),
                            "deny",
                            request.keep(),
                            request.mode(),
                            request.prompt(),
                            request.model(),
                            request.maxTurns(),
                            request.minutes(),
                            request.repository(),
                            request.credentials(),
                            request.parameters(),
                            request.branch(),
                            request.subagents(),
                            request.base(),
                            request.parent(),
                            request.named(),
                            request.shortName(),
                            request.allRepositories(),
                            request.work(),
                            request.result());
        }
        return null;
    }

    /**
     * Starts a task and hands the running thing back to the caller.
     *
     * @param out Where progress is reported.
     * @param err Where refusals and failures are reported.
     * @param after What to do once it is up.
     * @return The exit code.
     */
    /**
     * Who is offered a remedy where the start would name a command: nobody, unless a terminal is
     * there.
     */
    /** Why the upstream could not be read, when that refused this start. */
    private UpstreamProbe.@Nullable Answer unreadableUpstream;

    private Offer offer = Offer.NOBODY;

    /**
     * What a start returns when the person took another way to start: {@link #retry()} says which.
     */
    static final int RETRY = -2;

    /**
     * The way the person took instead of a refusal: {@code "shell"}, or {@code "name:<name>"}; or
     * none.
     */
    private @Nullable String retry;

    /**
     * Returns the way the person took instead of a refusal, for the caller to start again with.
     *
     * @return {@code "shell"}, {@code "name:<name>"}, or {@code null}.
     */
    @Nullable String retry() {
        return retry;
    }

    /**
     * Asks whether to start under the next free name, where earlier work of this name waits at the
     * gate.
     *
     * @param earlier The refusal.
     * @param free The next free name, or {@code null} when none was found.
     * @param offer Who is asked.
     * @return {@code "name:<free>"} on a yes, or {@code null}.
     */
    static @Nullable String nameInstead(
            final String earlier, final @Nullable String free, final Offer offer) {
        return free != null
                        && offer != Offer.NOBODY
                        && offer.confirm(earlier, "Start it as '" + free + "' instead?", "", false)
                ? "name:" + free
                : null;
    }

    /**
     * Asks whether to start a shell in the task instead of an agent that cannot work.
     *
     * @param missing What is missing.
     * @param offer Who is asked.
     * @return {@code "shell"} on a yes, or {@code null}.
     */
    static @Nullable String shellInstead(final String missing, final Offer offer) {
        return offer != Offer.NOBODY
                        && offer.confirm(missing, "Start a shell in the task instead?", "", false)
                ? "shell"
                : null;
    }

    /**
     * Offers what a refusal would name, through the given helper: at a terminal, a person is asked
     * and the remedy run.
     *
     * @param offer The helper.
     * @return This launch.
     */
    public TaskLaunch offering(final Offer offer) {
        this.offer = offer;
        return this;
    }

    /**
     * Who answers whether a task starts without a certificate on a host in test mode; {@code null}
     * for a start nobody asked for at the command line, as through the daemon, which refuses.
     */
    private Offer.@Nullable Asker uncertifiedAsker;

    /** Whether {@code --yes} answered that question. */
    private boolean uncertifiedYes;

    /** Whether this start goes on without a certificate, once the host and the person said. */
    private @Nullable Boolean uncertified;

    /**
     * Lets a start on a host whose root switched on {@code start-uncertified} go on without a
     * certificate, after a warning and a yes. The host alone decides whether it may: no answer here
     * starts a task uncertified on a host that is not in test mode.
     *
     * @param yes Whether {@code --yes} answers the question.
     * @param asker Who is asked; {@link Offer.Asker#NOBODY} without a terminal or with {@code
     *     --no-input}, which refuses unless {@code yes}.
     * @return This launch.
     */
    public TaskLaunch startingUncertified(final boolean yes, final Offer.Asker asker) {
        this.uncertifiedYes = yes;
        this.uncertifiedAsker = asker;
        return this;
    }

    /**
     * Whether this start goes on without a certificate: the host holds {@code start-uncertified},
     * the warning was said, and the person or {@code --yes} said yes. Asked once per start.
     */
    private boolean startsUncertified(final PrintWriter err) {
        if (uncertified == null) {
            final Offer.Asker asker = uncertifiedAsker;
            boolean going = false;
            if (asker != null && host.switches().contains(HostPerson.START_UNCERTIFIED)) {
                err.println(
                        "porelo: this host is in test mode, so the task starts WITHOUT a"
                                + " certificate: what it passes on is vouched for by nobody");
                err.flush();
                if (uncertifiedYes) {
                    going = true;
                } else {
                    final String answer = asker.ask("Start the task without the check? y/N");
                    final String given =
                            answer == null ? "" : answer.strip().toLowerCase(java.util.Locale.ROOT);
                    going = given.equals("y") || given.equals("yes");
                }
            }
            uncertified = going;
        }
        return uncertified;
    }

    /** The tasks whose landed work this task was started to read; none for every other task. */
    private java.util.List<String> reads = java.util.List.of();

    /** A task's name as a reader gives it: one ref component, no pattern. */
    private static final java.util.regex.Pattern READABLE =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * Returns the first name a reader may not give, or {@code null} when every one is a task's.
     *
     * @param tasks The names.
     * @return The first that is no task's name, or {@code null}.
     */
    public static @Nullable String unreadable(final java.util.List<String> tasks) {
        for (final String task : tasks) {
            if (task == null || !READABLE.matcher(task).matches()) {
                return String.valueOf(task);
            }
        }
        return null;
    }

    /**
     * Starts this task to read where the named tasks' work landed: its gate shows it those, read
     * only, and nothing else of any other task. A name that is no task of this project shows
     * nothing, since the gate serves the project's own mirror.
     *
     * @param tasks The tasks it reads.
     * @return This launch.
     * @throws IllegalArgumentException When a name is no task's name.
     */
    public TaskLaunch reading(final java.util.List<String> tasks) {
        final String refused = unreadable(tasks);
        if (refused != null) {
            throw new IllegalArgumentException("'" + refused + "' is no task's name");
        }
        this.reads = java.util.List.copyOf(new java.util.LinkedHashSet<>(tasks));
        return this;
    }

    /** The key the program starting this task gave it, or {@code null} when it gave none. */
    private @Nullable String callerKey;

    /** The most characters a caller's key may have. */
    public static final int CALLER_KEY_MOST = 200;

    /**
     * Says why a caller's key cannot be kept, or {@code null} when it can.
     *
     * <p>The key is the caller's own and opaque to Porelo, so nothing else of it is judged: only
     * what a listing could not give back as it was given.
     *
     * @param key The key, as given.
     * @return The reason, or {@code null}.
     */
    public static @Nullable String callerKeyRefusal(final String key) {
        if (key.codePointCount(0, key.length()) > CALLER_KEY_MOST) {
            return "is longer than " + CALLER_KEY_MOST + " characters";
        }
        if (key.codePoints().anyMatch(Character::isISOControl)) {
            return "holds a control character";
        }
        return null;
    }

    /**
     * Starts this task with a key of its caller's own, which List and Watch give back on its row
     * from then on: after a restart of the caller, of the daemon or of the machine, and after a
     * start that failed once its container was made. A label never changes it, and a resume keeps
     * it.
     *
     * @param key The key, or {@code null} or {@code ""} for none.
     * @return This launch.
     * @throws IllegalArgumentException When the key cannot be kept.
     */
    public TaskLaunch keyedBy(final @Nullable String key) {
        if (key == null || key.isEmpty()) {
            this.callerKey = null;
            return this;
        }
        final String refused = callerKeyRefusal(key);
        if (refused != null) {
            throw new IllegalArgumentException("the caller's key " + refused);
        }
        this.callerKey = key;
        return this;
    }

    /** Who is asked whether to take the repository out of {@code default}; nobody by default. */
    private Offer leavingDefault = Offer.NOBODY;

    /** The repository of {@code default} the person said to take out once this start is through. */
    private @Nullable String leaveDefault;

    /** Whether this launch said already that a project of the organization names its repository. */
    private boolean namedInDefaultSaid;

    /**
     * Asks once, at a start in {@code default} on a repository a project of the organization names,
     * whether to take it out of {@code default}; the start goes on in {@code default} either way.
     * Without it the start only says so, as a start through the daemon does.
     *
     * @param offer Who is asked: a person at a terminal.
     * @return This launch.
     */
    public TaskLaunch askingToLeaveDefault(final Offer offer) {
        this.leavingDefault = offer;
        return this;
    }

    /**
     * Says, once and not as a failure, that a project of the organization names this repository of
     * {@code default} and how it is taken out, and asks a person who can answer.
     */
    private void sayNamedInDefault(final PrintWriter out, final String repository) {
        final DefaultNamed.@Nullable Named named = DefaultNamed.of(context, repository);
        if (named == null) {
            return;
        }
        out.println("named          " + named.said());
        out.flush();
        if (!request.dryRun()
                && leavingDefault != Offer.NOBODY
                && leavingDefault.confirm(
                        "'" + named.project() + "' names '" + repository + "'",
                        "Take it out of '"
                                + DefaultProject.NAME
                                + "' once this start is through and no task of it works on"
                                + " it? This start goes on in '"
                                + DefaultProject.NAME
                                + "' either way",
                        "",
                        true)) {
            leaveDefault = repository;
        }
    }

    /**
     * Takes the repository out of {@code default} on the person's yes, once the start is through,
     * through the same removal an interface makes: a task of {@code default} still on it, this
     * start's own among them, keeps it there, and that is said with what to do.
     */
    private void leaveDefaultIfSaid(final PrintWriter out, final PrintWriter err) {
        final String repository = leaveDefault;
        if (repository == null) {
            return;
        }
        leaveDefault = null;
        switch (DefaultNamed.remove(context, new TaskInventory(context).tasks(), repository)) {
            case DefaultNamed.Removal.Removed removed -> {
                out.println(
                        "removed        "
                                + repository
                                + " from '"
                                + DefaultProject.NAME
                                + "'; its mirror and anything waiting in it stay");
                removed.keys()
                        .forEach(
                                key ->
                                        out.println(
                                                "forgot         deploy key '"
                                                        + key.title()
                                                        + "' "
                                                        + key.fingerprint()
                                                        + " - remove it at the forge"));
                out.flush();
            }
            case DefaultNamed.Removal.InUse used -> {
                err.println(
                        "porelo: '"
                                + repository
                                + "' stays in '"
                                + DefaultProject.NAME
                                + "' while "
                                + String.join(", ", used.tasks())
                                + " works on it; remove that task, then 'porelo project default"
                                + " remove "
                                + repository
                                + "'");
                err.flush();
            }
            case DefaultNamed.Removal.NotThere missing -> {
                err.println(
                        "porelo: '"
                                + DefaultProject.NAME
                                + "' has no repository called '"
                                + missing.repository()
                                + "' any more");
                err.flush();
            }
        }
    }

    /** Where a start from the person's machine asks for its certificate, or {@code null}. */
    private java.io.@org.jspecify.annotations.Nullable BufferedReader certifyIn;

    private java.io.@org.jspecify.annotations.Nullable PrintWriter certifyOut;

    /**
     * Has the starting person's machine certify the task's key over this start's own input and
     * output, as a start through SSH from there does.
     *
     * @param in Where the certificate comes from.
     * @param out Where the request goes.
     * @return This launch.
     */
    public TaskLaunch certifyingOver(final java.io.BufferedReader in, final PrintWriter out) {
        this.certifyIn = in;
        this.certifyOut = out;
        return this;
    }

    /** Where a start through the daemon asks for its certificate, or {@code null}. */
    private @org.jspecify.annotations.Nullable CertificateWaits certificateWaits;

    private CertificateWaits.@org.jspecify.annotations.Nullable Asker certificateAsker;

    /**
     * Has the person's computer certify the task's key through an interface, as the daemon's {@code
     * Start} does when it is asked to.
     *
     * @param waits Where the answer arrives.
     * @param asker How this start asks.
     * @return This launch.
     */
    public TaskLaunch certifyingBy(
            final CertificateWaits waits, final CertificateWaits.Asker asker) {
        this.certificateWaits = waits;
        this.certificateAsker = asker;
        return this;
    }

    private TaskCertification.@org.jspecify.annotations.Nullable Certifier remoteCertifier(
            final String project, final boolean onAHost, final java.util.List<String> certifiers) {
        if (certifyIn != null) {
            return new StdinCertifier(
                    certifyIn, java.util.Objects.requireNonNull(certifyOut), certifiers);
        }
        return certificateWaits == null || !TaskCertifying.asksThePerson(project, onAHost)
                ? null
                : certificateWaits.certifier(
                        java.util.Objects.requireNonNull(certificateAsker), certifiers);
    }

    /**
     * Shows what a host this machine never met offers, and records its key when a person says it is
     * the one its owner publishes: a trust decision, so it defaults to no and is never taken by
     * {@code --yes}.
     *
     * @param host The host.
     * @param err Where it is shown and a refusal said.
     * @return Whether the host is known now.
     */
    boolean trustUnmet(final String host, final PrintWriter err) {
        if (offer == Offer.NOBODY) {
            return false;
        }
        final java.util.List<HostKeys.Offered> offered = HostKeys.offeredBy(context, host);
        if (offered.isEmpty()) {
            return false;
        }
        final HostKeys.Offered key =
                offered.stream()
                        .filter(one -> one.type().equals("ssh-ed25519"))
                        .findFirst()
                        .orElse(offered.get(0));
        err.println("porelo: " + host + " offers:");
        offered.forEach(one -> err.printf("    %-20s %s%n", one.type(), one.fingerprint()));
        err.flush();
        return offer.resolve(
                new Offer.Remedy(
                        "this machine has never met " + host,
                        "Trust its "
                                + key.type()
                                + " key "
                                + key.fingerprint()
                                + ", as its owner publishes it?",
                        "porelo credentials trust-host " + host,
                        "",
                        true,
                        () -> {
                            try {
                                return HostKeys.trust(context, host, key.fingerprint()) != null;
                            } catch (final java.io.IOException ex) {
                                err.println(
                                        "porelo: the key could not be recorded: "
                                                + ex.getMessage());
                                return false;
                            }
                        },
                        () -> HostKeys.known(context, host)),
                err);
    }

    /**
     * Returns why the upstream could not be read, when that is what refused this start.
     *
     * @return The answer, or {@code null} when the start was not refused for it.
     */
    public UpstreamProbe.@Nullable Answer unreadableUpstream() {
        return unreadableUpstream;
    }

    /**
     * Reads the upstream of the repository this start is for, as the gate will.
     *
     * @param project The project.
     * @return What is in the way, or {@code null} when nothing is.
     */
    UpstreamProbe.@Nullable Finding upstreamFinding(final Project project) {
        final String upstream = request.upstream();
        if (project.securityClass() == org.fuin.porelo.core.project.SecurityClass.OFFLINE
                && upstream != null) {
            // Refused as itself when the workspace is opened: an offline project takes no
            // --upstream.
            return null;
        }
        try {
            return UpstreamProbe.forStart(
                    context,
                    project,
                    GateSupport.repository(project, request.repository()),
                    upstream);
        } catch (final RuntimeException ex) {
            // A repository that is not there is refused as itself, by the check before this one.
            return null;
        }
    }

    /**
     * Offers to store a key for an upstream that refused this machine with nothing stored for it,
     * and reads it again.
     *
     * <p>Only where nothing is stored: with a key declared and refused, which key the forge expects
     * is the forge's to know, so nothing is offered that could only be a guess. Never taken by
     * {@code --yes}: there is no harmless default for a path.
     *
     * @param finding What the first read found.
     * @param project The project.
     * @param err Where it is asked and said.
     * @return What a second read found, or the first finding when nothing was stored.
     */
    UpstreamProbe.@Nullable Finding storeRefusedKey(
            final UpstreamProbe.Finding finding, final Project project, final PrintWriter err) {
        final String url =
                request.upstream() != null && !request.upstream().isBlank()
                        ? request.upstream()
                        : GateSupport.repository(project, request.repository()).upstream();
        if (offer == Offer.NOBODY
                || url == null
                || finding.answer().cause() != UpstreamProbe.Cause.KEY_REFUSED
                || GitCredentialNames.kindOf(url) != GitCredentialNames.Kind.KEY) {
            return finding;
        }
        final CredentialDeclarations.Check check =
                new CredentialDeclarations(context).check(url, "git");
        if (check.outcome() == CredentialDeclarations.Outcome.READY
                || check.outcome() == CredentialDeclarations.Outcome.VAULT_LOCKED
                || (check.credential() != null
                        && check.credential().source()
                                != org.fuin.porelo.core.credential.Credential.Source.VAULT)) {
            return finding;
        }
        final java.util.List<String> names = GitCredentialNames.candidatesFor(url);
        final String entry =
                check.credential() != null
                        ? check.credential().id()
                        : names.isEmpty() ? null : names.get(0);
        if (entry == null) {
            return finding;
        }
        final String path =
                offer.text(
                        finding.answer().detail(),
                        "Path of a private key to store as '"
                                + entry
                                + "' for it ('porelo credentials keys' lists this account's):",
                        "porelo vault put " + entry + " --from-file <the private key file>");
        if (path == null) {
            return finding;
        }
        // The command's own code, on this terminal: it asks for the vault's passphrase where it has
        // to.
        context.exec()
                .applyAsInt(
                        java.util.List.of(
                                PoreloBinary.path(), "vault", "put", entry, "--from-file", path));
        return upstreamFinding(project);
    }

    private TaskReservations.@Nullable Lease reservation;

    private TaskReservations.@Nullable ContainerNameTaken nameTaken;

    /**
     * Returns the text an attended task's agent takes as its session's first turn: the prompt of a
     * start whose mode is {@code AGENT}, which runs nothing at the start.
     *
     * @return The text, or {@code null} for any other start.
     */
    public @Nullable String opening() {
        return request.mode() == org.fuin.porelo.wire.TaskMode.AGENT ? request.prompt() : null;
    }

    /**
     * Says, for an attended start given a prompt, that its agent cannot take the prompt as its
     * session's first turn. A usage error, refused before any credential is asked for.
     *
     * @param agent The selected agent, or {@code null} for none.
     * @param err Where the refusal is said.
     * @return Whether the start is refused.
     */
    private boolean refusesOpening(final @Nullable InstalledAgent agent, final PrintWriter err) {
        final String opening = opening();
        if (opening == null || agent == null) {
            return false;
        }
        try {
            agent.definition().attendedCommand(null, opening);
            return false;
        } catch (final org.fuin.porelo.agent.api.AgentException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return true;
        }
    }

    /** The refusal shared by terminal, daemon and child starts. */
    public TaskReservations.@Nullable ContainerNameTaken containerNameTaken() {
        return nameTaken;
    }

    private String startContainer(Project project) {
        final TaskIdentities.Result result =
                new TaskIdentities(context).lookup(request.task(), project.name());
        if (result instanceof TaskIdentities.Found found) {
            return found.identity().container();
        }
        try {
            return context.tasks().containerName(project, request.task(), request.shortName());
        } catch (IllegalArgumentException unnamed) {
            // A name no task can have is refused by the start in its own words, before anything
            // is made; until then the name it would have had is only looked at.
            if (new TaskIdentities(context)
                    .newTaskRefusal(project.name(), request.task())
                    .isPresent()) {
                return org.fuin.porelo.runtime.ContainerName.of(project, request.task());
            }
            throw unnamed;
        }
    }

    private int reservationVaultCode = 69;

    private void reserveTask(Project project, String container, PrintWriter err)
            throws java.io.IOException {
        reservationVaultCode =
                !project.mail().conversations().isEmpty()
                                || request.mode() == org.fuin.porelo.wire.TaskMode.SHELL
                        ? 77
                        : 69;
        context.openIfShut(
                context.vault().exists() && context.recordedId(container) == null, true, err);
        reservation =
                new TaskReservations(context).start(project.name(), request.task(), container);
    }

    private void releaseReservation() {
        final TaskReservations.Lease held = reservation;
        reservation = null;
        if (held != null) {
            try {
                held.close();
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }
    }

    /**
     * Starts a task and reports every entered stage to its listener.
     *
     * @param out Existing progress output.
     * @param err Refusals.
     * @param after The action once running.
     * @return The launch's exit code.
     */
    public int launch(PrintWriter out, PrintWriter err, AfterStart after) {
        steps.started(StartStep.Step.SOURCE);
        final PrintWriter reported = reporting(err);
        try {
            final int code = launchSteps(out, reported, after);
            steps.finish(code);
            // The start went on in 'default' whatever was answered; only now does the repository
            // go, and only when no task of 'default' is left on it.
            if (code != RETRY) {
                leaveDefaultIfSaid(out, err);
            }
            return code;
        } catch (TaskReservations.ContainerNameTaken ex) {
            nameTaken = ex;
            err.println("porelo: " + ex.getMessage());
            err.flush();
            steps.finish(64);
            return 64;
        } catch (final TaskReservations.MailboxNotDeleted ex) {
            steps.refusal(TaskReservations.MailboxNotDeleted.DETAIL);
            err.println("porelo: " + TaskReservations.MailboxNotDeleted.DETAIL);
            err.flush();
            steps.finish(70);
            return 70;
        } catch (org.fuin.porelo.vault.VaultException ex) {
            credentialRefusal = new CredentialRefusal("", true, null);
            err.println("porelo: " + ex.getMessage());
            err.println("porelo: nothing was created");
            err.flush();
            final int code = reservationVaultCode;
            steps.finish(code);
            return code;
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        } catch (RuntimeException ex) {
            steps.refusal(String.valueOf(ex.getMessage()));
            steps.finish(70);
            throw ex;
        } finally {
            releaseReservation();
            steps.clear();
        }
    }

    private PrintWriter reporting(PrintWriter err) {
        return new PrintWriter(err, true) {
            /** {@inheritDoc} */
            @Override
            public void println(String line) {
                steps.refusal(line);
                super.println(line);
            }
        };
    }

    private int launchSteps(PrintWriter out, PrintWriter err, AfterStart after)
            throws java.io.IOException {
        final java.util.Optional<String> badShort =
                org.fuin.porelo.core.project.ShortName.refusal(request.shortName());
        if (badShort.isPresent()) {
            err.println("porelo: --short: " + badShort.orElseThrow());
            err.flush();
            return 64;
        }
        final TaskWork work = request.work();
        final String workRefused = work == null ? null : work.refusal();
        if (workRefused != null) {
            err.println("porelo: --work: " + workRefused);
            err.flush();
            return 64;
        }
        final String readingRefused = request.allRepositoriesRefusal();
        if (readingRefused != null) {
            err.println("porelo: " + readingRefused);
            err.flush();
            return 64;
        }
        final String resultRefused = request.resultRefusal();
        if (resultRefused != null) {
            err.println("porelo: " + resultRefused);
            err.flush();
            return 64;
        }
        // Even remembering the project would leave state for a task this machine cannot create.
        if (!request.dryRun()) {
            final java.util.Optional<String> missingInit = TaskInit.missing(context);
            if (missingInit.isPresent()) {
                err.println("porelo: " + missingInit.get());
                err.flush();
                return 69;
            }
        }
        Project project;
        java.nio.file.Path projectFile = request.projectFile();
        try {
            project = ProjectReader.read(projectFile);
            if (DefaultProject.is(project.name())) {
                final String refused = defaultRefuses();
                if (refused != null) {
                    err.println("porelo: " + refused);
                    err.flush();
                    return 2;
                }
                out.println(
                        "project        "
                                + DefaultProject.NAME
                                + " - Porelo's settings: guarded, nothing reachable"
                                + " beyond the agent's provider, blocked connections refused");
            }

            // If the organization has a project of that name, the file its start judged wins - and
            // the one in the directory is not consulted at all, not preferred and not merged.
            final ProjectSource.Found found = ProjectSource.resolve(context, project.name());
            final boolean judged = found.outcome() == ProjectSource.Outcome.ORGANIZATION;
            if (judged) {
                projectFile = java.util.Objects.requireNonNull(found.file());
                verifiedAt = found.commit();
                project = ProjectReader.read(projectFile);
                out.println("source         " + verifiedAt + " (the organization's, judged)");
            }

        } catch (ProjectException ex) {
            // A bad project file is the user's problem to fix, not a defect: report it as one line,
            // not as a stack trace.
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 2;
        }

        adopt(project, out);
        final java.util.Optional<String> badName =
                new TaskIdentities(context).newTaskRefusal(project.name(), request.task());
        if (badName.isPresent()) {
            // Before the plan, the dry run and anything written. podman refuses such a name only
            // when the container is created, after the image is built and the policy, resolver and
            // sidecar are on disk - and those stayed behind, named for a task that never was.
            err.println("porelo: " + badName.get());
            err.flush();
            return 64;
        }

        // After the task name and before the image, the plan and the dry run. Which repository the
        // work is for is the first thing about a task, and finding out later means finding out
        // somewhere else; the name is checked first only because a caller who got both wrong should
        // hear about what they typed before what they left out.
        final org.fuin.porelo.core.project.Repository repository;
        try {
            repository = chosenRepository(project);
        } catch (ProjectException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 64;
        }

        workedOn = repository;
        if (DefaultProject.is(project.name()) && !namedInDefaultSaid) {
            // Once, however often a refused start is tried again on this launch.
            namedInDefaultSaid = true;
            sayNamedInDefault(out, repository.name());
        }

        if (!request.dryRun()) {
            reserveTask(project, startContainer(project), err);
            try {
                if (!newCredentialsReady(project, projectFile, repository, out, err)) {
                    return credentialCheckCode;
                }
            } catch (ProjectException ex) {
                err.println("porelo: " + ex.getMessage());
                err.flush();
                return 64;
            }
            new ProjectRegistry(context.paths().projects().projectRegistry())
                    .remember(project.name(), projectFile.toAbsolutePath());
        }

        steps.started(StartStep.Step.CHECKS);
        out.println("task           " + request.task());
        out.println("project        " + project.name());
        out.println(
                "repository     "
                        + repository.name()
                        + (repository.name().equals(project.name()) ? " (the project's own)" : ""));
        final AgentChoice.Wanted wanted = AgentChoice.wanted(request.agentName(), repository);
        if (wanted.source() == AgentChoice.Source.REPOSITORY) {
            out.println("agent          " + wanted.name() + " (named for the repository)");
        }
        out.println("security class " + project.securityClass().name().toLowerCase());
        // Chosen per repository, one source taken whole and named with where it came from: the
        // image
        // is built as root outside the task's firewall, so which file said what is not a detail.
        // A task that reads every repository has none open behind a gate: it pushes nothing.
        final boolean gated = !request.noGate() && !request.allRepositories() && !request.dryRun();
        // A repository's own files in 'default' add to the project, the image aside: what they ask
        // of this machine is allowed once, as CanStart answers, before anything is built.
        final Project settled;
        try {
            settled = withOwnSettings(project, projectFile, repository, out, err);
        } catch (ProjectException ex) {
            err.println("porelo: " + ex.getMessage());
            err.println("porelo: nothing was created");
            err.flush();
            return 64;
        }
        if (settled == null) {
            err.println("porelo: nothing was created");
            err.flush();
            return 64;
        }
        ImageChoice image;
        final ProjectException previewRefusal;
        final Project asDeclared = project;
        try {
            final ImageChoice.Preview preview = allowedPreview(asDeclared, repository, out, err);
            if (preview == null) {
                err.println("porelo: nothing was created");
                err.flush();
                return 64;
            }
            image = preview.choice();
            previewRefusal = preview.mirrorRefusal();
        } catch (ProjectException ex) {
            err.println("porelo: " + ex.getMessage());
            err.println("porelo: nothing was created");
            err.flush();
            return 64;
        }
        if (!gated && previewRefusal != null) {
            err.println("porelo: " + previewRefusal.getMessage());
            err.println(MIRROR_IMAGE_AGE);
            err.println("porelo: nothing was created");
            err.flush();
            return 64;
        }
        project = image.applyTo(settled, repository);
        out.println(
                "base image     "
                        + project.baseImage()
                        + "  (from "
                        + image.baseFrom()
                        // The fetch may bring another: then a second line names the one it runs on.
                        + (fetches(gated, settled, repository)
                                ? ", as it stands before the fetch"
                                : "")
                        + ")");
        if (image.snippetFrom() != null) {
            out.println("image lines    from " + image.snippetFrom());
        }
        image.envFrom()
                .forEach((name, from) -> out.println("image env      " + name + " from " + from));
        out.println("task image     " + project.imageName());
        // Settled before anything exists, as the daemon's CanStart settles them: a value nothing
        // allows must never reach a task, and a start that names the wrong one hears it now.
        try {
            values = project.parameters().resolve(request.parameters());
        } catch (ProjectException ex) {
            err.println("porelo: " + ex.getMessage());
            err.println("porelo: nothing was created");
            err.flush();
            return 64;
        }
        values.forEach(
                (name, given) ->
                        out.println(
                                "value          "
                                        + name
                                        + "="
                                        + given.value()
                                        + " ("
                                        + given.origin()
                                        + ")"));
        if (!settleSubagents(project, out, err)) {
            return 64;
        }
        if (!request.noGate() && !request.allRepositories()) {
            // Settled here, before anything exists, and kept with the task: a resume and a later
            // change to the project file leave it as it is.
            final String refused = settleForgeBranch(project, repository);
            if (refused != null) {
                err.println("porelo: " + refused);
                err.println("porelo: nothing was created");
                err.flush();
                return 64;
            }
            out.println(
                    "branch         "
                            + forgeBranch
                            + (request.branch() == null ? "" : " (named with --branch)"));
        } else {
            final String refused = StartCheck.hostServicesWithoutAGate(project);
            if (refused != null) {
                err.println(
                        "porelo: "
                                + (request.allRepositories() ? "--all-repositories" : "--no-gate")
                                + ": "
                                + refused
                                + (request.allRepositories()
                                        ? "; a task that reads them all has none"
                                        : "; start it with its gate"));
                err.println("porelo: nothing was created");
                err.flush();
                return 64;
            }
        }
        out.flush();

        // Resolved here rather than at the point of use: a set name that does not exist is a
        // mistake in the project file, and the place to report one of those is beside the other
        // project-file errors - before an image is built, and where --dry-run can still see it.
        final java.util.Map<String, String> projectOrigins;
        try {
            projectOrigins =
                    EgressReport.projectEgress(project, context.paths().egress().egressSets());
        } catch (org.fuin.porelo.shield.EgressSetException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 2;
        }

        if (request.dryRun()) {
            try (InstalledAgents agents = context.agents()) {
                final AgentChoice.Choice choice =
                        AgentChoice.considerAgent(context, agents, request.agentName(), repository);
                // The provider it was found signed in to is the one its model is settled for.
                decision = choice;
                sayFoundWarnings(choice, err);
                sayChoice(choice, out);
                if (choice.refusal() == AgentChoice.AgentRefusal.SEVERAL_AGENTS) {
                    out.println(
                            "note           name an agent with --agent to include its hosts and refusals");
                } else if (choice.refusal() != null) {
                    err.println("porelo: " + choice.detail());
                    err.flush();
                    return 2;
                }
                final InstalledAgent selected = choice.agent();
                if (selected != null && refusesSubagents(selected, err)) {
                    return 64;
                }
                SelectedProvider serving = null;
                if (selected != null) {
                    // Refused as the start refuses it, in one line and 2: a provider the run
                    // cannot use is the caller's to fix, not a defect to report.
                    final SelectedProvider.Choice provider =
                            SelectedProvider.consider(
                                    context.providers(),
                                    selected.definition(),
                                    request.providerName() != null
                                            ? request.providerName()
                                            : choice.provider());
                    if (provider.refusal() != null) {
                        err.println("porelo: " + provider.detail());
                        err.flush();
                        return 2;
                    }
                    serving = provider.selection();
                }
                // As the start refuses it, so a dry run promises no start that would not come.
                if (refusesModel(selected, project, projectFile, out, err)) {
                    return 2;
                }
                EgressReport.reportReachable(
                        project,
                        EgressReport.compose(selected, serving, projectOrigins).origins(),
                        EgressReport.refusals(selected, project),
                        project.egress().hostServices(),
                        out);
            } catch (org.fuin.porelo.agent.api.AgentException ex) {
                err.println("porelo: " + ex.getMessage());
                err.flush();
                return 2;
            }
            if ((request.repository() == null || request.repository().isBlank())
                    && !project.repositories().isEmpty()) {
                // Said, because otherwise a project-level plan reads as a task's. A repository adds
                // to this, so what a task actually reaches is this or more - never less.
                out.println(
                        "note      this is what every repository of '"
                                + project.name()
                                + "' gets; name one with --repository to see what it adds");
            }
            out.flush();
            return 0;
        }

        final java.util.Optional<String> tooOld = context.podman().unsupportedVersion();
        if (tooOld.isPresent()) {
            // Refused rather than run with less than the guarantees Porelo describes. podman 4 has
            // no pasta, so the git gate cannot bind loopback and the endpoint a task pushes to sits
            // on the operator's network. Ubuntu 24.04 LTS ships 4.9.3 and never will ship anything
            // newer - podman is in universe, and a stable release does not change major versions -
            // so this is a real refusal, and the way out is a newer distribution.
            err.println("porelo: " + tooOld.get());
            err.flush();
            return 69;
        }

        // Beside podman's floor, before anything is made: otherwise this machine learns it from
        // whichever 'ssh-keygen -Y' fails first. One that cannot be read refuses nothing, and
        // doctor
        // says so.
        if (OpenSshFloor.ask(context.runner()) instanceof OpenSshFloor.TooOld old) {
            err.println("porelo: " + old.refusal());
            err.flush();
            return 69;
        }

        if (!hooksReady(out, err)) {
            return 69;
        }

        // Asked before anything of the task is made: a build that cannot reach the repositories
        // the project declares would ask the tool's default hosts, which the egress refuses.
        final ToolSettings.Answer toolSettings =
                ToolSettings.ask(project, context.paths().ecosystemDirectories(), adapters);
        if (toolSettings instanceof ToolSettings.Answer.Refused refused) {
            err.println("porelo: " + refused.reason());
            err.println("porelo: nothing was created");
            err.flush();
            return 69;
        }
        final java.util.List<org.fuin.porelo.agent.api.ContainerFile> toolFiles =
                ((ToolSettings.Answer.Files) toolSettings).files();

        final TaskRunner runner = context.tasks();
        final String container = startContainer(project);
        final TaskReservations.Lease claimed = reservation;
        if (claimed != null && claimed.made()) {
            // Another start made the task after this one found nothing to bring back, and its
            // finished attempt is the name's: going on, this start wrote its broker's token, pid
            // and socket over that start's, in the state directory both name, before its own
            // create failed on the taken name. Refused before anything of the task is touched.
            throw new TaskReservations.ContainerNameTaken(
                    container, project.name(), request.task(), project.name(), request.task());
        }
        runner.reserving(reservation);
        runner.onCreated(id -> createdId = id);
        // Until the task is handed on, a failure is a start that failed, which leaves nothing
        // running whether or not the task was to be kept.
        handedOver = false;
        launching = true;
        createdId = null;
        ownHelpers.clear();

        try (org.fuin.porelo.agent.api.InstalledAgents agents = context.agents()) {

            // First, before a credential is looked at or anything is made: a task its agent cannot
            // hold to its number is not one to prepare.
            final InstalledAgent asked = select(agents);
            reportAgentChoice(out, err);
            if (asked != null && refusesSubagents(asked, err)) {
                return 64;
            }
            // Settled with the provider and refused before anything is made: a model the agent has
            // no flag for would be dropped on the way in, attended or not, and the agent would
            // answer on its own default without a word.
            if (refusesModel(asked, project, projectFile, out, err)) {
                return 2;
            }

            credentials().reportStaleCredential(select(agents), out);
            credentials().reportLegacyCredential(select(agents), out);

            if (refusesCredential(select(agents), err)) {
                return 69;
            }

            // Both modes that asked for the agent itself. UNATTENDED because nobody is watching, so
            // a warning is written into an empty room and the failure is found later by somebody
            // who did not start it. AGENT because the person asked for the agent's session: landing
            // them in an agent that cannot authenticate is the refusal below
            // - "the person who asked for an agent finds a bare prompt with nothing explaining
            // why" - one step later, after an image and a container have been built for it.
            //
            // SHELL is deliberately left warning. Working inside the container by hand is exactly
            // what it is for, and whether the agent could authenticate may not matter at all to
            // somebody who is only going to open a terminal.
            //
            // Refused here, before the gate, the image and the container: a refusal that left a
            // workspace and a held container behind would be the warning again with a different
            // exit code. Every other credential the project names, resolved before anything is
            // made: a destination nobody declared is a mistake in the project file, not something
            // to find inside the task.
            final java.util.Map<String, Destination> extras = new java.util.LinkedHashMap<>();
            // A repository at a host this machine never met: refused here, as CanStart answers it.
            // It went ahead before, ssh refused the workspace's fetch, and the task ran without a
            // gate. The address the gate will use: '--upstream' when given, which the project file
            // does not name.
            // Not for a task that reads every repository: each is fetched by its own gate, which
            // names a host it has never met per repository and leaves only that one out.
            final String unmet =
                    request.allRepositories()
                            ? null
                            : StartCheck.unknownHost(
                                    context,
                                    request.projectFile(),
                                    request.repository(),
                                    request.upstream());
            if (unmet != null && trustUnmet(unmet, err)) {
                out.println("trusted   " + unmet);
                out.flush();
            } else if (unmet != null && offer != Offer.NOBODY) {
                err.println("porelo: nothing was created");
                err.flush();
                return 69;
            } else if (unmet != null) {
                err.println(
                        "porelo: this machine has never met "
                                + unmet
                                + ", where the repository is, so it stops"
                                + " rather than deciding for you. See what it offers with 'porelo credentials trust-host "
                                + unmet
                                + "', confirm one against what its owner publishes, and start again.");
                err.println("porelo: nothing was created");
                err.flush();
                return 69;
            }
            // The upstream read as the gate's clone will read it, before anything is made: a
            // refused key surfaced from the clone as "no git gate for this task", after the task
            // was half made (found on 2026-10-13).
            if (gated) {
                UpstreamProbe.Finding finding = upstreamFinding(project);
                if (finding != null && finding.refuses()) {
                    finding = storeRefusedKey(finding, project, err);
                }
                if (finding != null && finding.refuses()) {
                    unreadableUpstream = finding.answer();
                    err.println("porelo: " + StartCheck.said(finding.answer()));
                    err.println("porelo: nothing was created");
                    err.flush();
                    return 69;
                }
                if (finding != null) {
                    // A mirror is there, so the task starts from it, as a refresh that fails
                    // already lets it.
                    err.println(
                            "porelo: "
                                    + StartCheck.said(finding.answer())
                                    + " This task starts from what the"
                                    + " mirror holds, which may be behind, and approved work cannot go back until it is fixed.");
                    err.flush();
                }
            }
            // The same words and the same rule CanStart answers with, so a check and a start cannot
            // disagree.
            final String undeclared =
                    StartCheck.undeclared(context, request.projectFile(), request.credentials());
            if (undeclared != null) {
                err.println("porelo: " + undeclared.substring(undeclared.indexOf('\n') + 1));
                err.println(
                        "porelo: nothing was created; declare it in "
                                + context.paths().xdg().data().resolve("destinations")
                                + ", or correct what names it");
                err.flush();
                return 69;
            }
            // The run adds to what the project declares, and never takes one away or points it
            // elsewhere.
            final java.util.Map<String, String> declaredOrAdded =
                    new java.util.LinkedHashMap<>(project.credentials());
            for (final java.util.Map.Entry<String, String> added :
                    request.credentials().entrySet()) {
                final String declared = declaredOrAdded.get(added.getKey());
                if (declared != null && !declared.equals(added.getValue())) {
                    err.println(
                            "porelo: the project already names credential '"
                                    + added.getKey()
                                    + "' for '"
                                    + declared
                                    + "'; a run adds credentials and cannot point one of the project's elsewhere");
                    err.flush();
                    return 2;
                }
                declaredOrAdded.put(added.getKey(), added.getValue());
            }
            // Here the project is the one this start reads, a repository's own settings merged in.
            final String names =
                    StartCheck.namesRefused(
                            project.credentials().keySet(), request.credentials().keySet());
            if (names != null) {
                err.println("porelo: " + names.substring(names.indexOf('\n') + 1));
                err.println("porelo: nothing was created");
                err.flush();
                return 2;
            }
            // A shut vault is opened here, at a terminal, as 'porelo vault unlock' would: the
            // broker spends its tokens for the task's life. Only where this start reads it; without
            // a terminal the refusals below stay as they were.
            steps.started(StartStep.Step.CREDENTIALS);
            context.openIfShut(
                    !declaredOrAdded.isEmpty()
                            || wiring().unavailableFor(select(agents))
                                    != StartCredentials.State.PRESENT,
                    true,
                    err);
            // Unlocking can reveal a refused kind that the early check could not read.
            if (refusesCredential(select(agents), err)) {
                return 69;
            }
            for (final java.util.Map.Entry<String, String> named : declaredOrAdded.entrySet()) {
                final org.fuin.porelo.vault.VaultEntry entry =
                        context.readableCredentials()
                                .map(stored -> stored.get(named.getKey()))
                                .orElse(null);
                final Destination destination =
                        Destination.resolve(
                                named.getValue(),
                                Destination.all(context.paths().xdg().data()),
                                context.providers(),
                                entry == null ? null : entry.type());
                if (destination == null) {
                    err.println(
                            "porelo: the project names credential '"
                                    + named.getKey()
                                    + "' for '"
                                    + named.getValue()
                                    + "', and no destination or provider of that name is declared here");
                    err.println(
                            "porelo: nothing was created; declare it in "
                                    + context.paths().xdg().data().resolve("destinations")
                                    + ", or correct the project file");
                    err.flush();
                    return 69;
                }
                if (request.mode() == org.fuin.porelo.wire.TaskMode.UNATTENDED
                        && CredentialWiring.credentialUnavailable(
                                        context.readableCredentials(), named.getKey())
                                != StartCredentials.State.PRESENT) {
                    err.println(
                            "porelo: the project names credential '"
                                    + named.getKey()
                                    + "', and "
                                    + CredentialWiring.reason(
                                            CredentialWiring.credentialUnavailable(
                                                    context.readableCredentials(), named.getKey()),
                                            named.getKey()));
                    err.println(
                            "porelo: nothing was created; an unattended run cannot ask anybody, so it is refused"
                                    + " rather than started to fail");
                    err.flush();
                    return 69;
                }
                extras.put(named.getKey(), destination);
            }
            this.extras = java.util.Map.copyOf(extras);

            // A credential that has to be bought is tried once before anything exists: an
            // authorization server that refuses or cannot be reached makes it as missing as a key
            // the vault lacks.
            final java.util.Set<String> held = new java.util.LinkedHashSet<>(extras.keySet());
            final org.fuin.porelo.agent.api.InstalledAgent agentHere = select(agents);
            if (agentHere != null) {
                held.add(credentials().credentialName(agentHere));
            }
            for (final String name : held) {
                final String refused = purchaseRefused(name);
                if (refused == null) {
                    continue;
                }
                if (ungrantedAuthorization(name)) {
                    // Asked of a person too, not only said here: somebody else may be the one who
                    // can grant it.
                    AuthorizationsNeeded.raise(
                            context, name, container, project.name(), AuthorizationsNeeded.NEVER);
                }
                if (refusesWithoutCredential(request.mode())) {
                    err.println("porelo: credential '" + name + "': " + refused);
                    err.println(
                            "porelo: nothing was created; a task that cannot buy its token would fail on its first"
                                    + " request");
                    err.flush();
                    return 69;
                }
                err.println(
                        "porelo: credential '"
                                + name
                                + "': "
                                + refused
                                + " - the task starts, and requests that"
                                + " need it will fail until the authorization server sells a token");
                err.flush();
            }

            // A task asked to open the agent's session must have one. Before the workspace, the
            // image and the container: attaching a plain shell instead and recording the mode as
            // AGENT would say a task is something it is not, and the person who asked for an agent
            // finds a bare prompt with nothing explaining why.
            //
            // Only for AGENT. A SHELL task deliberately needs no agent - working inside the
            // container by hand is exactly what it is for.
            if (request.mode() == org.fuin.porelo.wire.TaskMode.AGENT
                    && select(agents) == null
                    && (retry = shellInstead("no agent to attach", offer)) != null) {
                return RETRY;
            }
            if (request.mode() == org.fuin.porelo.wire.TaskMode.AGENT && select(agents) == null) {
                err.println(
                        "porelo: no agent to attach"
                                + (AgentChoice.wanted(request.agentName(), workedOn).name() == null
                                        ? "; none is installed here"
                                        : ": no agent called '"
                                                + AgentChoice.wanted(request.agentName(), workedOn)
                                                        .name()
                                                + "' is installed"));
                err.println(
                        "porelo: nothing was created; run with '--attach shell' for a terminal"
                                + " without one");
                err.flush();
                return 69;
            }
            // An attended task given a prompt hands it to its agent as the session's first turn;
            // one whose agent cannot take it is refused here, before anything is made.
            if (refusesOpening(select(agents), err)) {
                err.println("porelo: nothing was created");
                err.flush();
                return 64;
            }

            if (gated) {
                steps.started(StartStep.Step.WORKSPACE);
            }
            if (gated) {
                final String branchHeld = branchHeldElsewhere(project);
                if (branchHeld != null) {
                    heldElsewhere =
                            new BranchTaken(
                                    request.task(),
                                    java.util.Objects.requireNonNull(forgeBranch),
                                    branchHeld);
                    err.println("porelo: " + branchHeld);
                    err.println("porelo: nothing was created");
                    err.flush();
                    return 64;
                }
            }
            workspace().forgeBranch(forgeBranch);
            final TaskWorkspace workspace = workspace().openWorkspace(project, gated, out, err);
            // Refused here, as the start would refuse it after its broker holds the credential:
            // only an actual workspace needs a certificate, and it is known from this line on.
            if (workspace != null && host.present()) {
                final java.util.List<String> certifiers =
                        TaskCertifying.certifiers(context, project);
                if (!TaskCertifying.canCertify(
                                project.name(),
                                certifiers,
                                remoteCertifier(project.name(), true, certifiers),
                                starterAgent)
                        && !startsUncertified(err)) {
                    throw new TaskCertification.Refused(
                            TaskCertifying.noCertificate(
                                    project.name(),
                                    request.task(),
                                    System.getProperty("user.name", ""),
                                    certifiers,
                                    repository.name()));
                }
            }
            final String earlier = workspace().refusal();
            if (earlier != null
                    && (retry = nameInstead(earlier, workspace().freeName(), offer)) != null) {
                return RETRY;
            }
            if (earlier != null) {
                err.println("porelo: " + earlier);
                err.println("porelo: nothing was created");
                err.flush();
                return 65;
            }
            if (workspace == null && previewRefusal != null) {
                err.println("porelo: " + previewRefusal.getMessage());
                err.println(MIRROR_IMAGE_AGE);
                err.println("porelo: nothing was created");
                err.flush();
                return 64;
            }
            if (workspace != null) {
                try {
                    startingBranch =
                            TaskBase.select(
                                    context, project, workspace.gate().mirror(), request.base());
                    if (startingBranch != null) {
                        workspace.startingFrom(startingBranch);
                        out.println(
                                "starts from    "
                                        + startingBranch.branch()
                                        + " ("
                                        + startingBranch.phrase()
                                        + ")");
                        out.flush();
                    }
                } catch (ProjectException ex) {
                    err.println("porelo: " + ex.getMessage());
                    err.println("porelo: nothing was created");
                    err.flush();
                    return 64;
                }
                // The tree the agent gets, read here before it can: a resume does not come this
                // way, its tree is in the agent's hands already.
                if (startingBranch != null
                        && !new SecretCheck(context, settled, out, err)
                                .check(
                                        repository.name(),
                                        workspace.gate().mirror(),
                                        "refs/heads/" + startingBranch.branch(),
                                        startingBranch.branch())) {
                    return 65;
                }
            }
            // Only the fetched file can settle a refused preview.
            if (workspace != null) {
                ImageChoice fetched;
                try {
                    try {
                        fetched = ImageChoice.of(context, asDeclared, repository);
                    } catch (ImageChoice.NotAllowed ex) {
                        // The fetch brought the file, or changed it: asked here, as before it.
                        for (final var each : ex.toAllow()) {
                            out.println("setting        " + RepositorySettingsText.line(each));
                        }
                        out.flush();
                        if (!allowedImage(ex, asDeclared, repository, err)) {
                            err.println("porelo: nothing was created");
                            err.flush();
                            return 64;
                        }
                        out.println(
                                "settings       image allowed for "
                                        + repository.name()
                                        + " of "
                                        + asDeclared.name()
                                        + " on this machine");
                        fetched = ImageChoice.of(context, asDeclared, repository);
                    }
                } catch (ProjectException ex) {
                    err.println("porelo: " + ex.getMessage());
                    err.println("porelo: nothing was created");
                    err.flush();
                    return 64;
                }
                if (previewRefusal != null || !fetched.equals(image)) {
                    project = fetched.applyTo(settled, repository);
                    out.println(
                            "base image     "
                                    + project.baseImage()
                                    + "  (from "
                                    + fetched.baseFrom()
                                    + ", read after the fetch)");
                    if (fetched.snippetFrom() != null) {
                        out.println("image lines    from " + fetched.snippetFrom());
                    }
                    fetched.envFrom()
                            .forEach(
                                    (name, from) ->
                                            out.println(
                                                    "image env      " + name + " from " + from));
                    out.println("task image     " + project.imageName());
                    out.flush();
                }
                image = fetched;
            }
            // Every mode: a shell without a gate cannot reach the host services either.
            final String gateless =
                    gated && workspace == null
                            ? StartCheck.hostServicesWithoutAGate(project)
                            : null;
            if (gateless != null) {
                err.println(
                        "porelo: nothing was created; "
                                + gateless
                                + ", since it did not open: fix what is said above");
                err.flush();
                return 69;
            }
            // No shell offered in its place: a shell without a gate has an empty workspace and
            // fixes nothing about what stopped the gate, which is said above.
            if (gated
                    && workspace == null
                    && request.mode() != org.fuin.porelo.wire.TaskMode.SHELL) {
                // An agent's work leaves through the gate. Started without one, it has an empty
                // workspace and nowhere to hand back what it does - which is how a task in
                // 'default' whose origin was over ssh started, worked on nothing and said exit 0. A
                // shell, which a person drives, still starts.
                err.println(
                        "porelo: nothing was created; the agent would work in an empty workspace with nowhere to"
                                + " hand its work back. Fix what is said above, or '--attach shell' starts a shell without"
                                + " a gate");
                err.flush();
                return 69;
            }

            final org.fuin.porelo.runtime.ImageLayers layers;
            try {
                layers = layers(agents, project, image, out);
            } catch (org.fuin.porelo.agent.api.AgentException ex) {
                err.println("porelo: " + ex.getMessage());
                err.flush();
                return 69;
            }

            final org.fuin.porelo.agent.api.InstalledAgent selected = select(agents);

            // Into the project's conversations before anything exists, and after every other
            // refusal: a task whose messages reach nobody is refused here, as a credential nobody
            // can reach is - and enrolled earlier, a start refused afterwards for something else
            // left its account at the transport with nothing to retire it.
            final TaskConversations conversations = new TaskConversations(context);
            final String unreachable =
                    conversations.enroll(
                            project,
                            container,
                            selected == null ? null : selected.definition().name(),
                            steps);
            if (unreachable != null) {
                err.println("porelo: " + unreachable);
                err.println("porelo: nothing was created");
                err.flush();
                return conversations.refusedWith();
            }
            enrolledIn = project;

            // The state directory has to exist before anything writes into it. start() also creates
            // it, but the credential proxy needs it first: it writes its socket, its token and its
            // pid there, and it has to be listening before the container exists.
            final java.nio.file.Path state = context.paths().tasks().containerState(container);
            Directories.create(state);
            // First, so a start that fails from here on is still found by its caller's key.
            TaskInventory.writeCallerKey(state, callerKey);
            if (startingBranch != null && workspace != null) {
                startingBranch.writeTo(state);
                startingBranch.writeForGate(workspace.gate().mirror(), request.task());
            }
            steps.recordIn(state);
            if (request.allRepositories()) {
                // Written by the host before the container exists, which mounts it read only: once
                // enrolled, so a start refused here is ended as every other refused start is.
                steps.started(StartStep.Step.WORKSPACE);
                final TaskRepositories reading = new TaskRepositories(context.paths(), container);
                final SecretCheck secrets = new SecretCheck(context, project, out, err);
                final java.util.List<String> readable =
                        workspace().readAll(project, reading.directory(), secrets, out, err);
                if (secrets.refused()) {
                    return cleanUp(runner, container, 65, false, out, err);
                }
                if (readable.isEmpty()) {
                    err.println(
                            "porelo: no repository of '"
                                    + project.name()
                                    + "' could be read here, so this task would have nothing to"
                                    + " read; fix what is said above and start again");
                    err.println("porelo: nothing was created");
                    err.flush();
                    return cleanUp(runner, container, 69, false, out, err);
                }
            }
            steps.started(StartStep.Step.IMAGE);

            // The mailbox, which is not in the state directory and does not go with it: a
            // conversation survives stop, start and a machine restart, and only 'remove' ends it.
            final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
            mailbox.create();
            informed(project, container, true);

            final TaskWiring wiring =
                    new TaskWiring(
                            workspace == null ? null : gate().gateAddress(project, err),
                            workspace == null ? 0 : workspace.port(),
                            mailbox.box());

            java.util.Map<String, String> environmentCache = new java.util.LinkedHashMap<>();

            // An agent that can only address a URL still gets the broker on its socket: only the
            // listening end moves into the container's namespace, and that is a relay started after
            // the container exists.
            final SelectedProvider serving = credentials().provider(selected);
            servingName = serving == null ? null : serving.name();
            // Every destination carries where it came from, so the report at the end of this can
            // answer "who decided this" rather than only "what is open". The same composition the
            // egress editor shows, from the same method.
            final EgressReport.Reachable reachable =
                    EgressReport.compose(selected, serving, projectOrigins);
            final java.util.List<String> domains = new java.util.ArrayList<>(reachable.domains());
            final java.util.Map<String, String> origins =
                    new java.util.LinkedHashMap<>(reachable.origins());

            // Whether the project's file allows the task an MCP endpoint is the daemon's to say;
            // when it does, the gateway is told the route before the broker starts, with its key
            // kept where the broker reads it.
            final McpWiring.Endpoint mcp =
                    McpWiring.open(context, container, project.name()).orElse(null);
            CredentialWiring.McpRoute mcpRoute = null;
            if (mcp != null) {
                mcpRoute =
                        new CredentialWiring.McpRoute(
                                McpWiring.destination(mcp.socket()),
                                TaskSecrets.mcpKeyEntry(container));
                final String unkept = new TaskSecrets(context).keepMcpKey(container, mcp.token());
                if (!unkept.isEmpty()) {
                    throw new McpWiring.Refused(McpWiring.Refusal.KEY_NOT_KEPT, unkept);
                }
            }
            final CredentialWiring.CredentialPlumbing plumbing =
                    wiring().startVault(selected, container, this.extras, mcpRoute, out, err);
            if (mcp != null && plumbing == null) {
                throw new McpWiring.Refused(
                        McpWiring.Refusal.NO_GATEWAY,
                        "the gateway did not come up, so this task's MCP endpoint is unreachable");
            }
            if (!values.isEmpty()) {
                // With each value's origin, so a branch named from one can be traced back; fixed
                // for the task's life, as its environment is.
                final java.util.Map<String, Object> recorded = new java.util.LinkedHashMap<>();
                values.forEach(
                        (name, given) ->
                                recorded.put(
                                        name,
                                        java.util.Map.of(
                                                "value", given.value(), "origin", given.origin())));
                try {
                    java.nio.file.Files.writeString(
                            context.paths()
                                    .tasks()
                                    .containerState(container)
                                    .resolve(TaskInventory.PARAMETERS_FILE),
                            org.fuin.porelo.wire.Json.write(recorded),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.io.IOException ex) {
                    err.println(
                            "porelo: could not record the values this task was started with: "
                                    + ex.getMessage());
                    err.flush();
                }
            }
            if (!this.extras.isEmpty()) {
                // By name and destination, for anything that asks what the task holds; never a
                // token.
                final java.util.Map<String, Object> given = new java.util.LinkedHashMap<>();
                this.extras.forEach((name, destination) -> given.put(name, destination.name()));
                try {
                    java.nio.file.Files.writeString(
                            context.paths()
                                    .tasks()
                                    .containerState(container)
                                    .resolve(TaskInventory.CREDENTIALS_FILE),
                            org.fuin.porelo.wire.Json.write(given),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.io.IOException ex) {
                    err.println(
                            "porelo: could not record which credentials this task holds: "
                                    + ex.getMessage());
                    err.flush();
                }
                GrantRecord.record(context, container, project.name(), this.extras.keySet(), err);
            }
            // The project's package repositories with a credential: read through a broker of their
            // own, which hands the task no token, only an address.
            final CredentialWiring.RepositoryPlumbing repositories =
                    wiring().startRepositories(project.toolRepositories(), container, out, err);
            if (repositories != null) {
                environmentCache.putAll(repositories.environment());
            }
            // The agent's URL endpoint, or any other credential: those are always reached over the
            // URL.
            final boolean needsRelay =
                    serving != null
                                    && serving.route().endpoint()
                                            == org.fuin.porelo.agent.api.ProviderRoute.Endpoint.URL
                            || plumbing != null && plumbing.servesOthers();
            if (plumbing != null) {
                environmentCache.putAll(plumbing.environment());
                // Left reachable. Withholding it stopped every agent that checks the provider is up
                // before it starts, and what it kept in was the phantom token: random, task-scoped,
                // and worth nothing to the provider. The real credential is what must not get out,
                // and it never enters the container at all.
                out.println(
                        "provider  "
                                + plumbing.upstreamHost()
                                + " reachable; the credential is not, only a task-scoped token");
            } else {
                environmentCache.putAll(environment(agents, out, err));
            }

            // The issue prefixes the task's repository owns, so its agent names and cites issues as
            // the repository does; empty where the repository names none.
            environmentCache.put(ISSUE_PREFIXES, String.join(",", repository.issues()));
            // Where a reading of an issue writes its findings, so the agent and the planner read
            // one value: the repository's own file's, the project's, or the default.
            environmentCache.put(READINGS_DIRECTORY, project.readingsDirectory());
            // The values it was started with, each under its declaration's variable; one declared
            // 'env: none' stays out. Fixed with the container, so a resume cannot change one.
            for (final var parameter : project.parameters().declared().values()) {
                final var given = values.get(parameter.name());
                if (given != null && parameter.env() != null) {
                    environmentCache.put(parameter.env(), given.value());
                }
            }
            // Certified before its git is set up, so a task with a key signs from its first commit;
            // one whose starter's agent holds no certifier's key starts as it did, unsigned.
            final java.util.List<String> certifiers =
                    workspace == null
                            ? java.util.List.of()
                            : TaskCertifying.certifiers(context, project);
            final TaskCertification.Issued issued =
                    workspace == null
                            ? null
                            : TaskCertifying.atStart(
                                    project.name(),
                                    request.task(),
                                    System.getProperty("user.name", ""),
                                    certifiers,
                                    remoteCertifier(project.name(), host.present(), certifiers),
                                    starterAgent,
                                    java.time.Clock.systemUTC());
            // A host records each task's certificate and makes its forge token: it starts none
            // without one, since what it passes on is then vouched for by nobody - unless its root
            // switched on test mode, and the person said yes to that after the warning.
            // Refused where every refusal is, so what was started for it, its vault's broker
            // first, is cleaned up with it.
            if (workspace != null && issued == null && host.present() && !startsUncertified(err)) {
                throw new TaskCertification.Refused(
                        TaskCertifying.noCertificate(
                                project.name(),
                                request.task(),
                                System.getProperty("user.name", ""),
                                certifiers,
                                java.util.Objects.requireNonNull(workedOn).name()));
            }
            if (workspace != null) {
                if (issued != null) {
                    workspace.certified(issued.certificate().line());
                }
                environmentCache.putAll(workspace.environment(project, request.task()));
            }

            final java.util.Map<String, String> refusals = EgressReport.refusals(selected, project);
            EgressReport.reportReachable(
                    project, origins, refusals, project.egress().hostServices(), out);
            runner.refusing(java.util.List.copyOf(refusals.keySet()));

            // What only this start knows: which agent, how somebody is meant to be involved, what
            // it was asked to do, its model and which ref its work goes to. Written before the
            // container runs, so a listing never reads a running task without it.
            writeProfile(container, selected, environmentCache.get("PORELO_TASK_REF"), err);
            // The port is decided before this, so the firewall rule can name it; the gate itself
            // starts afterwards, because its log lives in the state directory that start() creates.
            // Starting it first silently failed to spawn at all.
            runner.start(
                    project,
                    repository,
                    verifiedAt,
                    container,
                    layers,
                    environmentCache,
                    domains,
                    wiring,
                    out,
                    steps,
                    request.task(),
                    request.shortName());
            if (request.allRepositories()) {
                // What it reads cannot be written, so it holds nothing to hand on: said from the
                // start, so a task killed before any stop asked it is still removed without asking.
                heldNothing(container);
            }

            // Once the container is up, and only one the broker gave a token for: a credential the
            // vault lacks, or one the broker never came up for, was given to nobody. Names only,
            // never a value: which entry, and where.
            final String projectName = project.name();
            this.extras.forEach(
                    (entry, destination) -> {
                        if (plumbing != null
                                && plumbing.environment()
                                        .containsKey(
                                                "PORELO_TOKEN_"
                                                        + CredentialWiring.variableSuffix(entry))) {
                            EventLog.record(
                                    context,
                                    EventLog.CREDENTIAL,
                                    projectName,
                                    container,
                                    entry
                                            + " for "
                                            + destination.name()
                                            + (request.credentials().containsKey(entry)
                                                    ? ", given with --credential"
                                                    : ", named by the project"));
                        }
                    });
            // Beside the agent's files, and only in a container made now: one that comes back
            // keeps what it holds.
            if (!placeToolSettings(runner, container, toolFiles, out, err)) {
                return cleanUp(runner, container, 70, false, out, err);
            }
            // Before anything else is started for it: a setup that fails then leaves no relay or
            // gate behind, only the container and its broker, which stopping it ends.
            final java.util.List<org.fuin.porelo.agent.api.ContainerFile> inWorkspace =
                    placeAgentFiles(runner, selected, container, environmentCache, out, err);
            if (inWorkspace == null) {
                return cleanUp(runner, container, 70, false, out, err);
            }
            steps.at(container);
            steps.started(StartStep.Step.HELPERS);
            // A helper that did not come up fails the start: answered with 0, the task ran with
            // no endpoint or no gate, and nothing said so but a line in its log.
            // A socket agent's socket, too, is the relay's, bound inside the task once it runs:
            // a file mounted from the host is one the account could have replaced.
            if (plumbing != null
                    && (needsRelay || plumbing.socketInTask())
                    && !wiring().startRelay(
                                    runner,
                                    container,
                                    plumbing.socket(),
                                    needsRelay,
                                    plumbing.socketInTask(),
                                    out,
                                    err)) {
                return cleanUp(runner, container, 70, false, out, err);
            }
            if (repositories != null
                    && !wiring().startRepositoryRelay(
                                    runner, container, repositories.socket(), out, err)) {
                return cleanUp(runner, container, 70, false, out, err);
            }

            // Before the gate, which is told whether it started: a watch that did not left the
            // gate expecting a build report that never came, and the agent waited for it.
            final org.fuin.porelo.core.project.Builds hostBuilds =
                    BuildsWiring.hostBuilds(project, host.present(), projects);
            final BuildsWiring buildsWiring = new BuildsWiring(context, this::recordHelper);
            buildsWiring.owned = ownHelpers::add;
            final boolean buildsWatched =
                    buildsWiring.start(
                            project, repository, container, forgeBranch, hostBuilds, out, err);

            if (workspace != null) {
                if (!gate().reading(reads)
                        .followingBuilds(BuildsWiring.follows(project, hostBuilds))
                        .watchingBuilds(buildsWatched)
                        .startGate(
                                runner,
                                workspace,
                                wiring.gateAddress(),
                                container,
                                request.task(),
                                project,
                                forgeBranch,
                                issued == null
                                        ? null
                                        : certifiedGate(
                                                container,
                                                issued,
                                                hostPushRepository(project, repository)),
                                out,
                                err)) {
                    return cleanUp(runner, container, 70, false, out, err);
                }
                final boolean prepared =
                        workspace()
                                .prepareWorkspace(
                                        runner, workspace, container, environmentCache, out, err);
                if (!prepared && startingBranch != null) {
                    return cleanUp(runner, container, 70, false, out, err);
                }
            }
            if (mcp != null) {
                // Where the gate starts, and as it does; the task counts as started with it only
                // once it said 'ready'.
                recordHelper(
                        McpWiring.HELPER,
                        McpWiring.start(context, mcp),
                        java.util.Map.of(),
                        TaskHelpers.AFTER);
                out.println("mcp       " + mcp.socket() + McpWiring.PATH);
                out.flush();
            }
            if (!placeWorkspaceFiles(runner, selected, container, inWorkspace, out, err)) {
                return cleanUp(runner, container, 70, false, out, err);
            }

            out.println();

            // A watcher that did not come up fails the start as any helper does: only warned
            // about, the task ran with nobody to ask about a blocked connection.
            if (!clearance().startClearance(runner, project, container, out, err)) {
                return cleanUp(runner, container, 70, false, out, err);
            }
            // A host's two refusals, its kept key and its forge token, before anything records the
            // task as one to bring back: refused after, the next start resumed it without either.
            // A start refused here is removed, not kept, since nobody worked in it yet.
            discarding = true;
            if (issued != null) {
                // Without its key in the vault the gate cannot sign: say so, as for the tokens.
                final String unsigned =
                        new TaskSecrets(context)
                                .keepSigning(
                                        container,
                                        new TaskSecrets.Signing(
                                                issued.key().seedBase64(),
                                                issued.certificate().line()));
                if (!unsigned.isEmpty()) {
                    out.println("signing   " + unsigned);
                }
                // Taken only once its key is kept, which is what has a removal end it: taken
                // earlier, a start that failed on the way left it live until its certificate ran
                // out.
                if (workspace != null && host.present()) {
                    if (!unsigned.isEmpty()) {
                        throw new TaskCertification.Refused(
                                "this host makes a task's forge token only when the task's key is"
                                        + " kept, which is how its removal ends the token: "
                                        + unsigned);
                    }
                    final String forgeToken =
                            host.start(request.task(), issued.certificate().line());
                    if (!forgeToken.isEmpty()) {
                        final String unkeptToken =
                                new TaskSecrets(context).keepForgeToken(container, forgeToken);
                        if (!unkeptToken.isEmpty()) {
                            out.println("forge     " + unkeptToken);
                        }
                    }
                }
                // Kept past the task, so a signature by its plain key is traced to whoever vouched.
                try {
                    new TaskCertificateRecords(context)
                            .keep(
                                    request.task(),
                                    System.getProperty("user.name", ""),
                                    issued.certificate());
                } catch (java.io.IOException ex) {
                    out.println("trace     the certificate is not kept: " + ex.getMessage());
                }
            } else if (workspace != null && host.present() && startsUncertified(err)) {
                // The host's test mode, said and agreed to above: an empty input asks for it, and
                // the host refuses it unless its root's switch holds.
                final String forgeToken = host.startUncertified(request.task());
                if (!forgeToken.isEmpty()) {
                    final String unkeptToken =
                            new TaskSecrets(context).keepForgeToken(container, forgeToken);
                    if (!unkeptToken.isEmpty()) {
                        out.println("forge     " + unkeptToken);
                    }
                }
            }
            discarding = false;
            writeResumeRecord(container, err);
            // What brings it back after a reboot: its description and records saved where a reboot
            // does not reach, and its two tokens in the vault. A task that cannot keep them runs,
            // and says so.
            new TaskState(context).save(container);
            final String unkept =
                    new TaskSecrets(context)
                            .keep(
                                    container,
                                    TaskSecrets.fromRuntime(
                                            context.paths().tasks().containerState(container)),
                                    () -> existingTasks(container));
            if (!unkept.isEmpty()) {
                out.println("reboot    " + unkept);
            }
            if (mcp != null) {
                // Again, now that its container exists: a start beside this one that kept its
                // tokens before then pruned the key as a gone task's.
                final String mcpUnkept =
                        new TaskSecrets(context).keepMcpKey(container, mcp.token());
                if (!mcpUnkept.isEmpty()) {
                    out.println("mcp       " + mcpUnkept);
                }
            }

            // The task is up. What happens next - run the agent to completion, attach a terminal,
            // or simply say so - is the caller's, and it happens inside this scope on purpose: the
            // installed agents are a resource this owns, and the paths that follow a start use
            // them.
            // Only now that nothing can refuse the start: a record or an event of a start that then
            // failed would claim work that never ran.
            recordStart(container, project.name(), selected, err);
            steps.done(StartStep.Step.HELPERS);
            handedOver = true;
            releaseReservation();
            return after.started(
                    new Running(
                            runner,
                            agents,
                            selected,
                            container,
                            environmentCache,
                            project,
                            code -> cleanUp(runner, container, code, out, err),
                            model.model()));

        } catch (HostPerson.Refused ex) {
            hostRefusal = ex.getMessage();
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return cleanUp(
                    runner,
                    container,
                    ex.code() == 69 ? 69 : 65,
                    handedOver && request.keep(),
                    out,
                    err);
        } catch (TaskCertification.Refused ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return cleanUp(runner, container, 65, handedOver && request.keep(), out, err);
        } catch (TaskCertificates.Refused ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return cleanUp(runner, container, 70, handedOver && request.keep(), out, err);
        } catch (CredentialWiring.BrokerDown ex) {
            // Said where it happened; a start without the broker it asked for is no start.
            steps.refusal(String.valueOf(ex.getMessage()));
            return cleanUp(runner, container, 70, handedOver && request.keep(), out, err);
        } catch (McpWiring.Refused ex) {
            steps.refusal(String.valueOf(ex.getMessage()));
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return cleanUp(runner, container, ex.code(), handedOver && request.keep(), out, err);
        } catch (CommandException ex) {
            steps.dependencyFailed(ex.standardError());
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return cleanUp(runner, container, 70, handedOver && request.keep(), out, err);
        } catch (org.fuin.porelo.agent.api.AgentException ex) {
            // "Several agents are installed" is a sentence for a person. Printed as an object it
            // reached an interface as a Java class name, which says nothing about what to do.
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return cleanUp(runner, container, 69, handedOver && request.keep(), out, err);
        } catch (Exception ex) {
            err.println("porelo: " + ex);
            err.flush();
            return cleanUp(runner, container, 70, handedOver && request.keep(), out, err);
        }
    }

    /**
     * Runs the agent against the prompt this task was started with.
     *
     * <p>In the launch rather than in the CLI, because a task started over the socket is as
     * unattended as one started at a terminal and has to do the same thing. What stays in the CLI
     * is the rendering: the agent formats its own log for a person, and a socket has no person on
     * the far end - only the file, which {@code Tail} already serves.
     *
     * @param runner Runs the container.
     * @param selected The agent, or {@code null} when none is installed.
     * @param container Container name.
     * @param environment What the container was given, so the agent runs with the same.
     * @param out Where progress is reported.
     * @param err Where a failure is reported.
     * @return Exit code of the agent, or 69 when there is no agent to run.
     */
    public int runAgent(
            TaskRunner runner,
            @Nullable InstalledAgent selected,
            String container,
            java.util.Map<String, String> environment,
            PrintWriter out,
            PrintWriter err) {
        if (request.prompt() == null) {
            return runAgentSteps(runner, selected, container, environment, out, err);
        }
        steps.at(container);
        steps.started(StartStep.Step.AGENT);
        try {
            steps.recordIn(context.paths().tasks().containerState(container));
            final int code =
                    runAgentSteps(runner, selected, container, environment, out, reporting(err));
            steps.finish(code);
            return code;
        } catch (RuntimeException ex) {
            steps.refusal(String.valueOf(ex.getMessage()));
            steps.finish(70);
            throw ex;
        } finally {
            steps.clear();
        }
    }

    /**
     * Has the lines of this start's run sent as it writes them, as the agent shows them.
     *
     * @param lines Where each line goes; may block, which never holds the run.
     */
    public void followRun(java.util.function.Consumer<String> lines) {
        this.runLines = lines;
    }

    /**
     * Returns what this start's own run answered, read at its end.
     *
     * @return The result, or {@code null} when this start ran no agent.
     */
    public @Nullable RunResult runResult() {
        return runResult;
    }

    /**
     * Returns how many of the run's lines were not sent to a caller that read too slowly.
     *
     * @return The count, 0 when nothing was followed or all was sent.
     */
    public long runLinesDropped() {
        return runLinesDropped;
    }

    /**
     * Ends a run whose time limit passed as a stop ends a task: only the podman exec client was
     * ended, and the agent in the container went on, unbounded and unrecorded. The end is recorded
     * first, so a fleet view never sees the task stopped without knowing why, and only by the stop
     * that stops it running: a task somebody stopped just before was stopped by them, not by its
     * limit.
     *
     * @param context The machine.
     * @param container The task.
     * @param minutes The limit that passed.
     * @param err Where a failure is said, and what the stop left running.
     * @return Whether the stop went through: a task stopped, with nothing of it left running.
     */
    static boolean endAtLimit(
            PoreloContext context, String container, int minutes, PrintWriter err) {
        try {
            final TaskControl.Stopped stopped =
                    new TaskControl(context)
                            .stopRunning(
                                    container,
                                    () -> recordEndAtLimit(context, container, minutes, err));
            return reportStop(stopped, err) && stopped.outcome() == TaskControl.Outcome.STOPPED;
        } catch (RuntimeException ex) {
            err.println(
                    "porelo: the task could not be stopped at its time limit, and its agent may"
                            + " still run: "
                            + ex.getMessage());
            err.flush();
            return false;
        }
    }

    private void settleLimit(String container, boolean settled, PrintWriter err) {
        final java.nio.file.Path state = context.paths().tasks().containerState(container);
        if (!settled) {
            RunLimit.released(state);
            return;
        }
        try {
            RunLimit.closed(state);
        } catch (java.io.IOException ex) {
            err.println("porelo: could not close this run's time limit: " + ex.getMessage());
            err.flush();
        }
    }

    private static void recordEndAtLimit(
            PoreloContext context, String container, int minutes, PrintWriter err) {
        try {
            RunLimit.ended(
                    context.paths().tasks().containerState(container),
                    minutes,
                    java.time.Instant.now());
        } catch (java.io.IOException | RuntimeException ex) {
            err.println("porelo: could not record the end at its time limit: " + ex.getMessage());
            err.flush();
        }
    }

    private int runAgentSteps(
            TaskRunner runner,
            @Nullable InstalledAgent selected,
            String container,
            java.util.Map<String, String> environment,
            PrintWriter out,
            PrintWriter err) {
        // What a run of this call says, never what an earlier call's did.
        runResult = null;
        runLinesDropped = 0;
        if (selected == null) {
            err.println("porelo: a prompt needs an agent, and none is installed");
            err.flush();
            return NO_AGENT;
        }
        final String prompt = request.prompt();
        if (prompt == null) {
            err.println(
                    "porelo: this task was started without a prompt, so there is nothing to run");
            err.flush();
            return 2;
        }
        // Before anything of the run is written: a refused binary leaves no run behind.
        final AgentBinary.Pinned binary;
        switch (runner.agentBinary(container, selected.definition())) {
            case AgentBinary.Pinned pinned -> binary = pinned;
            case AgentBinary.Refused refused -> {
                err.println("porelo: " + refused.said());
                err.flush();
                return 70;
            }
        }
        final Path log = context.paths().tasks().containerState(container).resolve("task.log");
        // This run's log alone: a resumed task's last run must not answer for this one, nor stream
        // as if it were. Emptied in place, so a reader never finds it missing; a link is removed,
        // never followed.
        try {
            if (java.nio.file.Files.isSymbolicLink(log)) {
                java.nio.file.Files.delete(log);
            }
            java.nio.file.Files.newOutputStream(
                            log,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                            java.nio.file.StandardOpenOption.WRITE,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    .close();
        } catch (java.io.IOException ex) {
            err.println(
                    "porelo: the run's log could not be emptied, so nothing was run: "
                            + ex.getMessage());
            err.flush();
            return 70;
        }
        // Continuing is what starting a task that has a session does - not a verb of its own - and
        // only where the agent says how to name one: nothing is guessed, and a fresh session says
        // it is one.
        final TaskSession sessions = new TaskSession(context);
        final String continuing =
                sessions.toContinue(container, selected.definition()).orElse(null);
        final org.fuin.porelo.agent.api.RunRequest agentRequest =
                new org.fuin.porelo.agent.api.RunRequest(
                        prompt, runModel, request.maxTurns(), continuing, false, true, subagents);

        out.println();
        out.println("running   " + selected.name() + " (up to " + request.minutes() + " minutes)");
        out.println(
                "session   "
                        + (continuing != null
                                ? "continuing " + continuing
                                : selected.definition().sessionIds() == null
                                        ? "a fresh one - this agent does not say how to continue one"
                                        : "a fresh one"));
        out.flush();

        final java.util.function.Consumer<String> following = runLines;
        try {
            RunLimit.started(context.paths().tasks().containerState(container), request.minutes());
        } catch (java.io.IOException ex) {
            err.println("porelo: could not record this run's time limit: " + ex.getMessage());
            err.flush();
        }
        final TaskWorkRecords records = new TaskWorkRecords(context);
        try {
            records.runStarted(container);
        } catch (java.io.IOException ex) {
            err.println("porelo: could not mark this task's work record: " + ex.getMessage());
            err.flush();
        }
        final ToolCalls calls = new ToolCalls(container, commandMask(container, environment));
        // Always followed, to read the run's tool calls as its lines arrive; shown only to a
        // caller that follows.
        final RunFollower follower =
                RunFollower.start(
                        log,
                        lines -> {
                            try {
                                calls.read(selected.toolEvents(lines), java.time.Instant.now());
                            } catch (RuntimeException ex) {
                                // The lines are still shown; the record says the agent could not
                                // say, never that no tool ran.
                                calls.read(java.util.Optional.empty(), java.time.Instant.now());
                            }
                            if (following == null) {
                                final java.util.List<@Nullable String> hidden =
                                        new java.util.ArrayList<>();
                                lines.forEach(line -> hidden.add(null));
                                return hidden;
                            }
                            return selected.format(lines);
                        },
                        following == null ? line -> {} : following,
                        java.time.Duration.ofMillis(250));
        try {
            int code;
            org.fuin.porelo.runtime.ContainerException killed = null;
            // Closed at the run's end however it ended, but where the agent may still run: a wait
            // that was interrupted, and a stop at the limit that did not go through. Those are
            // left to what ends a run nothing waits on.
            boolean settled = true;
            try {
                code =
                        runner.runAgent(
                                selected,
                                binary,
                                container,
                                agentRequest,
                                environment,
                                log,
                                java.time.Duration.ofMinutes(request.minutes()));
            } catch (org.fuin.porelo.runtime.RunTimedOut ex) {
                killed = ex;
                code = TIMED_OUT;
                // A stop that went through closed the limit itself.
                settled = false;
                endAtLimit(context, container, request.minutes(), err);
            } catch (org.fuin.porelo.runtime.ContainerException ex) {
                killed = ex;
                code = TIMED_OUT;
                settled = !(ex.getCause() instanceof InterruptedException);
            } finally {
                // Before anything else is written to the caller: while it runs, the follower is
                // the only one writing there.
                follower.close();
                if (following != null) {
                    runLinesDropped = follower.dropped();
                }
                settleLimit(container, settled, err);
            }
            try {
                records.runEnded(container, calls, code);
            } catch (java.io.IOException ex) {
                err.println(
                        "porelo: could not complete this task's work record: " + ex.getMessage());
                err.flush();
            }
            if (killed != null) {
                // Whatever the agent managed to say before it was killed is the most useful thing
                // there is at this point. Throwing here would discard it, which is the opposite of
                // what someone diagnosing a stuck run needs.
                err.println("porelo: " + killed.getMessage());
                err.flush();
            }
            return code;
        } finally {
            final org.fuin.porelo.agent.api.Waiting waiting = selected.definition().waiting();
            runResult =
                    RunResult.of(
                            log, waiting == null ? null : waiting.lastMessage(), selected::format);
            // Whatever the run named, even one that was killed: the transcript up to there is worth
            // going on from.
            final org.fuin.porelo.agent.api.SessionIds ids = selected.definition().sessionIds();
            if (ids != null && selected.definition().supportsResume()) {
                TaskSession.fromRun(log, ids).ifPresent(id -> sessions.record(container, id));
            }
            // The run is the task's end as far as it goes: its file is taken now, before whatever
            // follows removes the container.
            try {
                new TaskResult(context.paths()).take(container);
            } catch (java.io.IOException ex) {
                err.println(
                        "porelo: the file this task hands back could not be kept: "
                                + ex.getMessage());
                err.flush();
            }
        }
    }

    /**
     * Runs the prompt in a task that existed already and is up again, with the agent it was made
     * with.
     *
     * <p><strong>Continuing is what starting a task with a prompt does</strong>, whether the task
     * is new or comes back: a start that brought a stopped task back and then ignored its prompt
     * reported a run nobody performed. The agent is the task's own, not whatever a caller names - a
     * conversation belongs to the agent that had it - and the run continues its session where the
     * agent can ({@link #runAgent}).
     *
     * @param container The task's container, running.
     * @param rendered What shows the run to a person afterwards, given the agent; the daemon passes
     *     nothing.
     * @param out Where progress is reported.
     * @param err Where a failure is reported.
     * @return Exit code of the agent, or {@link #NO_AGENT} when its agent is not installed.
     */
    public int runAgentInExisting(
            String container,
            java.util.function.@Nullable BiConsumer<InstalledAgent, String> rendered,
            PrintWriter out,
            PrintWriter err) {
        final org.fuin.porelo.wire.TaskProfile profile =
                org.fuin.porelo.wire.TaskProfile.readFrom(
                        context.paths().tasks().containerState(container));
        try (org.fuin.porelo.agent.api.InstalledAgents agents = context.agents()) {
            final InstalledAgent selected =
                    profile == null || profile.agent() == null
                            ? null
                            : agents.find(profile.agent()).orElse(null);
            // Its number and its model as its start settled them: the run request is where an
            // unattended agent hears them, on every run, never from a file read again now.
            subagents = profile == null ? 0 : profile.subagents();
            runModel = profile == null ? null : profile.model();
            // Nothing extra for the exec: a container that exists already carries what it was
            // given.
            final int code =
                    runAgent(context.tasks(), selected, container, java.util.Map.of(), out, err);
            if (code != NO_AGENT && rendered != null && selected != null) {
                rendered.accept(selected, container);
            }
            final int ended = promptEnded(container, code, out, err);
            return code == NO_AGENT ? code : ended;
        }
    }

    /**
     * Writes down what this task is, for anything that asks about it later.
     *
     * <p>Never fails the run. A task that cannot describe itself is worse than one that can, and
     * far better than one that did not start.
     *
     * <p>A task another task started records its parent.
     *
     * @param container Container name.
     * @param selected The agent, or {@code null} when the task has none.
     * @param branch Ref the work goes to, or {@code null}.
     * @param err Where a failure to record is reported.
     */
    private void writeProfile(
            String container,
            @Nullable InstalledAgent selected,
            @Nullable String branch,
            PrintWriter err) {
        try {
            new org.fuin.porelo.wire.TaskProfile(
                            org.fuin.porelo.wire.TaskProfile.VERSION,
                            selected == null ? null : selected.definition().name(),
                            request.mode(),
                            request.prompt(),
                            branch,
                            java.time.Instant.now().toString(),
                            request.clearance(),
                            null,
                            servingName,
                            model.model(),
                            forgeBranch,
                            subagents,
                            request.parent(),
                            model.from(),
                            model.label().isEmpty() ? null : model.label(),
                            request.named(),
                            ownSettingsHash.isEmpty() ? null : ownSettingsHash,
                            callerKey)
                    .writeTo(context.paths().tasks().containerState(container));
        } catch (java.io.IOException ex) {
            err.println("porelo: could not record what this task is: " + ex.getMessage());
            err.flush();
        }
    }

    /**
     * Starts the task's work record and writes the event log's {@code started} event for a task
     * another task started, once it runs.
     *
     * @param container Container name.
     * @param projectName Its project.
     * @param selected The agent, or {@code null} when the task has none.
     * @param err Where a failure to record is reported.
     */
    private void recordStart(
            String container,
            String projectName,
            @Nullable InstalledAgent selected,
            PrintWriter err) {
        try {
            new TaskWorkRecords(context)
                    .started(
                            container,
                            projectName,
                            workedOn == null ? null : workedOn.name(),
                            request.work(),
                            selected == null ? null : selected.definition().name(),
                            model.model(),
                            model.from());
        } catch (java.io.IOException ex) {
            err.println("porelo: could not start this task's work record: " + ex.getMessage());
            err.flush();
        }
        final String parent = request.parent();
        if (parent != null) {
            // Who started what, where a person reads the log: a task its parent stops is one the
            // log shows it started.
            EventLog.record(
                    context,
                    EventLog.STARTED,
                    projectName,
                    container,
                    "started by " + parent + " through its MCP endpoint");
        }
    }

    /**
     * Records a helper, so a resumed task can start the same thing rather than guess at it.
     *
     * @param name Helper name, matching its pid file.
     * @param command How it was started.
     * @param environment Extra variables it was given.
     * @param phase Whether it has to be up before the container, or needs the running container.
     */
    private void recordHelper(
            String name,
            java.util.List<String> command,
            java.util.Map<String, String> environment,
            String phase) {
        startedHelpers.add(
                new TaskHelpers.Helper(
                        name,
                        java.util.List.copyOf(command),
                        java.util.Map.copyOf(environment),
                        phase));
    }

    private Project project(PrintWriter out, PrintWriter err) {
        // A key this Porelo does not know is refused by the reader, with its line.
        return ProjectReader.read(request.projectFile());
    }

    /** Whether a start fetches before its task runs: a gated start of a project that connects. */
    private boolean fetches(
            final boolean gated,
            final Project project,
            final org.fuin.porelo.core.project.Repository repository) {
        return gated
                && project.securityClass() != org.fuin.porelo.core.project.SecurityClass.OFFLINE
                && (repository.upstream() != null || request.upstream() != null);
    }

    /**
     * Builds the image layers for this run: the agent's, then the chosen image's.
     *
     * <p>The project's go last so they can rely on the agent already being installed - which is the
     * usual reason for having them at all.
     */
    private org.fuin.porelo.runtime.ImageLayers layers(
            org.fuin.porelo.agent.api.InstalledAgents agents,
            Project project,
            ImageChoice image,
            PrintWriter out) {

        org.fuin.porelo.runtime.ImageLayers layers = org.fuin.porelo.runtime.ImageLayers.none();

        final org.fuin.porelo.agent.api.InstalledAgent agent = select(agents);
        if (agent != null) {
            out.println(
                    "agent     "
                            + agent.name()
                            + " "
                            + (agent.definition().version() == null
                                    ? ""
                                    : agent.definition().version()));
            // The definition's own layers, so each artifact is fetched on its owner's side of the
            // user switch: a root one before the root steps, the agent's after it.
            final org.fuin.porelo.agent.api.ImageLayer own = agent.definition().imageLayer();
            layers = layers.and(own.asRoot(), own.asAgent());
            layers =
                    layers.and(
                            AgentStaging.stage(
                                    agent, project, context.paths(), context.runner(), out),
                            java.util.List.of());
            // Resolved after every layer, the project's too, as the start will find it.
            layers = layers.pinning(agent.definition().binary());
        }

        if (!project.imageSnippetLines().isEmpty()) {
            out.println(
                    "snippet   "
                            + project.imageSnippetLines().size()
                            + " lines from "
                            + (image.snippetFrom() == null
                                    ? "the image environment"
                                    : image.snippetFrom()));
            layers = layers.and(project.imageSnippetLines(), java.util.List.of());
        }
        return layers.recording(ImageRecording.of(project, image, agent));
    }

    /**
     * Settles how many subagents the task's agent may run at the same time: {@code --subagents},
     * else the project's {@code limits.subagents}, else none.
     *
     * @param project The project.
     * @param out Where the number is said.
     * @param err Where a number below none is refused.
     * @return {@code false} when refused.
     */
    private boolean settleSubagents(Project project, PrintWriter out, PrintWriter err) {
        final Integer flag = request.subagents();
        if (flag != null && flag < 0) {
            err.println("porelo: --subagents takes 0 or more, not " + flag);
            err.println("porelo: nothing was created");
            err.flush();
            return false;
        }
        subagents = flag != null ? flag : project.limits().subagents();
        final String from = subagentsFrom(flag);
        subagentsAsked = from + " " + subagents;
        out.println(
                "subagents      "
                        + (subagents == 0 ? "none" : subagents + " at the same time")
                        + (subagents == 0 && from.equals("limits.subagents")
                                ? ""
                                : " (from " + from + ")"));
        return true;
    }

    /**
     * Says where a start's number of subagents comes from, as a refusal names it.
     *
     * @param flag Value of {@code --subagents}, or {@code null}.
     * @return The option or the key.
     */
    static String subagentsFrom(@Nullable Integer flag) {
        return flag != null ? "--subagents" : "limits.subagents";
    }

    /**
     * Refuses a task whose agent cannot hold it to its number of subagents.
     *
     * @param agent The task's agent.
     * @param err Where the refusal is said.
     * @return {@code true} when refused.
     */
    private boolean refusesSubagents(InstalledAgent agent, PrintWriter err) {
        final String refused = subagentsRefused(agent.definition(), subagents, subagentsAsked);
        if (refused == null) {
            return false;
        }
        err.println("porelo: " + refused);
        err.println("porelo: nothing was created");
        err.flush();
        return true;
    }

    /**
     * Says why an agent cannot be started for a number of subagents, if it cannot.
     *
     * @param definition The agent's definition.
     * @param subagents The most subagents the task allows at the same time.
     * @param asked Where the number came from, as the person wrote it.
     * @return Why not, or {@code null} when its adapter can enforce the number.
     */
    static @Nullable String subagentsRefused(
            org.fuin.porelo.agent.api.AgentDefinition definition, int subagents, String asked) {
        if (definition.subagents().enforces(subagents)) {
            return null;
        }
        return definition.name()
                + " can only switch its subagents off, so it can enforce 0 and not "
                + asked
                + "; start it with --subagents 0, or with an agent whose definition declares"
                + " 'subagents: count'";
    }

    @Nullable
    private InstalledAgent select(InstalledAgents agents) {
        if (chosenAgent[0] != null) {
            return agents.require(chosenAgent[0]);
        }
        if (AgentChoice.wanted(request.agentName(), workedOn).name() != null) {
            if (decision == null) {
                decision = AgentChoice.decided(context, agents, request.agentName(), workedOn);
            }
            return agentOf(agents, decision);
        }
        // Asked once per start: this is called at every step, and each asks every agent.
        if (decision == null) {
            decision = AgentChoice.considerAgent(context, agents, null, workedOn);
        }
        if (decision.refusal() == null) {
            return agentOf(agents, decision);
        }
        // A person is asked to choose only when neither --agent, the repository nor what was found
        // named one.
        final String named =
                decision.refusal() != AgentChoice.AgentRefusal.SEVERAL_AGENTS
                                || offer == Offer.NOBODY
                        ? null
                        : agentName(
                                decision.found().stream().map(AgentChoice.Found::agent).toList(),
                                null,
                                offer,
                                chosenAgent);
        if (named == null) {
            throw new org.fuin.porelo.agent.api.AgentException(decision.detail());
        }
        return agents.require(named);
    }

    /**
     * Returns the chosen agent from the agents in hand: a step may hold another lookup's, each with
     * its own processes.
     */
    private static @Nullable InstalledAgent agentOf(
            final InstalledAgents agents, final AgentChoice.Choice choice) {
        return choice.agent() == null ? null : agents.require(choice.agent().name());
    }

    private boolean agentChoiceReported;

    private void reportAgentChoice(PrintWriter out, PrintWriter err) {
        if (agentChoiceReported || decision == null) {
            return;
        }
        sayFoundWarnings(decision, err);
        if (chosenAgent[0] == null) {
            sayChoice(decision, out);
        } else {
            final String picked = decision.picked(chosenAgent[0]);
            if (picked != null) {
                out.println(picked);
                out.flush();
            }
        }
        agentChoiceReported = true;
    }

    private void sayChoice(final AgentChoice.Choice choice, final PrintWriter out) {
        final String line = choice.line(context.paths().xdg().home());
        if (line != null) {
            out.println(line);
            out.flush();
        }
    }

    private static void sayFoundWarnings(final AgentChoice.Choice choice, final PrintWriter err) {
        for (final AgentChoice.Found found : choice.found()) {
            if (found.warning() != null) {
                err.println("porelo: " + found.warning());
                err.flush();
            }
        }
    }

    /** The choice this start made; agents are asked what they find once per start. */
    private AgentChoice.@Nullable Choice decision;

    /** What an agent found that a named choice never asked, asked once when an import may help. */
    private final java.util.Map<String, AgentChoice.Found> foundLater = new java.util.HashMap<>();

    private AgentChoice.Found foundBy(final InstalledAgent agent) {
        final AgentChoice.Found decided = decision == null ? null : decision.foundBy(agent.name());
        return decided != null
                ? decided
                : foundLater.computeIfAbsent(
                        agent.name(), name -> AgentChoice.found(context, agent));
    }

    private java.util.List<String> importInstead(final @Nullable InstalledAgent agent) {
        if (agent == null) {
            return java.util.List.of();
        }
        final SelectedProvider selection = credentials().provider(agent);
        final var declared = agent.definition().provider();
        return AgentChoice.importCommand(
                foundBy(agent),
                selection == null ? null : selection.name(),
                declared == null ? null : declared.defaultProvider());
    }

    /**
     * The agent a person chose when several were installed and none was named: asked once per
     * start.
     */
    private final String[] chosenAgent = new String[1];

    /**
     * Returns the agent a start works with: the one named, else the one a person chooses when
     * several are installed, asked once and kept for the rest of the start.
     *
     * @param installed The installed agents' names.
     * @param named The one named, or {@code null}.
     * @param offer Who is asked.
     * @param chosen Where the choice is kept for this start.
     * @return The name, or {@code null} to leave it to the agent choice, which takes the only one
     *     or refuses.
     */
    static @Nullable String agentName(
            final java.util.List<String> installed,
            final @Nullable String named,
            final Offer offer,
            final @Nullable String[] chosen) {
        if (named != null) {
            return named;
        }
        if (chosen[0] == null && installed.size() > 1) {
            chosen[0] =
                    offer.choose(
                            "several agents are installed (" + String.join(", ", installed) + ")",
                            installed,
                            "porelo task start --agent <name>");
        }
        return chosen[0];
    }

    /**
     * Whether a mode is refused outright when the agent's credential cannot be had.
     *
     * <p>The two that asked for the agent itself. {@code SHELL} is not one of them: working inside
     * the container by hand is what it is for, and whether the agent could authenticate may not
     * matter to it at all - so it warns and starts, which is what every mode used to do.
     *
     * <p>Named rather than inlined because it is a decision, taken on 2026-09-12 after a locked
     * vault let an {@code AGENT} task build an image and a container and then land somebody in an
     * agent that could not authenticate.
     *
     * @param mode The mode asked for.
     * @return Whether a credential that cannot be had refuses the start.
     */
    static boolean refusesWithoutCredential(org.fuin.porelo.wire.TaskMode mode) {
        return mode == org.fuin.porelo.wire.TaskMode.UNATTENDED
                || mode == org.fuin.porelo.wire.TaskMode.AGENT;
    }

    /**
     * Builds the environment the container starts with.
     *
     * <p><strong>A phantom token, never the real credential.</strong> The agent inside the
     * container gets something that only Porelo's broker accepts, only for this task, and only
     * until it ends. An agent that leaks it has leaked something that stops working when the
     * container does.
     *
     * <p>Empty when the vault holds nothing for this agent - which is not an error. A task that
     * only needs a shell needs no credential, and failing here would make the common case depend on
     * the uncommon one.
     */
    /**
     * Returns the values a task was started with, as its record keeps them: a later run in the same
     * container is not given them again, and its command lines may hold them.
     *
     * @param paths Where the task's record is.
     * @param container The task.
     * @return The values; empty when the record holds none.
     */
    static java.util.List<String> startValues(PoreloPaths paths, String container) {
        return TaskInventory.parametersGiven(paths.tasks().containerState(container))
                .values()
                .stream()
                .map(given -> given.get("value"))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Returns how this task's tool calls are masked: by every value of the vault this machine
     * holds, every value the run was given and every value the task was started with, which a later
     * run in the same container is not given again; a vault that cannot be read masks every command
     * line whole, since its values cannot be looked for.
     *
     * @param container The task.
     * @param environment What the run was given.
     * @return The mask.
     */
    private CommandMask commandMask(String container, java.util.Map<String, String> environment) {
        final org.fuin.porelo.vault.VaultFile vault = context.vault();
        final java.util.List<String> values = new java.util.ArrayList<>(environment.values());
        values.addAll(startValues(context.paths(), container));
        if (!vault.exists()) {
            return new CommandMask(values);
        }
        final java.util.Optional<org.fuin.porelo.vault.VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty()) {
            return CommandMask.whole();
        }
        try {
            vault.read(opener.get()).values().forEach(entry -> values.add(entry.value()));
        } catch (org.fuin.porelo.vault.VaultException ex) {
            return CommandMask.whole();
        }
        return new CommandMask(values);
    }

    private java.util.Map<String, String> environment(
            org.fuin.porelo.agent.api.InstalledAgents agents, PrintWriter out, PrintWriter err) {

        final org.fuin.porelo.agent.api.InstalledAgent agent = select(agents);
        if (agent == null) {
            return java.util.Map.of();
        }

        final String variable = credentials().tokenVariable(agent);
        if (variable == null) {
            return java.util.Map.of();
        }

        final var credentials = context.credentials();
        if (!credentials.containsKey(credentials().credentialName(agent))) {
            // Reported once already, where the proxy would have been started.
            return java.util.Map.of();
        }

        final java.util.Map<String, String> secrets = new java.util.LinkedHashMap<>();
        credentials.forEach((key, entry) -> secrets.put(key, entry.value()));
        final org.fuin.porelo.vault.TokenBroker broker =
                new org.fuin.porelo.vault.TokenBroker(() -> secrets);
        final org.fuin.porelo.vault.PhantomToken token =
                broker.mint(
                        credentials().credentialName(agent),
                        request.task(),
                        java.time.Duration.ofHours(request.tokenHours()));

        // The variable's name alone: a prefix is part of a secret.
        out.println("token     " + variable);
        return java.util.Map.of(variable, token.value());
    }

    /**
     * Writes whatever the agent needs before it will run in a container it has never seen.
     *
     * <p>A vendor's tool expects to have been used once already - a wizard answered, a folder
     * trusted, a credential where its own login would have put it. None of that exists in a fresh
     * container, so the tool stops and asks a person. The agent says what to write; this writes it,
     * understanding none of it.
     *
     * <p>A file in the workspace waits for its checkout, which refuses to overwrite a file its
     * repository tracks: placed before, it stopped the checkout, and the workspace was left without
     * the push that reaches the gate.
     *
     * @param runner Runs the container.
     * @param agent The selected agent, or {@code null}.
     * @param container Container name.
     * @param environment Variables already prepared for the task.
     * @param out Where progress is reported.
     * @param err Where problems are reported.
     * @return The files in the workspace, still to be placed once it is checked out; {@code null}
     *     when the files could not be placed for an agent started attended, whose setup is the only
     *     place it hears its number of subagents, and the task must not start then.
     */
    private java.util.@org.jspecify.annotations.Nullable List<
                    org.fuin.porelo.agent.api.ContainerFile>
            placeAgentFiles(
                    TaskRunner runner,
                    org.fuin.porelo.agent.api.@org.jspecify.annotations.Nullable InstalledAgent
                            agent,
                    String container,
                    java.util.Map<String, String> environment,
                    PrintWriter out,
                    PrintWriter err) {

        if (agent == null) {
            return java.util.List.of();
        }
        final String variable = credentials().tokenVariable(agent);
        final String named = variable == null ? null : environment.get(variable);
        // An empty token rather than an early return. Two of the three files Claude Code declares
        // - onboarding done, this folder is trusted - have nothing to do with a credential, and
        // returning here withheld those too: somebody who starts a task without one and logs in
        // inside it met every dialog this requirement exists to remove. What to write without a
        // token is the agent's own decision, made against a blank one, not Porelo's to infer.
        final String token = named == null ? "" : named;
        try {
            final SelectedProvider selection = credentials().provider(agent);
            final var files =
                    agent.containerSetup(
                            new org.fuin.porelo.agent.api.SetupContext(
                                    token,
                                    credentials()
                                            .credentialType(credentials().credentialName(agent)),
                                    TaskWorkspace.MOUNT,
                                    wiring().endpointFor(agent, environment),
                                    selection == null ? "" : selection.name(),
                                    subagents));
            final java.util.List<org.fuin.porelo.agent.api.ContainerFile> inWorkspace =
                    files.stream()
                            .filter(file -> file.path().startsWith(TaskWorkspace.MOUNT + "/"))
                            .toList();
            for (final var file : files) {
                if (!inWorkspace.contains(file)) {
                    runner.place(container, file);
                }
            }
            if (files.size() > inWorkspace.size()) {
                out.println(
                        "prepared  "
                                + (files.size() - inWorkspace.size())
                                + " file(s) the agent needs to start"
                                + " without being asked");
            }
            return inWorkspace;
        } catch (java.io.IOException | RuntimeException ex) {
            return agentFilesFailed(agent, ex, err) ? null : java.util.List.of();
        }
    }

    /**
     * Writes the settings files the project's build tools need, as their adapters answered them.
     *
     * @param runner Runs the container.
     * @param container Container name.
     * @param files What {@link ToolSettings} answered.
     * @param out Where progress is reported.
     * @param err Where problems are reported.
     * @return {@code false} when they could not be placed: a build without them asks hosts the
     *     egress refuses, so the task must not start.
     */
    private boolean placeToolSettings(
            TaskRunner runner,
            String container,
            java.util.List<org.fuin.porelo.agent.api.ContainerFile> files,
            PrintWriter out,
            PrintWriter err) {
        if (files.isEmpty()) {
            return true;
        }
        try {
            for (final var file : files) {
                runner.place(container, file);
            }
        } catch (java.io.IOException | RuntimeException ex) {
            err.println("porelo: could not place the build tools' settings: " + ex.getMessage());
            err.flush();
            return false;
        }
        out.println(
                "prepared  "
                        + files.size()
                        + " settings file(s) for the build tools' repositories");
        out.flush();
        return true;
    }

    /**
     * Writes the agent's files in the workspace, once it is checked out.
     *
     * @param runner Runs the container.
     * @param agent The selected agent, or {@code null}.
     * @param container Container name.
     * @param files What {@link #placeAgentFiles} left for the workspace.
     * @param out Where progress is reported.
     * @param err Where problems are reported.
     * @return {@code false} when they could not be placed and the task must not start, as {@link
     *     #placeAgentFiles} answers it.
     */
    private boolean placeWorkspaceFiles(
            TaskRunner runner,
            org.fuin.porelo.agent.api.@org.jspecify.annotations.Nullable InstalledAgent agent,
            String container,
            java.util.List<org.fuin.porelo.agent.api.ContainerFile> files,
            PrintWriter out,
            PrintWriter err) {
        if (agent == null || files.isEmpty()) {
            return true;
        }
        if (request.allRepositories()) {
            // Its workspace is mounted read only, so a copy into it fails and, attended, refused
            // the start; the agent asks what these would have answered, which is said once here.
            out.println(
                    "skipped   "
                            + files.size()
                            + " file(s) the agent wants in "
                            + TaskRepositories.MOUNT
                            + ", which a task that reads its project cannot write; it may ask"
                            + " what they answer");
            out.flush();
            return true;
        }
        try {
            for (final var file : files) {
                runner.place(container, file);
            }
            out.println(
                    "prepared  "
                            + files.size()
                            + " file(s) in the workspace the agent needs to start"
                            + " without being asked");
            return true;
        } catch (java.io.IOException | RuntimeException ex) {
            return !agentFilesFailed(agent, ex, err);
        }
    }

    /**
     * Says that the agent's files could not be placed.
     *
     * @param agent The selected agent.
     * @param ex What went wrong.
     * @param err Where it is said.
     * @return Whether the task must not start: an agent started attended hears its number of
     *     subagents only from these files.
     */
    private boolean agentFilesFailed(
            org.fuin.porelo.agent.api.InstalledAgent agent, Exception ex, PrintWriter err) {
        err.println("porelo: could not prepare the agent's files: " + ex.getMessage());
        if (request.mode() == org.fuin.porelo.wire.TaskMode.AGENT) {
            // Started attended, its agent has no run request: without its setup it would run
            // with its subagents as its own default has them, not as the task asks.
            err.println(
                    "porelo: "
                            + agent.name()
                            + " is started attended and hears how many subagents it may run ("
                            + (subagents == 0 ? "none" : subagents + " at the same time")
                            + ") only from these files, so the task is not started without"
                            + " them");
            err.flush();
            return true;
        }
        // Otherwise not fatal: the run request carries the number, and the agent only asks the
        // questions this would have answered. Saying so beats a task that looks wired up and
        // then stops for input.
        err.flush();
        return false;
    }

    /**
     * Makes sure podman will run Porelo's hooks, repairing what a command may repair.
     *
     * <p><strong>Two of the four states are just "write the files".</strong> A user who has never
     * run {@code porelo setup}, and one whose descriptors an upgrade left behind, were both told to
     * go and run a command - and being told to run a command is a thing people forget, which is how
     * a machine ends up with an installation nobody completed. Doing it here costs nothing and
     * removes the remembering.
     *
     * <p><strong>Why this is not the package's job.</strong> podman reads hook descriptors per
     * user, so an install script running as root does not know whose configuration to write, and
     * putting a {@code hooks_dir} in the system configuration would point every user's podman at
     * Porelo - which is the {@code SHADOWED} offence Porelo refuses to tolerate in other people's
     * drop-ins. Here it runs as the user, in their own configuration, because they asked for a
     * task.
     *
     * <p><strong>Why the other two still refuse.</strong> Missing binaries are a broken
     * installation and writing descriptors would not fix them; a drop-in of somebody else's that
     * sorts later is theirs, and Porelo does not get to delete or reorder it. And the repair is
     * verified rather than assumed: what podman will do is asked again afterwards, because
     * installing cannot rule either of those out.
     *
     * @param out Where a repair is reported.
     * @param err Where a refusal is reported.
     * @return {@code true} if a task may start.
     */
    private boolean hooksReady(PrintWriter out, PrintWriter err) {

        final org.fuin.porelo.runtime.HookInstaller installer = context.hooks();
        org.fuin.porelo.runtime.HookInstaller.Registration hooks = installer.registration();

        if (hooks == org.fuin.porelo.runtime.HookInstaller.Registration.MISSING
                || hooks == org.fuin.porelo.runtime.HookInstaller.Registration.STALE) {
            final boolean first =
                    hooks == org.fuin.porelo.runtime.HookInstaller.Registration.MISSING;
            try {
                // Said out loud rather than done quietly: this writes into the operator's own
                // podman configuration, and somebody who removed the hooks on purpose should see
                // them come back rather than discover it later.
                final java.util.List<java.nio.file.Path> written = installer.install();
                out.println(
                        "hooks     "
                                + (first ? "registered with podman" : "brought up to date")
                                + ", "
                                + written.size()
                                + " files");
                out.flush();
            } catch (java.io.IOException ex) {
                err.println(
                        "porelo: the hooks could not be registered with podman: "
                                + ex.getMessage()
                                + " - try 'porelo setup'");
                err.flush();
                return false;
            }
            hooks = installer.registration();
        }

        if (hooks == org.fuin.porelo.runtime.HookInstaller.Registration.ACTIVE) {
            return true;
        }

        // Without the hooks the container comes up with no firewall at all. Saying so is the only
        // safe outcome: starting it anyway is the failure Porelo exists to prevent. The binaries
        // being present is not enough - podman has to be told to run them, and a later drop-in can
        // point it somewhere else.
        err.println(
                switch (hooks) {
                    case MISSING ->
                            "porelo: the hooks are not registered with podman,"
                                    + " run 'porelo setup' first";
                    case DANGLING ->
                            "porelo: the hook descriptors name binaries that are not"
                                    + " installed, run 'porelo setup' again";
                    case SHADOWED ->
                            "porelo: another containers.conf.d drop-in points hooks_dir at "
                                    + installer.effectiveHooksDirectories()
                                    + ", so Porelo's hooks would not run";
                    // Names the files, because "run setup again" after an upgrade that changed
                    // nothing visible reads like superstition until you can see what differs.
                    case STALE ->
                            "porelo: the installed hook files are from a different version of"
                                    + " Porelo, run 'porelo setup' again - "
                                    + installer.outdated();
                    case ACTIVE -> "";
                });
        err.flush();
        return false;
    }

    /**
     * Writes down what this run started, so the task can be resumed after it is stopped.
     *
     * @param container Container name.
     * @param err Where a failure is reported.
     */
    private java.util.Set<String> existingTasks(String container) {
        final java.util.Set<String> existing = new java.util.HashSet<>();
        existing.add(container);
        context.podman().poreloTasks().forEach(task -> existing.add(task.name()));
        return existing;
    }

    private void writeResumeRecord(String container, PrintWriter err) {
        if (startedHelpers.isEmpty()) {
            return;
        }
        try {
            new TaskHelpers(java.util.List.copyOf(startedHelpers))
                    .writeTo(context.paths().tasks().containerState(container));
        } catch (java.io.IOException ex) {
            // The task runs regardless; only resuming it later is lost.
            err.println("porelo: could not record how to resume this task: " + ex.getMessage());
            err.flush();
        }
    }

    /**
     * Says how to sign in, for an agent that has a sign-in of its own: the way out a person is told
     * first, because the other one - a shell - is where an agent with no credential says "Not
     * logged in".
     *
     * @param agent The agent, or {@code null}.
     * @return The sentence, ending in a space, or "".
     */
    private java.util.List<String> signInCommand(@Nullable InstalledAgent agent) {
        final java.util.List<String> imports = importInstead(agent);
        if (!imports.isEmpty()) {
            return imports;
        }
        final SelectedProvider selection = agent == null ? null : credentials().provider(agent);
        final org.fuin.porelo.agent.api.ProviderDefinition provider =
                selection == null ? null : context.providers().get(selection.name());
        return nextCommand(
                agent == null ? null : agent.name(),
                agent != null && AgentLogin.signsInHere(context, agent.definition()),
                provider,
                agent == null ? "" : credentials().credentialName(agent));
    }

    private String signIn(@Nullable InstalledAgent agent) {
        final java.util.List<String> imports = importInstead(agent);
        if (!imports.isEmpty()) {
            return "Copy what it already holds with 'porelo " + String.join(" ", imports) + "'; ";
        }
        final SelectedProvider selection = agent == null ? null : credentials().provider(agent);
        final org.fuin.porelo.agent.api.ProviderDefinition provider =
                selection == null ? null : context.providers().get(selection.name());
        return nextStep(
                agent == null ? null : agent.name(),
                agent != null && AgentLogin.signsInHere(context, agent.definition()),
                provider,
                agent == null ? "" : credentials().credentialName(agent));
    }

    /**
     * Returns the one command that gets a task its credential, said where a start is refused for
     * want of it.
     *
     * @param agent The agent's name, or {@code null} when none is installed.
     * @param logsIn Whether the agent declares a login of its own.
     * @param provider The provider chosen, or {@code null}.
     * @param credential The vault entry the credential would be under.
     * @return The sentence, ending in "; ", or empty when there is nothing to name.
     */
    static String nextStep(
            @Nullable String agent,
            boolean logsIn,
            org.fuin.porelo.agent.api.@Nullable ProviderDefinition provider,
            String credential) {
        final java.util.List<String> command = nextCommand(agent, logsIn, provider, credential);
        if (command.isEmpty()) {
            return "";
        }
        final String said = "'porelo " + String.join(" ", command) + "'";
        return switch (command.get(1)) {
            case "authorize" -> "Grant it first with " + said + "; ";
            case "login" -> "Sign in first with " + said + "; ";
            default -> "Store its key first with " + said + "; ";
        };
    }

    /**
     * Returns the one command that gets a task its credential, as {@link #nextStep} says it, as
     * arguments.
     *
     * @param agent The agent's name, or {@code null} when none is installed.
     * @param logsIn Whether the agent declares a login of its own.
     * @param provider The provider chosen, or {@code null}.
     * @param credential The vault entry the credential would be under.
     * @return Its arguments after {@code porelo}, or empty when there is nothing to name.
     */
    static java.util.List<String> nextCommand(
            @Nullable String agent,
            boolean logsIn,
            org.fuin.porelo.agent.api.@Nullable ProviderDefinition provider,
            String credential) {
        if (provider != null && !provider.grant().isEmpty()) {
            return java.util.List.of("vault", "authorize", provider.name());
        }
        if (agent != null && logsIn) {
            return java.util.List.of("vault", "login", agent);
        }
        return credential.isEmpty()
                ? java.util.List.of()
                : java.util.List.of("vault", "put", credential);
    }

    /**
     * Returns the remedy for a credential a start lacks: the command a refusal would name, run on
     * this terminal - its own code, which asks a key without echo or runs the agent's sign-in - and
     * the credential looked for again after.
     *
     * @param unavailable What is missing, as the refusal says it.
     * @param command The command's arguments after {@code porelo}.
     * @param holds Whether the credential is there.
     * @return The remedy.
     */
    Offer.Remedy credentialRemedy(
            final String unavailable,
            final java.util.List<String> command,
            final java.util.function.BooleanSupplier holds) {
        final String said = "porelo " + String.join(" ", command);
        return new Offer.Remedy(
                unavailable,
                "Run '" + said + "' now?",
                said,
                "",
                false,
                () ->
                        context.exec()
                                        .applyAsInt(
                                                java.util.stream.Stream.concat(
                                                                java.util.stream.Stream.of(
                                                                        PoreloBinary.path()),
                                                                command.stream())
                                                        .toList())
                                == 0,
                holds);
    }

    private boolean refusesCredential(
            org.fuin.porelo.agent.api.@Nullable InstalledAgent agent, PrintWriter err) {
        final var refusal = credentials().unbrokerable(agent);
        if (refusal == null) {
            return false;
        }
        err.println("porelo: " + refusal.reason());
        err.println("porelo: " + refusal.remedy());
        err.flush();
        return true;
    }

    /** The provider the agent this start took was found signed in to; a flag still wins. */
    private @Nullable String foundProvider() {
        if (decision == null) {
            return null;
        }
        if (chosenAgent[0] == null) {
            return decision.provider();
        }
        final AgentChoice.Found chosen = decision.foundBy(chosenAgent[0]);
        return chosen == null ? null : chosen.provider();
    }

    /** Checks a new start's credentials before the daemon asks an upstream for a host key. */
    public boolean credentialsReady(PrintWriter out, PrintWriter err) {
        credentialCheckCode = 69;
        if (request.dryRun()) {
            return true;
        }
        // A project file or repository the start cannot settle is the launch's own refusal, in
        // its own order (a task name it would refuse first); the credential gate answers only for
        // credentials.
        final Project project;
        final org.fuin.porelo.core.project.Repository repository;
        try {
            project = ProjectReader.read(request.projectFile());
            repository = chosenRepository(project);
        } catch (ProjectException ex) {
            return true;
        }
        workedOn = repository;
        return newCredentialsReady(project, request.projectFile(), repository, out, err);
    }

    private int credentialCheckCode = 69;

    /**
     * Returns the ordinary refusal's exit code when credential preflight did not raise a typed
     * error.
     */
    public int credentialCheckCode() {
        return credentialCheckCode;
    }

    /** A start refused before making anything because its model credential cannot be read. */
    public record CredentialRefusal(String agent, boolean locked, @Nullable String running) {}

    private @Nullable CredentialRefusal credentialRefusal;

    private @Nullable String hostRefusal;
    private @Nullable AgentSignInRuns signIns;
    private boolean waitForSignIn;

    /** Uses the daemon's shared sign-in registry; a wait has the registry's own time limit. */
    public TaskLaunch waitingForSignIn(AgentSignInRuns runs, boolean wait) {
        signIns = runs;
        waitForSignIn = wait;
        return this;
    }

    /**
     * Returns the host's refusal of the start in its own words, or null when the host refused
     * nothing.
     */
    public @Nullable String hostRefusal() {
        return hostRefusal;
    }

    /** Returns the credential refusal, or null when the start was not refused for it. */
    public @Nullable CredentialRefusal credentialRefusal() {
        return credentialRefusal;
    }

    /** Refuses every missing non-model credential before considering a wait on the agent. */
    private boolean refusesOtherCredentials(
            Project project, org.fuin.porelo.wire.TaskMode mode, PrintWriter err) {
        // A destination nobody declared refuses a start in every mode, before whether its
        // credential is there: the same words and the same rule the launch and CanStart answer
        // with, so the earlier check cannot say it otherwise.
        final String undeclared =
                StartCheck.undeclared(context, request.projectFile(), request.credentials());
        if (undeclared != null) {
            err.println("porelo: " + undeclared.substring(undeclared.indexOf('\n') + 1));
            err.println(
                    "porelo: nothing was created; declare it in "
                            + context.paths().xdg().data().resolve("destinations")
                            + ", or correct what names it");
            err.flush();
            return true;
        }
        if (mode != org.fuin.porelo.wire.TaskMode.UNATTENDED) {
            return false;
        }
        final java.util.Set<String> names =
                new java.util.LinkedHashSet<>(project.credentials().keySet());
        names.addAll(request.credentials().keySet());
        for (final String name : names) {
            final StartCredentials.State state =
                    CredentialWiring.credentialUnavailable(context.readableCredentials(), name);
            if (state == StartCredentials.State.PRESENT && authorizationMissing(name)) {
                // Asked of a person too, not only said here, in the launch's words: somebody else
                // may be the one who can grant it.
                AuthorizationsNeeded.raise(
                        context,
                        name,
                        startContainer(project),
                        project.name(),
                        AuthorizationsNeeded.NEVER);
                err.println(
                        "porelo: credential '"
                                + name
                                + "': nobody has granted it yet; a person authorizes it once with"
                                + " 'porelo vault authorize "
                                + name
                                + "'");
                err.println(
                        "porelo: nothing was created; an unattended run cannot ask anybody, so it is refused rather than started to fail");
                err.flush();
                return true;
            }
            if (state != StartCredentials.State.PRESENT) {
                if (state == StartCredentials.State.VAULT_LOCKED) {
                    credentialRefusal = new CredentialRefusal("", true, null);
                }
                err.println(
                        "porelo: the project names credential '"
                                + name
                                + "', and "
                                + CredentialWiring.reason(state, name));
                err.println(
                        "porelo: nothing was created; an unattended run cannot ask anybody, so it is refused rather than started to fail");
                err.flush();
                return true;
            }
        }
        return false;
    }

    /** Other missing credentials cannot be repaired by the agent's sign-in. */
    private boolean otherCredentialsPresent(Project project) {
        final java.util.Set<String> names =
                new java.util.LinkedHashSet<>(project.credentials().keySet());
        names.addAll(request.credentials().keySet());
        return names.stream()
                .allMatch(
                        name ->
                                CredentialWiring.credentialUnavailable(
                                                        context.readableCredentials(), name)
                                                == StartCredentials.State.PRESENT
                                        && !authorizationMissing(name));
    }

    /**
     * A stopped container keeps its agent; checking its recorded provider needs no installed
     * adapter.
     */
    private boolean refusesRecordedCredentials(
            org.fuin.porelo.wire.TaskProfile profile,
            String container,
            Project project,
            org.fuin.porelo.wire.TaskMode mode,
            PrintWriter err) {
        if (!refusesWithoutCredential(mode)) {
            return false;
        }
        final String agent = profile.agent();
        final String provider = profile.provider();
        context.openIfShut(
                provider != null
                        || !project.credentials().isEmpty()
                        || !request.credentials().isEmpty(),
                true,
                err);
        if (refusesOtherCredentials(project, mode, err)) {
            return true;
        }
        if (agent == null || provider == null) {
            return false;
        }
        // The broker's original key wins over a provider alias or a later legacy-key migration.
        final Path runtime = context.paths().tasks().containerState(container);
        final TaskHelpers helpers =
                TaskHelpers.readFrom(
                        java.nio.file.Files.isDirectory(runtime)
                                ? runtime
                                : context.paths().tasks().taskRecord(container));
        final String recordedKey = brokerCredential(helpers);
        final java.util.function.Supplier<StartCredentials.State> available =
                () -> {
                    final var stored = context.readableCredentials();
                    final String key =
                            recordedKey != null
                                    ? recordedKey
                                    : SelectedProvider.credentialKey(
                                            stored.map(java.util.Map::keySet)
                                                    .orElse(java.util.Set.of()),
                                            agent,
                                            provider);
                    return CredentialWiring.credentialUnavailable(stored, key);
                };
        final StartCredentials.Refusal refusal =
                StartCredentials.check(
                        agent,
                        available,
                        signIns,
                        waitForSignIn && otherCredentialsPresent(project));
        if (refusal == null) {
            return false;
        }
        credentialRefusal =
                new CredentialRefusal(
                        agent,
                        refusal.state() == StartCredentials.State.VAULT_LOCKED,
                        refusal.running());
        if (signIns == null) {
            err.println(
                    "porelo: "
                            + CredentialWiring.reason(
                                    refusal.state(), recordedKey == null ? provider : recordedKey));
            err.println(
                    "porelo: nothing was created; "
                            + nextStep(
                                    agent,
                                    false,
                                    context.providers().get(provider),
                                    recordedKey == null ? provider : recordedKey));
            err.flush();
        }
        return true;
    }

    private static @Nullable String brokerCredential(TaskHelpers helpers) {
        for (final TaskHelpers.Helper helper : helpers.helpers()) {
            if ("vault".equals(helper.name())) {
                final int option = helper.command().indexOf("--credential");
                if (option >= 0 && option + 1 < helper.command().size()) {
                    return helper.command().get(option + 1);
                }
            }
        }
        return null;
    }

    /** Keeps terminal remedies and words on the same gate the daemon reads as values. */
    private boolean refusesStartCredentials(
            @Nullable InstalledAgent agent,
            Project project,
            org.fuin.porelo.wire.TaskMode mode,
            PrintWriter out,
            PrintWriter err) {
        if (!refusesWithoutCredential(mode)) {
            return false;
        }
        context.openIfShut(
                !project.credentials().isEmpty()
                        || !request.credentials().isEmpty()
                        || wiring().unavailableFor(agent) != StartCredentials.State.PRESENT,
                true,
                err);
        if (refusesOtherCredentials(project, mode, err) || refusesCredential(agent, err)) {
            return true;
        }
        if (agent == null) {
            return false;
        }
        final StartCredentials.Refusal refusal =
                StartCredentials.check(
                        agent.name(),
                        () -> wiring().unavailableFor(agent),
                        signIns,
                        waitForSignIn && otherCredentialsPresent(project));
        if (refusal == null) {
            return false;
        }
        credentialRefusal =
                new CredentialRefusal(
                        agent.name(),
                        refusal.state() == StartCredentials.State.VAULT_LOCKED,
                        refusal.running());
        if (signIns != null) {
            return true;
        }
        final String unavailable =
                java.util.Objects.requireNonNull(
                        CredentialWiring.reason(
                                refusal.state(), credentials().credentialName(agent)),
                        "a refused credential has a reason");
        final java.util.List<String> command =
                offer == Offer.NOBODY ? java.util.List.of() : signInCommand(agent);
        if (!command.isEmpty()
                && offer.resolve(
                        credentialRemedy(
                                unavailable,
                                command,
                                () ->
                                        wiring().unavailableFor(agent)
                                                == StartCredentials.State.PRESENT),
                        err)) {
            credentialRefusal = null;
            out.println(
                    "credential "
                            + String.join(" ", command.subList(1, command.size()))
                            + " - in place");
            out.flush();
            return false;
        }
        credentialRefusal =
                new CredentialRefusal(
                        agent.name(),
                        refusal.state() == StartCredentials.State.VAULT_LOCKED,
                        refusal.running());
        if (command.isEmpty()) {
            err.println("porelo: " + unavailable);
            err.println(
                    "porelo: nothing was created; "
                            + (mode == org.fuin.porelo.wire.TaskMode.UNATTENDED
                                    ? "an unattended run cannot ask anybody, so it is refused rather than started to fail. "
                                            + signIn(agent).replaceFirst("; $", "")
                                    : "the agent would start without a credential and fail on its first request. "
                                            + signIn(agent)
                                            + "'--attach shell' starts the task without one."));
        } else {
            err.println("porelo: nothing was created");
        }
        err.flush();
        return true;
    }

    private CredentialChoice credentials() {
        if (credentials == null) {
            credentials =
                    new CredentialChoice(
                                    context,
                                    request.providerName() != null
                                            ? request.providerName()
                                            : foundProvider(),
                                    request.credentialType(),
                                    request.agentName())
                            .offering(offer);
        }
        return credentials;
    }

    /**
     * Tries to buy a token with a credential of the kind the broker buys with, once, before
     * anything exists.
     *
     * @param name The credential's vault entry.
     * @return Why it could not be bought, or {@code null} when it could or is not of that kind.
     */
    /** A declared grant is usable only after its authorization has been stored. */
    private boolean authorizationMissing(String name) {
        return ungrantedAuthorization(name) && !authorizationStored(name);
    }

    private boolean authorizationStored(String name) {
        return context.opener()
                .map(
                        opener ->
                                context.vault()
                                        .read(opener)
                                        .containsKey(TaskSecrets.GRANT_PREFIX + name))
                .orElse(false);
    }

    private boolean ungrantedAuthorization(String name) {
        final org.fuin.porelo.vault.VaultEntry entry =
                context.readableCredentials().map(stored -> stored.get(name)).orElse(null);
        return entry != null && org.fuin.porelo.supervisor.Grants.isGrant(entry.type());
    }

    private @Nullable String purchaseRefused(String name) {
        final org.fuin.porelo.vault.VaultEntry entry =
                context.readableCredentials().map(stored -> stored.get(name)).orElse(null);
        if (entry != null && org.fuin.porelo.supervisor.Grants.isGrant(entry.type())) {
            // Only whether a person has granted it: spending it here could rotate it under the
            // broker.
            final boolean granted = authorizationStored(name);
            return granted
                    ? null
                    : "nobody has granted it yet; a person authorizes it once with 'porelo vault authorize "
                            + name
                            + "'";
        }
        if (entry == null || !org.fuin.porelo.supervisor.TokenPurchase.KIND.equals(entry.type())) {
            return null;
        }
        try {
            new org.fuin.porelo.supervisor.TokenPurchase(
                            org.fuin.porelo.supervisor.TokenPurchase.Client.of(
                                    entry.value(), entry.settings()),
                            org.fuin.porelo.core.net.TrustedCertificates.builder(
                                            org.fuin.porelo.core.net.TrustedCertificates.file(
                                                    context.paths().xdg()))
                                    .connectTimeout(java.time.Duration.ofSeconds(30))
                                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                                    .build(),
                            java.time.Clock.systemUTC())
                    .current();
            return null;
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        } catch (org.fuin.porelo.supervisor.TokenPurchase.Refused ex) {
            return ex.getMessage();
        }
    }

    private @Nullable TaskBase startingBranch;

    /** Returns the branch and source this task starts from, once settled. */
    public @Nullable TaskBase startingBranch() {
        return startingBranch;
    }

    /**
     * The task's branch at the forge, settled when it is launched; {@code null} until then and for
     * a task without a gate.
     */
    private @Nullable String forgeBranch;

    /**
     * Makes the task's branch at the forge from {@code --branch} or the repository's scheme, and
     * checks it is one the forge accepts and git can name.
     *
     * @param project The project.
     * @param repository The repository the task works on.
     * @return Why it is refused, or {@code null} once {@link #forgeBranch} holds it.
     */
    private @Nullable String settleForgeBranch(
            final Project project, final org.fuin.porelo.core.project.Repository repository) {
        final TaskBranch.Settled settled =
                TaskBranch.settle(
                        project, repository.name(), request.task(), values, request.branch());
        if (settled.refused() != null) {
            return settled.refused();
        }
        final String name = java.util.Objects.requireNonNull(settled.name());
        forgeBranch = name;
        return null;
    }

    /**
     * Says why the task's branch is another task's already: two tasks of one repository on one
     * branch would pass their work on onto one, and one would move the other's. Asked just before
     * the workspace opens, once the runtime is known to be there.
     *
     * @param project The project.
     * @return Why it is refused, or {@code null}.
     */
    private @Nullable String branchHeldElsewhere(final Project project) {
        if (forgeBranch == null || workedOn == null) {
            return null;
        }
        final String other =
                TaskBranch.heldBy(
                        context,
                        project.name(),
                        workedOn.name(),
                        forgeBranch,
                        startContainer(project));
        return other == null
                ? null
                : "the task's branch '"
                        + forgeBranch
                        + "' is "
                        + other
                        + "'s already, and two tasks would pass their work on onto one; name another"
                        + " with --branch or other values";
    }

    /** The values the task was started with, settled when it is launched; empty until then. */
    private java.util.Map<String, org.fuin.porelo.core.project.Parameters.Given> values =
            java.util.Map.of();

    /** The project's other credentials, resolved when the task is launched; empty until then. */
    private java.util.Map<String, Destination> extras = java.util.Map.of();

    private CredentialWiring wiring() {
        if (wiring == null) {
            wiring =
                    new CredentialWiring(
                            context,
                            credentials(),
                            this::recordHelper,
                            request.task(),
                            request.tokenHours(),
                            request.upstream());
            wiring.owned = ownHelpers::add;
        }
        return wiring;
    }

    private GateWiring gate() {
        if (gate == null) {
            gate =
                    new GateWiring(
                            context,
                            this::recordHelper,
                            request.projectFile(),
                            request.upstream(),
                            request.repository());
        }
        return gate;
    }

    /**
     * Returns the branch an online start found the upstream holding already, when the start was
     * refused for it.
     *
     * @return The branch and why, or {@code null}.
     */
    public @Nullable BranchTaken branchTaken() {
        if (heldElsewhere != null) {
            return heldElsewhere;
        }
        if (workspace == null || workspace.takenBranch() == null) {
            return null;
        }
        return new BranchTaken(
                request.task(),
                java.util.Objects.requireNonNull(workspace.takenBranch()),
                String.valueOf(workspace.refusal()));
    }

    /** The branch another task of this machine holds, when that refused the start. */
    private @Nullable BranchTaken heldElsewhere;

    /**
     * The branch a start found taken - at the upstream, or by another task here - and why it was
     * refused.
     *
     * @param task The task's name, as the start settled it.
     * @param branch The branch.
     * @param detail What the refusal said.
     */
    public record BranchTaken(String task, String branch, String detail) {}

    /**
     * Returns the earlier work of this task's name that waits at the gate, when the start was
     * refused for it.
     *
     * @return The work, or {@code null}.
     */
    public org.fuin.porelo.gate.GitGate.@Nullable Waiting earlierWork() {
        return workspace == null ? null : workspace.earlierWork();
    }

    private WorkspaceSetup workspace() {
        if (workspace == null) {
            workspace =
                    new WorkspaceSetup(
                            context, request.task(), request.upstream(), request.repository());
        }
        return workspace;
    }

    /**
     * Returns the repository this task works on, refusing a request that names none.
     *
     * <p><strong>There is no case where Porelo picks</strong>, not even when the project has
     * exactly one. A default would be paid for later and somewhere else: a project that grows a
     * second repository would silently change what an existing command does, and a command whose
     * meaning depends on how many repositories exist today is one nobody can read. Naming it is one
     * word.
     *
     * <p>A task with no gate is the exception, and not really one: it gets an empty directory and
     * cannot commit anywhere, so there is no repository for it to be about.
     *
     * @param project The project.
     * @return The repository.
     * @throws ProjectException When none was named, or none of that name exists. The message names
     *     what there is to choose from.
     */
    private org.fuin.porelo.core.project.Repository chosenRepository(Project project) {
        if (request.repository() == null || request.repository().isBlank()) {
            // A task that reads them all takes its agent, image and egress from the project's own,
            // as one without a gate does: it works in none of them.
            if (request.noGate() || request.allRepositories() || request.dryRun()) {
                // A dry run starts nothing, so there is no task to start on a guessed repository -
                // which is the only thing the refusal below protects. What it reports without one
                // is the project's own repository, and that is not a pick: its egress and limits
                // ARE the project's block, so the answer is the project-level plan, true of every
                // repository. A repository of its own adds to it, which is why naming one shows
                // more rather than something else.
                return project.ownRepository();
            }
            // Ready to paste, because the first run of all is the one that cannot know the answer:
            // the wizard writes the project file moments earlier, and being told what there is to
            // choose from without being told how to say it would make the improvement into an
            // obstacle.
            throw new ProjectException(
                    "say which repository this task is for."
                            + System.lineSeparator()
                            + "       '"
                            + project.name()
                            + "' works in: "
                            + String.join(", ", project.workRepositoryNames())
                            // No task name in it: an unnamed task is named after the repository it
                            // gets, and the placeholder it carries until then is nothing a person
                            // chose.
                            + System.lineSeparator()
                            + "       porelo task start --repository "
                            + project.workRepositoryNames().get(0));
        }
        final org.fuin.porelo.core.project.Repository chosen =
                GateSupport.repository(project, request.repository());
        if (OwnRepository.refused(project, chosen.name())) {
            throw new ProjectException(OwnRepository.refusal(project));
        }
        final String offHost =
                new HostForgeAddress(context).refusal(project, chosen.name(), request.upstream());
        if (offHost != null) {
            throw new ProjectException(offHost);
        }
        return chosen;
    }

    private ClearanceWiring clearance() {
        if (clearanceWiring == null) {
            clearanceWiring =
                    new ClearanceWiring(
                            context, this::recordHelper, request.task(), request.clearance());
        }
        return clearanceWiring;
    }

    /**
     * The commit this task's configuration was applied at, or "" for a project nobody follows.
     * Whether a signature was checked on it is the follow's, and {@link
     * ProjectSource.Found#verified()} says which.
     *
     * <p>A field rather than a local, because the start that labels the container happens inside a
     * scope that captures it - and it is written once, where the project is resolved.
     */
    private String verifiedAt = "";

    /** The project whose conversations this start took the task into, or {@code null}. */
    private @Nullable Project enrolledIn;

    /**
     * Whether the started task was handed on, to a session or a run: before, nobody worked in it.
     */
    private boolean handedOver;

    /** Whether a start that fails now removes its container rather than keeping it to resume. */
    private boolean discarding;

    /** Whether this launch is making a task, rather than bringing back one that exists. */
    private boolean launching;

    /** The id of the container this launch created, or {@code null} while it created none. */
    private @Nullable String createdId;

    /** What this run started on the host, written out so a later start can bring them back. */
    private final java.util.List<TaskHelpers.Helper> startedHelpers = new java.util.ArrayList<>();

    /**
     * The helpers this attempt started, by their own handles: what a start that lost its name ends,
     * since the pid files in the name's directory are every start's of that name.
     */
    private final java.util.List<ProcessHandle> ownHelpers = new java.util.ArrayList<>();

    /**
     * Which credential this task uses, and what to say about it. Built on first use because it
     * reads the options, and those are not set until picocli has parsed them.
     */
    private @Nullable CredentialChoice credentials;

    /** The provider this task was brokered to, once settled, for its profile. */
    private @Nullable String servingName;

    /** The broker, the relay and the signing agent this task needs. Built on first use. */
    private @Nullable CredentialWiring wiring;

    /** How the git gate is bound and firewalled for this task. Built on first use. */
    private @Nullable GateWiring gate;

    /** The repository the agent works in. Built on first use. */
    private @Nullable WorkspaceSetup workspace;

    /** The watcher that asks about blocked connections. Built on first use. */
    private @Nullable ClearanceWiring clearanceWiring;

    /**
     * Writes a certified task's certifiers beside its state, where its gate and a resume find them,
     * and names them for the gate.
     */
    private GateWiring.Certified certifiedGate(
            String container, TaskCertification.Issued issued, String hostRepository)
            throws java.io.IOException {
        final java.nio.file.Path file =
                context.paths().tasks().containerState(container).resolve(TaskState.CERTIFIERS);
        java.nio.file.Files.writeString(file, issued.signers());
        TaskState.certifiedUntil(
                context.paths().tasks().containerState(container),
                issued.certificate().validBefore());
        return new GateWiring.Certified(
                issued.principal(),
                file,
                // The host's own forge names a project's organization after the project.
                host.present() ? hostRepository : null);
    }

    /** The agent of the person starting the task, or {@code null} when there is none. */
    private static java.nio.file.@org.jspecify.annotations.Nullable Path agentSocket() {
        final String socket = System.getenv("SSH_AUTH_SOCK");
        return socket == null || socket.isBlank() ? null : java.nio.file.Path.of(socket);
    }

    /**
     * Removes a task that finished, and holds one that failed.
     *
     * <p><strong>A failure is kept, because nobody can ask for that in advance.</strong> {@code
     * --keep} has to be decided before the run, and the run an operator wants to look at is the one
     * that went wrong - which is known only afterwards. So a non-zero exit stops the container
     * rather than removing it: the workspace, the logs and anything that never reached the gate
     * stay where they are, {@code task attach} offers to bring it back, and discarding it is a
     * separate decision made by name.
     *
     * <p>Stopped through {@link TaskControl}, not by leaving it running: a task nobody is watching
     * that still holds a firewall, a gate and a credential proxy is not "kept", it is abandoned.
     * That is also what writes down what the workspace held.
     *
     * @param runner Runs the container.
     * @param container Container name.
     * @param code Exit code of the run.
     * @param out Where the decision is reported.
     * @param err Where survivors are reported.
     * @return The run's failure, or 70 if cleanup of a successful run failed.
     */
    private int cleanUp(
            TaskRunner runner, String container, int code, PrintWriter out, PrintWriter err) {
        return cleanUp(runner, container, code, request.keep(), out, err);
    }

    /**
     * Removes a task that finished, and holds one that failed, as {@link #cleanUp(TaskRunner,
     * String, int, PrintWriter, PrintWriter)} does.
     *
     * @param runner Runs the container.
     * @param container Container name.
     * @param code Exit code of the run.
     * @param keep Whether the container is left as it is; a start that failed never is.
     * @param out Where the decision is reported.
     * @param err Where survivors are reported.
     * @return The run's failure, or 70 if cleanup of a successful run failed.
     */
    private int cleanUp(
            TaskRunner runner,
            String container,
            int code,
            boolean keep,
            PrintWriter out,
            PrintWriter err) {
        try {
            return cleanUpOrFail(runner, container, code, keep, out, err);
        } catch (final RuntimeException ex) {
            // Said, never in its place: what failed while cleaning up replaced the refusal that
            // caused the clean-up, and a start refused for its agent answered over the socket that
            // its container was no task Porelo made.
            err.println("porelo: cleaning up after it failed: " + ex.getMessage());
            err.flush();
            return code == 0 ? 70 : code;
        }
    }

    /**
     * Returns where a host's forge keeps the copy an online task's push goes to: the repository
     * part of its placed address, {@code <project>/<central's last part>}, as the host's follow
     * made it, never the row's name.
     *
     * @param project The project, as the host placed it.
     * @param repository The task's repository.
     * @return {@code <project>/<repo>}.
     */
    static String hostPushRepository(
            Project project, org.fuin.porelo.core.project.Repository repository) {
        // Before a certified gate is made the start refused a row without a central on a host.
        final String copy =
                HostForgeAddress.copyOf(
                        project.name(),
                        repository.central() != null
                                ? repository.central()
                                : repository.upstream());
        return copy != null ? copy : project.name() + "/" + repository.name();
    }

    /**
     * Ends what a start made for a task once no container of it runs: its helpers, by their pid
     * files, and its MCP endpoint.
     *
     * <p>Only when no container is running: a start that failed fires no poststop hook, and the
     * helpers of one that runs belong to it.
     *
     * @param context Where the task's state and podman come from.
     * @param container The task.
     */
    static void reapOrphans(PoreloContext context, String container) {
        // A task whose removal took its record runs nothing of Porelo's, and podman is asked about
        // a task by its recorded id alone: asking by the name refuses it as no task Porelo made.
        if (context.recordedId(container) != null
                && context.podman().pidOf(container).orElse(0L) > 0) {
            return;
        }
        TaskLifecycle.stopHelpers(context.paths().tasks().containerState(container));
        // Its porelo-mcp went with the helpers; the daemon socket opened for it lives in porelod,
        // which no pid file reaches. Before the state goes: the stop finds porelo-mcp by its file.
        McpWiring.stop(context, container);
    }

    /**
     * Notes that a task stopped before it was handed on holds nothing, where its stop could not
     * say: before its workspace is a checkout nothing can be asked, and the removal its start names
     * then refused without {@code --force} a task nobody had worked in.
     *
     * @param container The task.
     */
    private void heldNothing(String container) {
        final UnhandedWork.Held nothing = new UnhandedWork.Held(true, 0, 0, null);
        for (final java.nio.file.Path where :
                java.util.List.of(
                        context.paths().tasks().containerState(container),
                        context.paths().tasks().taskRecord(container))) {
            if (!UnhandedWork.read(where).readable()) {
                UnhandedWork.note(where, nothing);
            }
        }
    }

    private static boolean reportStop(TaskControl.Stopped stopped, PrintWriter err) {
        for (final String alive : stopped.surviving()) {
            err.println("porelo: still running: " + alive);
        }
        err.flush();
        return stopped.clean();
    }

    /**
     * Ends what a start made for a container podman never made: its place in the project's
     * conversations, its mailbox, its guide and its state, as {@code task remove} ends them for a
     * task. Without a recorded container, no removal reached them, and the account stayed at the
     * transport until a prune forgot the only secret that could retire it.
     *
     * @param container Container name.
     * @param err Where what could not be ended is said.
     */
    private void forgetUnmade(String container, PrintWriter err) {
        final Project enrolled = enrolledIn;
        if (enrolled == null) {
            // Refused before it joined anything: nothing of it was made.
            return;
        }
        new TaskConversations(context).retire(enrolled, container, err);
        try {
            new Mailbox(context.paths().messaging().mailbox(container)).delete();
            new TaskGuide(context.paths(), container).delete();
            new TaskResult(context.paths()).forget(container);
        } catch (java.io.IOException ex) {
            err.println("porelo: could not delete the mailbox of " + container + ": " + ex);
        }
        try {
            new TaskRepositories(context.paths(), container).delete();
        } catch (java.io.IOException ex) {
            err.println("porelo: could not delete what " + container + " was to read: " + ex);
        }
        final java.nio.file.Path state = context.paths().tasks().containerState(container);
        if (java.nio.file.Files.isDirectory(state)) {
            try (java.util.stream.Stream<java.nio.file.Path> paths =
                    java.nio.file.Files.walk(state)) {
                for (final java.nio.file.Path path :
                        paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(path);
                }
            } catch (java.io.IOException ex) {
                err.println("porelo: could not delete the state of " + container + ": " + ex);
            }
        }
        err.flush();
    }

    /** Whether the task reads its whole project: asked for now, or made so. */
    private boolean reads(final String container) {
        return request.allRepositories()
                || new TaskRepositories(context.paths(), container).exists();
    }

    /**
     * Ends a run with a prompt: a task that reads its whole project is stopped and kept, or removed
     * when its start asked for that, whatever the run's exit, as an attached session's end does;
     * any other task is left as the run left it.
     *
     * <p>Called by whoever runs the prompt, after it showed the run: the removal takes the task's
     * logs with it.
     *
     * @param container The task.
     * @param code The run's exit code.
     * @param out Where the removal is said.
     * @param err Where what survived it is said.
     * @return The run's code, or 70 when a run that succeeded could not be removed.
     */
    public int promptEnded(
            final String container, final int code, final PrintWriter out, final PrintWriter err) {
        if (!reads(container)) {
            return code;
        }
        return cleanUp(context.tasks(), container, code, request.keep(), out, err);
    }

    private int cleanUpOrFail(
            TaskRunner runner,
            String container,
            int code,
            boolean keep,
            PrintWriter out,
            PrintWriter err) {
        final boolean failedOwn = launching && !handedOver && code != 0;
        if (!failedOwn) {
            return cleanUpRecorded(runner, container, code, keep, out, err);
        }
        final String onDisk = context.recordedId(container);
        final String made = createdId;
        if (made != null && !made.equals(onDisk)) {
            return removeUnrecorded(container, made, onDisk == null, code, err);
        }
        // The helpers it leaves, taken before its stop reaps them by their pid files and forgets
        // those: a stop only asks, and a broker that came up and ignores SIGTERM outlived the
        // start that failed after it. The state's only when the name's record is this start's.
        final java.util.Set<ProcessHandle> helpers = new java.util.LinkedHashSet<>(ownHelpers);
        if (made != null || onDisk == null) {
            helpers.addAll(
                    TaskLifecycle.running(context.paths().tasks().containerState(container)));
        }
        try {
            return cleanUpRecorded(runner, container, code, keep, out, err);
        } finally {
            if (!org.fuin.porelo.wire.HelperPid.endAll(helpers, GateWiring.HELPER_GRACE)) {
                err.println("porelo: still running: a helper of " + container);
                err.flush();
            }
        }
    }

    /**
     * Removes the container a failed start made when the name's record does not name it: the first
     * id record failed after the create. Every stop of a task resolves it from that record, and
     * refused this one, so the container stayed, recorded nowhere, and the broker the start had
     * started ran on. Removed by the id its create gave back, and its helpers ended by the handles
     * it holds, and by its pid files when no record names another start.
     *
     * @param container The task.
     * @param made The id its create gave back.
     * @param unrecorded Whether no record names any container for the name.
     * @param code The start's failure.
     * @param err Where what survived is said.
     * @return The start's failure.
     */
    private int removeUnrecorded(
            final String container,
            final String made,
            final boolean unrecorded,
            final int code,
            final PrintWriter err) {
        final boolean ended =
                unrecorded
                        ? TaskLifecycle.endHelpers(
                                context.paths().tasks().containerState(container),
                                ownHelpers,
                                GateWiring.HELPER_GRACE)
                        : org.fuin.porelo.wire.HelperPid.endAll(
                                ownHelpers, GateWiring.HELPER_GRACE);
        context.podman().remove(made);
        if (context.podman().stateOf(made)
                != org.fuin.porelo.runtime.Podman.ContainerState.ABSENT) {
            err.println("porelo: still running: container " + container + " (" + made + ")");
        }
        if (!ended) {
            err.println("porelo: still running: a helper of " + container);
        }
        err.flush();
        if (unrecorded) {
            McpWiring.stop(context, container);
            // Made and gone again: what the start made for it goes as for one podman never made.
            forgetUnmade(container, err);
        }
        return code;
    }

    private int cleanUpRecorded(
            TaskRunner runner,
            String container,
            int code,
            boolean keep,
            PrintWriter out,
            PrintWriter err) {

        // Only the container it recorded is the start's: one of the same name it never made is
        // somebody else's, and stopping or removing it by name destroyed what was not Porelo's. A
        // start that failed trusts only the id its own create gave back: the record on disk is the
        // name's, and a start of the same name racing this one wrote its own there.
        final boolean ownStart = launching && !handedOver;
        final String onDisk = context.recordedId(container);
        final String recorded = ownStart ? createdId : onDisk;
        if (ownStart && recorded == null && onDisk != null) {
            // A record of a container podman no longer knows names nothing: left, it named a task
            // that was gone. One of a live container is another start's and stays.
            try {
                new TaskReservations(context).forgetGone(container, onDisk);
            } catch (java.io.IOException | org.fuin.porelo.core.process.CommandException ex) {
                err.println("porelo: could not forget the record of " + container + ": " + ex);
                err.flush();
            }
        }
        if (ownStart
                && recorded == null
                && onDisk != null
                && context.podman().stateOf(onDisk)
                        != org.fuin.porelo.runtime.Podman.ContainerState.ABSENT) {
            // Another start's task holds the name: nothing of it is this start's to stop or
            // forget, its helpers and its state least of all. What this start made before its
            // create is still its own, a broker first: ended by the handles it holds, since the
            // pid files in the name's directory may name the other start's helpers by now.
            for (final ProcessHandle helper : ownHelpers) {
                org.fuin.porelo.wire.HelperPid.end(helper, GateWiring.HELPER_GRACE);
            }
            return code;
        }
        final boolean exists =
                recorded != null
                        && context.podman().stateOf(recorded)
                                != org.fuin.porelo.runtime.Podman.ContainerState.ABSENT;
        boolean unmade = false;
        // A task that read its whole project wrote nothing anybody can take back, so its end asks
        // nothing about work. It is kept as any task is, for its Result: removed at its end, a
        // discussion's idea went with it. Asked of the task too: one brought back by its name
        // alone was made to read all the same.
        final boolean reading = reads(container);
        final boolean kept = keep;
        // A run its limit ended is kept as a failed one is, with its log, whatever it read: its
        // removal took the log that says why it never ended. By its code and its record both: a
        // record a later session never cleared is no end of that session.
        final boolean limited =
                code == TIMED_OUT
                        && RunLimit.endedBy(context.paths().tasks().containerState(container))
                                != null;

        if (code != 0 && exists && discarding && !handedOver) {
            // Refused before anybody worked in it, and before it was recorded as one to bring
            // back: kept, the next start resumed it without what it was refused for.
            heldNothing(container);
            final TaskControl.Stopped removed =
                    new TaskControl(context).remove(container, false, true);
            if (!reportStop(removed, err)) return code;
            out.println("removed   " + container + " - it was refused before it was handed on");
            out.flush();
        } else if (code != 0 && exists && !kept && (!reading || limited)) {
            if (!handedOver) {
                // Kept to be started again, which brings back what is recorded: a start that failed
                // had recorded none of what it started for it, and it came back without them.
                writeResumeRecord(container, err);
                // Saved before the stop, which saves only a container that ran: one podman made
                // but never started, or that ended at once, kept its profile and its caller's key
                // in the runtime directory alone, and a reboot lost the key its caller finds it by.
                new TaskState(context).save(container);
            }
            final TaskControl.Stopped held = new TaskControl(context).stop(container);
            if (!reportStop(held, err)) return code == 0 ? 70 : code;
            if (!handedOver) {
                heldNothing(container);
            }
            out.println(
                    "kept      "
                            + container
                            + (limited
                                    ? " - its time limit passed, so nothing was removed"
                                    : " - it failed, so nothing was removed"));
            if (held.work() != null) {
                out.println("          it holds " + held.work());
            }
            out.println(
                    "          look with 'porelo task list', go back in with"
                            + " 'porelo task attach "
                            + container
                            + "'");
            out.println("          - which offers to start it again -");
            out.println("          discard with 'porelo task remove " + container + "'");
            out.flush();
        } else if (!kept) {
            // The same question 'task remove' asks before it removes anything. A workspace is in
            // the container's own writable layer, so this remove destroys it - and asking there but
            // not here was the difference between refusing to discard unreviewed work and
            // discarding it because somebody typed 'exit'. Only answerable while the container
            // runs, which it still is: the shell was an exec beside 'sleep infinity'. Not asked of
            // a task that only read: whatever it wrote is nothing it can hand on.
            final String held =
                    !reading && exists && context.podman().pidOf(container).orElse(0L) > 0
                            ? new TaskControl(context).heldBy(container).phrase()
                            : null;
            if (held == null) {
                // The ordinary ending, as 'task remove' ends a task: the container, and its account
                // in the project's conversations, its tokens and its mailbox with it. Removed with
                // the container alone, the account stayed at the transport, and a prune later
                // forgot the only credential that could retire it.
                if (recorded != null) {
                    final TaskControl.Stopped removed =
                            new TaskControl(context).remove(container, false, true);
                    if (!reportStop(removed, err)) return code == 0 ? 70 : code;
                } else {
                    unmade = true;
                }
            } else {
                // Stopped rather than left up, for the reason the failed branch is: a task nobody
                // is watching that still holds a firewall, a gate and a credential proxy is not
                // kept, it is abandoned.
                final TaskControl.Stopped stopped = new TaskControl(context).stop(container);
                if (!reportStop(stopped, err)) return code == 0 ? 70 : code;
                out.println(
                        "kept      "
                                + container
                                + " - it holds "
                                + held
                                + " that never reached the gate");
                out.println(
                        "          go back in with 'porelo task attach "
                                + container
                                + "',"
                                + " which offers to start it again,");
                out.println(
                        "          push it with 'porelo task remove "
                                + container
                                + " --rescue'"
                                + " while it runs,");
                out.println(
                        "          or discard it with 'porelo task remove "
                                + container
                                + " --force'");
                out.flush();
            }
        }
        if (kept && reading && exists) {
            // Stopped, never left running: one left up after its session held its project's trees
            // with nobody watching.
            final TaskControl.Stopped stopped = new TaskControl(context).stop(container);
            if (!reportStop(stopped, err)) return code == 0 ? 70 : code;
            out.println("kept      " + container + " - it only read, and is stopped");
            out.println("          remove it with 'porelo task remove " + container + "'");
            out.flush();
        }
        if (recorded == null) {
            // Nothing it made runs, whatever of that name does: its helpers are all that is left.
            TaskLifecycle.stopHelpers(context.paths().tasks().containerState(container));
            // And the daemon socket opened for its MCP endpoint, which no stop of a container
            // closes when there is none.
            McpWiring.stop(context, container);
        } else {
            reapOrphans(context, container);
        }
        if (unmade) {
            // After the reaping, which finds the helpers by the pid files in its state.
            forgetUnmade(container, err);
        }
        return code;
    }

    /**
     * Makes a task's mailbox as it is now, tells its agent how it works and whom it can reach, and
     * writes the guide to all of Porelo in the task.
     *
     * <p>Before its agent starts: an agent takes the guide in when it starts, and a missing one is
     * never added to its command. The guide's directory is made only with the task, before its
     * container, which mounts it; a task made before has no mount for it, and a directory made now
     * would point its agent at a file it cannot see.
     *
     * @param project The task's project.
     * @param container The task.
     * @param created Whether the task is being made now, rather than started again.
     * @throws java.io.IOException Writing failed.
     */
    private void informed(final Project project, final String container, final boolean created)
            throws java.io.IOException {
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        final TaskGuide guide = new TaskGuide(context.paths(), container);
        final TaskResult result = new TaskResult(context.paths());
        if (created) {
            // What an earlier task of this name handed back goes with this one's making.
            result.forget(container);
            if (request.result() != null) {
                result.prepare(container, request.result());
            }
        }
        if (created || guide.exists()) {
            // A task brought back has no values in hand; its record has the ones it was made with,
            // so the guide says the same after a resume.
            final java.util.Map<String, String> started = new java.util.LinkedHashMap<>();
            if (!values.isEmpty()) {
                values.forEach((name, given) -> started.put(name, given.value()));
            } else {
                TaskInventory.parametersGiven(context.paths().tasks().containerState(container))
                        .forEach((name, given) -> started.put(name, given.get("value")));
            }
            final java.util.Map<String, java.util.Map.Entry<String, String>> told =
                    new java.util.LinkedHashMap<>();
            started.forEach(
                    (name, value) -> {
                        final var declared = project.parameters().declared().get(name);
                        told.put(
                                name,
                                new java.util.AbstractMap.SimpleImmutableEntry<>(
                                        value, declared == null ? null : declared.env()));
                    });
            // What it reads is what its directory holds, so a task brought back is told the same.
            final TaskRepositories reading = new TaskRepositories(context.paths(), container);
            // Its endpoint from the file the daemon reads for it, so a task brought back is told
            // what it has now.
            TaskGuideText.write(
                    guide,
                    mailbox.exists(),
                    told,
                    reading.exists() ? reading.names() : null,
                    result.hasBox(container) ? result.name(container) : null,
                    McpWiring.told(context, container, project.name()));
        }
        if (!mailbox.exists()) {
            return;
        }
        mailbox.create();
        MailboxGuide.write(
                mailbox,
                new MessageWatch(context, java.time.Duration.ZERO)
                        .withSiblings(project, container));
    }
}
