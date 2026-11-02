package org.fuin.porelo.agent.api;

import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Reads an agent's credential out of the config directory it wrote during login.
 *
 * <p>Every vendor stores it differently, which is why this is an interface rather than a field. Two
 * shapes cover most of them and are implemented here; anything else is implemented by the agent
 * module that needs it, next to the agent it belongs to.
 */
@FunctionalInterface
public interface CredentialExtractor {

    /**
     * Reads the credential.
     *
     * @param configDirectory Directory the agent wrote its config into.
     * @return The credential, or empty if the agent has not been logged in.
     * @throws AgentException If the config exists but cannot be understood.
     */
    Optional<Credential> extract(Path configDirectory);

    /**
     * Reads the credential for one provider, from a store that can hold several.
     *
     * <p>Given a provider: that one's credential naming it, or empty. Given none: the first in the
     * tool's order that can be taken, naming its provider. A command or a reference to the
     * environment is never run, and counts as not held. An extractor written before this ignores
     * the provider and names none, which Porelo refuses when it asked for one.
     *
     * @param configDirectory Directory the agent wrote its config into.
     * @param provider The provider asked for, or {@code null}.
     * @return The credential, or empty.
     * @throws AgentException If the config exists but cannot be understood.
     */
    default Optional<Credential> extract(Path configDirectory, @Nullable String provider) {
        return extract(configDirectory);
    }

    /**
     * An extractor that finds nothing, for agents that need no credential at all.
     *
     * @return Extractor returning empty.
     */
    static CredentialExtractor none() {
        return directory -> Optional.empty();
    }
}
