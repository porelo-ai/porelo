package org.fuin.porelo.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.GateMode;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.runtime.ContainerName;
import org.fuin.porelo.runtime.ContainerSummary;
import org.fuin.porelo.wire.Sidecar;
import org.jspecify.annotations.Nullable;

/**
 * What tasks exist on this machine, as data rather than as a printed table.
 *
 * <p>The CLI renders this and the daemon serializes it, so that "what tasks are there" is answered
 * in one place. The interface and the CLI have to reach identical behavior through the same calls;
 * two implementations of the same question are how a feature comes to exist in one and not the
 * other, and how they come to disagree about something an operator is reading to decide what to
 * stop.
 *
 * <p>The container runtime knows the containers; only Porelo knows which project and security class
 * each belongs to. Both come from the task's own state directory rather than from the container
 * name, because a project name may contain the separator that name is built with.
 */
public final class TaskInventory {

    /** How the work ended, whether declared or derived. */
    public enum EndOutcome {
        /** The task declared its work complete. */
        DONE,
        /** The task declared that its work cannot continue. */
        BLOCKED,
        /** An undeclared run ended unfinished. */
        FAILED
    }

    /** How the work ended, declared by its task or derived from an unfinished run. */
    public record Ended(EndOutcome outcome, String why, String at) {
        /** Returns the outcome as the interface reads it. */
        public Map<String, Object> asMap() {
            return Map.of("outcome", outcome.name(), "why", why, "at", at);
        }
    }

    /**
     * One task, as much as this machine can say about it.
     *
     * @param name Container name, which is what every other command takes.
     * @param project Project the task belongs to, or {@code null} when nothing recorded it.
     * @param securityClass The project's class, or {@code null} when nothing recorded it.
     * @param state {@code done} for declared completion at rest or stopped; otherwise the runtime's
     *     words.
     * @param running Whether the runtime says it is up.
     * @param helpers How many recorded helper processes are alive.
     * @param agent Agent running in it, or {@code null} when it has none or nothing recorded one.
     * @param mode How somebody is meant to be involved, or {@code null} when nothing recorded it.
     * @param prompt What an unattended task was asked to do, or an attended task's first turn, or
     *     {@code null}.
     * @param branch Ref its work goes to, or {@code null} when it has no gate.
     * @param since When its container's current state began, ISO-8601, or empty when the runtime
     *     cannot say.
     * @param activity What the work is doing, as against what the container is doing.
     * @param waitingFor What it is waiting to be told, when it is waiting.
     * @param clearance What it does with a blocked connection, or {@code null} when nothing
     *     recorded it. {@code off} means nothing asks and nothing is refused, which is the one
     *     state that has to be visible wherever the task is listed.
     * @param label A caption somebody gave it, or {@code null}.
     * @param waiting 1 when its own work is waiting at the gate, 0 otherwise.
     * @param startAction What Start would do to it: RUNNING, RESUME or PREDATES_RESTART.
     * @param startDetail Why, when startAction is a refusal; empty otherwise.
     * @param repository The repository it works on, or {@code null}.
     * @param commit What its configuration was verified at, or {@code null}.
     * @param derived Whether it waits for a person, derived from its agent's own declaration -
     *     never observed.
     * @param credentials What it was given beyond its agent's own credential: entry to destination.
     * @param grants For each credential a person granted: {@code grantedBy} and {@code grantedAt}.
     * @param phase The current start step's text, or empty when no start is active.
     * @param builds Which reader follows the builds of its pushes, and what they did, the newest
     *     push first.
     * @param lastWrote Last modification time of the agent's log, ISO-8601, or "".
     * @param inboxWaiting New messages waiting in the host-side inbox, including for a stopped
     *     task.
     * @param forgeBranch The branch its work carries beyond the gate, as its start settled it, or
     *     {@code null} when it has no gate.
     * @param certified Whether its commits are signed by its own key, certified at its start, and
     *     judged at its gate.
     * @param certifiedUntil When its current certificate ends, or {@code ""} when it has none.
     * @param model The model its agent runs on, or {@code ""} for the agent's own default.
     * @param modelLabel What a person is shown for it, or {@code ""}.
     * @param modelFrom Where it was settled from: {@code start}, {@code repository}, {@code
     *     project} or {@code agent}; {@code ""} when nothing recorded the task.
     * @param named The name its start was given, or {@code null} when it took its repository's.
     * @param logicalTask Logical task for display, empty when unknown or contradictory, or {@code
     *     null} for a legacy snapshot.
     * @param ended How the work ended, or {@code null} when nothing declared or derived it.
     * @param result What was taken of the file it hands back, or {@code null} when nothing was.
     */
    public record Task(
            String name,
            @Nullable String project,
            @Nullable String securityClass,
            String state,
            boolean running,
            long helpers,
            @Nullable String agent,
            @Nullable String mode,
            @Nullable String prompt,
            @Nullable String branch,
            String since,
            Activity activity,
            @Nullable String waitingFor,
            @Nullable String clearance,
            @Nullable String label,
            int waiting,
            String startAction,
            String startDetail,
            @Nullable String repository,
            @Nullable String commit,
            AgentWaiting.Derived derived,
            Map<String, String> credentials,
            Map<String, Map<String, String>> grants,
            @Nullable String provider,
            AgentEnding.@Nullable Ended agentEnded,
            HandIns.Summary handIns,
            TaskBuilds.View builds,
            Map<String, Map<String, String>> parameters,
            String phase,
            String lastWrote,
            int inboxWaiting,
            @Nullable String forgeBranch,
            boolean certified,
            String certifiedUntil,
            String model,
            String modelLabel,
            String modelFrom,
            @Nullable String named,
            @Nullable String logicalTask,
            @Nullable Ended ended,
            TaskResult.@Nullable Taken result) {

        /** A snapshot made before the file a task hands back was carried. */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit,
                AgentWaiting.Derived derived,
                Map<String, String> credentials,
                Map<String, Map<String, String>> grants,
                @Nullable String provider,
                AgentEnding.@Nullable Ended agentEnded,
                HandIns.Summary handIns,
                TaskBuilds.View builds,
                Map<String, Map<String, String>> parameters,
                String phase,
                String lastWrote,
                int inboxWaiting,
                @Nullable String forgeBranch,
                boolean certified,
                String certifiedUntil,
                String model,
                String modelLabel,
                String modelFrom,
                @Nullable String named,
                @Nullable String logicalTask,
                @Nullable Ended ended) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    derived,
                    credentials,
                    grants,
                    provider,
                    agentEnded,
                    handIns,
                    builds,
                    parameters,
                    phase,
                    lastWrote,
                    inboxWaiting,
                    forgeBranch,
                    certified,
                    certifiedUntil,
                    model,
                    modelLabel,
                    modelFrom,
                    named,
                    logicalTask,
                    ended,
                    null);
        }

        /** A snapshot made before declared outcomes were carried. */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit,
                AgentWaiting.Derived derived,
                Map<String, String> credentials,
                Map<String, Map<String, String>> grants,
                @Nullable String provider,
                AgentEnding.@Nullable Ended agentEnded,
                HandIns.Summary handIns,
                TaskBuilds.View builds,
                Map<String, Map<String, String>> parameters,
                String phase,
                String lastWrote,
                int inboxWaiting,
                @Nullable String forgeBranch,
                boolean certified,
                String certifiedUntil,
                String model,
                String modelLabel,
                String modelFrom,
                @Nullable String named,
                @Nullable String logicalTask) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    derived,
                    credentials,
                    grants,
                    provider,
                    agentEnded,
                    handIns,
                    builds,
                    parameters,
                    phase,
                    lastWrote,
                    inboxWaiting,
                    forgeBranch,
                    certified,
                    certifiedUntil,
                    model,
                    modelLabel,
                    modelFrom,
                    named,
                    logicalTask,
                    null);
        }

        /** A snapshot made before a verified logical task was carried explicitly. */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit,
                AgentWaiting.Derived derived,
                Map<String, String> credentials,
                Map<String, Map<String, String>> grants,
                @Nullable String provider,
                AgentEnding.@Nullable Ended agentEnded,
                HandIns.Summary handIns,
                TaskBuilds.View builds,
                Map<String, Map<String, String>> parameters,
                String phase,
                String lastWrote,
                int inboxWaiting,
                @Nullable String forgeBranch,
                boolean certified,
                String certifiedUntil,
                String model,
                String modelLabel,
                String modelFrom,
                @Nullable String named) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    derived,
                    credentials,
                    grants,
                    provider,
                    agentEnded,
                    handIns,
                    builds,
                    parameters,
                    phase,
                    lastWrote,
                    inboxWaiting,
                    forgeBranch,
                    certified,
                    certifiedUntil,
                    model,
                    modelLabel,
                    modelFrom,
                    named,
                    null);
        }

        /** Creates a task whose model nothing recorded and whose start was given no name. */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit,
                AgentWaiting.Derived derived,
                Map<String, String> credentials,
                Map<String, Map<String, String>> grants,
                @Nullable String provider,
                AgentEnding.@Nullable Ended agentEnded,
                HandIns.Summary handIns,
                TaskBuilds.View builds,
                Map<String, Map<String, String>> parameters,
                String phase,
                String lastWrote,
                int inboxWaiting,
                @Nullable String forgeBranch,
                boolean certified,
                String certifiedUntil) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    derived,
                    credentials,
                    grants,
                    provider,
                    agentEnded,
                    handIns,
                    builds,
                    parameters,
                    phase,
                    lastWrote,
                    inboxWaiting,
                    forgeBranch,
                    certified,
                    certifiedUntil,
                    "",
                    "",
                    "",
                    null);
        }

        /**
         * Returns the task's name within its project.
         *
         * @return It, or {@code null} when nothing recorded its project or the container is not of
         *     it.
         */
        public @Nullable String shortName() {
            final String within =
                    logicalTask != null
                            ? logicalTask
                            : project == null
                                    ? ""
                                    : org.fuin.porelo.runtime.ContainerName.taskIn(project, name);
            return within.isEmpty() ? null : within;
        }

        /**
         * Returns the name a client shows for the task: the one its start was given, else its short
         * name, else its container's.
         *
         * @return The name to show; never the identity a call takes, which is {@link #name()}.
         */
        public String display() {
            final String within = shortName();
            return named != null ? named : within != null ? within : name;
        }

        /** Creates a task whose commits are not signed by a certified key of its own. */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit,
                AgentWaiting.Derived derived,
                Map<String, String> credentials,
                Map<String, Map<String, String>> grants,
                @Nullable String provider,
                AgentEnding.@Nullable Ended agentEnded,
                HandIns.Summary handIns,
                TaskBuilds.View builds,
                Map<String, Map<String, String>> parameters,
                String phase,
                String lastWrote,
                int inboxWaiting,
                @Nullable String forgeBranch) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    derived,
                    credentials,
                    grants,
                    provider,
                    agentEnded,
                    handIns,
                    builds,
                    parameters,
                    phase,
                    lastWrote,
                    inboxWaiting,
                    forgeBranch,
                    false,
                    "");
        }

        /** Creates a task whose branch beyond the gate is today's or unknown. */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit,
                AgentWaiting.Derived derived,
                Map<String, String> credentials,
                Map<String, Map<String, String>> grants,
                @Nullable String provider,
                AgentEnding.@Nullable Ended agentEnded,
                HandIns.Summary handIns,
                TaskBuilds.View builds,
                Map<String, Map<String, String>> parameters,
                String phase,
                String lastWrote,
                int inboxWaiting) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    derived,
                    credentials,
                    grants,
                    provider,
                    agentEnded,
                    handIns,
                    builds,
                    parameters,
                    phase,
                    lastWrote,
                    inboxWaiting,
                    null);
        }

        /** Creates a task with no active start phase, as callers before step reporting do. */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit,
                AgentWaiting.Derived derived,
                Map<String, String> credentials,
                Map<String, Map<String, String>> grants,
                @Nullable String provider,
                AgentEnding.@Nullable Ended agentEnded,
                HandIns.Summary handIns,
                TaskBuilds.View builds,
                Map<String, Map<String, String>> parameters) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    derived,
                    credentials,
                    grants,
                    provider,
                    agentEnded,
                    handIns,
                    builds,
                    parameters,
                    "",
                    "",
                    0);
        }

        /**
         * Constructor for a task started with no values, as every task was before a project could
         * declare them.
         *
         * @param name Container name.
         * @param project Project, or {@code null}.
         * @param securityClass Class, or {@code null}.
         * @param state Container state.
         * @param running Whether it is up.
         * @param helpers How many host processes it has.
         * @param agent Agent, or {@code null}.
         * @param mode Mode, or {@code null}.
         * @param prompt Prompt, or {@code null}.
         * @param branch Branch, or {@code null}.
         * @param since When it last changed.
         * @param activity What it is doing.
         * @param waitingFor What it waits for, or {@code null}.
         * @param clearance Clearance, or {@code null}.
         * @param label Its label, or {@code null}.
         * @param waiting How much waits.
         * @param startAction What a start would do.
         * @param startDetail Why.
         * @param repository Repository, or {@code null}.
         * @param commit Commit, or {@code null}.
         * @param derived What its agent's declaration reads.
         * @param credentials Credentials beyond the agent's.
         * @param grants Whom it acts as.
         * @param provider Provider, or {@code null}.
         * @param agentEnded How its agent ended, or {@code null}.
         * @param handIns What was handed in.
         * @param builds Its builds.
         */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit,
                AgentWaiting.Derived derived,
                Map<String, String> credentials,
                Map<String, Map<String, String>> grants,
                @Nullable String provider,
                AgentEnding.@Nullable Ended agentEnded,
                HandIns.Summary handIns,
                TaskBuilds.View builds) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    derived,
                    credentials,
                    grants,
                    provider,
                    agentEnded,
                    handIns,
                    builds,
                    Map.of());
        }

        /**
         * Constructor for a task that names no provider, the shape before it was recorded.
         *
         * @param name Container name.
         * @param project Project, or {@code null}.
         * @param securityClass Class, or {@code null}.
         * @param state Container state.
         * @param running Whether it is up.
         * @param helpers How many host processes it has.
         * @param agent Agent, or {@code null}.
         * @param mode Mode, or {@code null}.
         * @param prompt Prompt, or {@code null}.
         * @param branch Branch, or {@code null}.
         * @param since Since when.
         * @param activity What its work is doing.
         * @param waitingFor What it is waiting for, or {@code null}.
         * @param clearance Its clearance mode, or {@code null}.
         * @param label Its label, or {@code null}.
         * @param waiting Whether its work is waiting for review.
         * @param startAction What starting it would do.
         * @param startDetail Why.
         * @param repository Repository, or {@code null}.
         * @param commit Commit, or {@code null}.
         * @param derived What its screen and messages say.
         * @param credentials What it was given beyond its agent's own credential.
         * @param grants What it acts as.
         */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit,
                AgentWaiting.Derived derived,
                Map<String, String> credentials,
                Map<String, Map<String, String>> grants) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    derived,
                    credentials,
                    grants,
                    null,
                    null,
                    HandIns.Summary.none(),
                    TaskBuilds.View.none());
        }

        /**
         * Constructor for a task that acts as nobody, the shape before grants were recorded.
         *
         * @param name Container name.
         * @param project Project, or {@code null}.
         * @param securityClass Class, or {@code null}.
         * @param state Container state.
         * @param running Whether it is up.
         * @param helpers How many host processes it has.
         * @param agent Agent, or {@code null}.
         * @param mode Mode, or {@code null}.
         * @param prompt Prompt, or {@code null}.
         * @param branch Branch, or {@code null}.
         * @param since Since when.
         * @param activity What its work is doing.
         * @param waitingFor What it is waiting for, or {@code null}.
         * @param clearance Its clearance mode, or {@code null}.
         * @param label Its label, or {@code null}.
         * @param waiting Whether its work is waiting for review.
         * @param startAction What starting it would do.
         * @param startDetail Why.
         * @param repository Repository, or {@code null}.
         * @param commit Commit, or {@code null}.
         * @param derived What its screen and messages say.
         * @param credentials What it was given beyond its agent's own credential.
         */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit,
                AgentWaiting.Derived derived,
                Map<String, String> credentials) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    derived,
                    credentials,
                    Map.of());
        }

        /**
         * Constructor for a task given no credential beyond its agent's own, the shape before a
         * task held more than one.
         *
         * @param name Container name.
         * @param project Project, or {@code null}.
         * @param securityClass Class, or {@code null}.
         * @param state Container state.
         * @param running Whether it runs.
         * @param helpers Helper processes.
         * @param agent Agent, or {@code null}.
         * @param mode Mode, or {@code null}.
         * @param prompt Prompt, or {@code null}.
         * @param branch Branch, or {@code null}.
         * @param since Since when.
         * @param activity What its work is doing.
         * @param waitingFor What it waits for, or {@code null}.
         * @param clearance Clearance, or {@code null}.
         * @param label Label, or {@code null}.
         * @param waiting Pending pushes.
         * @param startAction What starting it would do.
         * @param startDetail Why.
         * @param repository Repository, or {@code null}.
         * @param commit Commit, or {@code null}.
         * @param derived What its screen and messages say.
         */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit,
                AgentWaiting.Derived derived) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    derived,
                    Map.of(),
                    Map.of());
        }

        /**
         * Constructor for a task nothing could be derived about.
         *
         * @param name Container name.
         * @param project Project name, or {@code null}.
         * @param securityClass Security class, or {@code null}.
         * @param state The runtime's own words.
         * @param running Whether it is up.
         * @param helpers How many host processes it has.
         * @param agent The agent, or {@code null}.
         * @param mode How somebody is involved, or {@code null}.
         * @param prompt What it was asked, or {@code null}.
         * @param branch The ref it pushes to, or {@code null}.
         * @param since When it entered its state.
         * @param activity What its work is doing.
         * @param waitingFor What it is waiting for, or {@code null}.
         * @param clearance Its clearance mode, or {@code null}.
         * @param label Its label, or {@code null}.
         * @param waiting Whether its work is waiting for review.
         * @param startAction What starting it would do.
         * @param startDetail Why.
         * @param repository The repository, or {@code null}.
         * @param commit The verified commit, or {@code null}.
         */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail,
                @Nullable String repository,
                @Nullable String commit) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    repository,
                    commit,
                    AgentWaiting.Derived.NOTHING);
        }

        /**
         * Constructor for a task taken before the repository was known.
         *
         * @param name Container name.
         * @param project Project name, or {@code null}.
         * @param securityClass Security class, or {@code null}.
         * @param state The runtime's own words.
         * @param running Whether it is up.
         * @param helpers How many host processes it has.
         * @param agent The agent, or {@code null}.
         * @param mode How somebody is involved, or {@code null}.
         * @param prompt What it was asked, or {@code null}.
         * @param branch The ref it pushes to, or {@code null}.
         * @param since When it entered its state.
         * @param activity What its work is doing.
         * @param waitingFor What it is waiting for, or {@code null}.
         * @param clearance Its clearance mode, or {@code null}.
         * @param label Its label, or {@code null}.
         * @param waiting Whether its work is waiting for review.
         * @param startAction What starting it would do.
         * @param startDetail Why.
         */
        public Task(
                String name,
                @Nullable String project,
                @Nullable String securityClass,
                String state,
                boolean running,
                long helpers,
                @Nullable String agent,
                @Nullable String mode,
                @Nullable String prompt,
                @Nullable String branch,
                String since,
                Activity activity,
                @Nullable String waitingFor,
                @Nullable String clearance,
                @Nullable String label,
                int waiting,
                String startAction,
                String startDetail) {
            this(
                    name,
                    project,
                    securityClass,
                    state,
                    running,
                    helpers,
                    agent,
                    mode,
                    prompt,
                    branch,
                    since,
                    activity,
                    waitingFor,
                    clearance,
                    label,
                    waiting,
                    startAction,
                    startDetail,
                    null,
                    null);
        }

        /**
         * Returns this task as plain values, for a caller that has to put it on a wire.
         *
         * @return The task, with {@code null} replaced by an empty string so the map is
         *     representable in every encoding a client might use.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("project", project == null ? "" : project);
            map.put("securityClass", securityClass == null ? "" : securityClass);
            // "" for a container created before the repository was labelled, which is a project's
            // own repository and the only one there was. An interface renders the empty string as
            // the project's own rather than as "unknown".
            map.put("repository", repository == null ? "" : repository);
            // What the task's configuration was verified at. "" when nothing verified it, which is
            // every task of a project this machine does not follow.
            map.put("commit", commit == null ? "" : commit);
            map.put("state", state);
            map.put("running", running);
            map.put("helpers", helpers);
            map.put("agent", agent == null ? "" : agent);
            map.put("provider", provider == null ? "" : provider);
            map.put("mode", mode == null ? "" : mode);
            map.put("prompt", prompt == null ? "" : prompt);
            map.put("branch", branch == null ? "" : branch);
            map.put("forgeBranch", forgeBranch == null ? "" : forgeBranch);
            map.put("certified", certified);
            map.put("certifiedUntil", certifiedUntil);
            map.put("model", model);
            map.put("modelLabel", modelLabel);
            map.put("modelFrom", modelFrom);
            map.put("since", since);
            map.put("activity", activity.name());
            map.put("lastWrote", lastWrote);
            map.put("inboxWaiting", inboxWaiting);
            if (agentEnded != null) {
                final Map<String, Object> run = new LinkedHashMap<>(agentEnded.asMap());
                if (result != null) {
                    run.put("result", result.asMap());
                }
                map.put("agentEnded", run);
            }
            if (ended != null) {
                map.put("ended", ended.asMap());
            }
            if (result != null) {
                map.put("result", result.asMap());
            }
            map.put("waitingFor", waitingFor == null ? "" : waitingFor);
            map.put("clearance", clearance == null ? "" : clearance);
            // Beside the name, never instead of it: the name is what every other call takes and
            // what somebody types at the machine. Empty means the row shows its real name.
            map.put("label", label == null ? "" : label);
            // Whether this task's own work is waiting at the gate. Answered here rather than left
            // as a join: Task.name is a CONTAINER name and PendingPush.name is a TASK name, and
            // several containers over time share one ref - so nothing a client lined up could be
            // right for more than one of them.
            map.put("waiting", waiting);

            // Carried rather than left to the caller: 'name' is a container name, and the rule
            // relating the two belongs here. It changed in this release, which is exactly why an
            // interface must not cut a prefix off the name to get it.
            final String within =
                    logicalTask != null
                            ? logicalTask
                            : project == null
                                    ? ""
                                    : org.fuin.porelo.runtime.ContainerName.taskIn(project, name);
            map.put("task", within);
            map.put("short", within);
            map.put("named", named == null ? "" : named);
            map.put("display", display());

            // What Start would do to this task, answered before anybody presses it. A listed task
            // exists by definition, so CREATE cannot appear here. Worked out where the state
            // directory is at hand; PREDATES_RESTART comes from there.
            //
            // Two values this does NOT yet produce, and both are absences rather than oversights:
            //
            //   SUPERSEDED_NAME  a container from before one-container-per-task cannot be told
            //                    apart from a task whose name genuinely ends in digits, and
            //                    guessing would refuse to start a task that is perfectly fine.
            //   NEEDS_VAULT      nothing here knows whether the vault is locked, and asking per
            //                    row would make a listing of forty tasks forty questions.
            //
            // A client renders a value it does not know rather than failing on it, so both can
            // start appearing without breaking anything.
            map.put("startAction", startAction);
            map.put("startDetail", startDetail);

            // Whether it waits for a person, as its agent's own declaration reads its output.
            // Derived, and kept apart from 'activity' and 'waitingFor', which are what the runtime
            // and the clearance watcher observed: an interface renders these as a reading, never as
            // a fact.
            map.put("screen", derived.screen().name());
            map.put("screenWaitingFor", derived.waitingFor());
            map.put("screenUnproven", derived.unproven());
            map.put("lastMessage", derived.lastMessage());
            map.put("asked", derived.asked().name());
            map.put("askedFrom", derived.askedFrom());
            // The session the next start continues, so an interface can say so before it is
            // pressed.
            map.put("session", derived.session());
            // What it was given beyond its agent's own credential, by name and destination - never
            // a token.
            map.put("credentials", credentials);
            // Whom it acts as, for each of those a person granted: an audit fact, never the grant
            // itself.
            map.put("grants", grants);
            // The values it was started with, each with where it came from; fixed for its life.
            map.put("parameters", parameters);

            // The container this task is, so a record entry of a name another task carried before
            // can be told apart; "" for a task with none recorded.
            map.put("run", handIns.run());
            // The bytes one file handed to it may have, fixed when it started: a client refuses a
            // larger file before reading it.
            map.put("handInLimit", handIns.limit());
            // What is in /porelo/files now, as it was handed in by this run.
            map.put("files", handIns.files().stream().map(HandIns.HandedFile::asMap).toList());
            // What the builds of its pushes did, the newest push first: the verdict a person sees
            // without opening it.
            map.put("builds", builds.builds().stream().map(TaskBuilds.Seen::asMap).toList());
            // Which forge follows them, so an empty list reads as "no push yet" rather than as
            // "nothing follows".
            map.put("buildReader", builds.reader());
            // Why a named reader does not follow them; "" while it does.
            map.put("buildProblem", builds.problem());
            if (builds.credential() != null) {
                map.put("buildsCredential", builds.credential().asMap());
            }

            map.put("phase", phase);
            return map;
        }
    }

    /**
     * What the work in a task is doing, as against what its container is doing.
     *
     * <p>{@code running} answers the container's question. This answers the one an operator
     * actually has, and the two are not the same: a task blocked on a question nobody saw and a
     * task grinding through a build are both running.
     */
    public enum Activity {

        /** Its agent declared completion, and its container is stopped or its activity was idle. */
        DONE,

        /** Its container is not up. Stopped, finished or dead - all three are gone. */
        DEAD,

        /** Waiting for a person to answer something. Said by the thing that asked, not guessed. */
        WAITING,

        /**
         * Producing output, or attached with an agent whose screen, read by its own declaration of
         * rest, shows it not at rest.
         */
        WORKING,

        /** Up, producing nothing, and not waiting for anybody as far as anything can tell. */
        IDLE,

        /**
         * Up and attached, and its agent's screen shows it at rest at its prompt, as its own
         * declaration reads it, on two looks apart.
         *
         * <p>Not idle: idle is inferred from a log that stayed quiet, this is what the agent
         * declares its rest looks like.
         */
        AT_REST,

        /**
         * Up, and its agent's run has ended - finished, or stopped by an error its agent read from
         * its own output.
         *
         * <p>Neither working nor idle: a run that ended on a provider's refusal said "idle", then
         * "working", while nothing would happen any more.
         */
        ENDED,

        /**
         * Up, and nothing here can see what it is doing.
         *
         * <p>A task somebody attached a terminal to writes its work to that terminal, not to a file
         * this can read. Reported as its own value rather than as idle: a state that is silently
         * wrong is worse than one that says it does not know.
         */
        UNKNOWN
    }

    /** How long a task's own log may be quiet before the work is called idle rather than busy. */
    private static final java.time.Duration QUIET = java.time.Duration.ofSeconds(60);

    /**
     * What a task the machine's restart took down says beside {@code RESUME}: not a refusal, but
     * the reason it is down, which a person should not have to read out of a boot log.
     */
    public static final String RESTARTED =
            "the machine restarted; starting it brings it back whole";

    /**
     * What an unattended task the machine's restart took down says beside {@code RESUME} instead of
     * {@link #RESTARTED}: a run nobody watches is never started again on its own, so it stays
     * stopped, and its next start continues its conversation.
     */
    public static final String INTERRUPTED =
            "the machine's restart interrupted its run; it stays stopped with its"
                    + " session kept, and its next start continues it";

    private final PoreloContext context;

    private final StartStepFile startSteps =
            new StartStepFile(new java.io.PrintWriter(System.err, true));

    private final @Nullable AgentWaiting agentWaiting;

    private final @Nullable AgentEnding ending;

    /**
     * Constructor for a listing that derives nothing about waiting for a person.
     *
     * <p>What most callers want: deriving starts the installed agents and reads screens, and a
     * command that only needs names and states must not pay for that.
     *
     * @param context Where podman and the paths come from.
     */
    public TaskInventory(PoreloContext context) {
        this(context, null);
    }

    /**
     * Constructor for a listing that says whether each task waits for a person.
     *
     * <p>Keep it for as long as the listing is read again - a watch - so what it derives is read at
     * the pace that is cheap rather than on every call.
     *
     * @param context Where podman and the paths come from.
     * @return The inventory.
     */
    public static TaskInventory deriving(PoreloContext context) {
        return new TaskInventory(
                context,
                new AgentWaiting(context, java.time.Clock.systemUTC()),
                AgentEnding.of(context));
    }

    /**
     * Constructor for a command asked once, such as {@code task status}: as {@link #deriving}, and
     * an attached agent's screen first seen at rest is looked at again after {@link Rest#LOOK},
     * since there is no earlier read to hold it against.
     *
     * @param context Where podman and the paths come from.
     * @return The inventory.
     */
    public static TaskInventory derivingOnce(PoreloContext context) {
        return new TaskInventory(
                context,
                AgentWaiting.lookingTwice(context, java.time.Clock.systemUTC()),
                AgentEnding.of(context));
    }

    /**
     * Constructor with the reader of what an agent waits for.
     *
     * @param context Where podman and the paths come from.
     * @param agentWaiting What derives whether a task waits for a person, or {@code null} for
     *     nothing.
     */
    TaskInventory(PoreloContext context, @Nullable AgentWaiting agentWaiting) {
        this(context, agentWaiting, null);
    }

    /**
     * Constructor with the readers of what an agent waits for and of how its run ended.
     *
     * @param context Where podman and the paths come from.
     * @param agentWaiting What derives whether a task waits for a person, or {@code null} for
     *     nothing.
     * @param ending What reads how a task's agent ended, or {@code null} for nothing.
     */
    public TaskInventory(
            PoreloContext context,
            @Nullable AgentWaiting agentWaiting,
            @Nullable AgentEnding ending) {
        this.context = context;
        this.agentWaiting = agentWaiting;
        this.ending = ending;
    }

    /**
     * Returns the project a task records on its container.
     *
     * <p>What a command about one task defaults to when no project is named: asking for it again
     * would be asking somebody to repeat what the machine already knows.
     *
     * @param container The task's container name.
     * @return The project, or {@code null} when there is no such task or it records none.
     */
    public @Nullable String projectOf(String container) {
        return tasks().stream()
                .filter(task -> task.name().equals(container))
                .map(Task::project)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the containers named like a task that are not one: no container id Porelo recorded
     * when it made them - made before it recorded one, or by something else. Nothing of Porelo's
     * acts on them, and they are never adopted; they are named so that "no tasks" does not hide a
     * container whose removal removes what is in it.
     *
     * @param listed The tasks there are.
     * @return Their names, in the runtime's order.
     */
    public List<String> unrecognized(final List<Task> listed) {
        final java.util.Set<String> names =
                listed.stream().map(Task::name).collect(java.util.stream.Collectors.toSet());
        return context.podman().poreloContainers().stream()
                .map(name -> name.split("\t", 2)[0])
                .filter(org.fuin.porelo.runtime.ContainerName::isTask)
                .filter(name -> !names.contains(name))
                .distinct()
                .toList();
    }

    /**
     * Says what a container named like a task with no recorded id is, where a command was asked
     * about it.
     *
     * @param name The container.
     * @return One line, without the {@code porelo: } prefix.
     */
    public static String unrecognizedNote(final String name) {
        return name
                + " is named like a task, but Porelo holds no container id for it (made before it recorded one,"
                + " or by something else), so nothing of Porelo's acts on it; removing it removes what is in it,"
                + " and 'podman cp' saves its workspace first";
    }

    /**
     * Returns tasks as a client is sent them.
     *
     * @param tasks The tasks, as {@link #tasks()} found them.
     * @return One row each, in the same order.
     */
    public List<Map<String, Object>> rows(List<Task> tasks) {
        final TaskIdeas kept = new TaskIdeas(context.paths());
        // Read once: the count held back is of the same ideas the reply carries some of.
        final List<List<TaskIdeas.Idea>> all =
                tasks.stream().map(task -> kept.read(task.name())).toList();
        final List<List<Map<String, Object>>> ideas = carried(all);
        final List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int index = 0; index < tasks.size(); index++) {
            final List<Map<String, Object>> listed = ideas.get(index);
            rows.add(row(tasks.get(index), listed, (long) all.get(index).size() - listed.size()));
        }
        return List.copyOf(rows);
    }

    /**
     * The most bytes of ideas, written as a client is sent them, one reply carries across all its
     * rows: a quarter of the 4 MiB a varlink message may hold. Each task's own bound is not enough,
     * since a listing carries every task's.
     */
    public static final int MOST_REPLY_IDEAS = 1024 * 1024;

    /**
     * Takes the ideas a reply carries: each task's oldest first, one of each task in turn, while
     * they fit in {@link #MOST_REPLY_IDEAS}. A task's ideas past it are left for a later reply,
     * never one skipped, so a client handing each id on once misses none.
     */
    static List<List<Map<String, Object>>> carried(final List<List<TaskIdeas.Idea>> ideas) {
        final List<List<Map<String, Object>>> carried = new java.util.ArrayList<>();
        final boolean[] full = new boolean[ideas.size()];
        ideas.forEach(each -> carried.add(new java.util.ArrayList<>()));
        long bytes = 0;
        boolean more = true;
        for (int number = 0; more; number++) {
            more = false;
            for (int task = 0; task < ideas.size(); task++) {
                if (full[task] || number >= ideas.get(task).size()) {
                    continue;
                }
                final Map<String, Object> idea = ideas.get(task).get(number).asMap();
                // With the comma between two.
                final long size =
                        org.fuin.porelo.wire.Json.write(idea)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                        .length
                                + 1L;
                if (bytes + size > MOST_REPLY_IDEAS) {
                    full[task] = true;
                    continue;
                }
                bytes += size;
                carried.get(task).add(idea);
                more = true;
            }
        }
        return carried.stream().map(List::copyOf).toList();
    }

    /**
     * Returns one task as a client is sent it: its own fields, and where its work landed.
     *
     * <p>Where work landed is the gate's, not the container's, so it is read from the project's
     * mirror here rather than carried on the task: {@code []} while the work waits, for a task that
     * records no project, and for one that never landed anything.
     *
     * @param task The task.
     * @return Its row.
     */
    public Map<String, Object> row(Task task) {
        return rows(List.of(task)).getFirst();
    }

    private Map<String, Object> row(
            Task task, List<Map<String, Object>> ideas, long ideasHeldBack) {
        final Map<String, Object> row = new LinkedHashMap<>(task.asMap());
        final String within = String.valueOf(row.getOrDefault("task", ""));
        final String project = task.project();
        final List<GitGate.Landed> found =
                project == null || within.isEmpty()
                        ? List.of()
                        : GitGate.landedIn(
                                GateSupport.mirror(context, project, task.repository()), within);
        row.put("landed", found.stream().map(landed -> landed(within, landed)).toList());
        row.put("minutes", RunLimit.minutes(context.paths().tasks().containerState(task.name())));
        row.put("callerKey", callerKey(task.name()));
        // What it proposed with ProposeIdea, from the host's own record, as much as the reply
        // carries; [] when none.
        row.put("ideas", ideas);
        // How many more it has than this reply carries; 0 when all are listed.
        row.put("ideasHeldBack", ideasHeldBack);
        return row;
    }

    /**
     * The file a start writes its caller's key into, before anything else of it can fail; the
     * task's profile carries the same key once it is written.
     */
    static final String CALLER_KEY_FILE = "caller-key";

    /**
     * Writes the key a start's caller gave it, where its state is made; a start given none leaves
     * none, whatever an earlier task of the name left.
     *
     * @param state The task's state directory.
     * @param key The caller's key, as given, or {@code null} for none.
     * @throws java.io.IOException If it cannot be written.
     */
    static void writeCallerKey(final Path state, final @Nullable String key)
            throws java.io.IOException {
        final Path file = state.resolve(CALLER_KEY_FILE);
        if (key == null) {
            Files.deleteIfExists(file);
            return;
        }
        Files.writeString(file, key, java.nio.charset.StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
                file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }

    /**
     * Returns the key the program that started a task gave it: from its state where it runs, else
     * from the record a reboot does not reach; from its profile, else from what its start wrote
     * before the profile existed.
     *
     * @param container The task's container.
     * @return The key, or {@code ""} when its start was given none.
     */
    String callerKey(final String container) {
        final Path runtime = context.paths().tasks().containerState(container);
        // A runtime directory that holds neither says nothing of the key: made again after a
        // reboot before its records were put back, it answered "none" for a keyed task.
        final Path state =
                Files.isRegularFile(runtime.resolve(org.fuin.porelo.wire.TaskProfile.FILE))
                                || Files.isRegularFile(runtime.resolve(CALLER_KEY_FILE))
                        ? runtime
                        : context.paths().tasks().taskRecord(container);
        final org.fuin.porelo.wire.TaskProfile profile =
                org.fuin.porelo.wire.TaskProfile.readFrom(state);
        if (profile != null) {
            final String recorded = profile.callerKey();
            return recorded == null ? "" : recorded;
        }
        try {
            final Path file = state.resolve(CALLER_KEY_FILE);
            return Files.isRegularFile(file)
                    ? Files.readString(file, java.nio.charset.StandardCharsets.UTF_8)
                    : "";
        } catch (java.io.IOException ex) {
            // A file that cannot be read says nothing; the task is still listed.
            return "";
        }
    }

    private static Map<String, Object> landed(String task, GitGate.Landed landed) {
        final String under = task + "/" + landed.branch();
        return Map.of(
                "branch",
                landed.branch(),
                "ref",
                GitGate.LANDED + under,
                "remote",
                "origin/landed/" + under,
                "commit",
                landed.commit());
    }

    /**
     * Returns every task on this machine, running or stopped.
     *
     * @return Tasks, in the order the runtime lists them.
     */
    public List<Task> tasks() {
        // The refs waiting in each mirror and mode, asked once rather than once per
        // task: this list is read on every change, and a git call per task would make it cost what
        // a listing must not.
        final Map<WaitingMirror, java.util.Set<String>> waiting = new java.util.HashMap<>();
        final List<ContainerSummary> summaries = context.podman().poreloTasks();
        if (agentWaiting != null) {
            // The screens first, several at once; describing each task then reads what was read.
            agentWaiting.readScreens(
                    summaries.stream()
                            .filter(ContainerSummary::running)
                            .map(
                                    summary -> {
                                        final org.fuin.porelo.wire.TaskProfile profile =
                                                org.fuin.porelo.wire.TaskProfile.readFrom(
                                                        context.paths()
                                                                .tasks()
                                                                .containerState(summary.name()));
                                        return new AgentWaiting.Wanted(
                                                summary.name(),
                                                true,
                                                profile == null ? null : profile.agent(),
                                                profile == null ? null : profile.mode().name());
                                    })
                            .toList());
        }
        return summaries.stream().map(summary -> describe(summary, waiting)).toList();
    }

    private record WaitingMirror(Path path, GateMode mode) {}

    /**
     * Returns the refs waiting for review in the mirror of one repository of a project, reading
     * each mirror once per gate mode.
     *
     * <p>The repository's own mirror, not the project's: a task on another repository - every task
     * of {@code default} - pushes there, and asking the project's said nothing waited.
     *
     * @param cache Filled as mirrors are seen.
     * @param project Project name, or {@code null} when nothing recorded one.
     * @param repository The task's repository, or {@code null} for the project's own.
     * @param securityClass The task's recorded class, or {@code null} when unknown.
     * @return Incoming ref names, without the namespace prefix.
     */
    private java.util.Set<String> waitingIn(
            Map<WaitingMirror, java.util.Set<String>> cache,
            @Nullable String project,
            @Nullable String repository,
            @Nullable String securityClass) {
        if (project == null) {
            return java.util.Set.of();
        }
        return cache.computeIfAbsent(
                new WaitingMirror(
                        GateSupport.mirror(context, project, repository),
                        "online".equalsIgnoreCase(securityClass)
                                ? GateMode.ONLINE
                                : GateMode.GATEKEEPING),
                key -> {
                    final Path mirror = key.path();
                    if (!java.nio.file.Files.isDirectory(mirror)) {
                        return java.util.Set.of();
                    }
                    try {
                        return java.util.Set.copyOf(
                                new GitGate(context.runner(), mirror, key.mode(), null).pending());
                    } catch (RuntimeException ex) {
                        // A directory that is not a repository, or a git that would not run.
                        // Neither is worth failing a listing for, and a task that cannot be asked
                        // reads as none waiting rather than as broken.
                        return java.util.Set.of();
                    }
                });
    }

    /** A ref belongs to this task only when its recorded branch names it. */
    private boolean waitingAtGate(
            Map<WaitingMirror, java.util.Set<String>> cache,
            @Nullable String project,
            @Nullable String repository,
            @Nullable String securityClass,
            org.fuin.porelo.wire.@Nullable TaskProfile profile) {
        if (profile == null
                || profile.branch() == null
                || !profile.branch().startsWith(GitGate.INCOMING)) {
            return false;
        }
        final String name = profile.branch().substring(GitGate.INCOMING.length());
        final java.util.Set<String> pending = waitingIn(cache, project, repository, securityClass);
        return pending.contains(name);
    }

    /**
     * Whether a task the restart took down needs its tokens from a vault that is locked right now.
     *
     * <p>Asked only for such a task, and only of the keyring - never by opening the vault - so a
     * listing stays cheap: the answer an interface needs to offer unlocking before anybody presses
     * Start.
     *
     * @param container The task's container.
     * @return true when starting it would be refused until the vault is unlocked
     */
    private boolean needsLockedVault(String container) {
        final boolean needsTokens =
                TaskHelpers.readFrom(context.paths().tasks().taskRecord(container))
                        .helpers()
                        .stream()
                        .anyMatch(
                                helper ->
                                        "gate".equals(helper.name())
                                                || "vault".equals(helper.name()));
        return needsTokens && context.vault().exists() && context.opener().isEmpty();
    }

    private Task describe(
            ContainerSummary summary, Map<WaitingMirror, java.util.Set<String>> waitingCache) {
        final Sidecar sidecar = sidecarOf(summary.name());
        final String project =
                summary.project() != null
                        ? summary.project()
                        : sidecar == null ? null : sidecar.project();
        final String securityClass =
                summary.securityClass() != null
                        ? summary.securityClass()
                        : sidecar == null ? null : sidecar.securityClass();
        final Path state = context.paths().tasks().containerState(summary.name());
        // The saved copy when a restart emptied the runtime directory: which agent and mode are
        // still true of it.
        final boolean restartTookIt =
                !summary.running()
                        && !Files.isDirectory(state)
                        && new TaskState(context).saved(summary.name());
        final org.fuin.porelo.wire.TaskProfile profile =
                org.fuin.porelo.wire.TaskProfile.readFrom(
                        restartTookIt ? context.paths().tasks().taskRecord(summary.name()) : state);
        final java.time.Instant lastWrote = lastWrote(state);
        final boolean waitingAtGate =
                waitingAtGate(waitingCache, project, summary.repository(), securityClass, profile);
        final AgentWaiting.Derived derived =
                agentWaiting == null
                        ? AgentWaiting.Derived.NOTHING
                        : agentWaiting.about(
                                summary.name(),
                                summary.running(),
                                profile == null ? null : profile.agent(),
                                profile == null ? null : profile.mode().name(),
                                state);
        // Asked after the screen, whose second look pauses: a question put during that pause wins
        // over the rest the screen showed.
        final String question =
                summary.running() ? org.fuin.porelo.wire.Waiting.about(state) : null;
        final String waitingFor =
                question != null
                        ? question
                        : summary.running() && waitingAtGate
                                ? "its commit waits at the gate"
                                : null;
        // Only by the deriving listing, and of a stopped task too while its state is here: an agent
        // whose container stopped with its run said only 'dead'.
        final AgentEnding.Ended ended =
                ending == null || restartTookIt
                        ? null
                        : ending.about(
                                summary.name(),
                                summary.running(),
                                profile == null ? null : profile.agent(),
                                state);
        final Activity observed =
                ended != null && summary.running()
                        ? Activity.ENDED
                        : activityOf(summary, lastWrote, waitingFor, derived.turn());
        final TaskDone.Declared declared = new TaskDone(context.paths()).read(summary.name());
        final Activity activity =
                (observed == Activity.DEAD || observed == Activity.IDLE)
                                && declared != null
                                && declared.outcome() == TaskDone.Outcome.DONE
                        ? Activity.DONE
                        : observed;
        return new Task(
                summary.name(),
                // The container's own label first: it survives a reboot, and the sidecar does not
                // - it lives in the runtime directory, which the system destroys when the user's
                // last session ends. Reported as every surviving task listing "-" for both after
                // the machine came back. The sidecar is the fallback, for containers a Porelo
                // without the labels created.
                project,
                securityClass,
                activity == Activity.DONE ? "done" : summary.state(),
                summary.running(),
                helpersOf(summary.name()),
                profile == null ? null : profile.agent(),
                // The enum's own name, like every other enum on the wire. The lower-case form is
                // the on-disk spelling and stops at the file: a client reading the contract sees
                // SHELL, AGENT, UNATTENDED and must get those.
                profile == null ? null : profile.mode().name(),
                profile == null ? null : profile.prompt(),
                profile == null ? null : profile.branch(),
                summary.since(),
                activity,
                waitingFor,
                profile == null ? null : profile.clearance(),
                profile == null ? null : profile.label(),
                waitingAtGate ? 1 : 0,
                // The same test resume makes, and a directory check rather than a runtime call: a
                // stopped task with no state directory was started before this machine restarted,
                // and Start would refuse it. Said here so nobody learns it by pressing.
                restartTookIt
                        ? (needsLockedVault(summary.name()) ? "NEEDS_VAULT" : "RESUME")
                        : !summary.running() && !Files.isDirectory(state)
                                ? "PREDATES_RESTART"
                                : summary.running() ? "RUNNING" : "RESUME",
                restartTookIt
                        ? (profile != null
                                        && profile.mode()
                                                == org.fuin.porelo.wire.TaskMode.UNATTENDED
                                ? INTERRUPTED
                                : RESTARTED)
                        : !summary.running() && !Files.isDirectory(state)
                                ? "started before this machine restarted; copy the workspace out with"
                                        + " 'podman cp "
                                        + summary.name()
                                        + ":/workspace ./recovered',"
                                        + " then 'porelo task remove "
                                        + summary.name()
                                        + " --force' - or 'porelo task resume "
                                        + summary.name()
                                        + "' at a terminal offers both"
                                : "",
                // From the container's label, like the project and the class above, and for the
                // same reason: it survives a reboot. There is no sidecar fallback because the
                // sidecar predates repositories - a task without the label worked on the only
                // repository its project had.
                summary.repository(),
                // From the container's label, like the three above. The project moves on; this must
                // not, or a question about a task is answered from a file that has changed.
                summary.commit(),
                derived,
                credentialsGiven(
                        restartTookIt ? context.paths().tasks().taskRecord(summary.name()) : state),
                grantsGiven(
                        restartTookIt ? context.paths().tasks().taskRecord(summary.name()) : state),
                profile == null ? null : profile.provider(),
                ended,
                HandIns.of(context).summary(summary.name()),
                buildsOf(
                        summary.name(),
                        summary.project() != null
                                ? summary.project()
                                : sidecar == null ? null : sidecar.project(),
                        summary.securityClass() != null
                                ? summary.securityClass()
                                : sidecar == null ? null : sidecar.securityClass(),
                        summary.running()),
                parametersGiven(
                        restartTookIt ? context.paths().tasks().taskRecord(summary.name()) : state),
                startSteps.phase(state),
                lastWrote == null ? "" : lastWrote.toString(),
                inboxWaiting(summary.name()),
                profile == null ? null : profile.forgeBranchOrToday(),
                java.nio.file.Files.isRegularFile(
                        (restartTookIt ? context.paths().tasks().taskRecord(summary.name()) : state)
                                .resolve(TaskState.CERTIFIERS)),
                TaskState.certifiedUntil(
                        (restartTookIt
                                ? context.paths().tasks().taskRecord(summary.name())
                                : state)),
                profile == null || profile.model() == null ? "" : profile.model(),
                profile == null || profile.modelLabel() == null ? "" : profile.modelLabel(),
                profile == null ? "" : profile.modelFrom(),
                profile == null ? null : profile.named(),
                logicalTask(summary),
                ended(declared, ended),
                resultOf(summary.name(), ended != null || stoppedAfterRunning(summary)));
    }

    /**
     * Tells whether the container stopped after it ran: not running, and not one that was created
     * or initialized and not yet started, in which nothing ran and nothing was handed back yet.
     *
     * @param summary What the runtime says.
     * @return {@code true} once it ran and stopped.
     */
    private static boolean stoppedAfterRunning(final ContainerSummary summary) {
        return !summary.running()
                && !summary.state().startsWith("Created")
                && !summary.state().startsWith("Initialized");
    }

    /**
     * Returns what was taken of the file a task hands back, taking it once when its agent ended or
     * its container stopped and nothing was taken yet.
     *
     * @param container The task.
     * @param over Whether its agent ended or its container stopped after it ran.
     * @return What was taken, or {@code null}.
     */
    private TaskResult.@Nullable Taken resultOf(final String container, final boolean over) {
        final TaskResult result = new TaskResult(context.paths());
        if (!over) {
            return result.taken(container);
        }
        try {
            return result.takeOnce(container);
        } catch (java.io.IOException ex) {
            // Taken at the next listing, or at the task's removal; nothing is said that is not so.
            return result.taken(container);
        }
    }

    private static @Nullable Ended ended(
            TaskDone.@Nullable Declared declared, AgentEnding.@Nullable Ended run) {
        if (declared != null) {
            return new Ended(
                    EndOutcome.valueOf(declared.outcome().name()), declared.why(), declared.at());
        }
        return run != null && !run.finished()
                ? new Ended(EndOutcome.FAILED, run.error(), run.at())
                : null;
    }

    /** A legacy project's label scopes its display name even when no id authorizes an action. */
    private String logicalTask(ContainerSummary summary) {
        final TaskIdentities.Result result = new TaskIdentities(context).read(summary);
        if (result instanceof TaskIdentities.Found found) {
            return found.identity().task();
        }
        final String project = summary.project();
        if (result instanceof TaskIdentities.Refused refused
                && refused.reason() == TaskIdentities.Reason.MISSING_RECORD
                && summary.task() == null
                && project != null
                && project.matches(Project.NAME_PATTERN)) {
            final String task = ContainerName.taskIn(project, summary.name());
            return task.matches(Project.TASK_NAME_PATTERN) ? task : "";
        }
        return "";
    }

    /**
     * Returns what a person sees of a task's builds, with why nothing follows them where something
     * should: an online task whose project names a forge, and no helper that follows them - its
     * launch stopped before it, or it ended.
     */
    private TaskBuilds.View buildsOf(
            final String container,
            final @Nullable String project,
            final @Nullable String securityClass,
            final boolean running) {
        final TaskBuilds.View view = new TaskBuilds(context.paths().tasks()).view(container);
        // Only for online tasks: the project file is read for them alone, and only they can follow
        // a build.
        if (!"online".equalsIgnoreCase(String.valueOf(securityClass))
                || !view.problem().isEmpty()) {
            return view;
        }
        if (view.reader().isEmpty()) {
            final org.fuin.porelo.core.project.Builds named = buildsNamedBy(project);
            return named == null
                    ? view
                    : new TaskBuilds.View(
                            named.forge(),
                            "the helper that follows its builds has"
                                    + " not started: the task's launch has not reached it, or stopped before it; start the task again",
                            view.builds(),
                            view.pullRequest(),
                            view.pullRequestProblem());
        }
        final java.nio.file.Path pid =
                context.paths()
                        .tasks()
                        .containerState(container)
                        .resolve(TaskBuilds.HELPER + ".pid");
        if (running && org.fuin.porelo.wire.HelperPid.verified(pid).isEmpty()) {
            return new TaskBuilds.View(
                    view.reader(),
                    "the helper that follows its builds is not running; stop the"
                            + " task and start it again to bring it back",
                    view.builds(),
                    view.pullRequest(),
                    view.pullRequestProblem());
        }
        return view;
    }

    /**
     * What a project's file in force names under builds; {@code null} when nothing, or it cannot be
     * read.
     */
    private org.fuin.porelo.core.project.@Nullable Builds buildsNamedBy(
            final @Nullable String project) {
        if (project == null) {
            return null;
        }
        try {
            final java.nio.file.Path file = ProjectSource.resolve(context, project).file();
            return file == null
                    ? null
                    : org.fuin.porelo.core.project.ProjectReader.read(file).builds();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** What a task was given beyond its agent's own credential, written when it is launched. */
    static final String CREDENTIALS_FILE = "credentials.json";

    /**
     * Whom a task acts as: for each credential a person granted, who and when, written when it is
     * launched.
     */
    static final String GRANTS_FILE = "grants.json";

    private static Map<String, Map<String, String>> grantsGiven(Path directory) {
        final Path file = directory.resolve(GRANTS_FILE);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            if (org.fuin.porelo.wire.Json.parse(
                            Files.readString(file, java.nio.charset.StandardCharsets.UTF_8))
                    instanceof Map<?, ?> named) {
                final Map<String, Map<String, String>> given = new java.util.LinkedHashMap<>();
                named.forEach(
                        (name, grant) -> {
                            if (grant instanceof Map<?, ?> fields) {
                                given.put(
                                        String.valueOf(name),
                                        Map.of(
                                                "grantedBy",
                                                String.valueOf(fields.get("grantedBy")),
                                                "grantedAt",
                                                String.valueOf(fields.get("grantedAt"))));
                            }
                        });
                return given;
            }
        } catch (java.io.IOException | RuntimeException ex) {
            // A file that cannot be read says nothing; the task is still listed.
        }
        return Map.of();
    }

    /** The values a task was started with, each with its origin, written when it is launched. */
    static final String PARAMETERS_FILE = "parameters.json";

    /**
     * Reads the values a task was started with from its record.
     *
     * @param directory The task's state directory.
     * @return Each value by its name, with {@code value} and {@code origin}; empty when there is no
     *     record or it cannot be read.
     */
    static Map<String, Map<String, String>> parametersGiven(Path directory) {
        final Path file = directory.resolve(PARAMETERS_FILE);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            if (org.fuin.porelo.wire.Json.parse(
                            Files.readString(file, java.nio.charset.StandardCharsets.UTF_8))
                    instanceof Map<?, ?> named) {
                final Map<String, Map<String, String>> given = new java.util.LinkedHashMap<>();
                named.forEach(
                        (name, value) -> {
                            if (value instanceof Map<?, ?> fields) {
                                // In this order on every read, so a program sees one shape.
                                final Map<String, String> one = new java.util.LinkedHashMap<>();
                                one.put("value", String.valueOf(fields.get("value")));
                                one.put("origin", String.valueOf(fields.get("origin")));
                                given.put(String.valueOf(name), one);
                            }
                        });
                return given;
            }
        } catch (java.io.IOException | RuntimeException ex) {
            // A file that cannot be read says nothing; the task is still listed.
        }
        return Map.of();
    }

    private static Map<String, String> credentialsGiven(Path directory) {
        final Path file = directory.resolve(CREDENTIALS_FILE);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            if (org.fuin.porelo.wire.Json.parse(
                            Files.readString(file, java.nio.charset.StandardCharsets.UTF_8))
                    instanceof Map<?, ?> named) {
                final Map<String, String> given = new java.util.LinkedHashMap<>();
                named.forEach(
                        (name, destination) ->
                                given.put(String.valueOf(name), String.valueOf(destination)));
                return given;
            }
        } catch (java.io.IOException | RuntimeException ex) {
            // A file that cannot be read says nothing; the task is still listed.
        }
        return Map.of();
    }

    /**
     * Works out what the task's work is doing.
     *
     * <p>In this order on purpose. A container that is down is dead whatever else is lying about in
     * its directory; a task that says it is waiting is waiting, because the thing that asked said
     * so; and only then does anything look at output, which is the one signal that needs a clock -
     * so it decides between working and idle and never between waiting and anything. An attached
     * agent that declares its rest is read from its screen before that: it writes no log.
     *
     * @param summary What the runtime says.
     * @param lastWrote Last observed log write time, or {@code null} without a readable log.
     * @param waitingFor What it is waiting to be told, or {@code null}.
     * @param turn Whether its attached agent's screen shows it at rest or at work.
     * @return What the work is doing.
     */
    private static Activity activityOf(
            ContainerSummary summary,
            java.time.@Nullable Instant lastWrote,
            @Nullable String waitingFor,
            AgentWaiting.Turn turn) {
        if (!summary.running()) {
            return Activity.DEAD;
        }
        if (waitingFor != null) {
            return Activity.WAITING;
        }
        if (turn == AgentWaiting.Turn.AT_REST) {
            return Activity.AT_REST;
        }
        if (turn == AgentWaiting.Turn.WORKING) {
            return Activity.WORKING;
        }
        if (lastWrote == null) {
            return Activity.UNKNOWN;
        }
        return lastWrote.isAfter(java.time.Instant.now().minus(QUIET))
                ? Activity.WORKING
                : Activity.IDLE;
    }

    /** Reads the log's write time once so its timestamp and activity agree. */
    private static java.time.@Nullable Instant lastWrote(Path state) {
        final Path log = state.resolve("task.log");
        if (!Files.isRegularFile(log)) {
            return null;
        }
        try {
            return Files.getLastModifiedTime(log).toInstant();
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    /** Counts waiting files without entering the container or creating a mailbox. */
    private int inboxWaiting(String container) {
        final Path inbox = new Mailbox(context.paths().messaging().mailbox(container)).inboxNew();
        if (!Files.isDirectory(inbox)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> files = Files.list(inbox)) {
            return Math.toIntExact(
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .count());
        } catch (java.io.IOException | java.io.UncheckedIOException ex) {
            return 0;
        }
    }

    /** Reads what Porelo recorded for a container, or {@code null} when nothing is left. */
    private @Nullable Sidecar sidecarOf(String container) {
        try {
            final Path file =
                    context.paths().tasks().containerState(container).resolve("sidecar.json");
            return Files.isRegularFile(file) ? Sidecar.readFrom(file) : null;
        } catch (java.io.IOException | RuntimeException ex) {
            // A task whose sidecar cannot be read is still worth listing, just with less detail.
            return null;
        }
    }

    /**
     * One log a task has written.
     *
     * @param name File name, which is what {@code Tail} takes.
     * @param bytes How large it is now.
     * @param at When it was last written, ISO-8601.
     */
    public record Log(String name, long bytes, String at, @Nullable String what) {

        /**
         * Returns this log as plain values, for a caller that has to put it on a wire.
         *
         * @return The log.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("bytes", bytes);
            map.put("at", at);
            // Left out rather than sent empty. A client draws absent as nothing; an empty string
            // under a name reads as a description that failed instead of one nobody gave.
            if (what != null && !what.isBlank()) {
                map.put("what", what);
            }
            return map;
        }
    }

    /**
     * Returns the logs one task has, newest content first.
     *
     * <p><strong>Listed rather than guessed.</strong> Which files a task has depends on what it
     * started - a task with no gate has no {@code gate.log}, one run with {@code --clearance off}
     * has no {@code clearance.log} - so a client that knew the names would be showing an empty
     * viewer for a file that was never going to exist, and would never show one added by a later
     * release. Same rule as the prompt key: derive nothing at the far end that this end knows.
     *
     * @param container Container name.
     * @return The logs, alphabetical, empty when the task has no state directory left.
     */
    /**
     * Tells whether a file in a task's state directory is one somebody may read.
     *
     * <p><strong>An allow-list, and it stays one.</strong> That directory also holds {@code
     * vault.token} - the live phantom token for the task - beside the sockets, the pid files, the
     * ruleset and the sidecar. "Everything that is not a secret" is a rule that has to be right
     * forever, including about files a later release adds; "these names" is a rule that fails
     * closed when somebody adds one.
     *
     * <p><strong>Two files are logs without saying so in their name.</strong> {@code events.jsonl}
     * is what the firewall blocked, which is exactly what somebody debugging a task that started
     * and did nothing needs, and {@code reader.err} is where the reader hook's own failures go. The
     * suffix rule quietly hid both - reported by an operator who noticed the list was short.
     *
     * <p>Used by the daemon's {@code Tail} as well, so that what a listing offers is what a read
     * accepts. They were separate rules, which is a listing that names a file the reader refuses.
     *
     * @param name File name, not a path.
     * @return {@code true} if it may be listed and read.
     */
    public static boolean isLog(String name) {
        return name.endsWith(".log")
                || name.equals(org.fuin.porelo.wire.ReaderEvents.FILE)
                || name.equals("reader.err");
    }

    public List<Log> logs(String container) {
        final Path state = context.paths().tasks().containerState(container);
        if (!org.fuin.porelo.runtime.ContainerName.isTask(container) || !Files.isDirectory(state)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(state)) {
            return files.filter(file -> isLog(file.getFileName().toString()))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .map(TaskInventory::describe)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (java.io.IOException ex) {
            // A directory that vanished while being read is a task somebody removed, which is not
            // this method's problem to report.
            return List.of();
        }
    }

    /**
     * Returns what a log holds, for the ones whose name does not say.
     *
     * <p><strong>Two files, and both of them are the ones somebody needs.</strong> {@code
     * events.jsonl} is what the firewall stopped - the file to read when a task starts and then
     * does nothing - and nothing in the name says so. {@code reader.err} is the process that writes
     * it complaining, which is why its sentence carries the consequence rather than the
     * description: empty is the normal case, so what matters is what a non-empty one means.
     *
     * <p><strong>Here rather than in a client.</strong> A table at the other end would drift the
     * day a file is added: the list grows, the new one has no sentence, and it looks like the
     * ordinary case. Only this side knows which files exist and what wrote them.
     *
     * <p><strong>Absent for everything else, deliberately.</strong> {@code gate.log} is the gate's
     * log; a sentence saying so is noise, and noise beside two lines that matter is what stops them
     * being read.
     *
     * @param name File name.
     * @return One line, or {@code null} when the name speaks for itself.
     */
    private static @Nullable String what(String name) {
        return switch (name) {
            case "events.jsonl" ->
                    "connections the firewall stopped - read this when a task"
                            + " starts and then does nothing";
            case "reader.err" ->
                    "the process that records those connections, complaining."
                            + " Anything in it means the record of blocks may be incomplete";
            default -> null;
        };
    }

    @Nullable
    private static Log describe(Path file) {
        try {
            final String name = file.getFileName().toString();
            return new Log(
                    name,
                    Files.size(file),
                    Files.getLastModifiedTime(file).toInstant().toString(),
                    what(name));
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    /** Counts the helper processes a task's state directory records as alive. */
    private long helpersOf(String container) {
        final Path state = context.paths().tasks().containerState(container);
        if (!Files.isDirectory(state)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(state)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".pid"))
                    .filter(TaskLifecycle::alive)
                    .count();
        } catch (java.io.IOException ex) {
            return 0;
        }
    }

    /**
     * Says why a container found by this task's name is another project's, or nothing when it is
     * this one's.
     *
     * <p>A name is not enough: project and task names both take hyphens, so {@code web} with task
     * {@code app-x} and {@code web-app} with task {@code x} are one container name, and a start
     * brought back the other project's task with its workspace and its egress. The project label
     * says whose it is; a container from before it is taken by its name, as it always was.
     *
     * @param found The container of that name.
     * @param project The project starting the task.
     * @return The refusal, or {@code null}.
     */
    public static @Nullable String ofAnotherProject(
            org.fuin.porelo.runtime.ContainerSummary found, String project) {
        final String owner = found.project();
        if (owner == null || owner.isBlank() || owner.equals(project)) {
            return null;
        }
        return found.name()
                + " is a task of project '"
                + owner
                + "', not of '"
                + project
                + "': the two names meet. Give this task another name";
    }
}
