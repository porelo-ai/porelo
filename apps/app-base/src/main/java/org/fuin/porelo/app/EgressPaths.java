package org.fuin.porelo.app;

import java.nio.file.Path;
import org.fuin.porelo.agent.api.AgentDirectory;
import org.jspecify.annotations.Nullable;

/**
 * Where Porelo keeps the files of egress: its sets and the network a task is on.
 * <p>
 * One class per area, beside {@link PoreloPaths}, which keeps only the roots and what belongs to the machine as a
 * whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record EgressPaths(PoreloPaths paths) {

    /**
     * Returns the curated egress sets to scan, the operator's own first.
     * <p>
     * Beside the providers and for the same reason: data an operator can add to without root and
     * without rebuilding anything.
     *
     * @return Directory scanner.
     */
    public org.fuin.porelo.shield.EgressSetDirectory egressSets() {
        return new org.fuin.porelo.shield.EgressSetDirectory(java.util.List.of(
                paths.xdg().data().resolve("egress"),
                PoreloPaths.packaged(org.fuin.porelo.shield.EgressSetDirectory.PACKAGED)));
    }

    /**
     * Returns the file podman is pointed at when Porelo starts a container.
     * <p>
     * Under the runtime directory rather than beside podman's own configuration: it applies to
     * Porelo's containers alone, so it has no business outliving the session or being read by a
     * container the operator starts.
     *
     * @return Network configuration file.
     */
    public Path networkConfiguration() {
        return paths.xdg().runtime().resolve("containers.conf");
    }
}
