package org.fuin.porelo.agent.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Says what an agent finds of itself on this machine, for choosing an agent at a start.
 *
 * <p>Read-only and quick, since every start asks again. It reads only as far as it must to say
 * which route is in use, and never runs a helper, starts the tool or reaches the network. It reads
 * the directory it is given and nothing from its own environment.
 */
public interface Finder {

    /**
     * Returns the findings, first the route the tool itself would use.
     *
     * @param configDirectory The directory the definition declares, expanded.
     * @return The findings; empty when nothing is there.
     * @throws IOException If a file cannot be read.
     */
    List<Finding> find(Path configDirectory) throws IOException;

    /**
     * A finder for an agent that cannot tell.
     *
     * @return A finder answering one sign-in with {@link Finding.SignedIn#CANNOT_SAY}.
     */
    static Finder cannotSay() {
        return configDirectory -> List.of(Finding.cannotSay());
    }
}
