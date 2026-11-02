package org.fuin.porelo.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.agent.api.Finding;
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
     * @param context The machine.
     * @param agents What is installed.
     * @param agentName Value of {@code --agent}, or {@code null}.
     * @param repository The repository the task works on, or {@code null} when there is none.
     * @return The agent, or {@code null} when none is installed.
     */
    @Nullable
    public static InstalledAgent select(
            PoreloContext context,
            InstalledAgents agents,
            @Nullable String agentName,
            @Nullable Repository repository) {
        return decided(context, agents, agentName, repository).agent();
    }

    /**
     * Picks the agent, or fails saying why, keeping what was found for the provider it gives.
     *
     * @param context The machine.
     * @param agents What is installed.
     * @param agentName Value of {@code --agent}, or {@code null}.
     * @param repository The repository the task works on, or {@code null} when there is none.
     * @return The choice, never a refused one.
     * @throws org.fuin.porelo.agent.api.AgentException If it was refused.
     */
    public static Choice decided(
            PoreloContext context,
            InstalledAgents agents,
            @Nullable String agentName,
            @Nullable Repository repository) {
        final Choice choice = considerAgent(context, agents, agentName, repository);
        if (choice.refusal() != null) {
            throw new org.fuin.porelo.agent.api.AgentException(choice.detail());
        }
        return choice;
    }

    /** Where the agent a run works with was named. */
    public enum Source {

        /** By {@code --agent}, which wins over every other. */
        OPTION,

        /** By the project file, for the repository the task works on. */
        REPOSITORY,

        /** By what it found of itself: the one agent signed in on this machine. */
        SIGNED_IN,

        /** By nobody: the machine has one, or none. */
        MACHINE;

        /**
         * Returns the name a client reads, which the interface agreed on.
         *
         * @return The name.
         */
        public String wire() {
            return switch (this) {
                case OPTION -> "FLAG";
                // What the repository's own 'agent' in the project file gave.
                case REPOSITORY -> "PROJECT";
                case SIGNED_IN -> "SIGNED_IN";
                case MACHINE -> "ONLY_ONE";
            };
        }
    }

    /**
     * What one agent found of itself, as a choice reads it: presence only, never a value.
     *
     * @param agent The agent's name.
     * @param state Signed in, not signed in, or cannot say, which is never not signed in.
     * @param importable Whether {@code vault import} can take the route it counted.
     * @param provider The provider it gives, always one this agent can drive here, or {@code null}.
     * @param kind The kind of its sign-in, where known, or {@code null}.
     * @param path Where its sign-in was found, or {@code null}.
     * @param paths Every path found, its command on the account's {@code PATH} included.
     * @param warning Why it could not be asked, or {@code null}.
     */
    public record Found(
            String agent,
            Finding.SignedIn state,
            boolean importable,
            @Nullable String provider,
            Finding.@Nullable Kind kind,
            @Nullable String path,
            List<String> paths,
            @Nullable String warning) {}

    /**
     * Asks one agent what it finds of itself. Every call asks again; nothing is remembered.
     *
     * <p>Never throws: a broken adapter must not stop a start the flags could still decide, so a
     * failure reads as cannot say, with a warning. The warning does not repeat what the agent said,
     * which is not known to carry no value.
     *
     * @param context The machine.
     * @param agent The agent.
     * @return What it found.
     */
    public static Found found(final PoreloContext context, final InstalledAgent agent) {
        final List<String> paths = new ArrayList<>();
        final String command = onPath(context, agent.definition().binary());
        if (command != null) {
            paths.add(command);
        }
        final String declared = agent.definition().configDirectory();
        if (declared == null) {
            return new Found(
                    agent.name(),
                    Finding.SignedIn.CANNOT_SAY,
                    false,
                    null,
                    null,
                    null,
                    paths,
                    null);
        }
        final List<Finding> findings;
        try {
            findings = agent.find(expanded(context, declared));
        } catch (RuntimeException ex) {
            return new Found(
                    agent.name(),
                    Finding.SignedIn.CANNOT_SAY,
                    false,
                    null,
                    null,
                    null,
                    paths,
                    "'" + agent.name() + "' failed to say what it finds of itself");
        }
        for (final Finding finding : findings) {
            if (finding.path() != null && !paths.contains(finding.path())) {
                paths.add(finding.path());
            }
        }
        return read(context, agent, findings, paths);
    }

    private static Found read(
            final PoreloContext context,
            final InstalledAgent agent,
            final List<Finding> findings,
            final List<String> paths) {
        final List<Finding> signedIn =
                findings.stream()
                        .filter(finding -> finding.signedIn() == Finding.SignedIn.SIGNED_IN)
                        .toList();
        if (signedIn.isEmpty()) {
            final boolean cannotSay =
                    findings.stream()
                            .anyMatch(finding -> finding.signedIn() == Finding.SignedIn.CANNOT_SAY);
            return new Found(
                    agent.name(),
                    cannotSay ? Finding.SignedIn.CANNOT_SAY : Finding.SignedIn.NOT_SIGNED_IN,
                    false,
                    null,
                    null,
                    null,
                    paths,
                    null);
        }
        // The first the import can take, as ExtractCredential answers it, otherwise the first.
        final Finding first =
                signedIn.stream()
                        .filter(finding -> Boolean.TRUE.equals(finding.importable()))
                        .findFirst()
                        .orElse(signedIn.getFirst());
        final String provider =
                first.provider() != null
                                && SelectedProvider.drivable(
                                                context.providers(), agent.definition())
                                        .contains(first.provider())
                        ? first.provider()
                        : null;
        return new Found(
                agent.name(),
                Finding.SignedIn.SIGNED_IN,
                Boolean.TRUE.equals(first.importable()),
                provider,
                first.kind(),
                first.path(),
                paths,
                null);
    }

    private static Path expanded(final PoreloContext context, final String declared) {
        final Path home = context.paths().xdg().home();
        return declared.equals("~")
                ? home
                : declared.startsWith("~/")
                        ? home.resolve(declared.substring(2))
                        : Path.of(declared);
    }

    /** An agent runs its own pinned command inside a task, so only Porelo can say this. */
    private static @Nullable String onPath(final PoreloContext context, final String binary) {
        final String path = context.environment().apply("PATH");
        if (path == null || binary.isEmpty() || binary.contains("/")) {
            return null;
        }
        for (final String directory : path.split(java.io.File.pathSeparator)) {
            if (!directory.isEmpty()) {
                final Path candidate = Path.of(directory, binary);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    /**
     * Returns the import that would give a task the credential an agent holds, where one would.
     *
     * @param found What the agent found.
     * @param provider The provider the task uses, or {@code null} when it is not brokered.
     * @param defaultProvider The agent's default provider, or {@code null}.
     * @return Its arguments after {@code porelo}, or empty when an import would not give it.
     */
    public static List<String> importCommand(
            final Found found,
            final @Nullable String provider,
            final @Nullable String defaultProvider) {
        if (found.state() != Finding.SignedIn.SIGNED_IN || !found.importable()) {
            return List.of();
        }
        if (found.provider() != null && found.provider().equals(provider)) {
            return List.of("vault", "import", found.agent(), "--provider", found.provider());
        }
        // An answer naming no provider is stored under the default, so it serves only that.
        return found.provider() == null && java.util.Objects.equals(provider, defaultProvider)
                ? List.of("vault", "import", found.agent())
                : List.of();
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
     * @param found What each agent found, signed-in ones first; empty when a name decided and
     *     nothing was asked.
     */
    public record Choice(
            @Nullable InstalledAgent agent,
            @Nullable AgentRefusal refusal,
            String detail,
            Source source,
            List<Found> found) {

        /**
         * Returns what one agent found.
         *
         * @param name The agent's name.
         * @return What it found, or {@code null} when it was not asked.
         */
        public @Nullable Found foundBy(final String name) {
            return found.stream().filter(one -> one.agent().equals(name)).findFirst().orElse(null);
        }

        /**
         * Returns what the chosen agent found.
         *
         * @return What it found, or {@code null} when there is none or it was not asked.
         */
        public @Nullable Found chosen() {
            return agent == null ? null : foundBy(agent.name());
        }

        /**
         * Returns the agents found signed in.
         *
         * @return Their names.
         */
        public List<String> signedIn() {
            return found.stream()
                    .filter(one -> one.state() == Finding.SignedIn.SIGNED_IN)
                    .map(Found::agent)
                    .toList();
        }

        /**
         * Returns the provider the chosen agent was found signed in to.
         *
         * @return Its name, or {@code null} when none was found.
         */
        public @Nullable String provider() {
            final Found chosen = chosen();
            return chosen == null ? null : chosen.provider();
        }

        /**
         * Returns the line a start names its choice with.
         *
         * @param home The account's home, shortened to a tilde.
         * @return The line, or {@code null} when no agent was chosen.
         */
        public @Nullable String line(final Path home) {
            if (agent == null) {
                return null;
            }
            final Found chosen = chosen();
            final String name = "agent " + agent.name();
            return switch (source) {
                case OPTION -> name + ", named with --agent";
                case REPOSITORY -> name + ", named by the project file";
                case SIGNED_IN ->
                        name
                                + provided(chosen)
                                + (chosen == null || chosen.path() == null
                                        ? ", found signed in"
                                        : ", found in " + shortened(chosen.path(), home));
                case MACHINE ->
                        name
                                + ", the only one installed"
                                + (chosen != null
                                                && chosen.state() == Finding.SignedIn.NOT_SIGNED_IN
                                        ? ", not found signed in"
                                        : ", cannot say whether it is signed in");
            };
        }

        /**
         * Returns the line for an agent a person picked: they picked it, not the provider found.
         *
         * @param name The agent they picked.
         * @return The line, or {@code null} when it was found with no provider to name.
         */
        public @Nullable String picked(final String name) {
            final String provider = provided(foundBy(name));
            return provider.isEmpty() ? null : "agent " + name + provider;
        }

        private static String provided(final @Nullable Found found) {
            if (found == null || found.provider() == null) {
                return "";
            }
            return ", provider "
                    + found.provider()
                    + (found.kind() == null
                            ? ""
                            : switch (found.kind()) {
                                case SUBSCRIPTION -> " (subscription)";
                                case API_KEY -> " (API key)";
                            });
        }

        private static String shortened(final String path, final Path home) {
            final Path full = Path.of(path);
            return full.startsWith(home) ? "~/" + home.relativize(full) : path;
        }
    }

    /**
     * Picks the agent a start on one repository should work with, without throwing.
     *
     * <p>{@code --agent}; the repository's; exactly one agent found signed in; several signed in,
     * or several installed and none signed in, is refused; one installed is that one. Agents are
     * asked what they find only when neither name decides, and every call asks again.
     *
     * @param context The machine.
     * @param agents What is installed.
     * @param agentName Value of {@code --agent}, or {@code null}.
     * @param repository The repository the task works on, or {@code null} when there is none.
     * @return The choice, refused or not.
     */
    public static Choice considerAgent(
            PoreloContext context,
            InstalledAgents agents,
            @Nullable String agentName,
            @Nullable Repository repository) {
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
                        source,
                        List.of());
            }
            if (named != null) {
                return new Choice(named, null, name, source, List.of());
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
                    source,
                    List.of());
        }
        if (agents.size() == 0) {
            // A task with no agent is legitimate - the walking skeleton attaches a shell - so this
            // is not an error, only an image without an agent in it.
            return new Choice(null, null, "no agent is installed", source, List.of());
        }
        final List<Found> found = new ArrayList<>();
        agents.all().stream()
                .map(agent -> found(context, agent))
                .sorted(
                        java.util.Comparator.comparing(
                                one -> one.state() != Finding.SignedIn.SIGNED_IN))
                .forEach(found::add);
        final List<String> signedIn =
                found.stream()
                        .filter(one -> one.state() == Finding.SignedIn.SIGNED_IN)
                        .map(Found::agent)
                        .toList();
        if (signedIn.size() == 1) {
            return new Choice(
                    agents.require(signedIn.getFirst()),
                    null,
                    signedIn.getFirst(),
                    Source.SIGNED_IN,
                    List.copyOf(found));
        }
        if (agents.size() == 1) {
            return new Choice(
                    agents.all().getFirst(),
                    null,
                    agents.all().getFirst().name(),
                    source,
                    List.copyOf(found));
        }
        return new Choice(
                null,
                AgentRefusal.SEVERAL_AGENTS,
                "Several agents are installed ("
                        + String.join(", ", found.stream().map(Found::agent).toList())
                        + ")"
                        + (signedIn.isEmpty()
                                ? ""
                                : ", " + String.join(", ", signedIn) + " found signed in")
                        + ", so --agent is required",
                source,
                List.copyOf(found));
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
