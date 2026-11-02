package org.fuin.porelo.organization.gittree;

import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How a change's commit is signed: through the acting person's agent, which holds the key and hands
 * nothing of it over.
 *
 * @param environment What git's signing program needs to reach the agent.
 * @param keyFile A key's own file instead of the agent, for a key that never was in one.
 */
public record CommitSigning(Map<String, String> environment, @Nullable Path keyFile) {

    /**
     * Signs through an agent.
     *
     * @param socket The agent's socket.
     * @return The signing.
     */
    public static CommitSigning agent(final String socket) {
        return new CommitSigning(Map.of("SSH_AUTH_SOCK", socket), null);
    }

    /** What git is told to sign with. */
    String signingKey(final String key) {
        return keyFile == null ? "key::" + key : keyFile.toAbsolutePath().toString();
    }
}
