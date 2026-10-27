package org.fuin.porelo.app;

import org.fuin.porelo.agent.api.InstalledAgent;
import org.fuin.porelo.agent.api.InstalledAgents;
import org.fuin.porelo.core.project.Repository;
import org.jspecify.annotations.Nullable;

/**
 * Which agent a command works with: one rule, for a start and for the egress editor alike.
 *
 * <p>In the agents' area, so the egress editor can show what the same agent would be granted
 * without reaching into the task's: two rules for "which agent" would differ exactly when several
 * are installed, which is when it matters.
 */
public final class AgentChoice {

    private AgentChoice() {}

    /**
     * Picks the agent a start on one repository should work with.
     *
     * @param agents What is installed.
     * @param agentName Value of {@code --agent}, or {@code null}.
     * @param repository The repository the task works on, or {@code null} when there is none.
     * @return The agent, or {@code null} when none is installed.
     */
    @Nullable
    public static InstalledAgent select(
            InstalledAgents agents, @Nullable String agentName, @Nullable Repository repository) {
        final Choice choice = considerAgent(agents, agentName, repository);
        if (choice.refusal() != null) {
            throw new org.fuin.porelo.agent.api.AgentException(choice.detail());
        }
        return choice.agent();
    }

    /** Where the agent a run works with was named. */
    public enum Source {

        /** By {@code --agent}, which wins over every other. */
        OPTION,

        /** By the project file, for the repository the task works on. */
        REPOSITORY,

        /** By nobody: the machine has one, or none. */
        MACHINE
    }

    /**
     * The agent a run asks for before anything installed is looked at.
     *
     * @param name The agent's name, or {@code null} when the machine decides.
     * @param source Where it was named.
     */
    public record Wanted(@Nullable String name, Source source) {}

    /**
     * Returns which agent a run asks for: {@code --agent}, else the repository's, else none.
     *
     * @param agentName Value of {@code --agent}, or {@code null}.
     * @param repository The repository the task works on, or {@code null} when there is none.
     * @return The name asked for and where it came from.
     */
    public static Wanted wanted(@Nullable String agentName, @Nullable Repository repository) {
        if (agentName != null) {
            return new Wanted(agentName, Source.OPTION);
        }
        if (repository != null && repository.agent() != null) {
            return new Wanted(repository.agent(), Source.REPOSITORY);
        }
        return new Wanted(null, Source.MACHINE);
    }

    /** Why no single agent could be picked. */
    public enum AgentRefusal {

        /** A name was given and nothing is installed under it. */
        UNKNOWN_AGENT,

        /** Several are installed and the run named none, so somebody has to choose. */
        SEVERAL_AGENTS
    }

    /**
     * Which agent a run would use, as a value rather than as an exception.
     *
     * <p>The same reason {@code SelectedProvider.consider} exists: anything answering "could this
     * start" has to reach the verdict {@link #select} reaches, from the same code, and telling "no
     * such agent" from "several installed" by reading an exception's message would be parsing prose
     * for a decision.
     *
     * @param agent The agent, or {@code null} when none is installed or the choice was refused.
     * @param refusal Why it was refused, or {@code null} when it was not.
     * @param detail What to tell a person. Always set.
     * @param source Where the agent was named, so a plan can say it.
     */
    public record Choice(
            @Nullable InstalledAgent agent,
            @Nullable AgentRefusal refusal,
            String detail,
            Source source) {}

    /**
     * Picks the agent a start on one repository should work with, without throwing.
     *
     * @param agents What is installed.
     * @param agentName Value of {@code --agent}, or {@code null}.
     * @param repository The repository the task works on, or {@code null} when there is none.
     * @return The choice, refused or not.
     */
    public static Choice considerAgent(
            InstalledAgents agents, @Nullable String agentName, @Nullable Repository repository) {
        final Wanted wanted = wanted(agentName, repository);
        final Source source = wanted.source();
        if (wanted.name() != null) {
            final String name = wanted.name();
            final InstalledAgent named = agents.find(name).orElse(null);
            // Installed but not started is not "not installed": the reason is what a person can act
            // on, and leaving it out read as "Found: none" while the agent was there.
            final String failed =
                    agents.failures().get(org.fuin.porelo.agent.api.AgentDirectory.PREFIX + name);
            if (named == null && failed != null) {
                return new Choice(
                        null,
                        AgentRefusal.UNKNOWN_AGENT,
                        "The agent '" + name + "' is installed but did not start: " + failed,
                        source);
            }
            if (named != null) {
                return new Choice(named, null, name, source);
            }
            final String found =
                    agents.names().isEmpty() ? "none" : String.join(", ", agents.names());
            return new Choice(
                    null,
                    AgentRefusal.UNKNOWN_AGENT,
                    source == Source.REPOSITORY
                            ? notInstalledForRepository(
                                    name,
                                    java.util.Objects.requireNonNull(
                                                    repository,
                                                    "an agent from the project file has its repository")
                                            .name(),
                                    found)
                            : "No agent named '" + name + "' is installed. Found: " + found,
                    source);
        }
        if (agents.size() == 1) {
            return new Choice(
                    agents.all().getFirst(), null, agents.all().getFirst().name(), source);
        }
        if (agents.size() == 0) {
            // A task with no agent is legitimate - the walking skeleton attaches a shell - so this
            // is not an error, only an image without an agent in it.
            return new Choice(null, null, "no agent is installed", source);
        }
        return new Choice(
                null,
                AgentRefusal.SEVERAL_AGENTS,
                "Several agents are installed ("
                        + String.join(", ", agents.names())
                        + "), so --agent is required",
                source);
    }

    private static String notInstalledForRepository(String name, String repository, String found) {
        return "The project file names the agent '"
                + name
                + "' for the repository '"
                + repository
                + "', and it is not installed: install "
                + org.fuin.porelo.agent.api.AgentDirectory.PREFIX
                + name
                + ", or name another with --agent. Found: "
                + found;
    }
}
