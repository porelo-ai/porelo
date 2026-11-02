package org.fuin.porelo.organization.gittree;

import java.nio.file.Path;
import java.util.Map;

/**
 * How a change's commits are signed: through the acting person's agent, which holds the keys and
 * hands nothing of them over.
 *
 * @param environment What git's signing program needs to reach the agent.
 * @param keyFiles A key's own file instead of the agent, for a key that never was in one.
 */
public record CommitSigning(Map<String, String> environment, Map<String, Path> keyFiles) {

    /**
     * Signs through an agent.
     *
     * @param socket The agent's socket.
     * @return The signing.
     */
    public static CommitSigning agent(final String socket) {
        return new CommitSigning(Map.of("SSH_AUTH_SOCK", socket), Map.of());
    }

    /** What git is told to sign with. */
    String signingKey(final String key) {
        final Path file = keyFiles.get(key);
        return file == null ? "key::" + key : file.toAbsolutePath().toString();
    }
}
