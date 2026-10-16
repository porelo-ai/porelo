package org.fuin.porelo.app;

import java.nio.file.Path;
import org.fuin.porelo.agent.api.AgentDirectory;
import org.jspecify.annotations.Nullable;

/**
 * Where Porelo keeps the files of agents and providers.
 * <p>
 * One class per area, beside {@link PoreloPaths}, which keeps only the roots and what belongs to the machine as a
 * whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record AgentPaths(PoreloPaths paths) {

    /**
     * Returns the agent directories to scan, the operator's own first.
     *
     * @return Directory scanner.
     */
    public AgentDirectory agentDirectory() {
        return new AgentDirectory(java.util.List.of(paths.xdg().data().resolve("agents"), paths.packagedAgents()),
                java.util.List.of(paths.xdg().data().resolve("agents.d"),
                        PoreloPaths.packaged(AgentDirectory.PACKAGED_DESCRIPTIONS)));
    }

    /**
     * Returns the provider directories to scan, the operator's own first.
     * <p>
     * Data files rather than executables, so they live under {@code share} where an agent binary
     * lives under {@code libexec}.
     *
     * @return Directory scanner.
     */
    public org.fuin.porelo.agent.api.ProviderDirectory providerDirectory() {
        return new org.fuin.porelo.agent.api.ProviderDirectory(java.util.List.of(
                paths.xdg().data().resolve("providers"),
                PoreloPaths.packaged(org.fuin.porelo.agent.api.ProviderDirectory.PACKAGED)));
    }
}
